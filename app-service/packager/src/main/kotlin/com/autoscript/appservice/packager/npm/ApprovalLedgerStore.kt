package com.autoscript.appservice.packager.npm

import com.autoscript.domain.npm.ApprovalAction
import com.autoscript.domain.npm.ApprovalDecision
import com.autoscript.domain.npm.ApprovalRequest
import com.autoscript.domain.npm.ApprovalStatus
import com.autoscript.domain.npm.ApprovalTicket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 审批账本持久化（docs §10.2 存储布局 · `files/.autojs/approve-ledger.jsonl`）。
 *
 * 为什么要落盘：审批是**信任决策**，重启蒸发 = 用户批准过的包要重新批（§10.5 铁律
 * 「版本升级必须重新审批」的两面——同版本也不该重复打扰）；记录条目绑定
 * `pkg + 版本 + 动作`，UI 审计页要能回放全部历史（含 REJECTED）。
 *
 * 格式（jsonl，追加 + fsync + 半行容忍，与 scheduler JournalFileStore /
 * InstallJournal 同款持久化纪律）：
 * - `{"op":"submit","requestId":R,"projectId":P,"pkg":N,"versionHash":H,"action":A,"at":M}`
 * - `{"op":"resolve","requestId":R,"status":"APPROVED"|"REJECTED","at":M}`
 *
 * 崩溃自愈：replay 时最后状态即终态（resolve 单向写，无回滚事件）。
 */
interface ApprovalStore {
    fun insertSubmit(r: ApprovalRequest)
    fun insertResolve(requestId: String, status: ApprovalStatus, atMillis: Long)
    fun all(): List<ApprovalEntry>
    /** 崩溃恢复用：已持久化的最大 seq（防重启后 id 碰撞）。 */
    fun lastSeq(): Long

    data class ApprovalEntry(
        val request: ApprovalRequest,
        val status: ApprovalStatus,
        val decidedAtMillis: Long? = null,
    )
}

class FileApprovalStore(private val dir: Path) : ApprovalStore {

    private val file: Path = dir.resolve("approve-ledger.jsonl")
    private val lock = Any()

    init {
        Files.createDirectories(dir)
    }

    override fun insertSubmit(r: ApprovalRequest) = synchronized(lock) {
        val line = buildString {
            append("""{"op":"submit","requestId":""").append(q(r.id))
            append(""","projectId":""").append(q(r.projectId))
            append(""","pkg":""").append(q(r.pkg))
            append(""","versionHash":""").append(q(r.versionHash))
            append(""","action":""").append(q(r.action.name))
            append(""","at":""").append(r.requestedAtMillis)
            append("}")
        }
        append(line)
    }

    override fun insertResolve(requestId: String, status: ApprovalStatus, atMillis: Long) = synchronized(lock) {
        append(
            buildString {
                append("""{"op":"resolve","requestId":""").append(q(requestId))
                append(""","status":""").append(q(status.name))
                append(""","at":""").append(atMillis)
                append("}")
            },
        )
    }

    override fun all(): List<ApprovalStore.ApprovalEntry> = synchronized(lock) {
        if (!Files.exists(file)) return emptyList()
        val requests = LinkedHashMap<String, ApprovalRequest>()
        val statuses = LinkedHashMap<String, ApprovalStatus>()
        val decidedAt = HashMap<String, Long>()
        Files.readAllLines(file, StandardCharsets.UTF_8).forEach { line ->
            val rec = parse(line) ?: return@forEach
            when (rec.op) {
                "submit" -> {
                    requests[rec.requestId] = ApprovalRequest(
                        rec.requestId, rec.projectId ?: "", rec.pkg ?: "",
                        rec.versionHash ?: "", rec.action ?: ApprovalAction.INSTALL_SCRIPT,
                        rec.at ?: 0,
                    )
                    statuses[rec.requestId] = ApprovalStatus.PENDING
                }
                "resolve" -> {
                    rec.requestId.takeIf { it in requests }?.let {
                        statuses[it] = rec.status ?: ApprovalStatus.APPROVED
                        if (rec.at != null) decidedAt[it] = rec.at
                    }
                }
            }
        }
        requests.map { (id, req) ->
            ApprovalStore.ApprovalEntry(req, statuses[id] ?: ApprovalStatus.EXPIRED, decidedAt[id])
        }
    }

    override fun lastSeq(): Long = synchronized(lock) {
        all().mapNotNull { it.request.id.removePrefix("apr-").toLongOrNull() }.maxOrNull() ?: 0L
    }

    private fun append(line: String) {
        Files.write(
            file,
            (line + "\n").toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND, StandardOpenOption.CREATE,
        )
        java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE).use { it.force(true) }
    }

    private data class Rec(
        val op: String, val requestId: String,
        val projectId: String? = null, val pkg: String? = null,
        val versionHash: String? = null, val action: ApprovalAction? = null,
        val status: ApprovalStatus? = null, val at: Long? = null,
    )

    private fun parse(line: String): Rec? {
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
                        'n' -> sb.append('\n'); else -> return null
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
            val s = p
            if (p < line.length && line[p] == '-') p++
            while (p < line.length && line[p].isDigit()) p++
            if (p == s) return null
            return line.substring(s, p).toLongOrNull()
        }
        val op = str("op") ?: return null
        val rid = str("requestId") ?: return null
        if (op != "submit" && op != "resolve") return null
        return Rec(
            op = op, requestId = rid,
            projectId = str("projectId"), pkg = str("pkg"),
            versionHash = str("versionHash"),
            action = str("action")?.let { runCatching { ApprovalAction.valueOf(it) }.getOrNull() },
            status = str("status")?.let { runCatching { ApprovalStatus.valueOf(it) }.getOrNull() },
            at = num("at"),
        )
    }

    private fun q(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            '\n' -> append("\\n"); else -> append(c)
        }
        append('"')
    }
}
