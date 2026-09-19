/// <reference types="node" />

import net from 'node:net'
import { BridgeEnvelope, BridgeRequest, BridgeResponse, InvokeHandler } from './bridge'
import { AutojsError } from './errors'
import { runtimeBridge } from './runtime'

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
  socketPath?: string
  /** 单帧上限（防恶意/损坏的巨型 frame 吞内存）。 */
  maxFrameBytes?: number
  /** 连接超时（毫秒）。 */
  connectTimeout?: number
}

const DEFAULT_MAX_FRAME = 64 * 1024 * 1024
const FALLBACK_CONNECT_TIMEOUT = 10_000

export class SocketBootstrap {
  readonly socketPath: string
  readonly maxFrameBytes: number
  readonly connectTimeout: number

  private socket: net.Socket | null = null
  private buffer: Buffer = Buffer.alloc(0)
  private queue: BridgeRequest[] = []
  private draining = false
  private closed = false

  constructor(opts: SocketBootstrapOptions = {}) {
    const p = opts.socketPath ?? process.env.AUTOSCRIPT_HOST_SOCKET
    if (!p) throw new Error('SocketBootstrap 需要 socketPath 或 AUTOSCRIPT_HOST_SOCKET')
    this.socketPath = p
    this.maxFrameBytes = opts.maxFrameBytes ?? DEFAULT_MAX_FRAME
    this.connectTimeout = opts.connectTimeout ?? FALLBACK_CONNECT_TIMEOUT
  }

  onSocketError: ((e: Error) => void) | null = null

  private emitSocketError(e: Error): void {
    // 桥断链：宿主依赖方（看门狗/连接熔断）监听；默认无监听不抛。
    if (this.onSocketError) this.onSocketError(e)
  }

  get connected(): boolean {
    return this.socket !== null && !this.closed
  }

  /** 建立连接（幂等；后续重连由宿主层决定，不自动重试 —— 对齐桥连接熔断）。 */
  connect(): Promise<void> {
    if (this.closed) return Promise.reject(new Error('SocketBootstrap 已关闭'))
    if (this.socket) return Promise.resolve()
    return new Promise((resolve, reject) => {
      const sock = net.connect(this.socketPath)
      const timer = setTimeout(() => {
        reject(new AutojsError({ code: 'ERR_ENGINE_STOPPED', detail: `socket 连接超时: ${this.socketPath}` }))
        sock.destroy()
      }, this.connectTimeout)

      sock.on('connect', () => {
        clearTimeout(timer)
        this.socket = sock
        resolve()
      })
      sock.on('data', (chunk: Buffer) => this.onData(chunk))
      sock.on('error', (e) => {
        clearTimeout(timer)
        this.close()
        this.emitSocketError(e)
      })
      sock.on('close', () => {
        clearTimeout(timer)
        this.close()
      })
    })
  }

  /**
   * [InvokeHandler]：把请求编码成信封入队。返回 undefined = 已投递、等 [handleResponse]。
   * 未连接时同步抛错（对齐 invoke 的 ERR_ENGINE_STOPPED 快速拒绝）。
   */
  readonly handler: InvokeHandler = (ns, method, payload, reqId, ttl) => {
    this.checkConnected()
    this.enqueue(BridgeEnvelope.encodeRequest({ id: reqId, ns, m: method, payload, ttl, side: null }))
    return undefined
  }

  private checkConnected(): void {
    if (!this.socket || this.closed) {
      throw new AutojsError({ code: 'ERR_ENGINE_STOPPED', detail: '桥 socket 未连接' })
    }
  }

  /** 安装到单例桥（重复 install 由 RuntimeBridge 拒绝）。 */
  install(): void {
    runtimeBridge.install(this.handler)
  }

  private enqueue(req: BridgeRequest): void {
    this.queue.push(req)
    if (!this.draining) this.drain()
  }

  /** 队列续写：只在一个 drain 循环里动队列，socket 写满即等 drain 再回。 */
  private drain(): void {
    if (this.draining) return
    const sock = this.socket
    if (!sock || this.closed) return
    this.draining = true
    const frame = Buffer.concat([Buffer.from(JSON.stringify(this.queue[0]), 'utf8'), Buffer.from([0x0a])])
    if (!sock.write(frame)) {
      sock.once('drain', () => {
        this.draining = false
        this.drain()
      })
      return
    }
    this.queue.shift()
    if (this.queue.length > 0) {
      process.nextTick(() => this.drain())
    } else {
      this.draining = false
    }
  }

  private onData(chunk: Buffer): void {
    this.buffer = this.buffer.length === 0 ? chunk : Buffer.concat([this.buffer, chunk])
    if (this.buffer.length > this.maxFrameBytes) {
      this.close()
      this.emitSocketError(new Error(`桥帧超过上限 ${this.maxFrameBytes} 字节`))
      return
    }
    let nl: number
    while ((nl = this.buffer.indexOf(0x0a)) !== -1) {
      const line = this.buffer.subarray(0, nl)
      this.buffer = this.buffer.subarray(nl + 1)
      if (line.length === 0) continue
      let frame: BridgeResponse
      try {
        frame = JSON.parse(line.toString('utf8')) as BridgeResponse
      } catch {
        this.emitSocketError(new Error('桥响应帧非法 JSON'))
        continue
      }
      if (frame && (frame.t === 'ok' || frame.t === 'err')) {
        runtimeBridge.handleResponse(frame)
      }
      // 其他 t 值（事件帧）由事件订阅层处理（P1）；本层忽略。
    }
  }

  close(): void {
    if (this.closed) return
    this.closed = true
    if (this.socket) {
      const s = this.socket
      this.socket = null
      s.end(() => s.destroy())
    }
  }
}

/** 便捷入口：按 opts 创建 + 连接 + 安装。 */
export async function connectBootstrap(opts: SocketBootstrapOptions = {}): Promise<SocketBootstrap> {
  const b = new SocketBootstrap(opts)
  await b.connect()
  b.install()
  return b
}