import { runtimeBridge } from './runtime'

/**
 * `datastore` 命名空间（§9.6；Kotlin 对偶 `DatastoreNamespaceHandler`）。
 *
 * wire 形状与 handler 逐字对齐（`datastore.test.cjs` 的 mock 宿主复刻 handler 回包）：
 * - `get` 回 `{found,value}` 信封 —— **键缺失 → `undefined`、存的 JSON null → `null`**，
 *   两者不折叠（Kotlin `StoredEntry` 契约同款区分；信封在本层拆开）；
 * - `put` 发 `{key,value}`（value 任意 JSON 值，宿主转文本存、不解释业务结构；
 *   `undefined` 经 JSON.stringify 键直接消失 → 宿主 ERR_INVALID_PARAM，诚实报缺参）；
 * - `remove`/`contains` 回裸 boolean；`keys` 回字符串数组；`clear` 无返回体。
 *
 * 诚实缺位（不发注定失败的请求）：
 * - `transaction` 不上桥（P0 无 begin/commit 句柄 + TTL 面），JS 侧**没有这个方法**；
 * - 字节值不过 JSON 桥（§7.4 side-channel 未接）—— 宿主存了 Bytes 时 `get`
 *   如实 `ERR_NOT_IMPLEMENTED`，本层不 base64 假装通用；
 * - 多库（Pro 的 `storages.create` 风格）未上桥：键前缀约定待后续，本层是单一默认库。
 */
export const datastore = {
  /** 读一键；缺失 `undefined`，存的 JSON null 回 `null`（两者不折叠）。 */
  async get(key: string, opts: { timeout?: number } = {}): Promise<unknown> {
    const r = await runtimeBridge.invoke(
      'datastore', 'get', { key }, { ttl: opts.timeout ?? 5_000 },
    ) as { found: boolean; value?: unknown }
    return r?.found ? r.value : undefined
  },

  /** 写/覆盖一键（任意 JSON 可序列化值）。 */
  async put(key: string, value: unknown, opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke('datastore', 'put', { key, value }, { ttl: opts.timeout ?? 5_000 })
  },

  /** 删一键；回是否真的移除了东西（键不存在 → false，幂等不抛）。 */
  async remove(key: string, opts: { timeout?: number } = {}): Promise<boolean> {
    return (await runtimeBridge.invoke('datastore', 'remove', { key }, { ttl: opts.timeout ?? 5_000 })) === true
  },

  async contains(key: string, opts: { timeout?: number } = {}): Promise<boolean> {
    return (await runtimeBridge.invoke('datastore', 'contains', { key }, { ttl: opts.timeout ?? 5_000 })) === true
  },

  /** 全部键（顺序不作契约保证，调用方如需稳定序自行排序）。 */
  async keys(opts: { timeout?: number } = {}): Promise<string[]> {
    return (await runtimeBridge.invoke('datastore', 'keys', null, { ttl: opts.timeout ?? 5_000 })) as string[]
  },

  async clear(opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke('datastore', 'clear', null, { ttl: opts.timeout ?? 5_000 })
  },
}
