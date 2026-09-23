import { InvokeHandler } from './bridge';
/**
 * bootstrap loader（docs/framework-design.md §7.5 / §12.4）：
 * 把 [RuntimeBridgeImpl.install] 的投递回调和 [handleResponse] 结算接上真实传输。
 *
 * 两个接入面：
 * 1. 嵌入式宿主（Node 内置 Android 进程）：[attachNative] / [NativeBootstrap] ——
 *    fd 由宿主 kBootstrap 注入（§7.5 addon 不自连，本面**不碰** `setSocketFd`），
 *    `setup(onFrame)` 按 id 结算回包、`addon.invoke` 直接作 [InvokeHandler] 注入
 *    （addon 侧导出即为 InvokeHandler 形），经 JNI→Kotlin Router 走全异步。
 * 2. 开发/回退面（桌面/CI 无 addon）：[SocketBootstrap] unix socket + newline-delimited
 *    JSON frame 直连。request 走 socket 出，response 走 socket 入 → 仍是全异步、requestId 关联。
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
/** N-API addon 的 JS 面（`bridge_addon.cc` 导出；结构型契约 —— 单测可注入假实现）。 */
export interface BridgeNativeAddon {
    /** 宿主注入已连 socket fd（**本面不调** —— §7.5 建连归宿主，见 [NativeBootstrap] KDoc）。 */
    setSocketFd(fd: number): void;
    /** 一次性建 data 面 TSF：回包行文本回调（二次调用 addon 侧抛 ERR_INVALID_PARAM）。 */
    setup(onFrame: (line: string) => void): void;
    /** 投递请求帧（InvokeHandler 同形）；未注 fd / 写失败 → 同步抛 `ERR_*`（`.code` 在普通 Error 上）。 */
    invoke(ns: string, method: string, payloadJson: string | null, reqId: number, ttl: number): void;
}
export interface NativeBootstrapOptions {
    /** addon 模块实例；缺省 `require(AUTOSCRIPT_BRIDGE_ADDON)`（宿主 spawn 注入的路径）。 */
    addon?: BridgeNativeAddon;
}
/**
 * 嵌入式宿主接入面（§7.8 启动序③「JS 侧 setup 由 facade 接入时调」的落地）：
 * - [setup]：`addon.setup(onFrame)` 一次性接结算面 —— ok/err 行 → [runtimeBridge.handleResponse]
 *   按在途 id 结算；**负 id / 未知 id 静默丢**（kBootstrap 心跳 `-seq` 与跨代迟到响应的合同：
 *   查不到即丢，绝不撞别的在途调用）；非法 JSON 走 [onFrameError] 钩子，不炸在途；
 * - [install]：`addon.invoke` 直接作 [InvokeHandler] 注入（返回 undefined = 已投递、等结算；
 *   同步抛错经 `errFromThrown` 折叠 —— NAPI 的 `ERR_ENGINE_STOPPED` 等真码原样保留）；
 * - **不碰 `setSocketFd`**：fd 注入归宿主 kBootstrap（§7.5「宿主注入已连 fd、addon 不自连」），
 *   本面接的是"fd 已就位"之后的 JS 半边；
 * - 装配顺序 setup → install：TSF 先就位，任何回包都有人收（install 前到达的按未知 id 丢，
 *   与 `droppedData()` 的未 setup 记账互补）。
 *
 * 与 [SocketBootstrap] 同纪律：install 单例、重复安装由 [runtimeBridge] 拒绝。
 */
export declare class NativeBootstrap {
    readonly addon: BridgeNativeAddon;
    /** 响应帧非法 JSON 时的诊断钩子（缺省 null：与 `onSocketError` 同款"默认不抛"）。 */
    onFrameError: ((e: Error) => void) | null;
    private setupDone;
    constructor(opts?: NativeBootstrapOptions);
    /**
     * 接结算面（幂等：本实例二次调用 no-op —— addon 侧 `setup` 是一次性的，
     * 重复 attach 场景由 [runtimeBridge.install] 的单例拒绝兜底，两层各管各的）。
     */
    setup(): void;
    private onFrame;
    /** [InvokeHandler]：addon.invoke 直接注入；undefined = 已投递，等 onFrame 结算。 */
    readonly handler: InvokeHandler;
    /** 安装到单例桥（重复 install 由 runtimeBridge 拒绝，与 SocketBootstrap 同口径）。 */
    install(): void;
}
/**
 * 嵌入式宿主便捷接线（同步 —— 无 IO：fd 已由宿主注入，本函数只做 setup + install）。
 * 打包入口/脚本首行调一次；返回的实例挂 [NativeBootstrap.onFrameError] 诊断钩子。
 * 可 `await`（值被包成已兑现 Promise），与 `connectBootstrap` 的用法对称。
 */
export declare function attachNative(opts?: NativeBootstrapOptions): NativeBootstrap;
