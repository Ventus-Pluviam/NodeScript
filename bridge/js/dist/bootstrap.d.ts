import { InvokeHandler } from './bridge';
/**
 * bootstrap loader（docs/framework-design.md §7.5 / §12.4）：
 * 把 [RuntimeBridgeImpl.install] 的投递回调和 [handleResponse] 结算接上真实传输。
 *
 * 两个接入面：
 * 1. 嵌入式宿主（Node 内置 Android 进程）：宿主把 N-API addon 的 `invoke` 直接注入，
 *    经 JNI→Kotlin Router 走全异步；本类不含该接缝（addon 侧导出即为 InvokeHandler）。
 * 2. 开发/回退面（桌面/CI 无 addon）：unix socket + newline-delimited JSON frame 直连。
 *    request 走 socket 出，response 走 socket 入 → 仍是全异步、requestId 关联。
 *
 * 背压：队列 + drain 续写（§7.5 背压可控）；不阻塞脚本事件循环。
 */
export interface SocketBootstrapOptions {
    /** socket 路径；默认 `AUTOSCRIPT_HOST_SOCKET` 环境变量。 */
    socketPath?: string;
    /** 单帧上限（防恶意/损坏的巨型 frame 吞内存）。 */
    maxFrameBytes?: number;
    /** 连接超时（毫秒）。 */
    connectTimeout?: number;
}
export declare class SocketBootstrap {
    readonly socketPath: string;
    readonly maxFrameBytes: number;
    readonly connectTimeout: number;
    private socket;
    private buffer;
    private queue;
    private draining;
    private closed;
    constructor(opts?: SocketBootstrapOptions);
    onSocketError: ((e: Error) => void) | null;
    private emitSocketError;
    get connected(): boolean;
    /** 建立连接（幂等；后续重连由宿主层决定，不自动重试 —— 对齐桥连接熔断）。 */
    connect(): Promise<void>;
    /**
     * [InvokeHandler]：把请求编码成信封入队。返回 undefined = 已投递、等 [handleResponse]。
     * 未连接时同步抛错（对齐 invoke 的 ERR_ENGINE_STOPPED 快速拒绝）。
     */
    readonly handler: InvokeHandler;
    private checkConnected;
    /** 安装到单例桥（重复 install 由 RuntimeBridge 拒绝）。 */
    install(): void;
    private enqueue;
    /** 队列续写：只在一个 drain 循环里动队列，socket 写满即等 drain 再回。 */
    private drain;
    private onData;
    close(): void;
}
/** 便捷入口：按 opts 创建 + 连接 + 安装。 */
export declare function connectBootstrap(opts?: SocketBootstrapOptions): Promise<SocketBootstrap>;
