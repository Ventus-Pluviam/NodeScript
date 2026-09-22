/**
 * 引擎进程池模型（docs/framework-design.md §8.1/§8.2）：并发上限 = 池容量，超载排队（绝不静默丢）。
 * 对应 :app-service:runtime EnginePool 语义 + :domain:engine ScriptEngine / RuntimeChannel / EngineSessionHandle。
 * 面：exec → 会话句柄（cancel = 四步 quiesce；onExit；命名通道）。
 */

import { runtimeBridge } from './runtime'

/** 运行簿记收据（与 :domain EngineRunReceipt 对齐：runId + HandleRef）。 */
export interface EngineRunReceipt {
  readonly runId: number
  readonly handle: { refId: number; generation: number }
}

/** 一次运行请求（与 :domain EngineRunRequest 对齐）。 */
export interface EngineRunRequest {
  readonly projectId: string
  readonly scriptPath: string
  readonly args?: readonly string[]
  readonly runNonce?: string | null
  readonly timeoutMillis?: number | null
}

/** 池统计（与 :app-service:runtime EnginePool 统计对齐）。 */
export interface EnginePoolStats {
  readonly capacity: number
  readonly free: number
  readonly busy: number
}

/** 引擎状态（与 :domain ScriptEngine.EngineStatus 对齐）。 */
export type EngineStatus =
  | 'IDLE' | 'BOOTING' | 'RUNNING' | 'QUIESCING' | 'STOPPED' | 'CRASHED'

/** 池获取结果（与 runtime 的 PoolAcquireOutcome 对齐：granted/timedOut/failed）。 */
export type PoolAcquireOutcome =
  | { kind: 'granted'; runId: number; handle: { refId: number; generation: number } }
  | { kind: 'timedOut' }
  | { kind: 'failed'; message: string }

/** 通道订阅句柄（与 :domain ChannelSubscription 对齐）。 */
export interface ChannelSubscription {
  cancel(): void
}

/**
 * 命名双向通道（§8 RuntimeChannel）：脚本↔宿主 JSON 事件；
 * 大二进制走 side-channel（§7.4），不入事件载荷。
 */
export interface RuntimeChannel {
  readonly name: string
  /** 脚本 → 宿主：发事件（JSON 字符串载荷）。 */
  emit(event: string, payload?: string | null, opts?: { timeout?: number }): Promise<void>
  /**
   * 宿主 → 脚本：订阅事件；返回取消句柄。
   *
   * 拉取实现（节流轮询 `channelDrain`，游标 `sinceSeq`）：与 Kotlin 侧"缓冲 + 游标，
   * 不做回调推送"语义一致（见 EnginesNamespaceHandler 注释）。轮询失败吞掉等下一轮
   * （宿主消失/通道关闭由 close/stop 路径表达，不在这里炸订阅）；定时器 unref，
   * 不保活脚本事件循环（与 startHeartbeat 同一条纪律）。
   */
  on(
    event: string,
    listener: (payload: string | null) => void,
    opts?: { pollMillis?: number },
  ): ChannelSubscription
  /** 关闭并丢弃缓冲（幂等：重复 close 不再发桥调用）。 */
  close(opts?: { timeout?: number }): Promise<void>
}

/** 通道事件帧（与 Kotlin ChannelEvent 对齐：seq/event/payload）。 */
export interface ChannelEvent {
  readonly seq: number
  readonly event: string
  readonly payload: string | null
}

/** `channel` wire 回包（Kotlin `{name,channelId}`），`channel()` 包成 [EngineChannel]。 */
export interface ChannelWire {
  readonly name: string
  readonly channelId: number
}

/** drain 回包（Kotlin `{last,events}`）。 */
export interface ChannelDrain {
  readonly last: number
  readonly events: readonly ChannelEvent[]
}

/**
 * 命名通道的 JS 实现（`engines.channel()` 的返回值）。
 *
 * - `emit` → `channelEmit {channelId,event,payload}`（空事件名由宿主判
 *   ERR_INVALID_PARAM，本层不预检 —— 预检会制造"两处校验口径"漂移）；
 * - `on` → 节流轮询 `channelDrain {channelId,sinceSeq,max}`，本地游标只进不退，
 *   按事件名过滤后回调；
 * - `close` → `channelClose {channelId}`，本地先置位（轮询即停），重复调用 no-op。
 */
export class EngineChannel implements RuntimeChannel {
  readonly name: string
  readonly channelId: number
  private closed = false

  constructor(name: string, channelId: number) {
    this.name = name
    this.channelId = channelId
  }

  get isClosed(): boolean {
    return this.closed
  }

  async emit(event: string, payload: string | null = null, opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke(
      'engines',
      'channelEmit',
      { channelId: this.channelId, event, payload },
      { ttl: opts.timeout ?? 10_000 },
    )
  }

  on(
    event: string,
    listener: (payload: string | null) => void,
    opts: { pollMillis?: number } = {},
  ): ChannelSubscription {
    const period = opts.pollMillis ?? 500
    if (!Number.isFinite(period) || period <= 0) {
      throw new Error(`pollMillis 必须 > 0: ${period}`)
    }
    let cursor = 0
    let cancelled = false
    let timer: NodeJS.Timeout | null = null
    const tick = async (): Promise<void> => {
      timer = null
      if (cancelled || this.closed) return
      try {
        const out = (await runtimeBridge.invoke(
          'engines',
          'channelDrain',
          { channelId: this.channelId, sinceSeq: cursor, max: DEFAULT_DRAIN_MAX },
          { ttl: Math.max(period * 2, 2_000) },
        )) as ChannelDrain
        cursor = out.last ?? cursor
        for (const e of out.events ?? []) {
          if (e.event === event) listener(e.payload ?? null)
        }
      } catch {
        /* 轮询失败吞掉等下一轮：宿主抖动不该炸掉订阅；真死了 close/stop 会表达 */
      }
      if (!cancelled && !this.closed) {
        timer = setTimeout(() => {
          void tick()
        }, period)
        unrefTimer(timer)
      }
    }
    timer = setTimeout(() => {
      void tick()
    }, period)
    unrefTimer(timer)
    return {
      cancel: () => {
        cancelled = true
        if (timer) {
          clearTimeout(timer)
          timer = null
        }
      },
    }
  }

  async close(opts: { timeout?: number } = {}): Promise<void> {
    if (this.closed) return
    this.closed = true
    await runtimeBridge.invoke(
      'engines',
      'channelClose',
      { channelId: this.channelId },
      { ttl: opts.timeout ?? 10_000 },
    )
  }
}

/** drain 单批上限（与 Kotlin 缺省 max=128 同口径）。 */
export const DEFAULT_DRAIN_MAX = 128

function unrefTimer(t: NodeJS.Timeout): void {
  const u = (t as unknown as { unref?: () => void }).unref
  if (typeof u === 'function') u.call(t)
}

/** 退出信息（与 :domain CrashInfo 对齐）。 */
export interface CrashInfo {
  cause?: string | null
  message?: string | null
}

/**
 * engines.exec() 会话句柄：cancel（优雅四步 quiesce）+ onExit（STOPPED/CRASHED 回调）
 * + 命名通道。对应 :domain EngineSessionHandle。
 */
export interface EngineSession {
  readonly runId: number
  readonly handle: { refId: number; generation: number }
  cancel(opts?: { timeout?: number; signal?: AbortSignal }): Promise<boolean>
  onExit(listener: (info: CrashInfo | null) => void): ChannelSubscription
  get channel(): RuntimeChannel | null
}

export const engines = {
  /**
   * 启动一次执行（§8 池仲裁；返回会话句柄）。同引擎一次一脚本；运行态由 RuntimeController 仲裁。
   * 超载排队，不做静默丢弃。
   */
  async exec(request: EngineRunRequest, opts: { timeout?: number; signal?: AbortSignal } = {}): Promise<EngineSession> {
    return (await runtimeBridge.invoke('engines', 'exec', request, {
      ttl: opts.timeout ?? 15_000,
      signal: opts.signal,
    })) as EngineSession
  },

  /** 池容量快照（容量/空闲/占用）。 */
  async poolStats(opts: { timeout?: number } = {}): Promise<EnginePoolStats> {
    return (await runtimeBridge.invoke('engines', 'poolStats', null, { ttl: opts.timeout ?? 5_000 })) as EnginePoolStats
  },

  /** 请求侧主动停止（PoolAcquireOutcome 语义）：runId 未发行 → failed；发行后取消是 EngineSession.cancel。 */
  async stop(runId: number, opts: { timeout?: number } = {}): Promise<boolean> {
    return (await runtimeBridge.invoke('engines', 'stop', { runId }, { ttl: opts.timeout ?? 10_000 })) === true
  },

  /**
   * 打开（或复用）命名通道（§8：同 host 的通道生命周期由 :app-service:runtime 管理）。
   * 回包包成 [EngineChannel]（此前是 wire 原样 `as RuntimeChannel` —— `.emit()` 当场
   * TypeError 的空壳，已补实）。
   */
  async channel(name: string, opts: { timeout?: number } = {}): Promise<RuntimeChannel> {
    const wire = (await runtimeBridge.invoke('engines', 'channel', { name }, {
      ttl: opts.timeout ?? 10_000,
    })) as ChannelWire
    return new EngineChannel(wire.name, wire.channelId)
  },

  /**
   * 心跳打点（§8.4 缺口②的引擎侧源头）：宿主据此算「距上次心跳多久」，看门狗据此判失联。
   *
   * `seq` 单调递增，由本进程内计数器给出（见 [heartbeatSeq]）：宿主的账本只认递增序号，
   * 重复/乱序帧不被采纳（回 false）。**不要**为了"显得活着"而高频重发同一 seq ——
   * 那既骗不过账本，也会让积压帧把死掉之后的样子伪装成活的。
   */
  async heartbeat(runId: number, seq: number, opts: { timeout?: number } = {}): Promise<boolean> {
    return (await runtimeBridge.invoke(
      'engines',
      'heartbeat',
      { runId, seq },
      { ttl: opts.timeout ?? 2_000 },
    )) === true
  },
}

/** 心跳序号计数器（进程内单调）：宿主账本靠它分辨"新心跳"与"积压的旧心跳"。 */
let heartbeatSeq = 0
export function nextHeartbeatSeq(): number {
  heartbeatSeq += 1
  return heartbeatSeq
}

/**
 * 心跳定时打点（§8.4 缺口②）：按宿主周期发一次心跳，返回取消句柄。
 *
 * 周期取宿主 `heartbeatPeriodMillis`（装配侧经 [installHeartbeatPeriod] 注入；缺省 500ms）。
 * 原生宿主侧由引擎启动脚本在 isolate 起来后调用；桌面/测试可直接调 [engines.heartbeat]。
 *
 * 语义：unref 的定时器不保活事件循环（§5.3 `napi_unref_threadsafe_function` 同思路）——
 * 心跳不该让一个"脚本已跑完"的进程赖着不死。
 */
export function startHeartbeat(runId: number, opts: { periodMillis?: number } = {}): () => void {
  const period = opts.periodMillis ?? heartbeatPeriodMillis
  const t = setInterval(() => {
    void engines.heartbeat(runId, nextHeartbeatSeq()).catch(() => {
      /* 心跳失败不炸脚本：宿主的 CPU/RSS 两路仍在兜底；连续失败由看门狗按失联处理 */
    })
  }, period)
  if (typeof (t as unknown as { unref?: () => void }).unref === 'function') {
    (t as unknown as { unref: () => void }).unref()
  }
  return () => clearInterval(t)
}

/** 宿主注入的心跳周期（毫秒；缺省 500ms，与 §8.4 的周期同源）。 */
let heartbeatPeriodMillis = 500
export function installHeartbeatPeriod(millis: number): void {
  if (!Number.isFinite(millis) || millis <= 0) {
    throw new Error(`heartbeatPeriodMillis 必须 > 0: ${millis}`)
  }
  heartbeatPeriodMillis = millis
}

/**
 * 运行句柄代理（§7.4 句柄面）：exec 签发；cancel = 四步 quiesce（§8.3），onExit 绑退出事件。
 * 与 Kotlin :domain EngineSessionHandle 对齐（engine/receipt/channel/exitSink 由运行时实现注入）。
 */
export interface EngineSessionHandle {
  readonly runId: number
  readonly handle: { refId: number; generation: number }
}