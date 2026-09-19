import { BridgeResponse, InvokeHandler } from './bridge';
/**
 * 调用的任务句柄（可取消；与 RequestRegistry 的 cancel 语义对齐）。
 * 取消触发 ERR_TIMEOUT 收尾，符合「每次跨进程操作必有 TTL」铁律（§7.1 铁律3）。
 */
export interface InvocationHandle {
    cancel(): void;
}
/**
 * 调用选项：TTL（默认 [DEFAULT_TTL]）+ 可选 AbortSignal（§12.1 超时/取消）。
 */
export interface InvokeOptions {
    /** 本条调用的 TTL（毫秒）。发送方超时以 ERR_TIMEOUT 收尾。 */
    ttl?: number;
    signal?: AbortSignal;
}
/**
 * 单例桥（§7.1 RuntimeBridge）：requestId 生成/关联、TTL、错误折叠、in-flight 记账。
 * - [install] 由 bootstrap/宿主注入投递函数 `(ns, method, payloadJson, reqId, ttl)`：
 *   - resolve/返回非 undefined → 视为「内联答案」立即结算（bootstrap 内联路径）；
 *   - resolve/返回 undefined → 表示已投递、等响应，由 [handleResponse] 按 id 结算。
 * - [handleResponse] 宿主在收到 ok/err 响应信封时回调 → 按 id settle 挂起的调用；
 * - TTL 单定时器驱动：到点 cancel → ERR_TIMEOUT，同时释放 in-flight，杜绝悬挂记账。
 * - 同步抛错/拒绝 → ERR_INVALID_PARAM 折叠（对齐 BridgeRouter 的 handler 异常分类）。
 */
export declare class RuntimeBridgeImpl {
    private handler;
    /** 安装宿主投递回调（bootstrap/插件在引擎启动时注入 ESM/UMD addon）。 */
    install(handler: InvokeHandler): void;
    get installed(): boolean;
    /** 宿主在收到 ok/err 响应时回调（Kotlin Router → N-API TSF → JS 事件循环）。 */
    handleResponse(resp: BridgeResponse): void;
    /** 发起一条调用。模块层把参数对象 JSON.stringify 后作为 payload 传入。 */
    invoke(namespace: string, method: string, params?: unknown, opts?: InvokeOptions): Promise<unknown>;
}
/** 单例引用：脚本侧 `require('auto')` 命名空间根对象挂 `auto.bridge`。 */
export declare const runtimeBridge: RuntimeBridgeImpl;
