package com.autoscript.appservice.scheduler.persist

import com.autoscript.appservice.scheduler.core.ScheduledTask
import com.autoscript.appservice.scheduler.core.ScreenGuarantee
import com.autoscript.appservice.scheduler.core.TimedSchedule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

/**
 * 注册表文件语义（§8.6 调度持久性）：upsert/tombstone 收敛、重启重建、半行截断容忍。
 *
 * 与 [JournalFileStoreTest] 同一地位：崩溃形态（最后半行）必须收敛而不是响亮失败 ——
 * 丢的是最后一次 schedule/cancel，内存重建后仍是一张自洽的表（AlarmManager 侧多响一次
 * 由 onTrigger 查无任务直接 return 兜住，不双跑）。
 */
class FileTaskStoreTest {

    @TempDir
    lateinit var dir: Path

    private fun once(id: String, delay: Long = 60) =
        ScheduledTask(id, "名-$id", "p", "$id.js", TimedSchedule.Once(delay))

    private fun daily(id: String) =
        ScheduledTask(
            id, "名-$id", "p", "$id.js", TimedSchedule.Daily(9, 30),
            screen = ScreenGuarantee.SCREEN_ON,
            args = listOf("--fast", "带 空格\"引号"),
            scriptTimeoutMillis = 5_000,
            timezone = ZoneId.of("Asia/Shanghai"),
        )

    @Test
    fun `cron 表达式往返 —— 注册表不丢排期形态`() {
        val cron = ScheduledTask("c1", "名-c1", "p", "c1.js", TimedSchedule.Cron("0 9 * * 1"))
        FileTaskStore(dir).use { store ->
            store.put(cron)
            assertEquals(cron, store.loadAll().single())
        }
        FileTaskStore(dir).use { reopened ->
            assertEquals(cron, reopened.loadAll().single(), "重启重建：cron 行同样回来")
        }
    }

    @Test
    fun `put 后 loadAll 全字段往返`() {
        FileTaskStore(dir).use { store ->
            store.put(once("t1"))
            store.put(daily("t2"))
            val got = store.loadAll().associateBy { it.id }
            assertEquals(once("t1"), got["t1"])
            assertEquals(daily("t2"), got["t2"])
        }
    }

    @Test
    fun `同 id 覆盖：upsert 不是追加两份`() {
        FileTaskStore(dir).use { store ->
            store.put(once("t1", 60))
            store.put(once("t1", 600))
            val all = store.loadAll()
            assertEquals(1, all.size)
            assertEquals(TimedSchedule.Once(600), all.single().schedule)
        }
    }

    @Test
    fun `cancel 落 tombstone：重启重放后仍无该任务`() {
        FileTaskStore(dir).use { store ->
            store.put(once("t1"))
            store.put(once("t2"))
            store.remove("t1")
            assertEquals(listOf("t2"), store.loadAll().map { it.id })
        }
        FileTaskStore(dir).use { reopened ->
            assertEquals(listOf("t2"), reopened.loadAll().map { it.id }, "tombstone 重放后收敛")
        }
    }

    @Test
    fun `从未登记的 id remove 不炸不留痕`() {
        FileTaskStore(dir).use { store ->
            store.remove("ghost")
            assertTrue(store.loadAll().isEmpty())
        }
        FileTaskStore(dir).use { reopened ->
            assertTrue(reopened.loadAll().isEmpty(), "幽灵 tombstone 重放无影响")
        }
    }

    @Test
    fun `重启重建：关 store 再开任务都在`() {
        FileTaskStore(dir).use { store ->
            store.put(once("t1"))
            store.put(daily("t2"))
        }
        FileTaskStore(dir).use { reopened ->
            val got = reopened.loadAll().associateBy { it.id }
            assertEquals(once("t1"), got["t1"])
            assertEquals(daily("t2"), got["t2"])
        }
    }

    @Test
    fun `最后半行截断容忍：崩溃写一半仍收敛到此前状态`() {
        FileTaskStore(dir).use { store ->
            store.put(once("t1"))
        }
        // 模拟崩溃：直接往文件尾追加半行（无换行）
        Files.write(
            dir.resolve("tasks.jsonl"),
            """{"op":"put","id":"t-half"""".toByteArray(),
            java.nio.file.StandardOpenOption.APPEND,
        )
        FileTaskStore(dir).use { reopened ->
            assertEquals(listOf("t1"), reopened.loadAll().map { it.id }, "半行丢弃，此前行不受影响")
        }
    }

    @Test
    fun `disabled 任务同样持久：恢复后留名由 rearm 决定续不续排`() {
        FileTaskStore(dir).use { store ->
            store.put(once("t1").copy(enabled = false))
            assertEquals(false, store.loadAll().single().enabled)
        }
        FileTaskStore(dir).use { reopened ->
            assertEquals(false, reopened.loadAll().single().enabled)
        }
    }
}
