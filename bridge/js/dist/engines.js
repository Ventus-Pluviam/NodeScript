"use strict";
/**
 * 引擎进程池模型（docs/framework-design.md §8.1/§8.2）：并发上限 = 池容量，超载排队（绝不静默丢）。
 * 对应 :app-service:runtime EnginePool 语义 + :domain:engine ScriptEngine / RuntimeChannel / EngineSessionHandle。
 * 面：exec → 会话句柄（cancel = 四步 quiesce；onExit；命名通道）。
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.engines = void 0;
exports.nextHeartbeatSeq = nextHeartbeatSeq;
exports.startHeartbeat = startHeartbeat;
exports.installHeartbeatPeriod = installHeartbeatPeriod;
const runtime_1 = require("./runtime");
exports.engines = {
    /**
     * 启动一次执行（§8 池仲裁；返回会话句柄）。同引擎一次一脚本；运行态由 RuntimeController 仲裁。
     * 超载排队，不做静默丢弃。
     */
    async exec(request, opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('engines', 'exec', request, {
            ttl: opts.timeout ?? 15_000,
            signal: opts.signal,
        }));
    },
    /** 池容量快照（容量/空闲/占用）。 */
    async poolStats(opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('engines', 'poolStats', null, { ttl: opts.timeout ?? 5_000 }));
    },
    /** 请求侧主动停止（PoolAcquireOutcome 语义）：runId 未发行 → failed；发行后取消是 EngineSession.cancel。 */
    async stop(runId, opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('engines', 'stop', { runId }, { ttl: opts.timeout ?? 10_000 })) === true;
    },
    /** 打开（或复用）命名通道（§8：同 host 的通道生命周期由 :app-service:runtime 管理）。 */
    async channel(name, opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('engines', 'channel', { name }, { ttl: opts.timeout ?? 10_000 }));
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
