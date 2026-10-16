package com.autoscript.appservice.npm

import java.nio.file.Files
import java.nio.file.Path

/**
 * 「这个目录所在分区还剩多少可用字节」的**唯一一处读法**（§10.2 磁盘预检的取数口）。
 *
 * **为什么不用 `Files.getFileStore(dir).usableSpace`**（2026-10-17 设备实测）：
 * Android 上的 `java.nio.file` 只实现了 NIO2 的一半，而 `FileSystemProvider.getFileStore`
 * 正是没实现的那一格 —— 调它直接抛 `UnsupportedOperationException("getFileStore")`。
 * 难看的不是"读不到磁盘"，是**装依赖这条路整个走不通**：[InstallCoordinator] 的每次重操作
 * 入口（`enqueueHeavy`）都要跑一次探针；而那句 message 会被 `:ui` 的
 * `opError = t.message ?: t.javaClass.simpleName` **原样显示给用户** —— 用户看到的就只有
 * 「getFileStore」四个字，没有任何线索指向"磁盘"。
 * 本机 JVM 与 CI 都复现不了（那边 `getFileStore` 好得很：存在的目录读得出数、不存在的抛
 * `NoSuchFileException`），**这一类只能由设备实测发现**。
 *
 * 换成 [java.io.File.usableSpace]：Android 侧由 `statvfs(2)` 实现（`java.io` 是 Android
 * 从 API 1 起就有的门面，不属于"NIO2 那一半"），宿主 JVM 侧同样是真数。
 *
 * **读不出来时不抛、回 0**（Android 与宿主 JVM 的 `File.usableSpace` 都是这条语义，已实测）：
 * 0 < 预检下限 ⇒ 如实报 `ERR_DISK_FULL`。宁可拦一次（用户清理后重试能过），也不放行一次
 * 把分区写满的安装。代价是**读不到与真满了在数字上分不开**（都是 0）—— 但两者的处置相同
 * （别装），且报错里带着这个数字，用户看得见。
 */
internal object FreeSpace {

    /**
     * [dir] 所在分区的可用字节。
     *
     * **路径不存在时回 0**（`statvfs` 对不存在的路径失败，两边实现都把失败折成 0）——
     * 所以调用方**必须先判存在性**：见 [usableBytesFor]。
     */
    fun usableBytes(dir: Path): Long = dir.toFile().usableSpace

    /**
     * 目标目录**可能还不存在**时的读数：不存在就退到 [fallback]。
     *
     * 为什么非有这一档：项目目录在第一次 install 前本来就不存在（[NpmShellKit] 的
     * `defaultFreeSpaceProbe` 一直是这么写的），而不存在的路径 `usableSpace` **回 0** ——
     * 直接拿它去比预检下限，新项目第一次装依赖就会撞一句**假的** `ERR_DISK_FULL`。
     * 退到 [fallback]（Android 装配层给的是 `filesDir`）是同一块分区，答案是同一个
     * —— §10.2 磁盘预检问的是分区余量，不是"项目目录在不在"。
     */
    fun usableBytesFor(dir: Path, fallback: Path): Long =
        usableBytes(if (Files.exists(dir)) dir else fallback)
}
