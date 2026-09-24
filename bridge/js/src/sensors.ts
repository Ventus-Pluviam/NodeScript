import { runtimeBridge } from './runtime'

/**
 * 传感器采样点（与 `:domain` `SensorEvent` 逐字段对齐）。
 * `values` 语义随传感器类型（加速度计是三轴 m/s²，光照是单值 lux —— 见 v9 文档）；
 * `timestamp` 是系统 boot-time 纳秒，不透明（不做时钟换算）。
 */
export interface SensorEvent {
  /** 订阅内单调递增序号；环超界丢最旧时出现空洞（可见，不断流）。 */
  seq: number
  values: number[]
  accuracy: number
  timestamp: number
}

/** `SensorDelay` 四档（Kotlin 对偶 `:domain` `SensorDelay`；wire 传名字面量）。 */
export type SensorDelay = 'FASTEST' | 'GAME' | 'UI' | 'NORMAL'

/**
 * 采样订阅（`register` 的返回值；`unsubscribe` 即 `unregister(ref)`）。
 *
 * `on('change')` 只是 facade 的**节流轮询**（与 `EngineChannel.on` 同一条纪律，
 * 不是第二套订阅语义）：每次回调前 `drain` 拉走 `cursor` 之后的增量，
 * 空增量不回调（游标不动）—— 回调频率上限由 `intervalMs` 钳住（≥16ms），
 * 与底层 `SensorDelay` 无关（那是"系统多快采"，这是"脚本多快看"）。
 */
export interface SensorSubscription {
  /** 订阅持有的句柄游标（`drain` 的起点；`unsubscribe` 后失效）。 */
  cursor: number
  /** 拉一次增量并前移游标；空增量回空数组（游标不动）。 */
  drain(opts?: { max?: number; timeout?: number }): Promise<SensorEvent[]>
  /**
   * 订阅增量回调（节流轮询实现；返回停订函数）。
   * 注销语义：如果 `drain` 报 `ERR_STALE_HANDLE`（别人 `unregisterAll` 掐了订阅），
   * 轮询**自动停**并把最后一次错误交给 `onError`（不传则吞掉 —— 停是既定事实）。
   */
  on(event: 'change', cb: (events: SensorEvent[]) => void, opts?: { intervalMs?: number; max?: number; onError?: (e: unknown) => void }): () => void
  /** 注销本订阅（已知已关幂等成功；`unsubscribe` 后再 `drain` 即 `ERR_STALE_HANDLE`）。 */
  unsubscribe(opts?: { timeout?: number }): Promise<void>
}

/**
 * `sensors` 命名空间（§12.2 传感器面；Kotlin 对偶 `SensorsNamespaceHandler`）。
 *
 * wire 形状与 handler 逐字段对齐（`sensors.test.cjs` 的 mock 宿主复刻）：
 * `isSupported` 发 `{name}` → 裸 boolean、`register` 发 `{name,delay?}` →
 * `{ref:{refId,generation}}`、`unregister` 发 `{ref}` → `true`、
 * `unregisterAll` 空参 → `true`、`drain` 发 `{ref,sinceSeq?,max?}` →
 * `{first,last,events}`（空增量 `first == last == sinceSeq` + 空表）。
 *
 * 诚实口径（与 `:domain` `SensorSource` KDoc 同步，别各自漂移）：
 * - 未知名/设备缺席 → 抛 `ERR_NOT_SUPPORTED`（不是回 null —— v9 的
 *   `ignoresUnsupportedSensor=true` 回 null 是**本层的折叠**，见 `register` 的
 *   `ignoresUnsupported` 选项，SPI 只抛，flag 不进契约）；
 * - `registerListener` 回 false → 抛 `ERR_SERVICE_DISABLED`（传感器在但系统不给收）；
 * - 未知/跨代句柄 → 抛 `ERR_STALE_HANDLE`；
 * - 空白名/拼错 delay/非正 max/缺 ref → 宿主 `ERR_INVALID_PARAM`，且一次注册/注销都没发出去。
 *
 * 范围：P0 只做 motion/environment 名单（心率等 `BODY_SENSORS` 系要运行时权限，
 * 等有真实消费方再开 —— `isSupported('heart_rate')` 如实 false，不是抛错）。
 * 未约定的别名（`on`/`subscribe`/`watch`/`once`）两侧都不提供，
 * 宿主如实 `ERR_NOT_IMPLEMENTED`，本层也不发。
 */
export const sensors = {
  /** 设备是否支持该传感器（空白名 → 宿主 `ERR_INVALID_PARAM`，不是 false）。 */
  async isSupported(name: string, opts: { timeout?: number } = {}): Promise<boolean> {
    return (await runtimeBridge.invoke('sensors', 'isSupported', { name }, { ttl: opts.timeout ?? 5_000 })) === true
  },

  /**
   * 注册监听并返回订阅。
   * @param delay 缺省 `NORMAL`（省电侧默认，不是 FASTEST）；wire 传名字面量。
   * @param ignoresUnsupported v9 兼容折叠：为 true 且宿主报 `ERR_NOT_SUPPORTED` 时回
   *   `null`（其余错误照常抛 —— 不支持是"没这个传感器"，拒收/句柄错是"现场坏了"，
   *   后者吞掉就是谎）。
   */
  async register(
    name: string,
    opts: { delay?: SensorDelay; timeout?: number; ignoresUnsupported?: boolean } = {},
  ): Promise<SensorSubscription | null> {
    const payload: { name: string; delay?: SensorDelay } = { name }
    if (opts.delay !== undefined) payload.delay = opts.delay
    let ref: { refId: number; generation: number }
    try {
      const got = (await runtimeBridge.invoke('sensors', 'register', payload, { ttl: opts.timeout ?? 5_000 })) as {
        refId: number
        generation: number
      }
      ref = { refId: got.refId, generation: got.generation }
    } catch (e: unknown) {
      if (opts.ignoresUnsupported === true && (e as { code?: string })?.code === 'ERR_NOT_SUPPORTED') return null
      throw e
    }
    const sub: SensorSubscription = {
      cursor: 0,
      async drain(drainOpts: { max?: number; timeout?: number } = {}): Promise<SensorEvent[]> {
        const p: { ref: { refId: number; generation: number }; sinceSeq: number; max?: number } = {
          ref, sinceSeq: sub.cursor,
        }
        if (drainOpts.max !== undefined) p.max = drainOpts.max
        const batch = (await runtimeBridge.invoke('sensors', 'drain', p, { ttl: drainOpts.timeout ?? 5_000 })) as {
          first: number
          last: number
          events: SensorEvent[]
        }
        if (batch.events.length > 0) sub.cursor = batch.last
        return batch.events
      },
      on(event: 'change', cb: (events: SensorEvent[]) => void, onOpts: { intervalMs?: number; max?: number; onError?: (e: unknown) => void } = {}): () => void {
        if (event !== 'change') throw new Error(`未知 sensors 事件: ${event}`)
        const intervalMs = Math.max(onOpts.intervalMs ?? 200, 16)
        let stopped = false
        let timer: ReturnType<typeof setInterval> | undefined
        const tick = (): void => {
          void sub.drain({ max: onOpts.max }).then((events) => {
            if (!stopped && events.length > 0) cb(events)
          }).catch((e: unknown) => {
            // 订阅已死（别人 unregisterAll）→ 自动停：停是既定事实，不重试。
            if ((e as { code?: string })?.code === 'ERR_STALE_HANDLE') {
              stopped = true
              if (timer !== undefined) clearInterval(timer)
            }
            if (onOpts.onError !== undefined) onOpts.onError(e)
          })
        }
        timer = setInterval(tick, intervalMs)
        return () => { stopped = true; if (timer !== undefined) clearInterval(timer) }
      },
      async unsubscribe(uOpts: { timeout?: number } = {}): Promise<void> {
        await runtimeBridge.invoke('sensors', 'unregister', { ref }, { ttl: uOpts.timeout ?? 5_000 })
      },
    }
    return sub
  },

  /**
   * 注销**全部**订阅。
   *
   * 越界提醒（与 `:domain` KDoc 同步）：宿主侧没有脚本归属，清的就是全清 ——
   * 多脚本并发时误调会掐掉别人的订阅。要精准请 `subscription.unsubscribe()`。
   */
  async unregisterAll(opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke('sensors', 'unregisterAll', null, { ttl: opts.timeout ?? 5_000 })
  },
}
