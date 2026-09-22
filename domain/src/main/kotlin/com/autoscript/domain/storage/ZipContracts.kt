package com.autoscript.domain.storage

import java.nio.file.Path

/**
 * `zip` 归档契约（docs/framework-design.md §9.6，JS 对偶待建 `auto.zip`）。
 *
 * 为什么住 `:domain`：与 [DataStore] 同一套理由 —— 实现（`java.util.zip`）落
 * `:platform:system`（§6 模块表本行的「zip」），把「要什么操作」与「怎么编排
 * 归档字节」切开，上层（handler/装配）才不背归档细节。`java.util.zip` 本身
 * 零 Android 依赖，实现可整类进本机 JVM 单测（README ops 表里唯一「接触面：无」的一行）。
 *
 * **安全底线（契约级义务，与 shell 的超时义务同级）**：[extract] 必须防
 * **zip-slip** —— 条目解析出目标目录之外（`../` 逃逸、绝对路径条目）即整次拒绝
 * （抛 `ERR_INVALID_PARAM`，越界文件一个字节都不许落）。这不是实现细节是契约：
 * 归档是**外部输入**，脚本解一个来路不明的 zip 不该能把文件写到应用私有区之外。
 *
 * 与 packager 既有 zip 代码的边界：`NpmSnapshot`/离线包那套 `java.util.zip`
 * 是 npm 专用（固定 mtime、integrity 清单），**不是**本 SPI 的实现也不复用 ——
 * 通用归档面归这里。
 */
interface ZipArchiver {

    /**
     * 压缩文件或目录 → zip。
     * - 源不存在 → `ERR_FILE_NOT_FOUND`（缺文件是错误不是空包）；
     * - 目录递归，条目名 = 相对源根的 `/` 分隔路径；空目录以目录条目保留；
     * - 目标父目录自动创建；**原子落位**（写旁路临时文件再 rename）——
     *   中途失败不留半截 zip 冒充成品（§9.6 原子部署同一纪律）；
     * - 已存在的目标被替换（重打包是常态，不报 ERR_FILE_EXISTS）。
     */
    suspend fun compress(source: Path, archive: Path)

    /**
     * 解压 zip → 目标目录。
     * - 归档不存在 → `ERR_FILE_NOT_FOUND`；不是合法 zip → `ERR_IO`；
     * - 目标目录不存在则创建；同名文件覆盖（解压替换是常态）；
     * - **zip-slip 防线**见文件头 KDoc：越界条目 → `ERR_INVALID_PARAM`，
     *   且在**写任何字节之前**完成全部路径校验（先验后写，不"解到一半才发现"）。
     */
    suspend fun extract(archive: Path, targetDir: Path)
}
