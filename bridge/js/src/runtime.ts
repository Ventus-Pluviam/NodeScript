/// <reference types="node" />

import { BridgeResponse, InvokeHandler } from './bridge'
import { AutojsError, ErrPayload, errFromPayload } from './errors'

/**
 * 调用的任务句柄（可取消；与 RequestRegistry 的 cancel 语义对齐）。
 * 取消触发 ERR_TIMEOUT 收尾，符合「每次跨进程操作必有 TTL」铁律（§7.1 铁律3）。
 */
export interface InvocationHandle {
  cancel(): void
}

/**
 * 调用选项：TTL（默认 [DEFAULT_TTL]）+ 可选 AbortSignal（§12.1 超时/取消）。
 */
export interface InvokeOptions {
  /** 本条调用的 TTL（毫秒）。发送方超时以 ERR_TIMEOUT 收尾。 */
  ttl?: number
  signal?: AbortSignal
}

const DEFAULT_TTL_MILLIS = 5_000

/** 当前调用 ID 分配（进程内单调；与 BridgeRequest.id 对齐）。 */
let nextReqId = 1
function reqId(): number {
  const id = nextReqId
  nextReqId += 1
  if (nextReqId > Number.MAX_SAFE_INTEGER) nextReqId = 1
  return id
}

function makeTimeout(ttlMs: number): AutojsError {
  return new AutojsError({ code: 'ERR_TIMEOUT', detail: `桥调用超过 TTL ${ttlMs}ms` })
}

/** ok 载荷为 JSON 文本（§7.5）；facade 层统一 parse 后交给模块。 */
function parsePayload(payload: string | null): unknown {
  return payload === null ? null : JSON.parse(payload)
}

/**
 * in-flight 登记（§7.5 requestId 关联）：JS 侧只按 id 记账（Kotlin 侧 RequestRegistry 有完整
 * TTL/去重语义）。host 回投的 ok/err 响应经 [handleResponse] 按 id 结算。
 */
interface PendingInvocation {
  settle(resp: BridgeResponse): void
}
const inflight = new Map<number, PendingInvocation>()

/**
 * 单例桥（§7.1 RuntimeBridge）：requestId 生成/关联、TTL、错误折叠、in-flight 记账。
 * - [install] 由 bootstrap/宿主注入投递函数 `(ns, method, payloadJson, reqId, ttl)`：
 *   - resolve/返回非 undefined → 视为「内联答案」立即结算（bootstrap 内联路径）；
 *   - resolve/返回 undefined → 表示已投递、等响应，由 [handleResponse] 按 id 结算。
 * - [handleResponse] 宿主在收到 ok/err 响应信封时回调 → 按 id settle 挂起的调用；
 * - TTL 单定时器驱动：到点 cancel → ERR_TIMEOUT，同时释放 in-flight，杜绝悬挂记账。
 * - 同步抛错/拒绝 → ERR_INVALID_PARAM 折叠（对齐 BridgeRouter 的 handler 异常分类）。
 */
export class RuntimeBridgeImpl {
  private handler: InvokeHandler | null = null

  /** 安装宿主投递回调（bootstrap/插件在引擎启动时注入 ESM/UMD addon）。 */
  install(handler: InvokeHandler): void {
    if (this.handler) throw new Error('RuntimeBridge 已安装，不允许重复 install')
    this.handler = handler
  }

  get installed(): boolean {
    return this.handler !== null
  }

  /** 宿主在收到 ok/err 响应时回调（Kotlin Router → N-API TSF → JS 事件循环）。 */
  handleResponse(resp: BridgeResponse): void {
    if (!inflight.has(resp.id)) return // 未知/已结算/超时已收割 → 丢弃（跨代/重复响应）
    const entry = inflight.get(resp.id)!
    inflight.delete(resp.id)
    entry.settle(resp)
  }

  /** 发起一条调用。模块层把参数对象 JSON.stringify 后作为 payload 传入。 */
  async invoke(namespace: string, method: string, params: unknown = null, opts: InvokeOptions = {}): Promise<unknown> {
    const h = this.handler
    if (!h) throw new AutojsError({ code: 'ERR_ENGINE_STOPPED', detail: 'RuntimeBridge 未安装宿主（引擎未就绪）' })

    const ttl = opts.ttl ?? DEFAULT_TTL_MILLIS
    const id = reqId()
    const payloadJson = params === null ? null : JSON.stringify(params)

    return new Promise<unknown>((resolve, reject) => {
      let settled = false
      let timer: NodeJS.Timeout | null = null

      const finish = (fn: () => void): void => {
        if (settled) return
        settled = true
        inflight.delete(id)
        if (timer) clearTimeout(timer)
        fn()
      }

      const onSettle = (resp: BridgeResponse): void => {
        if (resp.t === 'ok') {
          let parsed: unknown
          try {
            parsed = parsePayload(resp.payload)
          } catch (e) {
            finish(() => reject(new AutojsError({ code: 'ERR_INVALID_PARAM', detail: `响应载荷非法 JSON: ${String(e)}` })))
            return
          }
          finish(() => resolve(parsed))
        } else {
          finish(() => reject(errFromPayload({
            code: resp.code,
            detail: resp.detail ?? undefined,
          } satisfies ErrPayload)))
        }
      }
      const cancel = (): void => finish(() => reject(makeTimeout(ttl)))

      inflight.set(id, { settle: onSettle })
      timer = setTimeout(cancel, ttl)

      if (opts.signal) {
        const onAbort = (): void => cancel()
        opts.signal.addEventListener('abort', onAbort, { once: true })
        // once:true 在触发时自动移除，无句柄泄漏
      }

      // 投递：同步抛错 → 立即失败（对齐 BridgeRouter 的 handler 异常 → ERR_INVALID_PARAM）；
      // resolve/返回非 undefined → 内联答案；undefined → 等 handleResponse。
      let out: unknown
      try {
        out = h(namespace, method, payloadJson, id, ttl)
      } catch (e) {
        finish(() => reject(e instanceof AutojsError ? e : new AutojsError({ code: 'ERR_INVALID_PARAM', detail: String(e) })))
        return
      }
      if (out !== undefined && typeof (out as Promise<unknown>).then === 'function') {
        const p = out as Promise<unknown>
        p.then(
          (v) => { if (v !== undefined) finish(() => resolve(v)) },
          (e) => finish(() => reject(e instanceof AutojsError ? e : new AutojsError({ code: 'ERR_ENGINE_STOPPED', detail: String(e) }))),
        )
      } else if (out !== undefined) {
        finish(() => resolve(out))
      }
    })
  }
}

/** 单例引用：脚本侧 `require('auto')` 命名空间根对象挂 `auto.bridge`。 */
export const runtimeBridge = new RuntimeBridgeImpl()