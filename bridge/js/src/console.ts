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

import { runtimeBridge } from './runtime'

/** 日志级别（对齐 console.log/info/warn/error/debug 五档）。 */
export type ConsoleLevel = 'log' | 'info' | 'warn' | 'error' | 'debug'

/** 队列错误（发送丢包/背压时；对齐 §7.3 queueError 回调）。 */
export interface ConsoleQueueError {
  readonly level: ConsoleLevel
  readonly reason: string
}

type QueueErrorListener = (e: ConsoleQueueError) => void

const queueErrorListeners = new Set<QueueErrorListener>()

function emitQueueError(level: ConsoleLevel, reason: string): void {
  if (queueErrorListeners.size === 0) return
  const e: ConsoleQueueError = { level, reason }
  for (const l of [...queueErrorListeners]) l(e)
}

/** 参数序列化：JSON 可序列化原样走；其余（循环/函数/BigInt）降级为 String()，绝不抛。 */
function serializeArgs(args: readonly unknown[]): string {
  const parts: string[] = []
  for (const a of args) {
    try {
      if (typeof a === 'string') {
        parts.push(a)
      } else {
        const j = JSON.stringify(a)
        parts.push(j === undefined ? String(a) : j)
      }
    } catch {
      parts.push(String(a))
    }
  }
  return parts.join(' ')
}

async function send(level: ConsoleLevel, args: readonly unknown[]): Promise<void> {
  const text = serializeArgs(args)
  try {
    // 数据面 fire-and-forget：短 TTL，失败即丢（不阻塞脚本事件循环）。
    await runtimeBridge.invoke('console', 'log', { level, text }, { ttl: 2_000 })
  } catch (e) {
    emitQueueError(level, e instanceof Error ? e.message : String(e))
  }
}

export const consoleSink = {
  log(...args: unknown[]): Promise<void> { return send('log', args) },
  info(...args: unknown[]): Promise<void> { return send('info', args) },
  warn(...args: unknown[]): Promise<void> { return send('warn', args) },
  error(...args: unknown[]): Promise<void> { return send('error', args) },
  debug(...args: unknown[]): Promise<void> { return send('debug', args) },

  /** 背压/丢包回调（§7.3 queueError）。返回退订函数。 */
  onQueueError(listener: QueueErrorListener): () => void {
    queueErrorListeners.add(listener)
    return () => { queueErrorListeners.delete(listener) }
  },
}
