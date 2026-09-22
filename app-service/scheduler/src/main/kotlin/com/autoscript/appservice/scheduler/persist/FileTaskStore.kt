package com.autoscript.appservice.scheduler.persist

import com.autoscript.appservice.scheduler.core.ScheduledTask
import com.autoscript.appservice.scheduler.core.ScreenGuarantee
import com.autoscript.appservice.scheduler.core.TaskStore
import com.autoscript.appservice.scheduler.core.TimedSchedule
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.ZoneId

/**
 * 注册表文件实现（`tasks.jsonl`，与意图日志同一目录、同一追加纪律，§8.6）。
 *
 * 行格式（冻结，[JsonLine] 解析）：
 * - `{"op":"put",...全字段...}` —— schedule 直写（upsert，同 id 覆盖）
 * - `{"op":"del","id":"t1"}` —— cancel 落 tombstone（不删行：崩溃截断只丢最后半行，重放收敛）
 *
 * 与 [JournalFileStore] 同纪律：每次写 `force(true)`、启动 replay 全量重建、
 * 容忍最后半行。字段全字符串化（`kind`/`a`/`b` 表调度计划：once→a=delaySeconds；
 * daily→a=hour,b=minute；cron→a=expr），参数列表展平为 `argsN` + `arg0…` ——
 * persist 层零第三方依赖，格式漂移在编译期可见。
 */
class FileTaskStore(dir: Path) : TaskStore {

    private val file: Path = dir.resolve("tasks.jsonl")
    private val channel: FileChannel
    private val writeLock = Any()
    private val tasks = HashMap<String, ScheduledTask>()   // replay 重建的收敛视图

    init {
        Files.createDirectories(dir)
        replay()
        channel = FileChannel.open(
            file,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
        )
    }

    override fun put(task: ScheduledTask): Unit = synchronized(writeLock) {
        appendLine(Codec.put(task))
        tasks[task.id] = task
    }

    override fun remove(taskId: String): Unit = synchronized(writeLock) {
        appendLine("""{"op":"del","id":${JsonLine.quote(taskId)}}""" + "\n")
        tasks.remove(taskId)
    }

    override fun loadAll(): List<ScheduledTask> = synchronized(writeLock) {
        tasks.values.sortedBy { it.id }
    }

    override fun close() {
        synchronized(writeLock) { channel.force(true); channel.close() }
    }

    private fun replay() {
        if (!Files.exists(file)) return
        val bytes = Files.readAllBytes(file)
        var pos = 0
        while (pos < bytes.size) {
            val nl = findNewline(bytes, pos)
            if (nl < 0) break                        // 最后半行：丢弃（崩溃截断）
            val line = String(bytes, pos, nl - pos, StandardCharsets.UTF_8)
            pos = nl + 1
            if (line.isBlank()) continue
            Codec.apply(line, tasks)
        }
    }

    private fun findNewline(b: ByteArray, from: Int): Int {
        for (i in from until b.size) if (b[i] == '\n'.code.toByte()) return i
        return -1
    }

    private fun appendLine(s: String) {
        val buf = ByteBuffer.wrap(s.toByteArray(StandardCharsets.UTF_8))
        while (buf.hasRemaining()) channel.write(buf)
        channel.force(true)
    }

    internal object Codec {

        fun put(t: ScheduledTask): String = buildString {
            val (kind, a, b) = when (val s = t.schedule) {
                is TimedSchedule.Once -> Triple("once", s.delaySeconds.toString(), "")
                is TimedSchedule.Daily -> Triple("daily", s.hourOfDay.toString(), s.minuteOfHour.toString())
                is TimedSchedule.Cron -> Triple("cron", s.expr, "")
            }
            append("""{"op":"put","id":""").append(q(t.id))
            append(""","name":""").append(q(t.name))
            append(""","project":""").append(q(t.projectId))
            append(""","script":""").append(q(t.scriptPath))
            append(""","kind":""").append(q(kind))
            append(""","a":""").append(q(a))
            append(""","b":""").append(q(b))
            append(""","screen":""").append(q(t.screen.name))
            append(""","argsN":""").append(t.args.size)
            t.args.forEachIndexed { i, arg -> append(""","arg$i":""").append(q(arg)) }
            append(""","timeout":""")
            if (t.scriptTimeoutMillis == null) append("null") else append(t.scriptTimeoutMillis)
            append(""","tz":""").append(q(t.timezone.id))
            append(""","enabled":""").append(if (t.enabled) 1 else 0)
            append("}\n")
        }

        fun apply(line: String, into: MutableMap<String, ScheduledTask>) {
            val f = JsonLine.parse(line)
            when (f.str("op")) {
                "put" -> {
                    val schedule = when (f.str("kind")) {
                        "once" -> TimedSchedule.Once(f.str("a").toLong())
                        "daily" -> TimedSchedule.Daily(f.str("a").toInt(), f.str("b").toInt())
                        "cron" -> TimedSchedule.Cron(f.str("a"))
                        else -> throw java.io.IOException("tasks 行损坏（kind=${f["kind"]}）")
                    }
                    val n = f.long("argsN").toInt()
                    val args = (0 until n).map { f.str("arg$it") }
                    val task = ScheduledTask(
                        id = f.str("id"),
                        name = f.str("name"),
                        projectId = f.str("project"),
                        scriptPath = f.str("script"),
                        schedule = schedule,
                        screen = ScreenGuarantee.valueOf(f.str("screen")),
                        args = args,
                        scriptTimeoutMillis = f.optLong("timeout"),
                        timezone = ZoneId.of(f.str("tz")),
                        enabled = f.long("enabled") != 0L,
                    )
                    into[task.id] = task
                }
                "del" -> into.remove(f.str("id"))
                else -> throw java.io.IOException("tasks 行损坏（op=${f["op"]}）")
            }
        }

        private fun q(s: String): String = JsonLine.quote(s)
    }
}
