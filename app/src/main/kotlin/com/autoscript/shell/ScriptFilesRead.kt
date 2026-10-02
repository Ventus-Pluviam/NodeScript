package com.autoscript.shell

import com.autoscript.domain.host.ScriptFileRow
import com.autoscript.domain.host.ScriptFilesSnapshot
import com.autoscript.domain.scripts.ScriptPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.stream.Collectors
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * 脚本文件清单的拼装（项目页文件列表；`files/scripts/` 一棵树的平铺）。
 *
 * **为什么单独一个对象**（与 [TaskCenterRead]/[CapabilityCenterRead] 同一条理由）：
 * `Application` 在 JVM 单测里构造不出来，而"哪些条目算用户资产、按什么排序、
 * 排除什么"这段判断必须可测。抽出来之后 `AppShellApplication` 只剩一句转接。
 *
 * 三条口径：
 * - **项目根不存在回空清单**：还没部署过任何项目是真实事实，不是失败 —— 冒充
 *   失败会让首装用户看到一条红字；只有遍历本身抛（IO 异常）才向上传；
 * - **只列用户资产**：`node_modules`（npm 依赖缓存，数量巨大且不是用户写的）与
 *   点开头条目（`.DS_Store` 那类噪声）不进列表 —— 列表是"我的脚本文件"，不是
 *   "目录里的一切"；目录本身（项目文件夹）也不是文件行，不列；
 * - **排序在本层定死**：修改时间倒序（最近动过的在最前）—— 这是 TG 会话列表
 *   "最近会话在最上"的同一读法；呈现层不再排一次（两处排序必然漂移）。
 */
object ScriptFilesRead {

    /** 点开头的文件（系统噪声）与 npm 依赖目录不进列表。 */
    private fun Path.isNoise(): Boolean = name.startsWith(".")

    /**
     * 平铺整棵 `files/scripts/` 树为文件行快照。
     *
     * @param filesDir App 私有文件目录（`Context.filesDir` 的 Path 形态）。
     */
    fun snapshot(filesDir: Path): ScriptFilesSnapshot {
        val root = ScriptPaths.projectsRoot(filesDir)
        if (!Files.isDirectory(root)) return ScriptFilesSnapshot(emptyList())
        Files.walk(root).use { stream ->
            val rows = stream
                .filter { Files.isRegularFile(it) }
                .filter { !it.isNoise() }
                .filter { path -> root.relativize(path).none { segment -> segment.toString() == "node_modules" } }
                .map { path ->
                    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                    val rel = root.relativize(path).joinToString("/")
                    ScriptFileRow(
                        projectId = root.relativize(path).first().toString(),
                        relPath = rel,
                        name = path.name,
                        // 小写归一：`.JS` 与 `.js` 是同一类文件，图标与筛选都按一类说。
                        ext = path.extension.lowercase(),
                        sizeBytes = attrs.size(),
                        modifiedMillis = attrs.lastModifiedTime().toMillis(),
                    )
                }
                // 不用 `kotlin.streams.toList`：它被 Java 成员 Stream.toList()（API 34）遮蔽，
                // API 33 真机上就是 IncompatibleClassChangeError（AtomicDeployer.kt 同一条教训）。
                .collect(Collectors.toList())
                .sortedByDescending { it.modifiedMillis }
            return ScriptFilesSnapshot(rows)
        }
    }
}
