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
 * 脚本文件清单的拼装（项目页文件列表；`files/scripts/` 一棵树，文件与文件夹都进清单）。
 *
 * **为什么单独一个对象**（与 [TaskCenterRead]/[CapabilityCenterRead] 同一条理由）：
 * `Application` 在 JVM 单测里构造不出来，而"哪些条目算用户资产、目录怎么计数、
 * 排除什么"这段判断必须可测。抽出来之后 `AppShellApplication` 只剩一句转接。
 *
 * 三条口径：
 * - **项目根不存在回空清单**：还没部署过任何项目是真实事实，不是失败 —— 冒充
 *   失败会让首装用户看到一条红字；只有遍历本身抛（IO 异常）才向上传；
 * - **只列用户资产**：`node_modules`（npm 依赖缓存，数量巨大且不是用户写的）**整棵
 *   子树**（含目录本身）与点开头条目（`.DS_Store` 那类噪声）不进列表 —— 列表是
 *   "我的脚本文件"，不是"目录里的一切"；
 * - **不排序**：读数与排序分家（TG 文件页 `sortFileItems` 的同一条分工）—— 排序档
 *   /逆向是用户在界面里现选的呈现偏好（"仅应用于此文件夹"），在快照层排死就没法换。
 *   快照只保证目录遍历序。
 *
 * **目录怎么进来**：`Files.walk` 的每个目录（除根）产一行 `isDirectory=true` 的行 ——
 * 文件夹与文件混排（TG 文件页目录在前的读法靠呈现层排），`childCount` 数它的直接子项
 * （`Files.list` 现数；目录的"大小"就是它，TG 文件夹行也不显示字节数）。
 * 目录行 `relPath` 以 `/` 结尾：呈现层不用再对一遍 `isDirectory`。
 */
object ScriptFilesRead {

    /** 点开头的条目（系统噪声）不进列表。 */
    private fun Path.isNoise(): Boolean = name.startsWith(".")

    /**
     * 读整棵 `files/scripts/` 树为文件/文件夹行快照（**未排序**，见类 KDoc）。
     *
     * @param filesDir App 私有文件目录（`Context.filesDir` 的 Path 形态）。
     */
    fun snapshot(filesDir: Path): ScriptFilesSnapshot {
        val root = ScriptPaths.projectsRoot(filesDir)
        if (!Files.isDirectory(root)) return ScriptFilesSnapshot(emptyList())
        Files.walk(root).use { stream ->
            val rows = stream
                .filter { path -> path != root }
                .filter { path -> !root.relativize(path).any { segment -> segment.toString() == "node_modules" } }
                .filter { !it.isNoise() }
                .map { path ->
                    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                    val rel = root.relativize(path).joinToString("/")
                    val isDir = attrs.isDirectory
                    ScriptFileRow(
                        projectId = root.relativize(path).first().toString(),
                        // 目录行以 / 结尾（契约约定：呈现层靠它区分，不再对一遍 isDirectory）。
                        relPath = if (isDir) "$rel/" else rel,
                        name = path.name,
                        // 小写归一：`.JS` 与 `.js` 是同一类文件，图标与筛选都按一类说。
                        ext = if (isDir) "" else path.extension.lowercase(),
                        isDirectory = isDir,
                        // 文件夹行现场数直接子项（node_modules/点开头的子项也照数 ——
                        // 这里数的是"里面有 N 项"的事实，列表收不收它另一回事）。
                        childCount = if (isDir) Files.list(path).use { s -> s.count().toInt() } else 0,
                        sizeBytes = if (isDir) 0L else attrs.size(),
                        modifiedMillis = attrs.lastModifiedTime().toMillis(),
                    )
                }
                // 不用 `kotlin.streams.toList`：它被 Java 成员 Stream.toList()（API 34）遮蔽，
                // API 33 真机上就是 IncompatibleClassChangeError（AtomicDeployer.kt 同一条教训）。
                .collect(Collectors.toList())
            return ScriptFilesSnapshot(rows)
        }
    }
}
