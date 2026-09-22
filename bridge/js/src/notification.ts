import { runtimeBridge } from './runtime'

/** `post` 的入参与 `:domain` `NotificationSpec` 逐字段对齐。 */
export interface NotificationSpec {
  /** 通知 id：同 id 重发即覆盖，也是 §8.5「只发一次」的幂等键目标 —— 必填，不自动发号。 */
  id: number
  /** 正文（必填，非空白）。 */
  text: string
  /** 标题；省略即不设标题（与传 `null` 同义）。 */
  title?: string | null
}

/**
 * `notification` 命名空间（§12.2；Kotlin 对偶 `NotificationNamespaceHandler`）。
 *
 * wire 形状与 handler 逐字段对齐（`notification.test.cjs` 的 mock 宿主复刻）：
 * `post` 发 `{id,text,title}`、`cancel` 发 `{id}`、`canPost` 空参 —— 三方法与
 * `:domain` `NotificationPoster` 1:1，未约定的别名（`notify`/`show`/`cancelAll`）
 * 两侧都不提供，宿主对未知方法如实 `ERR_NOT_IMPLEMENTED`。
 *
 * 诚实口径：
 * - `post` 未授权 → 抛 `ERR_PERMISSION_DENIED`（**不是 resolve**）：Android 被拒时
 *   不抛异常直接丢弃通知，静默成功是最坏的谎，门禁判据在宿主侧；
 * - `cancel` **无回执** → `Promise<void>`：系统没有任何"撤到了没有"的读口，
 *   这里不编一个撤销成功的布尔（宿主回的 `true` 只表示"这次调用发出去了"）；
 * - `title` 省略与 `null` 同义，由 JSON 键是否在场表达，facade 不做合并策略。
 *
 * 范围（与 `:domain` KDoc 同步，别各自漂移）：不带 `channelId`（minSdk 26 的默认
 * channel 归实现）、不带 actions/点击意图（与 `dialogs` 通知回调同属 §14 P2）、
 * 不带 `ongoing`/样式/优先级。
 */
export const notification = {
  /** 通知是否可发（应用通知开关 + `POST_NOTIFICATIONS`；读侧不需要授权）。 */
  async canPost(opts: { timeout?: number } = {}): Promise<boolean> {
    return (await runtimeBridge.invoke('notification', 'canPost', null, { ttl: opts.timeout ?? 5_000 })) === true
  },

  /** 发/覆盖一条通知。未授权 → 抛 `ERR_PERMISSION_DENIED`。 */
  async post(spec: NotificationSpec, opts: { timeout?: number } = {}): Promise<void> {
    const payload: { id: number; text: string; title?: string } = { id: spec.id, text: spec.text }
    if (spec.title !== undefined && spec.title !== null) payload.title = spec.title
    await runtimeBridge.invoke('notification', 'post', payload, { ttl: opts.timeout ?? 5_000 })
  },

  /** 按 id 撤销；幂等无回执（撤一个没发过的 id 什么也不会发生）。 */
  async cancel(id: number, opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke('notification', 'cancel', { id }, { ttl: opts.timeout ?? 5_000 })
  },
}
