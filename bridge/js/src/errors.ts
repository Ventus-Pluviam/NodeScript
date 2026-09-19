/**
 * 错误目录（docs/framework-design.md §7.6 / §12.1）。
 * 与 :domain 的 ErrorCode 目录一一致：JS 侧 instanceof AutojsError 可策略化 try/catch。
 * 新增错误码需同步 §7.6 与 :domain:core.ErrorCode。
 */

/** 机器可判错误码（与 :domain:core.ErrorCode.code 逐字一致）。 */
export const enum ErrCode {
  TIMEOUT = 'ERR_TIMEOUT',
  STALE_HANDLE = 'ERR_STALE_HANDLE',
  PERMISSION_DENIED = 'ERR_PERMISSION_DENIED',
  SERVICE_DISABLED = 'ERR_SERVICE_DISABLED',
  SCREEN_LOCKED = 'ERR_SCREEN_LOCKED',
  BLACK_FRAME = 'ERR_BLACK_FRAME',
  CAPTURE_DENIED = 'ERR_CAPTURE_DENIED',
  ENGINE_STOPPED = 'ERR_ENGINE_STOPPED',
  ENGINE_CRASHED = 'ERR_ENGINE_CRASHED',
  NOT_IMPLEMENTED = 'ERR_NOT_IMPLEMENTED',
  INVALID_PARAM = 'ERR_INVALID_PARAM',
  FILE_NOT_FOUND = 'ERR_FILE_NOT_FOUND',
  DISK_FULL = 'ERR_DISK_FULL',
  NOT_FOUND = 'ERR_NOT_FOUND',
  NPM_SPAWN_BLOCKED = 'ERR_NPM_SPAWN_BLOCKED',
  NOT_SUPPORTED = 'ERR_NOT_SUPPORTED',
  REGISTRY_UNAVAILABLE = 'ERR_REGISTRY_UNAVAILABLE',
  NPM_LOWMEM = 'ERR_NPM_LOWMEM',
}

/** 桥回包中的可序列化错误骨架（跨进程往返的唯一错误载体）。 */
export interface ErrPayload {
  code: string
  detail?: string | null
  module?: string
  method?: string
  javaClass?: string
  javaStack?: string
}

/**
 * 统一异常类型：脚本侧可 `catch (e) { if (e instanceof AutojsError) … }` 策略化。
 * 由桥把 ErrPayload 还原为实例；模块封装层不得悄悄吞掉。
 */
export class AutojsError extends Error {
  readonly code: string
  readonly detail?: string | null
  readonly module?: string
  readonly method?: string
  readonly javaClass?: string
  readonly javaStack?: string

  constructor(payload: ErrPayload) {
    super(`[${payload.code}]${payload.method ? ` ${payload.module}.${payload.method}` : ''}${payload.detail ? `: ${payload.detail}` : ''}`)
    this.name = 'AutojsError'
    this.code = payload.code
    this.detail = payload.detail
    this.module = payload.module
    this.method = payload.method
    this.javaClass = payload.javaClass
    this.javaStack = payload.javaStack
  }

  /** 快捷判错：`err.is('ERR_TIMEOUT')`。 */
  is(code: string): boolean {
    return this.code === code
  }
}

/** 未找到（UiSelector findOne 无匹配）——对齐 AutoJsPro v9 的 NotFoundError 语义（§7.6）。 */
export class NotFoundError extends AutojsError {
  constructor(detail?: string) {
    super({ code: ErrCode.NOT_FOUND, detail })
    this.name = 'NotFoundError'
  }
}

/** 错误目录常量（与 enum 等值，供数组/字典场景）。 */
export const ERROR_CODES: readonly string[] = [
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
  'ERR_DISK_FULL',
  'ERR_NOT_FOUND',
  'ERR_NPM_SPAWN_BLOCKED',
  'ERR_NOT_SUPPORTED',
  'ERR_REGISTRY_UNAVAILABLE',
  'ERR_NPM_LOWMEM',
]

function fromErrPayload(p: ErrPayload): AutojsError {
  if (p.code === ErrCode.NOT_FOUND) return new NotFoundError(p.detail ?? undefined)
  return new AutojsError(p)
}

export { fromErrPayload as errFromPayload }

/** 抛错（辅助）：把桥回包转成可抛异常。 */
export function throwErr(p: ErrPayload): never {
  throw fromErrPayload(p)
}