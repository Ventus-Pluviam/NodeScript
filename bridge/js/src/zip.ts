import { runtimeBridge } from './runtime'

/**
 * `zip` 命名空间（§9.6；Kotlin 对偶 `ZipNamespaceHandler`）。
 *
 * wire 形状与 handler 逐字段对齐（`zip.test.cjs` 的 mock 宿主复刻）：
 * `compress` 发 `{source,archive}`、`extract` 发 `{archive,targetDir}`，
 * 两路径原样到 SPI —— zip-slip 防线、原子落位全在宿主实现里，facade 不碰字节。
 *
 * TTL 缺省 60s（归档可大可慢，5s 默认必超）；错误原样抛 AutojsError
 * （`ERR_FILE_NOT_FOUND`/`ERR_IO`/`ERR_INVALID_PARAM` 不折叠）。
 * 方法名只有 `compress`/`extract` —— 没约定过的别名（如 `unzip`）不提供，
 * 宿主对未知方法如实 ERR_NOT_IMPLEMENTED。
 */
export const zip = {
  /** 压缩文件或目录 → zip（目录递归；目标已存在则替换）。 */
  async compress(source: string, archive: string, opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke(
      'zip', 'compress', { source, archive }, { ttl: opts.timeout ?? 60_000 },
    )
  },

  /** 解压 zip → 目标目录（不存在则创建；zip-slip 越界条目在宿主侧整次拒绝）。 */
  async extract(archive: string, targetDir: string, opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke(
      'zip', 'extract', { archive, targetDir }, { ttl: opts.timeout ?? 60_000 },
    )
  },
}
