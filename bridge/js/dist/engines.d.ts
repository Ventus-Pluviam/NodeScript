/**
 * 引擎进程池模型（docs/framework-design.md §8.1/§8.2）：并发上限 = 池容量，超载排队（绝不静默丢）。
 * 对应 :app-service:runtime EnginePool 语义 + :domain:engine ScriptEngine / RuntimeChannel / EngineSessionHandle。
 * 面：exec → 会话句柄（cancel = 四步 quiesce；onExit；命名通道）。
 */
/** 运行簿记收据（与 :domain EngineRunReceipt 对齐：runId + HandleRef）。 */
export interface EngineRunReceipt {
    readonly runId: number;
    readonly handle: {
        refId: number;
        generation: number;
    };
}
/** 一次运行请求（与 :domain EngineRunRequest 对齐）。 */
export interface EngineRunRequest {
    readonly projectId: string;
    readonly scriptPath: string;
    readonly args?: readonly string[];
    readonly runNonce?: string | null;
    readonly timeoutMillis?: number | null;
}
/** 池统计（与 :app-service:runtime EnginePool 统计对齐）。 */
export interface EnginePoolStats {
    readonly capacity: number;
    readonly free: number;
    readonly busy: number;
}
/** 引擎状态（与 :domain ScriptEngine.EngineStatus 对齐）。 */
export type EngineStatus = 'IDLE' | 'BOOTING' | 'RUNNING' | 'QUIESCING' | 'STOPPED' | 'CRASHED';
/** 池获取结果（与 runtime 的 PoolAcquireOutcome 对齐：granted/timedOut/failed）。 */
export type PoolAcquireOutcome = {
    kind: 'granted';
    runId: number;
    handle: {
        refId: number;
        generation: number;
    };
} | {
    kind: 'timedOut';
} | {
    kind: 'failed';
    message: string;
};
/** 通道订阅句柄（与 :domain ChannelSubscription 对齐）。 */
export interface ChannelSubscription {
    cancel(): void;
}
/**
 * 命名双向通道（§8 RuntimeChannel）：脚本↔宿主 JSON 事件；
 * 大二进制走 side-channel（§7.4），不入事件载荷。
 */
export interface RuntimeChannel {
    readonly name: string;
    /** 脚本 → 宿主：发事件（JSON 字符串载荷）。 */
    emit(event: string, payload?: string | null, opts?: {
        timeout?: number;
    }): Promise<void>;
    /**
     * 宿主 → 脚本：订阅事件；返回取消句柄。
     *
     * 拉取实现（节流轮询 `channelDrain`，游标 `sinceSeq`）：与 Kotlin 侧"缓冲 + 游标，
     * 不做回调推送"语义一致（见 EnginesNamespaceHandler 注释）。轮询失败吞掉等下一轮
     * （宿主消失/通道关闭由 close/stop 路径表达，不在这里炸订阅）；定时器 unref，
     * 不保活脚本事件循环（与 startHeartbeat 同一条纪律）。
     */
    on(event: string, listener: (payload: string | null) => void, opts?: {
        pollMillis?: number;
    }): ChannelSubscription;
    /** 关闭并丢弃缓冲（幂等：重复 close 不再发桥调用）。 */
    close(opts?: {
        timeout?: number;
    }): Promise<void>;
}
/** 通道事件帧（与 Kotlin ChannelEvent 对齐：seq/event/payload）。 */
export interface ChannelEvent {
    readonly seq: number;
    readonly event: string;
    readonly payload: string | null;
}
/** `channel` wire 回包（Kotlin `{name,channelId}`），`channel()` 包成 [EngineChannel]。 */
export interface ChannelWire {
    readonly name: string;
    readonly channelId: number;
}
/** drain 回包（Kotlin `{last,events}`）。 */
export interface ChannelDrain {
    readonly last: number;
    readonly events: readonly ChannelEvent[];
}
/**
 * 命名通道的 JS 实现（`engines.channel()` 的返回值）。
 *
 * - `emit` → `channelEmit {channelId,event,payload}`（空事件名由宿主判
 *   ERR_INVALID_PARAM，本层不预检 —— 预检会制造"两处校验口径"漂移）；
 * - `on` → 节流轮询 `channelDrain {channelId,sinceSeq,max}`，本地游标只进不退，
 *   按事件名过滤后回调；
 * - `close` → `channelClose {channelId}`，本地先置位（轮询即停），重复调用 no-op。
 */
export declare class EngineChannel implements RuntimeChannel {
    readonly name: string;
    readonly channelId: number;
    private closed;
    constructor(name: string, channelId: number);
    get isClosed(): boolean;
    emit(event: string, payload?: string | null, opts?: {
        timeout?: number;
    }): Promise<void>;
    on(event: string, listener: (payload: string | null) => void, opts?: {
        pollMillis?: number;
    }): ChannelSubscription;
    close(opts?: {
        timeout?: number;
    }): Promise<void>;
}
/** drain 单批上限（与 Kotlin 缺省 max=128 同口径）。 */
export declare const DEFAULT_DRAIN_MAX = 128;
/** 退出信息（与 :domain CrashInfo 对齐）。 */
export interface CrashInfo {
    cause?: string | null;
    message?: string | null;
}
/**
 * engines.exec() 会话句柄：cancel（优雅四步 quiesce）+ onExit（STOPPED/CRASHED 回调）
 * + 命名通道。对应 :domain EngineSessionHandle。
 */
export interface EngineSession {
    readonly runId: number;
    readonly handle: {
        refId: number;
        generation: number;
    };
    cancel(opts?: {
        timeout?: number;
        signal?: AbortSignal;
    }): Promise<boolean>;
    onExit(listener: (info: CrashInfo | null) => void, opts?: {
        pollMillis?: number;
    }): ChannelSubscription;
    get channel(): RuntimeChannel | null;
}
export declare const engines: {
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
    exec(request: EngineRunRequest, opts?: {
        timeout?: number;
        signal?: AbortSignal;
    }): Promise<EngineSession>;
    /** 池容量快照（容量/空闲/占用）。 */
    poolStats(opts?: {
        timeout?: number;
    }): Promise<EnginePoolStats>;
    /** 请求侧主动停止（PoolAcquireOutcome 语义）：runId 未发行 → failed；发行后取消是 EngineSession.cancel。 */
    stop(runId: number, opts?: {
        timeout?: number;
    }): Promise<boolean>;
    /**
     * 引擎侧状态快照（`onExit` 轮询的地基；Kotlin `probeStatus` 只读在途表）。
     *
     * 在途 → 状态名字符串（与 :domain `EngineStatus` 枚举名逐字一致）；
     * 已结算/从未存在 → 抛 `ERR_NOT_FOUND`（结算后无状态可读，宿主不伪造 `"STOPPED"`，
     * 见 handler `status` 注释 —— 调用方不得把 NOT_FOUND 翻译成任何终态）。
     */
    status(runId: number, opts?: {
        timeout?: number;
    }): Promise<EngineStatus>;
    /**
     * 打开（或复用）命名通道（§8：同 host 的通道生命周期由 :app-service:runtime 管理）。
     * 回包包成 [EngineChannel]（此前是 wire 原样 `as RuntimeChannel` —— `.emit()` 当场
     * TypeError 的空壳，已补实）。
     */
    channel(name: string, opts?: {
        timeout?: number;
    }): Promise<RuntimeChannel>;
    /**
     * 心跳打点（§8.4 缺口②的引擎侧源头）：宿主据此算「距上次心跳多久」，看门狗据此判失联。
     *
     * `seq` 单调递增，由本进程内计数器给出（见 [heartbeatSeq]）：宿主的账本只认递增序号，
     * 重复/乱序帧不被采纳（回 false）。**不要**为了"显得活着"而高频重发同一 seq ——
     * 那既骗不过账本，也会让积压帧把死掉之后的样子伪装成活的。
     */
    heartbeat(runId: number, seq: number, opts?: {
        timeout?: number;
    }): Promise<boolean>;
};
export declare function nextHeartbeatSeq(): number;
/**
 * 心跳定时打点（§8.4 缺口②）：按宿主周期发一次心跳，返回取消句柄。
 *
 * 周期取宿主 `heartbeatPeriodMillis`（装配侧经 [installHeartbeatPeriod] 注入；缺省 500ms）。
 * 原生宿主侧由引擎启动脚本在 isolate 起来后调用；桌面/测试可直接调 [engines.heartbeat]。
 *
 * 语义：unref 的定时器不保活事件循环（§5.3 `napi_unref_threadsafe_function` 同思路）——
 * 心跳不该让一个"脚本已跑完"的进程赖着不死。
 */
export declare function startHeartbeat(runId: number, opts?: {
    periodMillis?: number;
}): () => void;
export declare function installHeartbeatPeriod(millis: number): void;
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
export declare class EngineSessionImpl implements EngineSession {
    readonly runId: number;
    readonly handle: {
        refId: number;
        generation: number;
    };
    private cancelCalled;
    constructor(runId: number, handle: {
        refId: number;
        generation: number;
    });
    get channel(): RuntimeChannel | null;
    cancel(opts?: {
        timeout?: number;
        signal?: AbortSignal;
    }): Promise<boolean>;
    onExit(listener: (info: CrashInfo | null) => void, opts?: {
        pollMillis?: number;
    }): ChannelSubscription;
}
/**
 * 运行句柄代理（§7.4 句柄面）：exec 签发；cancel = 四步 quiesce（§8.3），onExit 绑退出事件。
 * 与 Kotlin :domain EngineSessionHandle 对齐（engine/receipt/channel/exitSink 由运行时实现注入）。
 */
export interface EngineSessionHandle {
    readonly runId: number;
    readonly handle: {
        refId: number;
        generation: number;
    };
}
