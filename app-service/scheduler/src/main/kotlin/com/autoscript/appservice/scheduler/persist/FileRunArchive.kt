package com.autoscript.appservice.scheduler.persist

import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.RunArchive
import com.autoscript.domain.scripts.RunRecord
import com.autoscript.domain.scripts.RunState
import com.autoscript.domain.scripts.isTerminal
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * jsonl 追加式运行档案（JVM 本机/单测实现；Android 生产由 SQLiteDatabase 实现替换，语义不变）。
 *
 * 与 [JournalFileStore] 同构的崩溃持久纪律（docs/framework-design.md §8.5）：
 * - 持久形态：`<dir>/run-archive.jsonl`，每行一条 record：
 *   `{"op":"put","id":N,"projectId":"…","scriptPath":"…","runNonce":"…","state":"…",`
 *   `"startedAt":M|null,"finishedAt":M|null,"intentRunId":K|null}`；
 * - 每次写后 `FileChannel.force(true)`；启动 replay 全量重建内存索引，容忍最后一条半行；
 * - 状态机裁定（终态不可改写/复活、link 不一致拒绝、关联成立不改写）与
 *   `InMemoryRunArchive` 逐字一致 —— 本类是"同一语义换了存储引擎"，不是第二套语义。
 *
 * 为什么档案与意图日志是两个文件：意图日志的键是 intentRunId（投递侧），档案的键是
 * engineRunId（执行侧）；两套 runId 是两个真值（`EngineRunLink` 只做关联，不做合并）。
 * 合并成一个文件会让「只写一侧」的孤儿记录在格式上不可见 —— 分开存，孤儿才可被审计。
 */
class FileRunArchive(private val dir: Path) : RunArchive, AutoCloseable {

    private val file: Path = dir.resolve("run-archive.jsonl")
    private val channel: FileChannel
    private val writeLock = Any()

    private val records = HashMap<Long, RunRecord>()        // engineRunId → record
    private val linkOf = HashMap<Long, EngineRunLink>()     // engineRunId → 双 id 关联

    init {
        Files.createDirectories(dir)
        replay()
        channel = FileChannel.open(
            file,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
        )
    }

    override suspend fun put(record: RunRecord, link: EngineRunLink?): RunRecord = synchronized(writeLock) {
        adjudicate(record, link)        // 先裁定（响亮失败在落盘前，不污染 journal）
        appendLine(ArchiveCodec.put(record, link?.intentRunId))
        records[record.id] = record
        if (link != null) linkOf[record.id] = link
        record
    }

    override suspend fun record(engineRunId: Long): RunRecord? = synchronized(writeLock) {
        records[engineRunId]
    }

    override suspend fun link(engineRunId: Long): EngineRunLink? = synchronized(writeLock) {
        linkOf[engineRunId]
    }

    override suspend fun recordsOfIntent(intentRunId: Long): List<RunRecord> = synchronized(writeLock) {
        linkOf.values.filter { it.intentRunId == intentRunId }
            .mapNotNull { records[it.engineRunId] }
            .sortedBy { it.id }
    }

    override suspend fun recordsOfProject(projectId: String): List<RunRecord> = synchronized(writeLock) {
        records.values.filter { it.projectId == projectId }.sortedBy { it.id }
    }

    override suspend fun unfinished(): List<RunRecord> = synchronized(writeLock) {
        records.values.filter { !it.state.isTerminal }.sortedBy { it.id }
    }

    override fun close() {
        channel.force(true)
        channel.close()
    }

    /**
     * 状态机裁定（与 `InMemoryRunArchive.put` 逐字同义）：
     * - link.engineRunId 与 record.id 不一致 → IllegalArgumentException；
     * - 终态被改写/复活 → IllegalStateException（点名"不可改写"/"复活"）；
     * - 双 id 关联已成立又带不同 link → IllegalStateException（点名"关联已成立"）。
     */
    private fun adjudicate(record: RunRecord, link: EngineRunLink?) {
        if (link != null && link.engineRunId != record.id) {
            throw IllegalArgumentException(
                "EngineRunLink.engineRunId=${link.engineRunId} 与 RunRecord.id=${record.id} 不一致",
            )
        }
        val existing = records[record.id]
        if (existing != null) {
            if (existing.state.isTerminal) {
                val verb = if (!record.state.isTerminal) "复活" else "不可改写"
                throw IllegalStateException(
                    "终态记录$verb: engineRunId=${record.id} state=${existing.state}",
                )
            }
            val existingLink = linkOf[record.id]
            if (link != null && existingLink != null && existingLink != link) {
                throw IllegalStateException(
                    "双 id 关联已成立，不允许改写: $existingLink → $link",
                )
            }
        }
    }

    private fun replay() {
        if (!Files.exists(file)) return
        val bytes = Files.readAllBytes(file)
        var pos = 0
        while (pos < bytes.size) {
            val nl = findNewline(bytes, pos)
            if (nl < 0) break                       // 最后半行：丢弃（崩溃截断）
            val line = String(bytes, pos, nl - pos, StandardCharsets.UTF_8)
            pos = nl + 1
            if (line.isBlank()) continue
            val rec = ArchiveCodec.parse(line)
            // replay 不走 adjudicate：journal 是已裁定事实的追加史，同一 id 后行覆盖前行
            // 即"前进"；终态后又出现行 = 上次崩溃在裁定后、落盘前的重复写，取终态（后行胜）。
            val prev = records[rec.record.id]
            val winner = if (prev != null && prev.state.isTerminal && !rec.record.state.isTerminal) prev else rec.record
            records[rec.record.id] = winner
            if (rec.intentRunId != null) {
                val cand = EngineRunLink(rec.intentRunId, rec.record.id)
                // 关联一旦成立不改写：首个 link 胜（与 adjudicate 的"成立不改写"同向）
                linkOf.putIfAbsent(rec.record.id, cand)
            }
        }
    }

    private fun appendLine(line: String) {
        val buf = ByteBuffer.wrap(line.toByteArray(StandardCharsets.UTF_8))
        while (buf.hasRemaining()) channel.write(buf)
        channel.force(true)
    }

    private fun findNewline(bytes: ByteArray, from: Int): Int {
        for (i in from until bytes.size) if (bytes[i] == '\n'.code.toByte()) return i
        return -1
    }

    internal object ArchiveCodec {

        fun put(r: RunRecord, intentRunId: Long?): String = buildString {
            append("""{"op":"put","id":""").append(r.id)
            append(""","projectId":""").append(q(r.projectId))
            append(""","scriptPath":""").append(q(r.scriptPath))
            append(""","runNonce":""").append(q(r.runNonce))
            append(""","state":""").append(q(r.state.name))
            append(""","startedAt":""")
            if (r.startedAtMillis == null) append("null") else append(r.startedAtMillis)
            append(""","finishedAt":""")
            if (r.finishedAtMillis == null) append("null") else append(r.finishedAtMillis)
            append(""","intentRunId":""")
            if (intentRunId == null) append("null") else append(intentRunId)
            append("}\n")
        }

        data class Rec(val record: RunRecord, val intentRunId: Long?)

        fun parse(line: String): Rec {
            val fields = JsonLine.parse(line)
            if (fields["op"] != "put") throw java.io.IOException("archive 行损坏（op=${fields["op"]}）")
            return Rec(
                RunRecord(
                    id = fields.long("id"),
                    projectId = fields.str("projectId"),
                    scriptPath = fields.str("scriptPath"),
                    runNonce = fields.str("runNonce"),
                    state = RunState.valueOf(fields.str("state")),
                    startedAtMillis = fields.optLong("startedAt"),
                    finishedAtMillis = fields.optLong("finishedAt"),
                ),
                intentRunId = fields.optLong("intentRunId"),
            )
        }

        private fun q(s: String): String = JsonLine.quote(s)
    }
}
