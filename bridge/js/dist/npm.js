"use strict";
/**
 * npm 依赖管理命名空间（docs/framework-design.md §10.8 / §12.3 auto.npm）。
 * P0：install/remove/ci/list/prune/dedupe/offlineGap/audit、registry 配置、离线导入、
 * approval 只提交请求（人机分离：绝不脚本直调 approve）、progress/approval/warning/finished
 * 事件流。宿主没有主动推给脚本的通道（§7.5 入站面只有按 requestId 结算的 ok/err），
 * 所以事件面是**带游标的拉取轮询**（§10.7 `drainEvents`/`drainApprovals`），
 * 不是推送：首订开定时器，退订干净自停。
 *
 * 全部操作跨进程路由到全局安装会话（:app-service:packager InstallCoordinator），TTL 绑定，
 * 绝不阻塞脚本事件循环；脚本内不直接 require('child_process')。
 * 事件流在 bootstrap loader 层经 RuntimeChannel 注入（见 runtime.ts handleResponse 注释）。
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.npm = void 0;
exports.feedWarning = feedWarning;
exports.installEventPollPeriod = installEventPollPeriod;
exports.installApprovalPollPeriod = installApprovalPollPeriod;
exports.pumpInstallEvents = pumpInstallEvents;
exports.pumpApprovals = pumpApprovals;
const runtime_1 = require("./runtime");
const errors_1 = require("./errors");
class EventHub {
    listeners = new Set();
    on(listener) {
        this.listeners.add(listener);
        return () => this.listeners.delete(listener);
    }
    emit(e) {
        for (const l of [...this.listeners])
            l(e);
    }
    /** 在场监听数：定时器靠它自停（没人听了就不该继续占着轮询）。 */
    get size() {
        return this.listeners.size;
    }
}
const progress = new EventHub();
const approvals = new EventHub();
const warnings = new EventHub();
const finished = new EventHub();
exports.npm = {
    /** 安装（排队→门禁→起会话→执行→post-check→归档；P0）。回包 = 排队结果，非装完。 */
    async install(spec, opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('npm', 'install', { spec, save: opts.save ?? true, offline: opts.offline ?? false }, {
            ttl: opts.timeout ?? 60_000,
        }));
    },
    async remove(spec, opts = {}) {
        await runtime_1.runtimeBridge.invoke('npm', 'remove', { spec }, { ttl: opts.timeout ?? 60_000 });
    },
    /** lockfile v3 严格重建（验签后）；市场脚本唯一入口。 */
    async ci(opts = {}) {
        await runtime_1.runtimeBridge.invoke('npm', 'ci', { offline: opts.offline ?? true }, { ttl: opts.timeout ?? 120_000 });
    },
    /** 轻操作：Kotlin 直读，不依赖网络。 */
    async list(opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('npm', 'list', { depth: opts.depth ?? 0 }, { ttl: opts.timeout ?? 10_000 }));
    },
    async prune(opts = {}) {
        await runtime_1.runtimeBridge.invoke('npm', 'prune', null, { ttl: opts.timeout ?? 60_000 });
    },
    async dedupe(opts = {}) {
        await runtime_1.runtimeBridge.invoke('npm', 'dedupe', null, { ttl: opts.timeout ?? 60_000 });
    },
    /** 离线闭包差距（缺哪些包、共多大）。 */
    async offlineGap(opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('npm', 'offlineGap', null, { ttl: opts.timeout ?? 30_000 }));
    },
    async audit(opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('npm', 'audit', { offline: opts.offline ?? true }, {
            ttl: opts.timeout ?? 120_000,
        }));
    },
    async setRegistry(registry, opts = {}) {
        // registry 变更经 :main 卡可配列表 + 审计
        await runtime_1.runtimeBridge.invoke('npm', 'setRegistry', { registry, scope: opts.scope ?? null }, { ttl: opts.timeout ?? 10_000 });
    },
    async importOfflineBundle(uri, opts = {}) {
        await runtime_1.runtimeBridge.invoke('npm', 'importOfflineBundle', { uri }, { ttl: opts.timeout ?? 120_000 });
    },
    async importTarball(path, opts = {}) {
        await runtime_1.runtimeBridge.invoke('npm', 'importTarball', { path }, { ttl: opts.timeout ?? 120_000 });
    },
    /**
     * 审批：只提交请求，绝不脚本直调（人机分离，UI 人工确认）。
     *
     * 回包 `{requestId, status, scripts}`：前两个是宿主票号与状态（`pending`），
     * [ApprovalRequest.scripts] 是**入参回显** —— 宿主校验了数组形态并原样带回，
     * 让脚本能确认「我声明的脚本清单宿主收到了」。不回显的话，宿主与脚本各持一份
     * scripts，改了哪一侧都看不出来（与 setRegistry 的 scope 同一条纪律）。
     *
     * 若宿主拒绝提交，会抛 ERR_PERMISSION_DENIED/ERR_NPM_* —— 如实上抛。
     */
    async requestApprove(pkg, opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('npm', 'requestApprove', { pkg, scripts: opts.scripts ?? [], versionHash: opts.versionHash ?? null }, { ttl: opts.timeout ?? 10_000 }));
    },
    /** 进度事件（数据面，可丢包）。返回退订函数；首订即开拉取轮询。 */
    onProgress(listener) {
        ensureEventTimer();
        return progress.on(listener);
    },
    /** 审批请求事件（宿主 approvals 拉取口；自己的轮询与安装事件互不牵连）。 */
    onApproval(listener) {
        ensureApprovalTimer();
        return approvals.on(listener);
    },
    /** 警告（此类不可恢复的静默漂移变响亮错误）。 */
    onWarning(listener) {
        ensureEventTimer();
        return warnings.on(listener);
    },
    /**
     * 安装终止（成功**和**失败都发，detail 带失败原因）。
     *
     * `install()` 的回包只是「已入队」，装没装完只能听这里 —— 没有它，脚本要么
     * 轮询 `list()` 猜、要么干脆不知道失败（§1 诚实原则）。
     */
    onFinished(listener) {
        ensureEventTimer();
        return finished.on(listener);
    },
};
/** 已知警告种类（与 :domain `InstallEvent.Kind` 五个枚举值一一对应；双断言防漂移）。 */
const WARNING_KINDS = [
    'scripts-skipped',
    'trust-downgraded',
    'registry-fallback',
    'low-memory',
    'disk-quota',
];
/**
 * 宿主向 facade 喂安装警告（装配侧/N-API TSF 回调调用；桌面/测试可直接调）。
 *
 * 为什么必须有这个显式投递缝：桥的入站面只有「按 requestId 结算的 ok/err」
 * （§7.5），而 `InstallEvent.Warning` 不是任何 invoke 的应答——它是宿主
 * `progress(projectId)` Flow 的旁路推送（§7.3 tsf_data 数据面语义）。hub 没有
 * 投递缝的话，`onWarning` 就是「订阅了但永远不响」，比没有这个 API 更糟：
 * 脚本会以为平台不报警（§10.5-3 禁止的静默）。
 *
 * 形态对齐 engines.ts 的 [installHeartbeatPeriod]：宿主注入 + 模块内消费。
 * 与心跳不同，警告**不拉取**——它的价值就在「当下这一下」（丢包的进度事件可以
 * 补拉，而「来源未校验」这种降信任标记必须在安装当下让人看见）。
 */
function feedWarning(e) {
    if (!WARNING_KINDS.includes(e.kind)) {
        // 未知种类 = 契约漂移（Kotlin 新增 InstallEvent.Kind 而这里没同步）。
        // 宁可炸，也不能「收不到还以为没有」——那正是 §10.5-3 要禁的静默面。
        throw new Error(`未知安装警告种类: ${String(e.kind)}（:domain InstallEvent.Kind 新增值时须同步 bridge/js/src/npm.ts）`);
    }
    warnings.emit(e);
}
// ══════════ 事件拉取（§10.7：宿主→脚本无推送面，带 seq 游标轮询） ══════════
/** 已知安装阶段（与 :domain `InstallEvent.Phase` 六个值逐字对齐；与 [WARNING_KINDS] 同纪律）。 */
const PHASES = [
    'queued',
    'resolve',
    'download',
    'reify',
    'post-check',
    'done',
];
/** 已知审批动作（与 :domain `ApprovalAction` 对齐；`lowercase()` 会把 RUN_SCRIPT 折成 run_script 恰好撞上，纯属巧合）。 */
const APPROVAL_ACTIONS = ['install_script', 'run_script', 'exec'];
/** 一轮拉多少（宿主侧环有界 512，32 足够一拍装完）。 */
const EVENT_BATCH = 32;
/** 事件游标（已拉过的最大 seq，下次 `sinceSeq`）。只前进：退订再订不回退，漏掉的在环里还捞得到。 */
let eventSeq = 0;
let approvalSeq = 0;
let eventPollPeriodMillis = 250;
let approvalPollPeriodMillis = 250;
let eventTimer = null;
let approvalTimer = null;
let eventPumping = false;
let approvalPumping = false;
function checkPeriod(millis, what) {
    if (!Number.isFinite(millis) || millis <= 0)
        throw new Error(`${what} 必须 > 0: ${millis}`);
}
/** 事件轮询周期注入缝（形态对齐 engines.installHeartbeatPeriod；改周期须在首订前生效）。 */
function installEventPollPeriod(millis) {
    checkPeriod(millis, 'eventPollPeriodMillis');
    eventPollPeriodMillis = millis;
}
/** 审批轮询周期注入缝（独立于事件轮询：审批要等人，不必跟进度同拍）。 */
function installApprovalPollPeriod(millis) {
    checkPeriod(millis, 'approvalPollPeriodMillis');
    approvalPollPeriodMillis = millis;
}
function drainBatchOf(payload, key) {
    const b = payload;
    if (!b || !Array.isArray(b[key]) || typeof b.first !== 'number' || typeof b.last !== 'number') {
        // 形状对不上 = 两侧契约漂移（宿主改了回包而这里没同步）。响亮炸：静默吞掉
        // 就是「拉回来一堆 undefined 却继续跑」，比没有这个 API 更糟。
        throw new Error(`npm 拉取回包形状不符（须 {first,last,${key}:[]}）: ${JSON.stringify(payload)}`);
    }
    return { first: b.first, last: b.last, items: b[key] };
}
/**
 * 拉一轮安装事件（progress / warning / finished 三路共用一个游标与定时器）。
 *
 * 错误分两档，分界线是「能不能自己好」：
 * - `ERR_NOT_IMPLEMENTED` = 宿主没实现 `events` → **响亮上抛**。订阅了却永远收不到，
 *   正是 feedWarning KDoc 说的「比没有这个 API 更糟」，必须崩在脸上；
 * - 其余（超时/断链/引擎未就绪）= 瞬时 → 吞掉走下一拍，游标不动，不丢事件。
 *
 * 与 a11y.events 同口径：回包里的 `seq` 是宿主环的位置，游标取 `last`；
 * `first > eventSeq+1` 说明环有界丢过最旧的（进度是可丢数据面，如实跳过不补造）。
 */
async function pumpInstallEvents() {
    if (eventPumping)
        return;
    eventPumping = true;
    try {
        let payload;
        try {
            payload = await runtime_1.runtimeBridge.invoke('npm', 'events', { sinceSeq: eventSeq, batch: EVENT_BATCH }, { ttl: 5_000 });
        }
        catch (e) {
            if (isNotImplemented(e))
                throw e;
            return;
        }
        const { last, items } = drainBatchOf(payload, 'events');
        for (const w of items)
            routeInstallEvent(w);
        if (last > eventSeq)
            eventSeq = last;
    }
    finally {
        eventPumping = false;
    }
}
/** 拉一轮审批请求（独立游标：审批不必等安装事件那一拍）。 */
async function pumpApprovals() {
    if (approvalPumping)
        return;
    approvalPumping = true;
    try {
        let payload;
        try {
            payload = await runtime_1.runtimeBridge.invoke('npm', 'approvals', { sinceSeq: approvalSeq, batch: EVENT_BATCH }, { ttl: 5_000 });
        }
        catch (e) {
            if (isNotImplemented(e))
                throw e;
            return;
        }
        const { last, items } = drainBatchOf(payload, 'requests');
        for (const w of items) {
            const action = w.action;
            if (!APPROVAL_ACTIONS.includes(action)) {
                throw new Error(`未知审批动作: ${String(action)}（:domain ApprovalAction 新增值时须同步 bridge/js/src/npm.ts）`);
            }
            approvals.emit({
                id: String(w.id),
                projectId: String(w.projectId),
                pkg: String(w.pkg),
                versionHash: String(w.versionHash),
                action: action,
                requestedAtMillis: Number(w.requestedAtMillis),
            });
        }
        if (last > approvalSeq)
            approvalSeq = last;
    }
    finally {
        approvalPumping = false;
    }
}
function isNotImplemented(e) {
    if (e instanceof errors_1.AutojsError)
        return e.is("ERR_NOT_IMPLEMENTED" /* ErrCode.NOT_IMPLEMENTED */);
    return e?.code === 'ERR_NOT_IMPLEMENTED'; // N-API 形态的裸错误
}
/** 一条宿主事件 → 对应 hub；未知分支/未知取值一律响亮（平台说过了但 facade 听不见 = 静默漂移）。 */
function routeInstallEvent(w) {
    switch (w.type) {
        case 'progress': {
            if (!PHASES.includes(w.phase)) {
                throw new Error(`未知安装阶段: ${String(w.phase)}（:domain InstallEvent.Phase 新增值时须同步 bridge/js/src/npm.ts）`);
            }
            progress.emit({
                projectId: String(w.projectId),
                handleId: String(w.handleId),
                phase: w.phase,
                name: w.name ?? null,
                percent: w.percent ?? null,
            });
            return;
        }
        case 'warning':
            // 过 feedWarning 而不是直接 warnings.emit：kind 的逐字校验只写一处（双断言防漂移）。
            feedWarning({
                projectId: String(w.projectId),
                handleId: String(w.handleId),
                kind: w.kind,
                pkgs: Array.isArray(w.pkgs) ? w.pkgs : [],
                message: String(w.message ?? ''),
            });
            return;
        case 'finished': {
            if (typeof w.success !== 'boolean') {
                throw new Error(`finished 事件缺布尔字段 success: ${JSON.stringify(w)}`);
            }
            finished.emit({
                projectId: String(w.projectId),
                handleId: String(w.handleId),
                success: w.success,
                detail: w.detail ?? null,
            });
            return;
        }
        default:
            throw new Error(`未知安装事件 type: ${String(w.type)}（:domain InstallEvent 上桥新增分支时须同步 bridge/js/src/npm.ts）`);
    }
}
function unref(t) {
    const u = t.unref;
    if (typeof u === 'function')
        u.call(t);
}
/** 响亮上抛：瞬时错已在 pump 内吞掉，走到这里的都是契约漂移类，不许静默成「收不到」。 */
function loud(p) {
    void p.catch((e) => {
        queueMicrotask(() => {
            throw e;
        });
    });
}
function stopEventTimer() {
    if (eventTimer)
        clearInterval(eventTimer);
    eventTimer = null;
}
function stopApprovalTimer() {
    if (approvalTimer)
        clearInterval(approvalTimer);
    approvalTimer = null;
}
function ensureEventTimer() {
    if (!eventTimer) {
        eventTimer = setInterval(() => {
            if (progress.size + warnings.size + finished.size === 0) {
                stopEventTimer(); // 没人听了就自停；游标保留，重订时从原位续拉
                return;
            }
            loud(pumpInstallEvents());
        }, eventPollPeriodMillis);
        unref(eventTimer);
    }
    // 每次订阅都立拉一轮（不只定时器首建时）：退订到重订之间定时器可能还活着
    // 但一拍没到，只在首建时拉会让人以为「订了不响」。并发由 eventPumping 挡。
    loud(pumpInstallEvents());
}
function ensureApprovalTimer() {
    if (!approvalTimer) {
        approvalTimer = setInterval(() => {
            if (approvals.size === 0) {
                stopApprovalTimer();
                return;
            }
            loud(pumpApprovals());
        }, approvalPollPeriodMillis);
        unref(approvalTimer);
    }
    loud(pumpApprovals());
}
