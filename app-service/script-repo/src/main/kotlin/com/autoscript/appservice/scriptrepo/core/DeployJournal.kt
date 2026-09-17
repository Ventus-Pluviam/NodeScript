package com.autoscript.appservice.scriptrepo.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 部署事务日记（docs/framework-design.md §9.6 原子部署 / §10 install.journal 精神）。
 *
 * 记录行：`<nonce><relPath(enc)><sha256><state>`，state ∈ STAGED|COMMITTED|ROLLED_BACK。
 * 崩溃恢复：[unfinished] 返回未落定的 STAGED 记录，由调度方决定回滚或重做。
 */
class DeployJournal(private val file: Path) {

    enum class State { STAGED, COMMITTED, ROLLED_BACK }

    data class Record(val nonce: String, val relPath: String, val sha256: String, val state: State)

    fun begin(nonce: String, relPath: String, hash: String) {
        append(Record(nonce, relPath, hash, State.STAGED))
    }

    fun commit(nonce: String, relPath: String, hash: String) {
        append(Record(nonce, relPath, hash, State.COMMITTED))
    }

    fun rollback(nonce: String, relPath: String, hash: String) {
        append(Record(nonce, relPath, hash, State.ROLLED_BACK))
    }

    /** 全部记录（含历史，便于审计与清理）。 */
    fun all(): List<Record> {
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file, StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('')
                if (parts.size != 4) null
                else Record(parts[0], FieldCodec.dec(parts[1]), parts[2], State.valueOf(parts[3]))
            }
    }

    /** 崩溃后未落定的记录：同一 nonce 的最后一条仍是 STAGED（其后无 COMMITTED/ROLLED_BACK）。 */
    fun unfinished(): List<Record> {
        val lastByNonce = HashMap<String, Record>()
        for (r in all()) lastByNonce[r.nonce] = r
        return lastByNonce.values.filter { it.state == State.STAGED }
    }

    private fun append(r: Record) {
        Files.createDirectories(file.parent)
        val line = "${r.nonce}${FieldCodec.enc(r.relPath)}${r.sha256}${r.state.name}\n"
        Files.write(file, line.toByteArray(StandardCharsets.UTF_8), StandardOpenOption.APPEND, StandardOpenOption.CREATE)
    }
}