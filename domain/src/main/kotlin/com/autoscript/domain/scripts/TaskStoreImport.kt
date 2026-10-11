package com.autoscript.domain.scripts

import com.autoscript.domain.json.DomainJson
import java.time.ZoneId

/**
 * jsonl 任务注册表 → SQLite 的**一次性导入解析**（§8.6 存储引擎替换的迁移面）。
 *
 * **为什么解析住 `:domain` 而不是解析方**：与 [IntentLogImport] 同一理由 ——
 * `:platform:system` 的 `SqliteTaskStore.importTasks` 要「行」，`:app` 装配层要「这个文件
 * 该不该导」。放这里两边共用同一份口径，且它是纯函数（零 Android、零 IO），本机 JVM 可测。
 *
 * **格式**：与**仍在服役的** jsonl 注册表（`FileTaskStore` 的 `Codec.put`）逐字一致 ——
 * 这里读的正是老设备上 `tasks.jsonl` 那些行，格式冻结不动：
 * ```
 * {"op":"put","id":…,"name":…,"project":…,"script":…,"kind":"once|daily|cron",
 *  "a":…,"b":…,"screen":"ANY|SCREEN_ON|SCREEN_OFF","argsN":N,"arg0":…,…,
 *  "timeout":…|null,"tz":"…","enabled":0|1}
 * {"op":"del","id":"t1"}
 * ```
 *
 * **墓碑必须一起解析出来**（[Row.revokedAtMillis]）：jsonl 侧的 `del` 是**墓碑行**而非删行，
 * 只导存活行会让「历史上撤销过某个 id」在迁移后变成「那个任务复活了」—— 用户看到的是
 * 「我没登记过的任务自己开始跑」。这是本条最容易被漏掉的一半。
 *
 * **容忍度与写侧对齐**（§8.6）：尾部无换行的半行**丢弃**（写中断留下的残行）；
 * 行内损坏（JSON 非法 / 字段型别越界 / 未知的 `kind`）**响亮失败**，不静默跳过 ——
 * 那是「注册表坏了」，把它当成「少几条任务」会让用户的排期静默消失。
 */
object TaskStoreImport {

    /** 一条可导入的行（[task] + 墓碑时刻；`null` = 存活行）。 */
    data class Row(val task: ScheduledTask, val revokedAtMillis: Long?)

    /**
     * 解析整份 jsonl 文本（**按 id 收敛** —— jsonl 是 append 史，同 id 可有多行）。
     *
     * 收敛口径与 jsonl 侧 replay 逐条同款：`put` 覆盖同 id、`del` 落墓碑、
     * **墓碑之后又 `put` 即复活**（后来的行胜）。
     *
     * @throws IllegalArgumentException 行损坏（JSON 非法 / 字段缺失或型别越界 / kind 不认识）
     */
    fun parse(text: String): List<Row> {
        val live = LinkedHashMap<String, ScheduledTask>()
        val revokedAt = LinkedHashMap<String, Long?>()
        var lineStart = 0
        while (true) {
            val nl = text.indexOf('\n', lineStart)
            // 找不到换行 = 尾部半行（残行丢弃：没落完的写等于没发生）；空行跳过。
            // 两处跳转合成一个判据（detekt LoopWithTooManyJumpStatements）。
            if (nl < 0) break
            val line = text.substring(lineStart, nl)
            lineStart = nl + 1
            if (!line.isBlank()) apply(line, live, revokedAt)
        }
        // 全量（含墓碑）：迁移的「墓碑一起导」那半就靠这个。
        val ids = LinkedHashSet<String>().apply { addAll(live.keys); addAll(revokedAt.keys) }
        return ids.map { id ->
            val task = live[id]
            if (task != null) {
                Row(task, revokedAt[id])
            } else {
                // 只有墓碑没有行的 id：jsonl 的 del 墓碑**不带载荷**（只有 id）——
                // 导成一行占位（name/project 留空、排期给 Once(0)），语义上它只是「已撤销」。
                Row(placeholderFor(id), revokedAt[id])
            }
        }
    }

    private fun apply(
        line: String,
        live: MutableMap<String, ScheduledTask>,
        revokedAt: MutableMap<String, Long?>,
    ) {
        val f = DomainJson.decode(line) as? DomainJson.Value.Obj
            ?: throw IllegalArgumentException("tasks 行损坏：载荷不是 JSON 对象")
        val op = requireNotNull((f.fields["op"] as? DomainJson.Value.S)?.v) { "tasks 行损坏：缺 op" }
        when (op) {
            "put" -> {
                val id = str(f, "id")
                live[id] = decodeTask(id, f)
                revokedAt.remove(id)            // 复活：后来的 put 胜，墓碑清掉
            }
            "del" -> {
                val id = str(f, "id")
                live.remove(id)
                // **墓碑时刻 = 0L 而不是 null**：null 在本对象里是「存活」的含义，
                // 拿它当墓碑会让 `Row.revokedAtMillis` 与存活行长得一样 ——
                // 迁移那侧据此判断「这行要不要带墓碑」，null 就成了「不带」。
                // jsonl 的 del 行不带时刻（那是 append 史，没记墙上钟），0L 是诚实的
                // 「有个撤销、但不知道什么时候」（SQLite 侧它是 `revoked_at = 0`，
                // 语义上与 jsonl 的墓碑一样：只表达「这行被撤销过」）。
                revokedAt[id] = 0L
            }
            // 未知 op **响亮失败**（不是跳过）：那是「注册表坏了」，当成「少几条任务」
            // 会让用户的排期静默消失，而根因查不出来。
            else -> throw IllegalArgumentException("tasks 行损坏：op=$op")
        }
    }

    private fun decodeTask(id: String, f: DomainJson.Value.Obj): ScheduledTask {
        val kind = str(f, "kind")
        val a = str(f, "a")
        val b = str(f, "b")
        // 非法 kind **响亮失败**（不回 Once(0) 之类）：那会让任务被投到错误时刻，
        // 而用户看到的只是「它怎么老是不触发」—— 根因查不出来。
        require(kind in KINDS) { "tasks 行损坏：kind=$kind（${KINDS.joinToString("/")} 之外的排期形态）" }
        val schedule = when (kind) {
            "once" -> TimedSchedule.Once(orThrow(a.toLongOrNull(), "a", kind, a))
            "daily" -> TimedSchedule.Daily(
                orThrow(a.toIntOrNull(), "a", kind, a),
                orThrow(b.toIntOrNull(), "b", kind, b),
            )
            else -> TimedSchedule.Cron(a)
        }
        val n = long(f, "argsN").toInt()
        val args = (0 until n).map { str(f, "arg$it") }
        return ScheduledTask(
            id = id,
            name = str(f, "name"),
            projectId = str(f, "project"),
            scriptPath = str(f, "script"),
            schedule = schedule,
            screen = ScreenGuarantee.valueOf(str(f, "screen")),
            args = args,
            scriptTimeoutMillis = optLong(f, "timeout"),
            timezone = runCatching { ZoneId.of(str(f, "tz")) }.getOrElse {
                throw IllegalArgumentException("tasks 行损坏：tz 不是合法时区（${str(f, "tz")}）", it)
            },
            enabled = long(f, "enabled") != 0L,
        )
    }

    /** 只剩墓碑的 id 的占位载荷（非空列全给合法值；它只表达「这个 id 被撤销过」）。 */
    private fun placeholderFor(id: String) = ScheduledTask(
        id = id, name = "", projectId = "", scriptPath = "",
        schedule = TimedSchedule.Once(0), enabled = false,
    )

    private fun str(f: DomainJson.Value.Obj, key: String): String =
        (f.fields[key] as? DomainJson.Value.S)?.v
            ?: throw IllegalArgumentException("tasks 行损坏：字段 $key 缺失或非字符串")

    /** 数字取 [DomainJson.Value.N]（它带 `raw` 文本 —— `null` 与 `0` 必须分得开）。 */
    private fun long(f: DomainJson.Value.Obj, key: String): Long =
        optLong(f, key) ?: throw IllegalArgumentException("tasks 行损坏：字段 $key 缺失或非数字")

    private fun optLong(f: DomainJson.Value.Obj, key: String): Long? =
        (f.fields[key] as? DomainJson.Value.N)?.raw?.toLongOrNull()

    private inline fun <T : Any> orThrow(value: T?, field: String, kind: String, raw: String): T =
        value ?: throw IllegalArgumentException("tasks 行损坏：kind=$kind 的 $field 不是整数（收到 $raw）")

    /** 三种合法排期形态（与 jsonl 侧 `Codec.put` 逐字同款）。 */
    private val KINDS = listOf("once", "daily", "cron")
}
