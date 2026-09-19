package com.autoscript.appservice.packager.npm

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 安装事务日记（docs/framework-design.md §10.4 事务化安装与崩溃自愈）。
 *
 * 记录格式（jsonl，与 scheduler JournalFileStore 同款持久化纪律：追加 + fsync + 半行容忍）：
 * - `{"op":"begin","nonce":N,"projectId":P,"stageDir":"node_modules.part-<ts>","at":M}`
 * - `{"op":"commit","nonce":N,...}` / `{"op":"fail","nonce":N,"detail":...}`
 *
 * 崩溃自愈：
 * - 启动/每次安装前置扫描 [unfinished]：存在 begin 无 commit/fail 的 nonce → 暂存目录是残骸，
 *   由协调器删 `node_modules.part-*` 并可按 lock 走 `ci --offline` 一键回滚重建；
 * - reify 目标 = `node_modules.part-<ts>` 暂存 → 完成校验 → rename 到位（[InstallStaging]）。
 */
class InstallJournal(private val dir: Path) {

    private val file: Path = dir.resolve("install.journal")
    private val lock = Any()

    data class Entry(
        val nonce: String,
        val projectId: String,
        val stageDir: String,
        val state: State,
        val atMillis: Long,
        val detail: String? = null,
    )

    enum class State { BEGIN, COMMIT, FAIL }

    fun begin(nonce: String, projectId: String, stageDir: String) =
        append(Entry(nonce, projectId, stageDir, State.BEGIN, System.currentTimeMillis()))

    fun commit(nonce: String, projectId: String, stageDir: String) =
        append(Entry(nonce, projectId, stageDir, State.COMMIT, System.currentTimeMillis()))

    fun fail(nonce: String, projectId: String, stageDir: String, detail: String?) =
        append(Entry(nonce, projectId, stageDir, State.FAIL, System.currentTimeMillis(), detail))

    /** 全部记录（回放/审计）。 */
    fun all(): List<Entry> = synchronized(lock) {
        if (!Files.exists(file)) return emptyList()
        Files.readAllLines(file, StandardCharsets.UTF_8).mapNotNull { parse(it) }
    }

    /** 未完成事务：同 nonce 最后一行仍是 BEGIN。 */
    fun unfinished(): List<Entry> = synchronized(lock) {
        val last = LinkedHashMap<String, Entry>()
        for (e in all()) last[e.nonce] = e
        last.values.filter { it.state == State.BEGIN }
    }

    private fun append(e: Entry) = synchronized(lock) {
        Files.createDirectories(dir)
        Files.write(
            file,
            (encode(e) + "\n").toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND, StandardOpenOption.CREATE,
        )
        // fsync 组：FileChannel.force 等价（Files.write 不保证落盘）
        java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE).use { it.force(true) }
    }

    private fun encode(e: Entry): String = buildString {
        append("""{"op":""").append(
            when (e.state) { State.BEGIN -> "\"begin\""; State.COMMIT -> "\"commit\""; State.FAIL -> "\"fail\"" },
        )
        append(""","nonce":""").append(q(e.nonce))
        append(""","projectId":""").append(q(e.projectId))
        append(""","stageDir":""").append(q(e.stageDir))
        append(""","at":""").append(e.atMillis)
        if (e.detail != null) append(""","detail":""").append(q(e.detail))
        append("}")
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
        fun num(key: String): Long? {
            val pat = "\"$key\":"
            val i = line.indexOf(pat)
            if (i < 0) return null
            var p = i + pat.length
            val start = p
            if (p < line.length && line[p] == '-') p++
            while (p < line.length && line[p].isDigit()) p++
            if (p == start) return null
            return line.substring(start, p).toLongOrNull()
        }
        val op = str("op") ?: return null
        val state = when (op) {
            "begin" -> State.BEGIN; "commit" -> State.COMMIT; "fail" -> State.FAIL; else -> return null
        }
        return Entry(
            nonce = str("nonce") ?: return null,
            projectId = str("projectId") ?: return null,
            stageDir = str("stageDir") ?: return null,
            state = state,
            atMillis = num("at") ?: return null,
            detail = str("detail"),
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

/**
 * 事务化 reify（§10.4）：`node_modules.part-<ts>` 暂存 → [commit] rename 到位。
 * rename 同分区原子；崩溃残骸 = 未 rename 的 `node_modules.part-*` 目录（由 journal.unfinished 索引）。
 */
class InstallStaging(private val layout: NpmProjectLayout) {

    /** 开暂存目录（已存在则先清空——同 nonce 重试）。 */
    fun begin(projectId: String, nonce: String): Path {
        val stage = stagePath(projectId, nonce)
        stage.toFile().deleteRecursively()
        Files.createDirectories(stage)
        return stage
    }

    /** 落位：暂存 → `node_modules`（旧目录先移到墓碑名再删，避免半替换窗口）。 */
    fun commit(projectId: String, nonce: String) {
        val stage = stagePath(projectId, nonce)
        require(Files.isDirectory(stage)) { "暂存目录不存在: $stage" }
        val target = layout.nodeModules(projectId)
        val tombstone = target.resolveSibling("node_modules.tombstone-${System.nanoTime()}")
        if (Files.isDirectory(target)) {
            Files.move(target, tombstone, StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // 回滚墓碑，不让项目处于无 node_modules 态
            if (Files.isDirectory(tombstone)) {
                Files.move(tombstone, target, StandardCopyOption.ATOMIC_MOVE)
            }
            throw e
        }
        tombstone.toFile().deleteRecursively()
    }

    /** 清理残骸：unfinished journal 对应的暂存目录。 */
    fun sweep(projectId: String, staleNonces: Set<String>) {
        for (n in staleNonces) stagePath(projectId, n).toFile().deleteRecursively()
    }

    fun stagePath(projectId: String, nonce: String): Path =
        layout.projectRoot(projectId).resolve("node_modules.part-$nonce")
}
