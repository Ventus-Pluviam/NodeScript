package com.autoscript.appservice.npm

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 量 `node_modules` 的两个读口：**这个目录一共多大**、以及**里面每个包各占多少**。
 *
 * **为什么在 `InstallCoordinator` 之外**：这两件事只依赖一个目录和一个时刻，不认识安装会话、
 * 事务、锁 —— 而协调器那个类体贴着 detekt 的 `LargeClass` 线（800 行）。把它们挪出来，
 * 是这一段本来就该有的位置，顺便也不必去抬那个阈值。
 *
 * **两张表分开**而不是合成一张：一张只回答"这个作用域一共多大"，另一张回答"里面每个包多大"，
 * 键的粒度不同 —— 合成一张要么每次多算一半用不上的东西，要么让两个读口互相覆盖。
 * 两张表 TTL 一致（[InstallCoordinator.SIZE_CACHE_TTL_MILLIS]）。
 *
 * **缓存键是目录路径而不是 projectId**：量的是什么，键就是什么。顺带挡掉一处真会踩的串味
 * —— 键若用 projectId，两个指向不同 `filesDir` 的协调器（测试里每个用例一个临时目录、
 * 生产里重新装配一次）会互相顶掉对方的读数。
 *
 * **为什么必须缓存**：这是**全量遍历**（大项目十万级文件、秒级），而两个读口都在主线程
 * 那条链上 —— 配额预检顶在"点安装 → 有反应"之间，`snapshot()` 则被依赖面板每 400ms 续拉
 * 一次（2026-10-17 那次「依赖装多了，一查看就闪退」就是这个形态）。
 *
 * **代价如实记**：盘上变了而时钟没走 → 回的是缓存里的旧值（陈旧上界 = TTL）。
 * 本进程装完**不失效**（一失效就等于每次安装照旧全量遍历，正是要修的东西）；
 * 脚本自己跑 npm 装的那部分更看不见 —— 期间可能放行一次把项目顶过配额的安装，
 * 表现是 npm 自己报 ENOSPC 或下次预检拦下，不会静默写坏数据（尺寸只决定「让不让开始」，
 * 不参与任何写路径决策）。
 */
internal suspend fun nodeModulesBytesOf(nodeModules: Path, at: Long): Long =
    withContext(Dispatchers.IO) {
        totalCache[nodeModules]?.let {
            if (at - it.atMillis < InstallCoordinator.SIZE_CACHE_TTL_MILLIS) return@withContext it.bytes
        }
        DirSizer.sizeBytes(nodeModules).also { totalCache[nodeModules] = MeasuredTotal(it, at) }
    }

/**
 * 每个顶层包占的字节（依赖列表每行那个尺寸，2026-10-17 用户口径「依赖列表显示一下各个依赖
 * 占用的空间大小」）。分摊判据见 [topLevelPkgOf]。
 */
internal suspend fun packageSizesOf(nodeModules: Path, at: Long): Map<String, Long> =
    withContext(Dispatchers.IO) {
        pkgCache[nodeModules]?.let {
            if (at - it.atMillis < InstallCoordinator.SIZE_CACHE_TTL_MILLIS) return@withContext it.bytes
        }
        val out = LinkedHashMap<String, Long>()
        if (Files.isDirectory(nodeModules)) {
            Files.walk(nodeModules).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { file ->
                    val name = topLevelPkgOf(nodeModules, file) ?: return@forEach
                    out[name] = (out[name] ?: 0L) + Files.size(file)
                }
            }
        }
        pkgCache[nodeModules] = MeasuredPkgs(out, at)
        out
    }

/** 目录总量的一次实测（[nodeModulesBytesOf] 的缓存条目）。 */
private class MeasuredTotal(val bytes: Long, val atMillis: Long)

/** 逐包尺寸的一次实测（[packageSizesOf] 的缓存条目）。 */
private class MeasuredPkgs(val bytes: Map<String, Long>, val atMillis: Long)

/** 量过的目录 → 总量（见本文件头的 TTL 与代价）。 */
private val totalCache = ConcurrentHashMap<Path, MeasuredTotal>()

/** 量过的目录 → 逐包尺寸（同上；与 [totalCache] 分开的理由见本文件头）。 */
private val pkgCache = ConcurrentHashMap<Path, MeasuredPkgs>()

/**
 * 这个文件算在哪个包头上。
 *
 * 取相对 `node_modules` 的第一段；带 scope 的（`@scope/pkg`）取前两段。三类**不算**：
 * - 点开头的（`.bin/` 那一堆 shim、`.package-lock.json`）—— 它们是 npm 的记账，不是包；
 * - 分段不够的（`node_modules/` 下的散文件、`@scope/` 下的散文件）—— 构不成一个包；
 * - 名字为空的（理论上到不了，防御性）。
 *
 * 嵌套的（`a/node_modules/b`）算在**最外层那个包**头上 —— 那是它装进来的。
 */
private fun topLevelPkgOf(nodeModules: Path, file: Path): String? {
    val rel = nodeModules.relativize(file)
    if (rel.nameCount < 2) return null
    val first = rel.getName(0).toString()
    if (first.isEmpty() || first.startsWith(".")) return null
    if (!first.startsWith("@")) return first
    if (rel.nameCount < 3) return null
    val second = rel.getName(1).toString()
    if (second.isEmpty() || second.startsWith(".")) return null
    return "$first/$second"
}
