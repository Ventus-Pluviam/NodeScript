/**
 * console 回传（docs/framework-design.md §7.3 tsf_data / §14 P0 最小桥）。
 *
 * 数据面语义：可丢包（丢包统计/背压，溢出时回调 JS 层 `queueError`）。
 * 因此 log 系列调用永不抛错给脚本——发送失败（未安装/TTL/宿主拒绝）一律吞掉，
 * 只经 `onQueueError` 通知（默认无监听不抛，对齐 SocketBootstrap.onSocketError）。
 *
 * 宿主侧：N-API addon 经 tsf_data 把行送到 :main 控制台；dev/CI socket 面走同一
 * `console.log` namespace（Kotlin Router 侧注册 console handler 落盘/EventBus）。
 */
/** 日志级别（对齐 console.log/info/warn/error/debug 五档）。 */
export type ConsoleLevel = 'log' | 'info' | 'warn' | 'error' | 'debug';
/** 队列错误（发送丢包/背压时；对齐 §7.3 queueError 回调）。 */
export interface ConsoleQueueError {
    readonly level: ConsoleLevel;
    readonly reason: string;
}
type QueueErrorListener = (e: ConsoleQueueError) => void;
export declare const consoleSink: {
    log(...args: unknown[]): Promise<void>;
    info(...args: unknown[]): Promise<void>;
    warn(...args: unknown[]): Promise<void>;
    error(...args: unknown[]): Promise<void>;
    debug(...args: unknown[]): Promise<void>;
    /** 背压/丢包回调（§7.3 queueError）。返回退订函数。 */
    onQueueError(listener: QueueErrorListener): () => void;
};
export {};
