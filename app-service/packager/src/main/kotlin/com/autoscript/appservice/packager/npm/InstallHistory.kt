package com.autoscript.appservice.packager.npm

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 安装审计史（docs/framework-design.md §10.2 存储布局 · `files/.autojs/install-history`，
 * §10.5-2「审计日志（approve/registry 变更/lock 重签）落 App 且可导出」）。
 *
 * 与 [InstallJournal] 的分工（同为 jsonl + 追加 + fsync + 半行容忍，但用途不同）：
 * - journal = **事务状态机**：begin/commit/fail 三段决定崩溃残骸清扫与 `ci --offline` 回滚，
 *   键是 nonce（一次尝试），没有「那次装了什么」的用户语义；
 * - history = **审计事实**：一次操作的最终结局（install/ci/prune/registry 变更…）+ 可读明细，
 *   按发生序追加，UI 审计页/导出直接读，**永不因事务清扫而消失**。
 *
 * 只追加、不改写：失败/取消也如实入史（审计要能回答「用户看到成功了吗」——不能只记成功）。
 */
class InstallHistory(private val dir: Path) {

    private val file: Path = dir.resolve("install-history.jsonl")
    private val lock = Any()

    data class Entry(
        val op: String,
        val projectId: String,
        val success: Boolean,
        val detail: String? = null,
        val atMillis: Long,
    )

    /** 记一条审计事实（op 见 [Op]；未知 op 也如实收下，不为了枚举好看丢事件）。 */
    fun record(op: String, projectId: String, success: Boolean, detail: String?, atMillis: Long = System.currentTimeMillis()) =
        synchronized(lock) {
            append(
                buildString {
                    append("""{"op":""").append(q(op))
                    append(""","projectId":""").append(q(projectId))
                    append("""","ok":""").append(if (success) "true" else "false")
                    if (detail != null) append(""","detail":""").append(q(detail))
                    append(""","at":""").append(atMillis)
                    append("}")
                },
            )
        }

    /** 全部历史（按写入序）。 */
    fun all(): List<Entry> = synchronized(lock) {
        if (!Files.exists(file)) return emptyList()
        Files.readAllLines(file, StandardCharsets.UTF_8).mapNotNull { parse(it) }
    }

    fun forProject(projectId: String): List<Entry> = all().filter { it.projectId == projectId }

    /** 已知操作名（UI 分组/导出用；未知 op 不受此限）。 */
    object Op {
        const val INSTALL = "install"
        const val CI = "ci"
        const val UNINSTALL = "uninstall"
        const val PRUNE = "prune"
        const val DEDUPE = "dedupe"
        const val REGISTRY = "registry"     // §10.5-2 registry 变更须审计
        const val IMPORT = "import"
        const val EXPORT = "export"
    }

    private fun append(line: String) {
        Files.createDirectories(dir)
        Files.write(
            file,
            (line + "\n").toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND, StandardOpenOption.CREATE,
        )
        // fsync 组：FileChannel.force 等价（Files.write 不保证落盘）
        java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE).use { it.force(true) }
    }

    private fun parse(line: String): Entry? {
        if (line.isBlank()) return null
        fun str(key: String): String? {
            val pat = "\"$key\":\""
            val i = line.indexOf(pat)
            if (i < 0) return null
            val start = i + pat.length
            val sb = StringBuilder()
            var p = start
            while (p < line.length) {
                val c = line[p++]
                if (c == '"') return sb.toString()
                if (c == '\\' && p < line.length) {
                    when (val e = line[p++]) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\')
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        else -> return null
                    }
                } else sb.append(c)
            }
            return null
        }
        fun bool(key: String): Boolean? {
            val pat = "\"$key\":"
            val i = line.indexOf(pat) ?: return null
            val rest = line.substring(i + pat.length)
            return when {
                rest.startsWith("true") -> true
                rest.startsWith("false") -> false
                else -> null
            }
        }
        fun num(key: String): Long? {
            val pat = "\"$key\":"
            val i = line.indexOf(pat) ?: return null
            var p = i + pat.length
            val s = p
            if (p < line.length && line[p] == '-') p++
            while (p < line.length && line[p].isDigit()) p++
            if (p == s) return null
            return line.substring(s, p).toLongOrNull()
        }
        val op = str("op") ?: return null
        return Entry(
            op = op,
            projectId = str("projectId") ?: "",
            success = bool("ok") ?: false,
            detail = str("detail"),
            atMillis = num("at") ?: 0,
        )
    }

    private fun q(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }
}
