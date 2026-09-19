"use strict";
/**
 * 引擎进程池模型（docs/framework-design.md §8.1/§8.2）：并发上限 = 池容量，超载排队（绝不静默丢）。
 * 对应 :app-service:runtime EnginePool 语义 + :domain:engine ScriptEngine / RuntimeChannel / EngineSessionHandle。
 * 面：exec → 会话句柄（cancel = 四步 quiesce；onExit；命名通道）。
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.engines = void 0;
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
};
