"use strict";
/**
 * 引擎进程池模型（docs/framework-design.md §8.1/§8.2）：并发上限 = 池容量，超载排队（绝不静默丢）。
 * 对应 :app-service:runtime EnginePool 语义 + :domain:engine ScriptEngine（会话面无 :domain 句柄类型：
 * Kotlin 侧是 `EnginesNamespaceHandler` 的 exec/stop/status/channel* 方法，JS 侧是本文件的
 * `EngineSessionImpl`/`EngineChannel` —— 已删无消费者的 `EngineSessionHandle` 死层，见 stale-jaguar 提交）。
 * 面：exec → 会话句柄（cancel = 四步 quiesce；onExit；命名通道）。
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.EngineSessionImpl = exports.engines = exports.DEFAULT_DRAIN_MAX = exports.EngineChannel = void 0;
exports.nextHeartbeatSeq = nextHeartbeatSeq;
exports.startHeartbeat = startHeartbeat;
exports.installHeartbeatPeriod = installHeartbeatPeriod;
const runtime_1 = require("./runtime");
const errors_1 = require("./errors");
/**
 * 命名通道的 JS 实现（`engines.channel()` 的返回值）。
 *
 * - `emit` → `channelEmit {channelId,event,payload}`（空事件名由宿主判
 *   ERR_INVALID_PARAM，本层不预检 —— 预检会制造"两处校验口径"漂移）；
 * - `on` → 节流轮询 `channelDrain {channelId,sinceSeq,max}`，本地游标只进不退，
 *   按事件名过滤后回调；
 * - `close` → `channelClose {channelId}`，本地先置位（轮询即停），重复调用 no-op。
 */
class EngineChannel {
    name;
    channelId;
    closed = false;
    constructor(name, channelId) {
        this.name = name;
        this.channelId = channelId;
    }
    get isClosed() {
        return this.closed;
    }
    async emit(event, payload = null, opts = {}) {
        await runtime_1.runtimeBridge.invoke('engines', 'channelEmit', { channelId: this.channelId, event, payload }, { ttl: opts.timeout ?? 10_000 });
    }
    on(event, listener, opts = {}) {
        const period = opts.pollMillis ?? 500;
        if (!Number.isFinite(period) || period <= 0) {
            throw new Error(`pollMillis 必须 > 0: ${period}`);
        }
        let cursor = 0;
        let cancelled = false;
        let timer = null;
        const tick = async () => {
            timer = null;
            if (cancelled || this.closed)
                return;
            try {
                const out = (await runtime_1.runtimeBridge.invoke('engines', 'channelDrain', { channelId: this.channelId, sinceSeq: cursor, max: exports.DEFAULT_DRAIN_MAX }, { ttl: Math.max(period * 2, 2_000) }));
                cursor = out.last ?? cursor;
                for (const e of out.events ?? []) {
                    if (e.event === event)
                        listener(e.payload ?? null);
                }
            }
            catch {
                /* 轮询失败吞掉等下一轮：宿主抖动不该炸掉订阅；真死了 close/stop 会表达 */
            }
            if (!cancelled && !this.closed) {
                timer = setTimeout(() => {
                    void tick();
                }, period);
                unrefTimer(timer);
            }
        };
        timer = setTimeout(() => {
            void tick();
        }, period);
        unrefTimer(timer);
        return {
            cancel: () => {
                cancelled = true;
                if (timer) {
                    clearTimeout(timer);
                    timer = null;
                }
            },
        };
    }
    async close(opts = {}) {
        if (this.closed)
            return;
        this.closed = true;
        await runtime_1.runtimeBridge.invoke('engines', 'channelClose', { channelId: this.channelId }, { ttl: opts.timeout ?? 10_000 });
    }
}
exports.EngineChannel = EngineChannel;
/** drain 单批上限（与 Kotlin 缺省 max=128 同口径）。 */
exports.DEFAULT_DRAIN_MAX = 128;
function unrefTimer(t) {
    const u = t.unref;
    if (typeof u === 'function')
        u.call(t);
}
exports.engines = {
    /**
     * 启动一次执行（§8 池仲裁；返回会话句柄）。同引擎一次一脚本；运行态由 RuntimeController 仲裁。
     * 超载排队，不做静默丢弃。
     */
    /**
     * 启动一次执行（§8 池仲裁；返回会话句柄）。同引擎一次一脚本；运行态由 RuntimeController 仲裁。
     * 超载排队，不做静默丢弃。
     *
     * 回包包成 [EngineSessionImpl]（此前是 wire 原样 `as EngineSession` —— `.cancel()`
     * 当场 TypeError 的空壳，已补实；与 `channel()` 包 `EngineChannel` 同一条纪律）。
     */
    async exec(request, opts = {}) {
        const wire = (await runtime_1.runtimeBridge.invoke('engines', 'exec', request, {
            ttl: opts.timeout ?? 15_000,
            signal: opts.signal,
        }));
        return new EngineSessionImpl(wire.runId, wire.handle);
    },
    /** 池容量快照（容量/空闲/占用）。 */
    async poolStats(opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('engines', 'poolStats', null, { ttl: opts.timeout ?? 5_000 }));
    },
    /** 请求侧主动停止（PoolAcquireOutcome 语义）：runId 未发行 → failed；发行后取消是 EngineSession.cancel。 */
    async stop(runId, opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('engines', 'stop', { runId }, { ttl: opts.timeout ?? 10_000 })) === true;
    },
    /**
     * 引擎侧状态快照（`onExit` 轮询的地基；Kotlin `probeStatus` 只读在途表）。
     *
     * 在途 → 状态名字符串（与 :domain `EngineStatus` 枚举名逐字一致）；
     * 已结算/从未存在 → 抛 `ERR_NOT_FOUND`（结算后无状态可读，宿主不伪造 `"STOPPED"`，
     * 见 handler `status` 注释 —— 调用方不得把 NOT_FOUND 翻译成任何终态）。
     */
    async status(runId, opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('engines', 'status', { runId }, {
            ttl: opts.timeout ?? 5_000,
        }));
    },
    /**
     * 打开（或复用）命名通道（§8：同 host 的通道生命周期由 :app-service:runtime 管理）。
     * 回包包成 [EngineChannel]（此前是 wire 原样 `as RuntimeChannel` —— `.emit()` 当场
     * TypeError 的空壳，已补实）。
     */
    async channel(name, opts = {}) {
        const wire = (await runtime_1.runtimeBridge.invoke('engines', 'channel', { name }, {
            ttl: opts.timeout ?? 10_000,
        }));
        return new EngineChannel(wire.name, wire.channelId);
    },
    /**
     * 心跳打点（§8.4 缺口②的引擎侧源头）：宿主据此算「距上次心跳多久」，看门狗据此判失联。
     *
     * `seq` 单调递增，由本进程内计数器给出（见 [heartbeatSeq]）：宿主的账本只认递增序号，
     * 重复/乱序帧不被采纳（回 false）。**不要**为了"显得活着"而高频重发同一 seq ——
     * 那既骗不过账本，也会让积压帧把死掉之后的样子伪装成活的。
     */
    async heartbeat(runId, seq, opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('engines', 'heartbeat', { runId, seq }, { ttl: opts.timeout ?? 2_000 })) === true;
    },
};
/** 心跳序号计数器（进程内单调）：宿主账本靠它分辨"新心跳"与"积压的旧心跳"。 */
let heartbeatSeq = 0;
function nextHeartbeatSeq() {
    heartbeatSeq += 1;
    return heartbeatSeq;
}
/**
 * 心跳定时打点（§8.4 缺口②）：按宿主周期发一次心跳，返回取消句柄。
 *
 * 周期取宿主 `heartbeatPeriodMillis`（装配侧经 [installHeartbeatPeriod] 注入；缺省 500ms）。
 * 原生宿主侧由引擎启动脚本在 isolate 起来后调用；桌面/测试可直接调 [engines.heartbeat]。
 *
 * 语义：unref 的定时器不保活事件循环（§5.3 `napi_unref_threadsafe_function` 同思路）——
 * 心跳不该让一个"脚本已跑完"的进程赖着不死。
 */
function startHeartbeat(runId, opts = {}) {
    const period = opts.periodMillis ?? heartbeatPeriodMillis;
    const t = setInterval(() => {
        void exports.engines.heartbeat(runId, nextHeartbeatSeq()).catch(() => {
            /* 心跳失败不炸脚本：宿主的 CPU/RSS 两路仍在兜底；连续失败由看门狗按失联处理 */
        });
    }, period);
    if (typeof t.unref === 'function') {
        t.unref();
    }
    return () => clearInterval(t);
}
/** 宿主注入的心跳周期（毫秒；缺省 500ms，与 §8.4 的周期同源）。 */
let heartbeatPeriodMillis = 500;
function installHeartbeatPeriod(millis) {
    if (!Number.isFinite(millis) || millis <= 0) {
        throw new Error(`heartbeatPeriodMillis 必须 > 0: ${millis}`);
    }
    heartbeatPeriodMillis = millis;
}
/**
 * `engines.exec()` 的会话实现（`exec` 的返回值）。
 *
 * - `cancel` → `engines.stop(runId)`（§4.1 归口 → 池四步 quiesce；已结算后调用落
 *   `ERR_NOT_FOUND`，幂等语义由宿主保证，本层不吞错）；
 * - `onExit` → 节流轮询 `engines.status(runId)`：`STOPPED` 报 `null`（干净结束），
 *   `CRASHED` 报 `{cause:'CRASHED'}`，结算离表（`ERR_NOT_FOUND`）则按"谁停的"诚实回答 ——
 *   经本会话 `cancel()` 停的报 `null`，外部结算（看门狗/他人 stop/正常跑完离表）的报
 *   `{cause:'UNKNOWN'}`：结算即离表（见 `probeStatus`），离表后宿主已无终态可读，
 *   报 `null` 会把 CRASHED 伪装成干净结束。轮询失败（TTL/断链）吞掉等下一轮，
 *   定时器 unref 不保活事件循环（与 `startHeartbeat` 同一条纪律）；
 * - `channel` 恒 `null`：命名通道走 `engines.channel(name)` 显式打开，会话不隐式持通道
 *   （隐式建通道会在宿主侧留一条永远没人 drain 的缓冲，等于漏水）。
 */
class EngineSessionImpl {
    runId;
    handle;
    cancelCalled = false;
    constructor(runId, handle) {
        this.runId = runId;
        this.handle = handle;
    }
    get channel() {
        return null;
    }
    async cancel(opts = {}) {
        this.cancelCalled = true;
        return exports.engines.stop(this.runId, opts);
    }
    onExit(listener, opts = {}) {
        const period = opts.pollMillis ?? 500;
        if (!Number.isFinite(period) || period <= 0) {
            throw new Error(`pollMillis 必须 > 0: ${period}`);
        }
        let settled = false;
        let timer = null;
        const finish = (info) => {
            if (settled)
                return;
            settled = true;
            if (timer) {
                clearTimeout(timer);
                timer = null;
            }
            listener(info);
        };
        const schedule = () => {
            if (settled)
                return;
            timer = setTimeout(() => {
                timer = null;
                void poll();
            }, period);
            unrefTimer(timer); // 只保"下一轮"：首轮的存活由它自己的 await 承担（见 poll 注释）
        };
        const poll = async () => {
            if (settled)
                return;
            let st;
            try {
                st = await exports.engines.status(this.runId);
            }
            catch (e) {
                if (e instanceof errors_1.AutojsError && e.code === "ERR_NOT_FOUND" /* ErrCode.NOT_FOUND */) {
                    // 结算离表：宿主已无终态可读。经本会话 cancel 的 = 我们亲手停的（干净）；
                    // 外部结算的一律 UNKNOWN —— 绝不把"查不到"翻译成"正常结束"。
                    finish(this.cancelCalled
                        ? null
                        : { cause: 'UNKNOWN', message: 'run 已结算离表，终态未知（结算后无状态可读）' });
                    return;
                }
                schedule(); // TTL/断链等：吞掉等下一轮，真死了结算路径会表达
                return;
            }
            if (st === 'STOPPED') {
                finish(null);
                return;
            }
            if (st === 'CRASHED') {
                finish({ cause: 'CRASHED', message: null });
                return;
            }
            schedule(); // RUNNING/BOOTING/QUIESCING/IDLE：还没终态，下一轮再问
        };
        // 首轮立即问（终态查询不等一个周期）：首轮的存活锚是 invoke 内部的 TTL 定时器
        // （ref，事件循环因它存活）+ 同步结算时同 tick 落定的 microtask —— 不另挂 ref 定时器。
        // 若首轮是"真异步未决"（宿主稍后才 handleResponse），事件循环靠那个 TTL 定时器活着，
        // resolve 后要么 finish（清定时器调回调），要么 schedule（挂 unref 的下一轮）。
        void poll();
        return {
            cancel: () => {
                settled = true;
                if (timer) {
                    clearTimeout(timer);
                    timer = null;
                }
            },
        };
    }
}
exports.EngineSessionImpl = EngineSessionImpl;
