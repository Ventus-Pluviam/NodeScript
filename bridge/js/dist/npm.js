"use strict";
/**
 * npm 依赖管理命名空间（docs/framework-design.md §10.8 / §12.3 auto.npm）。
 * P0：install/remove/ci/list/prune/dedupe/offlineGap/audit、registry 配置、离线导入、
 * approval 只提交请求（人机分离：绝不脚本直调 approve）、progress/approval/warning 事件流。
 *
 * 全部操作跨进程路由到全局安装会话（:app-service:packager InstallCoordinator），TTL 绑定，
 * 绝不阻塞脚本事件循环；脚本内不直接 require('child_process')。
 * 事件流在 bootstrap loader 层经 RuntimeChannel 注入（见 runtime.ts handleResponse 注释）。
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.npm = void 0;
exports.feedWarning = feedWarning;
const runtime_1 = require("./runtime");
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
}
const progress = new EventHub();
const approvals = new EventHub();
const warnings = new EventHub();
exports.npm = {
    /** 安装（排队→门禁→起会话→执行→post-check→归档；P0）。 */
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
    /** 审批：只提交请求，绝不脚本直调（人机分离，UI 人工确认）。 */
    async requestApprove(pkg, opts = {}) {
        // 若宿主拒绝提交，会抛 ERR_PERMISSION_DENIED/ERR_NPM_* —— 如实上抛
        await runtime_1.runtimeBridge.invoke('npm', 'requestApprove', { pkg, scripts: opts.scripts ?? [] }, { ttl: opts.timeout ?? 10_000 });
    },
    /** 进度事件（数据面，可丢包）。返回退订函数。 */
    onProgress(listener) {
        return progress.on(listener);
    },
    /** 审批请求事件（宿主经 approvals Flow 推过来）。 */
    onApproval(listener) {
        return approvals.on(listener);
    },
    /** 警告（此类不可恢复的静默漂移变响亮错误）。 */
    onWarning(listener) {
        return warnings.on(listener);
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
