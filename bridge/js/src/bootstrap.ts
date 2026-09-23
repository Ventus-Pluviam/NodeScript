/// <reference types="node" />

import net from 'node:net'
import { BridgeEnvelope, BridgeRequest, BridgeResponse, InvokeHandler } from './bridge'
import { AutojsError, ErrCode, errFromThrown } from './errors'
import { runtimeBridge } from './runtime'

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

/** N-API addon 的 JS 面（`bridge_addon.cc` 导出；结构型契约 —— 单测可注入假实现）。 */
export interface BridgeNativeAddon {
  /** 宿主注入已连 socket fd（**本面不调** —— §7.5 建连归宿主，见 [NativeBootstrap] KDoc）。 */
  setSocketFd(fd: number): void
  /** 一次性建 data 面 TSF：回包行文本回调（二次调用 addon 侧抛 ERR_INVALID_PARAM）。 */
  setup(onFrame: (line: string) => void): void
  /** 投递请求帧（InvokeHandler 同形）；未注 fd / 写失败 → 同步抛 `ERR_*`（`.code` 在普通 Error 上）。 */
  invoke(ns: string, method: string, payloadJson: string | null, reqId: number, ttl: number): void
}

export interface NativeBootstrapOptions {
  /** addon 模块实例；缺省 `require(AUTOSCRIPT_BRIDGE_ADDON)`（宿主 spawn 注入的路径）。 */
  addon?: BridgeNativeAddon
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
export class NativeBootstrap {
  readonly addon: BridgeNativeAddon

  /** 响应帧非法 JSON 时的诊断钩子（缺省 null：与 `onSocketError` 同款"默认不抛"）。 */
  onFrameError: ((e: Error) => void) | null = null

  private setupDone = false

  constructor(opts: NativeBootstrapOptions = {}) {
    if (opts.addon) {
      this.addon = opts.addon
      return
    }
    const p = process.env.AUTOSCRIPT_BRIDGE_ADDON
    if (!p) {
      throw new AutojsError({
        code: ErrCode.ENGINE_STOPPED,
        detail: '缺 AUTOSCRIPT_BRIDGE_ADDON（宿主未预载 addon：离线/未接线，快速拒绝不悬挂）',
      })
    }
    // eslint-disable-next-line @typescript-eslint/no-var-requires -- 动态路径 require N-API 模块（CJS 产物）
    this.addon = require(p) as BridgeNativeAddon
  }

  /**
   * 接结算面（幂等：本实例二次调用 no-op —— addon 侧 `setup` 是一次性的，
   * 重复 attach 场景由 [runtimeBridge.install] 的单例拒绝兜底，两层各管各的）。
   */
  setup(): void {
    if (this.setupDone) return
    this.addon.setup((line) => this.onFrame(line))
    this.setupDone = true
  }

  private onFrame(line: string): void {
    let frame: BridgeResponse
    try {
      frame = JSON.parse(line) as BridgeResponse
    } catch (e) {
      this.onFrameError?.(new Error(`桥响应帧非法 JSON: ${e instanceof Error ? e.message : String(e)}`))
      return
    }
    if (frame && (frame.t === 'ok' || frame.t === 'err')) {
      runtimeBridge.handleResponse(frame)
    }
    // 其他 t 值（事件帧 P1）由事件订阅层处理；本层忽略 —— 与 SocketBootstrap.onData 同口径
  }

  /** [InvokeHandler]：addon.invoke 直接注入；undefined = 已投递，等 onFrame 结算。 */
  readonly handler: InvokeHandler = (ns, method, payload, reqId, ttl) => {
    try {
      this.addon.invoke(ns, method, payload, reqId, ttl)
    } catch (e) {
      throw errFromThrown(e)
    }
    return undefined
  }

  /** 安装到单例桥（重复 install 由 runtimeBridge 拒绝，与 SocketBootstrap 同口径）。 */
  install(): void {
    runtimeBridge.install(this.handler)
  }
}

/**
 * 嵌入式宿主便捷接线（同步 —— 无 IO：fd 已由宿主注入，本函数只做 setup + install）。
 * 打包入口/脚本首行调一次；返回的实例挂 [NativeBootstrap.onFrameError] 诊断钩子。
 * 可 `await`（值被包成已兑现 Promise），与 `connectBootstrap` 的用法对称。
 */
export function attachNative(opts: NativeBootstrapOptions = {}): NativeBootstrap {
  const b = new NativeBootstrap(opts)
  b.setup()      // 先接结算面：TSF 就位后任何回包都有人收
  b.install()
  return b
}
