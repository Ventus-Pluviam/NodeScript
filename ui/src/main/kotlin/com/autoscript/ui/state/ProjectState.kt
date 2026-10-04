package com.autoscript.ui.state

import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.host.ScriptFileRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 排序档（项目页 ⋮ → 排序方式；TG 文件页 `sortFileItems` 的四档 + 逆向）。
 */
enum class FileSort(val label: String) {
    NAME("按名称"),
    SIZE("按大小"),
    DATE("按日期"),
    TYPE("按类型"),
}

/**
 * 项目页（文件列表）呈现态（纯数据，Compose 之外可 JVM 测）。
 *
 * 与 [TaskCenterState]/[ConsoleState] 同一条纪律：
 * - [load] 三态各自说话：没读到、读失败带原文、读到了才轮到"真的没有"；
 * - [query]（搜索词）与 [sort]/[reversed]（排序）是**本屏私有的现值**，不进读口
 *   —— 筛选与排序都是呈现（TG 文件页"仅应用于此文件夹"），不是读取；
 * - 操作面（新建文件/文件夹）三态照任务中心同构：[creating] 开着哪个对话框、
 *   [opError]/[opNotice] 回执 —— 失败**不清清单**。
 *
 * 时间/大小格式化在这一层（[ScriptFileRowUi.subtitle]），时刻由 [of] 参数注入，
 * 类内不读 `System.currentTimeMillis()`（可测 + 同帧一致）。
 */
data class ProjectState(
    val load: LoadState,
    val files: List<ScriptFileRowUi>,
    val sort: FileSort = FileSort.DATE,
    val reversed: Boolean = false,
    val creating: CreationKind? = null,
    val opError: String? = null,
    val opNotice: String? = null,
) {
    /** 新建对话框两种（TG FAB 展开的两个子按钮）。 */
    enum class CreationKind { FILE, FOLDER }

    companion object {

        /**
         * 一层的直接子项（TG 文件页点目录进一层、返回键回一层、一层只显示一层的同款语义）。
         *
         * 只在 [ScriptFileRowUi.relPath] 的字符串形态上工作 —— 快照是**整棵树**的
         * 平铺行集，"当前目录"是呈现层的视图状态，不入读口（TG 文件页"仅应用于此
         * 文件夹"的同一条分工）。
         *
         * @param folder 当前目录的**全路径前缀**（含项目名、尾随 `/`，如 `"demo/"`、
         *   `"demo/lib/"`）；**null = 全库根**（列的是各项目那一层，不是"没有目录"）。
         *   进一层 = `enter(row.relPath)` —— 目录行的 relPath 本身就是合格的前缀。
         * @return 该目录的直接子项（**不递归**：一层只显示一层）。
         */
        fun childrenOf(files: List<ScriptFileRowUi>, folder: String?): List<ScriptFileRowUi> {
            val prefix = folder ?: ""
            return files.filter { row ->
                // 去掉前缀后剩下的部分：空 = 目录行自身（"demo/lib/" 对 folder="demo/lib/"），
                // 排除掉；否则再剥目录行的尾随 '/' 数斜杠 —— 还剩 '/' 就是更深的层。
                // 目录行的 relPath 以 '/' 结尾（`ScriptFilesRead` 的口径），只数斜杠会把
                // "demo/lib/" 误判成第二层。
                val rest = row.relPath.removePrefix(prefix)
                row.relPath.startsWith(prefix) && rest.isNotEmpty() &&
                    rest.removeSuffix("/").count { it == '/' } == 0
            }
        }

        /**
         * 列表的候选集：**搜索时是整棵树**，否则是当前一层的直接子项。
         *
         * 为什么搜索要跨层：TG 文件选择器的搜索是**另一份数据集**（`searchAdapter`
         * 搜的是全库、还带"最近"分区，展开搜索栏时列表整个换掉），不是"在当前目录里
         * 筛一下" —— 用户敲文件名时找的是"那个文件在哪"，锁在当前层就是答非所问。
         *
         * 注意**不能**拿 `childrenOf(files, null)` 当"全库"用：那只是全库的**第一层**
         * （各项目目录本身），搜 "main.js" 会一个都搜不到。
         */
        fun poolFor(
            files: List<ScriptFileRowUi>,
            folder: String?,
            searching: Boolean,
        ): List<ScriptFileRowUi> = if (searching) files else childrenOf(files, folder)

        /**
         * 当前目录名（顶栏标题位；TG 文件页进入子目录后顶栏换目录名的同款）。
         *
         * 根（null）= **null**：全库根不是"某个目录"，顶栏保持项目页标题，不冒充一个目录名。
         */
        fun folderTitle(folder: String?): String? =
            folder?.removeSuffix("/")?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

        /**
         * 回退一层：`"demo/lib/"` → `"demo/"` → **null**（到根收口，返回键在根层不再吞）。
         */
        fun parentFolder(folder: String?): String? = when {
            folder == null -> null
            // "demo/" 已是项目那一层，再退就是全库根。
            '/' !in folder.removeSuffix("/") -> null
            else -> folder.removeSuffix("/").substringBeforeLast('/') + "/"
        }

        /**
         * 一行的键（多选集合的成员、LazyColumn 的 `key` —— 两处必须是同一个串，
         * 否则"选中的行"与"列表里的行"会对不上）。`projectId` 前缀不可省：
         * 两个项目下同名的 `main.js` 是不同的两行。
         */
        fun keyOf(row: ScriptFileRowUi): String = "${row.projectId}/${row.relPath}"

        /** 多选模式下点一行 = 加上/去掉（TG 选择模式：进了模式，点就不是"打开"了）。 */
        fun toggleSelection(selected: Set<String>, key: String): Set<String> =
            if (key in selected) selected - key else selected + key

        /**
         * 选择全部 / 取消全选（顶栏那格是**同一个开关**：TG 的 `SelectAll` 选满后
         * 自己变成 `DeselectAll`）。
         *
         * 范围是**当前可见集**（当前目录一层，或搜索命中的那些）—— 把看不见的行也圈进来，
         * 复制/删除就会动到用户没看到的东西。
         */
        fun toggleSelectAll(selected: Set<String>, visible: List<ScriptFileRowUi>): Set<String> {
            val keys = visible.map { keyOf(it) }.toSet()
            if (keys.isEmpty()) return selected
            return if (keys.all { it in selected }) selected - keys else selected + keys
        }

        /** 可见集是否已全选（顶栏那格显示"选择全部"还是"取消全选"）。 */
        fun allSelected(selected: Set<String>, visible: List<ScriptFileRowUi>): Boolean {
            val keys = visible.map { keyOf(it) }
            return keys.isNotEmpty() && keys.all { it in selected }
        }

        /** 首帧哨兵：没读到过（**不是**"没有文件"—— 那是读成功且目录为空）。 */
        val NOT_LOADED = ProjectState(load = LoadState.NotLoaded, files = emptyList())

        /**
         * 现取快照（排序/回执**不重置** —— 重取是读数，不是用户偏好的消失）。
         *
         * 取 `nowMillis`/`zone` 一次传进去：同一帧里所有"今天/昨天"共用同一个 now。
         */
        fun of(
            snapshot: com.autoscript.domain.host.ScriptFilesSnapshot,
            nowMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            previous: ProjectState? = null,
        ): ProjectState = ProjectState(
            load = LoadState.Loaded,
            files = snapshot.rows.map { ScriptFileRowUi.of(it, nowMillis, zone) },
            sort = previous?.sort ?: FileSort.DATE,
            reversed = previous?.reversed ?: false,
            opError = previous?.opError,
            opNotice = previous?.opNotice,
        )

        /** 读失败（原异常文案带上 —— 现场/ROM 奇异实现要靠它分辨）。 */
        fun failed(t: Throwable): ProjectState = ProjectState(
            load = LoadState.of(t),
            files = emptyList(),
        )
    }
}

/**
 * 一行文件（项目页列表的一行 = TG 会话列表的一行会话）。
 *
 * 版式对表 TG `DialogCell`/`SharedDocumentCell`（逐字提取见两处 KDoc）：
 * - 主行 = 文件名 16sp 粗体（`nameTextView` 16dp bold）；
 * - 次行 = "大小 · 修改时刻" 13sp（`dateTextView` 13dp；大小格式 = `formatFileSize`）；
 * - 头像位 = 文件类型图标（扩展名取色取字，`getThumbForNameOrMime` + `extTextView` 的读法）。
 */
data class ScriptFileRowUi(
    val projectId: String,
    val relPath: String,
    val name: String,
    val ext: String,
    val isDirectory: Boolean,
    /** 文件夹直接子项数（文件恒 0 —— 文件夹的"大小"位显示它，TG 文件页同款）。 */
    val childCount: Int,
    val sizeBytes: Long,
    /** 最后修改时刻（epoch ms）—— 排序"按日期"档的键（格式化后的时刻在 [subtitle]）。 */
    val modifiedMillis: Long,
    /** 已格式化的一句："1.2 MB · 昨天" / "348 B · 09-14 22:10"（TG 次行的读法；文件夹 = "N 项 · …"）。 */
    val subtitle: String,
) {
    /** 搜索命中：文件名或项目内路径含词（大小写不敏感；空词全命中）。 */
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase(Locale.ROOT)
        return name.lowercase(Locale.ROOT).contains(q) ||
            relPath.lowercase(Locale.ROOT).contains(q)
    }

    companion object {

        /**
         * TG `AndroidUtilities.formatFileSize(size)`（= removeZero=false, makeShort=false）
         * 的逐字移植：KB/MB 恒一位小数、GB 恒两位 —— `2048` 是 `2.0 KB` 不是 `2 KB`。
         */
        fun formatFileSize(size: Long): String = when {
            size == 0L -> "0 KB"
            size < 1024L -> "$size B"
            size < 1024L * 1024L -> "%.1f KB".format(size / 1024.0f)
            size < 1000L * 1024L * 1024L -> "%.1f MB".format(size / 1024.0f / 1024.0f)
            else -> "%.2f GB".format((size / 1024L / 1024L).toInt() / 1000.0f)
        }

        /**
         * 时刻文案（TG 会话列表时间位 `formatDate` 的读法）：今天 = `HH:mm`，
         * 今年 = `MM-dd`，跨年 = `yyyy-MM-dd`；文件没有"正在输入"，无需秒级。
         */
        fun formatTime(millis: Long, nowMillis: Long, zone: ZoneId): String {
            val now = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            val t = Instant.ofEpochMilli(millis).atZone(zone)
            val date = t.toLocalDate()
            return when {
                date == now -> t.format(DateTimeFormatter.ofPattern("HH:mm"))
                date.year == now.year -> t.format(DateTimeFormatter.ofPattern("MM-dd"))
                else -> t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            }
        }

        fun of(row: ScriptFileRow, nowMillis: Long, zone: ZoneId): ScriptFileRowUi = ScriptFileRowUi(
            projectId = row.projectId,
            relPath = row.relPath,
            name = row.name,
            ext = row.ext,
            isDirectory = row.isDirectory,
            childCount = row.childCount,
            sizeBytes = row.sizeBytes,
            modifiedMillis = row.modifiedMillis,
            // 文件夹不显示字节数（TG 文件页文件夹行同款）：显示"N 项"。
            subtitle = when {
                row.isDirectory -> "${row.childCount} 项 · ${formatTime(row.modifiedMillis, nowMillis, zone)}"
                else -> "${formatFileSize(row.sizeBytes)} · ${formatTime(row.modifiedMillis, nowMillis, zone)}"
            },
        )

        /**
         * 排序（TG 文件页 `sortFileItems` 的逐条读法）：
         * - **目录恒在最前**（`isDir1 != isDir2 → dir first`，逆向也一样 —— 逆向排的是
         *   每一段内部的次序，不是把文件夹压到底下）；
         * - NAME：文件名大小写不敏感（`compareToIgnoreCase`）；
         * - SIZE：大者在前（TG 的 size 档没有小者在前这一说，但本仓给了逆向）；
         * - DATE：新者在前（TG 缺省 = 最近动过的在最上）；
         * - TYPE：扩展名分组（字母序），组内按名称。
         */
        fun sorted(
            files: List<ScriptFileRowUi>,
            sort: FileSort,
            reversed: Boolean,
        ): List<ScriptFileRowUi> {
            // 目录段**恒按名称**（TG 文件页 isDir 分支的比较器就是纯 name —— 大小/日期
            // 对文件夹没意义，它们的 sizeBytes 恒 0）；文件段才按所选档排。
            val (dirRows, fileRows) = files.partition { it.isDirectory }
            val dirs = dirRows.sortedBy { it.name.lowercase(Locale.ROOT) }
            val rest = when (sort) {
                FileSort.NAME -> fileRows.sortedBy { row -> row.name.lowercase(Locale.ROOT) }
                FileSort.SIZE -> fileRows.sortedByDescending { row -> row.sizeBytes }
                FileSort.DATE -> fileRows.sortedByDescending { row -> row.modifiedMillis }
                FileSort.TYPE -> fileRows.sortedWith(
                    compareBy({ row: ScriptFileRowUi -> row.ext }, { row: ScriptFileRowUi -> row.name.lowercase(Locale.ROOT) }),
                )
            }
            // 逆向只翻**文件段**：文件夹永远在列表头部（TG 文件页同款）。
            return if (reversed) dirs + rest.asReversed() else dirs + rest
        }
    }
}
