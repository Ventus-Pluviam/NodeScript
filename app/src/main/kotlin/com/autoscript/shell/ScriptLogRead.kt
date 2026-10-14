package com.autoscript.shell

import com.autoscript.domain.host.ConsoleLineRow
import com.autoscript.domain.host.ScriptLogRow
import com.autoscript.domain.host.ScriptLogSnapshot
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 脚本输出日志文件的拼装（日志管理页「脚本输出」tab；2026-10-13 用户令）。
 *
 * 读的是落盘目录（`filesDir/.ns/script-logs/`，`ScriptLogStore` 写）——
 * **壳无关**（与 [ScriptFilesRead] 同一条"不依赖壳寄存器"口径）：目录照样可读，
 * 「运行中」那一列由调用方把在途 runId 集带进来（壳未就绪 = 空集 = 全 false）。
 *
 * **读失败回空表，不抛**（与 `ScriptFilesRead` 的"项目根不存在回空"同理 —— 区别是
 * 那边只有目录不存在才回空、遍历抛向上传；这边 index 坏行也跳过：崩溃可能留半行，
 * 半行不该让整份列表读不出来。真正的 IO 异常（目录不可读）才向上传，见 [snapshot]）。
 *
 * **index 重复行 last-wins**：同一 runId 记两条（崩溃重记）时后写的赢 —— 先写的那条
 * 对应同一份 `<runId>.log`，内容没有两份，列表也不该有两行。
 *
 * **scriptPath 是项目内形状**（如 `data/test.js`，任务登记/任务日志那一套）——
 * 文件清单的 `proj/…` 首段形状（`ScriptFileRow.relPath`）**不**进这里：index 写的是
 * `RuntimeController.start` 那条链上的 `request.scriptPath`，它与任务侧同源。
 * 呈现层标题要哪种斜杠形态自己拼（`ScriptLogRowUi.title`），本层只给事实。
 */
object ScriptLogRead {

    /** 最大日志行数（详情读口的截尾：长跑脚本的输出能到几十万行，全读撑爆详情页）。 */
    const val MAX_CONTENT_LINES = 2000

    /**
     * 拼文件列表（index 倒序 = 最近执行的在前；size = `<runId>.log` 字节数，缺失记 0）。
     *
     * @param activeRunIds 壳里此刻在途的 runId（`RuntimeController.activeRunIds`）——
     *   命中即 [ScriptLogRow.running] = true，呈现层此时不画大小、画「运行中」。
     */
    fun snapshot(filesDir: Path, activeRunIds: Set<Long> = emptySet()): ScriptLogSnapshot {
        val dir = logDir(filesDir)
        if (!Files.isDirectory(dir)) return ScriptLogSnapshot(emptyList())
        val index = dir.resolve(ScriptLogStore.INDEX_NAME)
        if (!Files.isRegularFile(index)) return ScriptLogSnapshot(emptyList())
        val entries = LinkedHashMap<Long, IndexEntry>()
        runCatching { Files.readAllLines(index, StandardCharsets.UTF_8) }
            .getOrElse { return ScriptLogSnapshot(emptyList()) }
            .forEach { raw ->
                parseIndexLine(raw)?.let { entries[it.runId] = it }
            }
        val rows = entries.values.reversed().map { e ->
            val size = runCatching { Files.size(dir.resolve(ScriptLogStore.logName(e.runId))) }
                .getOrDefault(0L)
            ScriptLogRow(
                runId = e.runId,
                projectId = e.projectId,
                scriptPath = e.scriptPath,
                startedAtMillis = e.startedAtMillis,
                sizeBytes = size,
                running = e.runId in activeRunIds,
            )
        }
        return ScriptLogSnapshot(rows)
    }

    /**
     * 读一次执行的日志全文（点列表行进详情；倒序取尾部 [maxLines] 行）。
     *
     * 坏行跳过（崩溃半行不掀翻整份详情）；文件缺失**抛**（调用方按"读不到"画，
     * 与 `ScriptFileOps.read` 同一条"空表≠读不到"纪律）。
     *
     * @return 时间升序（文件里就是这个序，截尾只掐掉头部）。
     */
    fun content(filesDir: Path, runId: Long, maxLines: Int = MAX_CONTENT_LINES): List<ConsoleLineRow> {
        require(maxLines > 0) { "maxLines 必须 > 0" }
        val file = logDir(filesDir).resolve(ScriptLogStore.logName(runId))
        require(Files.isRegularFile(file)) { "这次执行的日志文件不存在: $file" }
        val raw = Files.readAllLines(file, StandardCharsets.UTF_8)
        return raw.takeLast(maxLines).mapNotNull(::parseLogLine)
    }

    fun logDir(filesDir: Path): Path = filesDir.resolve(".ns").resolve("script-logs")

    private data class IndexEntry(
        val runId: Long,
        val projectId: String,
        val scriptPath: String,
        val startedAtMillis: Long,
    )

    private fun parseIndexLine(raw: String): IndexEntry? {
        val text = raw.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        val run = longField(text, "run") ?: return null
        val project = stringField(text, "project") ?: return null
        val path = stringField(text, "path") ?: return null
        val at = longField(text, "at") ?: return null
        return IndexEntry(run, project, path, at)
    }

    private fun parseLogLine(raw: String): ConsoleLineRow? {
        val text = raw.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        val seq = longField(text, "seq") ?: return null
        val at = longField(text, "at") ?: return null
        val level = stringField(text, "level") ?: return null
        val line = stringField(text, "text") ?: return null
        return ConsoleLineRow(seq = seq, runId = 0L, level = level, text = line, atMillis = at)
    }

    /**
     * 取一个字符串字段；形状不对（半行/截断）回 null（调用方跳过整行）。
     * 与 `ConsoleHistory.stringField` 同一份写法：`indexOf` 取第一次出现 ——
     * 故写侧必须把受约束的键放前面（见 `ScriptLogStore.begin` 的键序 KDoc）。
     */
    private fun stringField(text: String, name: String): String? {
        val key = "\"" + name + "\":"
        val start = text.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        if (from >= text.length || text[from] != '"') return null
        val sb = StringBuilder()
        var i = from + 1
        while (i < text.length) {
            when (val c = text[i]) {
                '\\' -> {
                    val next = text.getOrNull(i + 1) ?: return null
                    sb.append(
                        when (next) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            'u' -> {
                                val hex = text.substring(i + 2, (i + 6).coerceAtMost(text.length))
                                if (hex.length != 4) return null
                                hex.toIntOrNull(16)?.toChar() ?: return null
                            }
                            else -> return null
                        },
                    )
                    i += if (next == 'u') 6 else 2
                }
                '"' -> return sb.toString()
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    /**
     * 取一个数字字段。**只认裸数字**（与 [ScriptLogStore] 的 writer 严格同源：
     * 它写的是 `{"run":7`，不带引号）—— `run` 是行内首键、后面跟 `,`；`at` 是尾键、
     * 后面跟 `}`。形状不对（半行/截断）回 null（调用方跳过整行）。
     *
     * **为什么不能照抄 `ConsoleHistory.longField`**：那边的键是裸数字、值却要求带引号
     * （历史账的畸形形状）；本面的 writer 从生下来就是裸数字 —— 抄那份等于让每一行
     * index 都解析失败、列表恒空（正是 2026-10-14 那 6 条全红的根因）。
     */
    private fun longField(text: String, name: String): Long? {
        val key = "\"" + name + "\":"
        val start = text.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        if (from >= text.length) return null
        val comma = text.indexOf(',', from)
        val end = if (comma >= 0) comma else text.indexOf('}', from)
        if (end < 0) return null
        return text.substring(from, end).trim().toLongOrNull()
    }
}
