/**
 * 错误目录（docs/framework-design.md §7.6 / §12.1）。
 * 与 :domain 的 ErrorCode 目录一一致：JS 侧 instanceof AutojsError 可策略化 try/catch。
 * 新增错误码需同步 §7.6 与 :domain:core.ErrorCode。
 */
/** 机器可判错误码（与 :domain:core.ErrorCode.code 逐字一致）。 */
export declare const enum ErrCode {
    TIMEOUT = "ERR_TIMEOUT",
    STALE_HANDLE = "ERR_STALE_HANDLE",
    PERMISSION_DENIED = "ERR_PERMISSION_DENIED",
    SERVICE_DISABLED = "ERR_SERVICE_DISABLED",
    SCREEN_LOCKED = "ERR_SCREEN_LOCKED",
    BLACK_FRAME = "ERR_BLACK_FRAME",
    CAPTURE_DENIED = "ERR_CAPTURE_DENIED",
    ENGINE_STOPPED = "ERR_ENGINE_STOPPED",
    ENGINE_CRASHED = "ERR_ENGINE_CRASHED",
    NOT_IMPLEMENTED = "ERR_NOT_IMPLEMENTED",
    INVALID_PARAM = "ERR_INVALID_PARAM",
    FILE_NOT_FOUND = "ERR_FILE_NOT_FOUND",
    FILE_EXISTS = "ERR_FILE_EXISTS",
    DISK_FULL = "ERR_DISK_FULL",
    NOT_FOUND = "ERR_NOT_FOUND",
    NPM_SPAWN_BLOCKED = "ERR_NPM_SPAWN_BLOCKED",
    NOT_SUPPORTED = "ERR_NOT_SUPPORTED",
    REGISTRY_UNAVAILABLE = "ERR_REGISTRY_UNAVAILABLE",
    NPM_LOWMEM = "ERR_NPM_LOWMEM"
}
/** 桥回包中的可序列化错误骨架（跨进程往返的唯一错误载体）。 */
export interface ErrPayload {
    code: string;
    detail?: string | null;
    module?: string;
    method?: string;
    javaClass?: string;
    javaStack?: string;
}
/**
 * 统一异常类型：脚本侧可 `catch (e) { if (e instanceof AutojsError) … }` 策略化。
 * 由桥把 ErrPayload 还原为实例；模块封装层不得悄悄吞掉。
 */
export declare class AutojsError extends Error {
    readonly code: string;
    readonly detail?: string | null;
    readonly module?: string;
    readonly method?: string;
    readonly javaClass?: string;
    readonly javaStack?: string;
    constructor(payload: ErrPayload);
    /** 快捷判错：`err.is('ERR_TIMEOUT')`。 */
    is(code: string): boolean;
}
/** 未找到（UiSelector findOne 无匹配）——对齐 AutoJsPro v9 的 NotFoundError 语义（§7.6）。 */
export declare class NotFoundError extends AutojsError {
    constructor(detail?: string);
}
/** 错误目录常量（与 enum 等值，供数组/字典场景）。 */
export declare const ERROR_CODES: readonly string[];
declare function fromErrPayload(p: ErrPayload): AutojsError;
export { fromErrPayload as errFromPayload };
/** 抛错（辅助）：把桥回包转成可抛异常。 */
export declare function throwErr(p: ErrPayload): never;
/**
 * 把「handler 同步抛出的异常」折成 [AutojsError]（InvokeHandler 包装面用）：
 * - 已是 [AutojsError] → 原样（码与明细都可信）；
 * - 带 `ERR_*` 字符串 `.code` 的对象 → 以该码折叠 —— N-API `napi_throw_error` 产的
 *   就是这种（普通 Error + `.code`）；若折成 `ERR_INVALID_PARAM`，「桥没连上」会被
 *   说成「参数错了」，那是撒谎（§1 诚实：真原因原样上抛）；
 * - 其余 → `ERR_INVALID_PARAM` + message（与 [RuntimeBridgeImpl.invoke] 的异常折叠同口径）。
 */
export declare function errFromThrown(e: unknown): AutojsError;
