package com.autoscript.appservice.scriptrepo.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 部署事务日记（docs/framework-design.md §9.6 原子部署 / §10 install.journal 精神）。
 *
 * 记录行：`<nonce>US<relPath(enc)>US<sha256(enc)>US<state>（US = U+001F）`，state ∈ STAGED|COMMITTED|ROLLED_BACK。
 * 分隔符为 U+001F（ASCII Unit Separator）：路径/哈希中不可能出现的控制字符，避免空格分隔被
 * 含空格字段污染；全部字段经 [FieldCodec] URL 编码，行内绝不出现换行。
 * 崩溃恢复：[unfinished] 返回未落定的 STAGED 记录，由调度方决定回滚或重做。
 * 行级容错：撕裂写（崩溃正发生在 append 中途）只丢该残行，不误判其他记录状态。
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

    /** 全部记录（含历史，便于审计与清理）；撕裂残行逐条跳过（行级容错，不连坐完好行）。 */
    fun all(): List<Record> {
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file, StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull { line -> parseLine(line) }
    }

    /** 崩溃后未落定的记录：同一 nonce 的最后一条仍是 STAGED（其后无 COMMITTED/ROLLED_BACK）。 */
    fun unfinished(): List<Record> {
        val lastByNonce = HashMap<String, Record>()
        for (r in all()) lastByNonce[r.nonce] = r
        return lastByNonce.values.filter { it.state == State.STAGED }
    }

    private fun append(r: Record) {
        Files.createDirectories(file.parent)
        val line = "${r.nonce}$SEP${FieldCodec.enc(r.relPath)}$SEP${FieldCodec.enc(r.sha256)}$SEP${r.state.name}\n"
        Files.write(file, line.toByteArray(StandardCharsets.UTF_8), StandardOpenOption.APPEND, StandardOpenOption.CREATE)
    }

    private fun parseLine(line: String): Record? {
        val parts = line.split(SEP)
        if (parts.size != 4) return null                    // 撕裂残行/损坏行：跳过（行式分行保证只影响该行）
        val state = runCatching { State.valueOf(parts[3]) }.getOrNull() ?: return null
        return Record(
            nonce = parts[0],
            relPath = FieldCodec.dec(parts[1]),
            sha256 = FieldCodec.dec(parts[2]),
            state = state,
        )
    }

    private companion object {
        /** 字段分隔符：U+001F Unit Separator（路径/哈希经 URL 编码后绝不含此字符）。 */
        const val SEP = '\u001F'
    }
}
