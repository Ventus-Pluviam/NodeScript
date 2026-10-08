package com.autoscript.domain.scripts

import com.autoscript.domain.json.DomainJson

/**
 * jsonl 意图日志 → SQLite 的**一次性导入解析**（§8.5 存储引擎替换的迁移面）。
 *
 * **为什么解析住 `:domain` 而不是解析方**：两个模块各需要一半 ——
 * `:platform:system` 的 `SqliteIntentStore.importRows` 要「行」，`:app` 装配层要
 * 「这个文件该不该导」。把解析与裁定都放这里，两边共用同一份口径，且它是纯函数
 * （零 Android、零 IO），本机 JVM 可测。
 *
 * **格式**：与**已退役的 jsonl 意图存储**（`JournalFileStore`，2026-10-08 删除）
 * 的落盘形态逐字一致 —— 这里读的正是老设备上那些文件，格式冻结不动：
 * ```
 * {"op":"start","runId":N,"projectId":…,"scriptPath":…,"runNonce":…,"trigger":…,
 *  "screen":…,"scheduledAt":…,"startedAt":…,"deadlineAt":…|null,"args":[…],"timeoutMillis":…|null}
 * {"op":"seal","runId":N,"outcome":"NAME","detail":…|null,"at":M}
 * ```
 * 老版本的行**缺 `args`/`timeoutMillis` 两键**（B11 之前落盘的）—— 缺键按默认解析
 * （空参数 / 无超时），与老写侧的 `optStrList`/`optLong` 同口径。
 *
 * **容忍度与写侧对齐**（§8.5「容忍最后一条半行」）：
 * - 尾部没有换行的半行**丢弃**（写中断留下的残行，没落完的组等于没发生）；
 * - 行内损坏（JSON 非法 / 字段型别越界 / seal 指向未知 runId）**响亮失败**，
 *   不静默跳过 —— 那是"日志坏了"，把它当成"少几条历史"会让幂等锚点悄悄变窄。
 */
object IntentLogImport {

    /** 一条可导入的意图行（runId 保留原值 —— 见 `IntentStoreSql.importRow` 的理由）。 */
    data class Row(
        val runId: Long,
        val start: IntentStore.StartRow,
        val outcome: IntentStore.StoredOutcome?,
        val committedAtMillis: Long?,
    )

    /**
     * 解析整份 jsonl 文本。
     *
     * @throws IllegalArgumentException 行损坏（JSON 非法 / 字段缺失或型别越界 /
     *   seal 指向不存在的 runId / 存活行与终态行自相矛盾）
     */
    fun parse(text: String): List<Row> {
        val starts = LinkedHashMap<Long, IntentStore.StartRow>()
        val seals = LinkedHashMap<Long, Pair<IntentStore.StoredOutcome, Long>>()
        var lineStart = 0
        while (true) {
            val nl = text.indexOf('\n', lineStart)
            // 尾部半行（找不到换行）= 写中断留下的残行：**丢弃**，与写侧 force 组同口径。
            if (nl < 0) break
            val line = text.substring(lineStart, nl)
            lineStart = nl + 1
            if (line.isNotBlank()) fold(line, starts, seals)
        }
        return starts.map { (runId, start) ->
            val sealed = seals[runId]
            Row(
                runId = runId,
                start = start,
                outcome = sealed?.first,
                committedAtMillis = sealed?.second,
            )
        }
    }

    private fun fold(
        line: String,
        starts: MutableMap<Long, IntentStore.StartRow>,
        seals: MutableMap<Long, Pair<IntentStore.StoredOutcome, Long>>,
    ) {
        val fields = try {
            objectFields(line)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("intent-log 行损坏：${e.message}", e)
        }
        val op = fields["op"] as? String
        requireNotNull(op) { "intent-log 行损坏：缺 op" }
        when (op) {
            "start" -> {
                val runId = long(fields, "runId")
                starts[runId] = IntentStore.StartRow(
                    projectId = str(fields, "projectId"),
                    scriptPath = str(fields, "scriptPath"),
                    runNonce = str(fields, "runNonce"),
                    trigger = str(fields, "trigger"),
                    screen = str(fields, "screen"),
                    scheduledAtMillis = long(fields, "scheduledAt"),
                    startedAtMillis = long(fields, "startedAt"),
                    deadlineMillis = optLong(fields, "deadlineAt"),
                    // 老行缺这两键 = 空参数 / 无超时（升级兼容，与老写侧同口径）
                    args = optStrList(fields, "args"),
                    timeoutMillis = optLong(fields, "timeoutMillis"),
                )
            }
            "seal" -> {
                val runId = long(fields, "runId")
                require(runId in starts) { "intent-log 行损坏：seal 指向未知 runId=$runId" }
                seals[runId] = IntentStore.StoredOutcome(
                    name = str(fields, "outcome"),
                    detail = optStr(fields, "detail"),
                ) to long(fields, "at")
            }
            else -> throw IllegalArgumentException("intent-log 行损坏：未知 op=\"$op\"")
        }
    }

    // —— 值域裁剪（只认 字符串/整数/null/字符串数组 四种，与冻结行格式一致）——

    private fun objectFields(line: String): Map<String, Any?> {
        val obj = DomainJson.decode(line) as? DomainJson.Value.Obj
            ?: throw IllegalArgumentException("不是 JSON 对象")
        return obj.fields.mapValues { (k, v) -> plain(k, v) }
    }

    /** 单个值的型别裁剪；四种之外一律响亮失败（冻结行格式是协议，不是 codec 的事）。 */
    private fun plain(key: String, v: DomainJson.Value): Any? = when (v) {
        is DomainJson.Value.S -> v.v
        is DomainJson.Value.N -> requireNotNull(v.raw.toLongOrNull()) { "字段 $key 非整数（${v.raw}）" }
        DomainJson.Value.Null -> null
        is DomainJson.Value.Arr ->
            v.items.map { requireNotNull(it as? DomainJson.Value.S) { "字段 $key 数组含非字符串" }.v }
        else -> throw IllegalArgumentException("字段 $key 值型越界")
    }

    private fun str(f: Map<String, Any?>, key: String): String =
        f[key] as? String ?: throw IllegalArgumentException("缺字符串字段 $key")

    private fun long(f: Map<String, Any?>, key: String): Long =
        f[key] as? Long ?: throw IllegalArgumentException("缺整数字段 $key")

    private fun optLong(f: Map<String, Any?>, key: String): Long? = when (val v = f[key]) {
        null -> null
        is Long -> v
        else -> throw IllegalArgumentException("字段 $key 非整数")
    }

    private fun optStr(f: Map<String, Any?>, key: String): String? = when (val v = f[key]) {
        null -> null
        is String -> v
        else -> throw IllegalArgumentException("字段 $key 非字符串")
    }

    private fun optStrList(f: Map<String, Any?>, key: String): List<String> = when (val v = f[key]) {
        null -> emptyList()
        is List<*> -> v.map { it as? String ?: throw IllegalArgumentException("字段 $key 数组含非字符串") }
        else -> throw IllegalArgumentException("字段 $key 非数组")
    }
}
