package com.autoscript.domain.scripts

import com.autoscript.domain.json.DomainJson

/**
 * jsonl 运行档案 → SQLite 的**一次性导入解析**（§8.5 存储引擎替换的迁移面；批 102 P4）。
 *
 * **为什么解析住 `:domain` 而不是解析方**：与 [IntentLogImport] / [TaskStoreImport] 同一理由
 * —— `:platform:system` 的 `SqliteRunArchive.importRecords` 要「行」，`:app` 装配层要
 * 「这个文件该不该导」。放这里两边共用同一份口径，且它是纯函数（零 Android、零 IO）。
 *
 * **格式**：与**仍在服役的** jsonl 档案（`FileRunArchive` 的 `ArchiveCodec.put`）逐字一致：
 * ```
 * {"op":"put","id":N,"projectId":…,"scriptPath":…,"runNonce":…,"state":"…",
 *  "startedAt":M|null,"finishedAt":M|null,"intentRunId":K|null,
 *  "exitCode":N|null,"crashSummary":"…"|null}
 * ```
 * 老版本的行**缺 `exitCode`/`crashSummary` 两键**（B11 之前落盘的）—— 缺键按默认解析
 * （null），与老写侧的 `optLong`/`optStr` 同口径。
 *
 * **收敛口径**（与 jsonl 侧 replay 逐条同款）：同 id 后行覆盖前行；**终态后又出现非终态行
 * 取终态**（上次崩溃在裁定后、落盘前的重复写）；关联**首个胜**（一旦成立不改写）。
 *
 * **尾部半行丢弃**（写中断的残行 = 没发生）；**行内损坏响亮失败**（那是「档案坏了」，
 * 把它当成「少几条历史」会让任务中心的追溯静默变窄）。
 */
object RunArchiveImport {

    /**
     * 一条可导入的行。
     *
     * [record] 带**原 engineRunId**（迁移硬要求，见 [RunArchiveWiring]）；
     * [intentRunId] 是双 id 关联（`null` = 那条行没有关联）。
     */
    data class Row(val record: RunRecord, val intentRunId: Long?)

    /**
     * 解析整份 jsonl 文本。
     *
     * @throws IllegalArgumentException 行损坏（JSON 非法 / 字段型别越界 / 未知 state）
     */
    fun parse(text: String): List<Row> {
        val records = LinkedHashMap<Long, RunRecord>()
        val links = HashMap<Long, Long>()          // engineRunId → intentRunId（首个胜）
        var lineStart = 0
        while (true) {
            val nl = text.indexOf('\n', lineStart)
            // 两处跳转合成一个判据（detekt LoopWithTooManyJumpStatements）：
            // 「找不到换行」= 尾部半行（残行丢弃）；「空行」跳过。
            if (nl < 0) break
            val line = text.substring(lineStart, nl)
            lineStart = nl + 1
            if (!line.isBlank()) apply(line, records, links)
        }
        return records.values.map { rec -> Row(rec, links[rec.id]) }
    }

    private fun apply(
        line: String,
        records: MutableMap<Long, RunRecord>,
        links: MutableMap<Long, Long>,
    ) {
        val f = DomainJson.decode(line) as? DomainJson.Value.Obj
            ?: throw IllegalArgumentException("archive 行损坏：载荷不是 JSON 对象")
        val op = (f.fields["op"] as? DomainJson.Value.S)?.v
            ?: throw IllegalArgumentException("archive 行损坏：缺 op")
        require(op == "put") { "archive 行损坏：op=$op" }

        val id = long(f, "id")
        val state = RunState.valueOf(str(f, "state"))   // 未知 state 响亮失败（不猜）
        val record = RunRecord(
            id = id,
            projectId = str(f, "projectId"),
            scriptPath = str(f, "scriptPath"),
            runNonce = str(f, "runNonce"),
            state = state,
            startedAtMillis = optLong(f, "startedAt"),
            finishedAtMillis = optLong(f, "finishedAt"),
            exitCode = optLong(f, "exitCode")?.toInt(),
            crashSummary = optStr(f, "crashSummary"),
        )
        // 终态后又出现非终态行 = 上次崩溃在裁定后、落盘前的重复写 → **取终态**（终态不可复活）
        val prev = records[id]
        records[id] = if (prev != null && prev.state.isTerminal && !state.isTerminal) prev else record
        // 关联**首个胜**（与 adjudicate 的「成立不改写」同向）
        optLong(f, "intentRunId")?.let { links.putIfAbsent(id, it) }
    }

    private fun str(f: DomainJson.Value.Obj, key: String): String =
        (f.fields[key] as? DomainJson.Value.S)?.v
            ?: throw IllegalArgumentException("archive 行损坏：字段 $key 缺失或非字符串")

    private fun long(f: DomainJson.Value.Obj, key: String): Long =
        requireNotNull(optLong(f, key)) { "archive 行损坏：字段 $key 缺失或非数字" }

    private fun optLong(f: DomainJson.Value.Obj, key: String): Long? =
        (f.fields[key] as? DomainJson.Value.N)?.raw?.toLongOrNull()

    private fun optStr(f: DomainJson.Value.Obj, key: String): String? =
        (f.fields[key] as? DomainJson.Value.S)?.v
}
