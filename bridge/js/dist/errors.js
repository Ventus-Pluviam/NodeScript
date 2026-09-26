"use strict";
/**
 * 错误目录（docs/framework-design.md §7.6 / §12.1）。
 * 与 :domain 的 ErrorCode 目录一一致：JS 侧 instanceof AutojsError 可策略化 try/catch。
 * 新增错误码需同步 §7.6 与 :domain:core.ErrorCode。
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.ERROR_CODES = exports.NotFoundError = exports.AutojsError = void 0;
exports.errFromPayload = fromErrPayload;
exports.throwErr = throwErr;
exports.errFromThrown = errFromThrown;
/**
 * 统一异常类型：脚本侧可 `catch (e) { if (e instanceof AutojsError) … }` 策略化。
 * 由桥把 ErrPayload 还原为实例；模块封装层不得悄悄吞掉。
 */
class AutojsError extends Error {
    code;
    detail;
    module;
    method;
    javaClass;
    javaStack;
    constructor(payload) {
        super(`[${payload.code}]${payload.method ? ` ${payload.module}.${payload.method}` : ''}${payload.detail ? `: ${payload.detail}` : ''}`);
        this.name = 'AutojsError';
        this.code = payload.code;
        this.detail = payload.detail;
        this.module = payload.module;
        this.method = payload.method;
        this.javaClass = payload.javaClass;
        this.javaStack = payload.javaStack;
    }
    /** 快捷判错：`err.is('ERR_TIMEOUT')`。 */
    is(code) {
        return this.code === code;
    }
}
exports.AutojsError = AutojsError;
/** 未找到（UiSelector findOne 无匹配）——对齐 AutoJsPro v9 的 NotFoundError 语义（§7.6）。 */
class NotFoundError extends AutojsError {
    constructor(detail) {
        super({ code: "ERR_NOT_FOUND" /* ErrCode.NOT_FOUND */, detail });
        this.name = 'NotFoundError';
    }
}
exports.NotFoundError = NotFoundError;
/** 错误目录常量（与 enum 等值，供数组/字典场景）。 */
exports.ERROR_CODES = [
    'ERR_TIMEOUT',
    'ERR_STALE_HANDLE',
    'ERR_PERMISSION_DENIED',
    'ERR_SERVICE_DISABLED',
    'ERR_SCREEN_LOCKED',
    'ERR_BLACK_FRAME',
    'ERR_CAPTURE_DENIED',
    'ERR_ENGINE_STOPPED',
    'ERR_ENGINE_CRASHED',
    'ERR_NOT_IMPLEMENTED',
    'ERR_INVALID_PARAM',
    'ERR_FILE_NOT_FOUND',
    'ERR_FILE_EXISTS',
    'ERR_DISK_FULL',
    'ERR_NOT_FOUND',
    'ERR_NPM_SPAWN_BLOCKED',
    'ERR_NOT_SUPPORTED',
    'ERR_REGISTRY_UNAVAILABLE',
    'ERR_NPM_LOWMEM',
    'ERR_IO',
];
function fromErrPayload(p) {
    if (p.code === "ERR_NOT_FOUND" /* ErrCode.NOT_FOUND */)
        return new NotFoundError(p.detail ?? undefined);
    return new AutojsError(p);
}
/** 抛错（辅助）：把桥回包转成可抛异常。 */
function throwErr(p) {
    throw fromErrPayload(p);
}
/**
 * 把「handler 同步抛出的异常」折成 [AutojsError]（InvokeHandler 包装面用）：
 * - 已是 [AutojsError] → 原样（码与明细都可信）；
 * - 带 `ERR_*` 字符串 `.code` 的对象 → 以该码折叠 —— N-API `napi_throw_error` 产的
 *   就是这种（普通 Error + `.code`）；若折成 `ERR_INVALID_PARAM`，「桥没连上」会被
 *   说成「参数错了」，那是撒谎（§1 诚实：真原因原样上抛）；
 * - 其余 → `ERR_INVALID_PARAM` + message（与 [RuntimeBridgeImpl.invoke] 的异常折叠同口径）。
 */
function errFromThrown(e) {
    if (e instanceof AutojsError)
        return e;
    const raw = e;
    const code = typeof e === 'object' && e !== null && typeof raw?.code === 'string' && raw.code.startsWith('ERR_')
        ? raw.code
        : "ERR_INVALID_PARAM" /* ErrCode.INVALID_PARAM */;
    const detail = e instanceof Error ? e.message : String(e);
    return new AutojsError({ code, detail });
}
