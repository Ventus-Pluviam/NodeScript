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
