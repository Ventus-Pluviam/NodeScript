import { runtimeBridge } from './runtime'

/**
 * `settings` 命名空间（§9.6；Kotlin 对偶 `SettingsNamespaceHandler`）。
 *
 * wire 形状与 handler 逐字段对齐（`settings.test.cjs` 的 mock 宿主复刻）：
 * 方法表照抄 `:domain` `SystemSettings` 的五件 —— `canWrite`/`getString`/`getInt`/
 * `putString`/`putInt`。**不提供猜型的 `get`/`put`**：`Settings.System` 的串与数是
 * 两套系统 API（`putString` 存原文、`putInt` 存数字），按 `typeof value` 推断等于在
 * 桥面发明一条 SPI 没有的策略（`"128"` 该存串还是数？），由调用方显式选；
 * 未约定的别名宿主如实 `ERR_NOT_IMPLEMENTED`，本层也不发。
 *
 * 读侧缺失回 `null`（不回 `undefined`）：缺键是常态答案（§9.6「不拿 0/空串冒充」），
 * 裸 JSON `null` 就是那条答案 —— 与 `datastore.get` 拆 `{found,value}` 信封不同，
 * 因为本契约的值面只有 string|number，`null` 不会与任何合法值撞。
 *
 * 错误原样抛（`ERR_PERMISSION_DENIED` 未授 WRITE_SETTINGS / `ERR_IO` 已授权仍被拒 /
 * `ERR_INVALID_PARAM` 参数口径），不折叠 —— 授权问题要能被调用方识别并引导。
 */
export const settings = {
  /** `WRITE_SETTINGS` 是否已授（写前的诚实探针；读设置不需要授权）。 */
  async canWrite(opts: { timeout?: number } = {}): Promise<boolean> {
    return (await runtimeBridge.invoke('settings', 'canWrite', null, { ttl: opts.timeout ?? 5_000 })) === true
  },

  /** 读字符串设置；键缺失 → `null`（绝不拿空串冒充缺失）。 */
  async getString(key: string, opts: { timeout?: number } = {}): Promise<string | null> {
    return (await runtimeBridge.invoke(
      'settings', 'getString', { key }, { ttl: opts.timeout ?? 5_000 },
    )) as string | null
  },

  /** 读整型设置；键缺失 → `null`（0 是合法亮度，不拿 0 冒充）。 */
  async getInt(key: string, opts: { timeout?: number } = {}): Promise<number | null> {
    return (await runtimeBridge.invoke(
      'settings', 'getInt', { key }, { ttl: opts.timeout ?? 5_000 },
    )) as number | null
  },

  /** 写字符串设置（空串是合法值）。未授 `WRITE_SETTINGS` → 抛 `ERR_PERMISSION_DENIED`。 */
  async putString(key: string, value: string, opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke('settings', 'putString', { key, value }, { ttl: opts.timeout ?? 5_000 })
  },

  /** 写整型设置。失败口径同 putString（未授 `ERR_PERMISSION_DENIED`、已授权仍被拒 `ERR_IO`）。 */
  async putInt(key: string, value: number, opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke('settings', 'putInt', { key, value }, { ttl: opts.timeout ?? 5_000 })
  },
}
