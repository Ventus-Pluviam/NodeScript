package com.autoscript.appservice.scheduler.persist

import com.autoscript.domain.scripts.ScheduledTask
import com.autoscript.domain.scripts.ScreenGuarantee
import com.autoscript.domain.scripts.TimedSchedule
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
 * 与已删除的 `JournalFileStoreTest` 同一地位：崩溃形态（最后半行）必须收敛而不是响亮失败 ——
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

    /**
     * 外审 B3 半（2026-10-11）：半行不能只"在内存里丢"，**盘上也得清掉**。
     *
     * 原实现的洞：replay 遇到无换行的尾行就 `break`（内存里干净了），但**盘上那半截还在**，
     * 紧接着的 append 会把它与下一条记录粘成一行 —— 此后每次重启 replay 都在这一行上抛
     * `IOException`，构造即失败 = 调度注册表再也打不开。这条用例走完整闭环：
     * 崩溃留半行 → 重开 → **再写一条** → 再重开仍读得出来。
     */
    @Test
    fun `半行截断后新写入不被粘连：重开仍可读（外审 B3 半）`() {
        FileTaskStore(dir).use { store -> store.put(once("t1")) }
        Files.write(
            dir.resolve("tasks.jsonl"),
            """{"op":"put","id":"t-half"""".toByteArray(),
            java.nio.file.StandardOpenOption.APPEND,
        )
        FileTaskStore(dir).use { reopened ->
            assertEquals(listOf("t1"), reopened.loadAll().map { it.id })
            reopened.put(once("t2"))       // 关键：往半行后面接着写
        }
        FileTaskStore(dir).use { again ->
            assertEquals(
                listOf("t1", "t2"), again.loadAll().map { it.id },
                "半行必须已在打开时截掉：否则新行与残片粘成一行，重放直接抛 IOException",
            )
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
