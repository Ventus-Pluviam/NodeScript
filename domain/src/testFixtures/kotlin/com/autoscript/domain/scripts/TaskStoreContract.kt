package com.autoscript.domain.scripts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * [TaskStore] 契约套件（§8.6）：**同一组用例打在每一个实现上**。
 *
 * 与 [RunArchiveContract] 同一架子 —— §8.6 的存储引擎从 jsonl 换成 SQLite
 * （2026-10-11 批 102 P4）是**替换**不是重写。「upsert / 墓碑幂等 / 收敛全量」三条若在
 * 各实现间有任何一条不同，后果是「换引擎那天定时任务的行为变了」（最坏形态：
 * 被撤销过的任务复活并自己开始跑）。
 *
 * 三个实现各跑一遍：`InMemoryTaskStore`（`:domain` testFixtures，零 IO）、
 * `FileTaskStore`（jsonl）、`SqliteTaskStore`（`:platform:system`）。
 *
 * **持久性不在本套件**：`TaskStore` 契约**没有**要求跨 [open] 持久 —— 零 IO 的内存实现
 * 重开即清零是它的语义本身。与档案那条同一条纪律，故另立
 * [TaskStorePersistenceContract] 只由两个持久实现继承。
 */
abstract class TaskStoreContract {

    /** 打开一个**新实例**（指向同一份底层数据）。每次调用都必须是新实例。 */
    protected abstract fun open(): TaskStore

    protected fun once(id: String, delaySeconds: Long = 60) =
        ScheduledTask(id, "名-$id", "p", "$id.js", TimedSchedule.Once(delaySeconds))

    protected fun daily(id: String) = ScheduledTask(
        id, "名-$id", "p", "$id.js", TimedSchedule.Daily(9, 30),
        screen = ScreenGuarantee.SCREEN_ON,
        args = listOf("--fast", "带 空格\"引号"),
        scriptTimeoutMillis = 5_000,
        timezone = ZoneId.of("Asia/Shanghai"),
    )

    protected fun cron(id: String, expr: String = "0 9 * * 1") =
        ScheduledTask(id, "名-$id", "p", "$id.js", TimedSchedule.Cron(expr))

    @Test
    fun `put 后 loadAll 全字段往返`() {
        val store = open()
        store.put(once("t1"))
        store.put(daily("t2"))
        val got = store.loadAll().associateBy { it.id }
        assertEquals(once("t1"), got["t1"])
        assertEquals(daily("t2"), got["t2"], "排期形态/屏幕契约/参数/超时/时区都逐字段往返")
        store.close()
    }

    @Test
    fun `同 id 覆盖：upsert 不是追加两份`() {
        val store = open()
        store.put(once("t1", 60))
        store.put(once("t1", 600))
        val all = store.loadAll()
        assertEquals(1, all.size, "同 id 必须收敛成一条")
        assertEquals(TimedSchedule.Once(600), all.single().schedule, "留的是后写的那份")
        store.close()
    }

    @Test
    fun `remove 落墓碑：收敛视图里即刻没有该任务`() {
        // 单实例内的收敛（不需要持久性）；「跨重开墓碑仍在」住 [TaskStorePersistenceContract]。
        val store = open()
        store.put(once("t1"))
        store.put(once("t2"))
        store.remove("t1")
        assertEquals(listOf("t2"), store.loadAll().map { it.id }, "撤销后收敛视图立刻没有它")
        store.close()
    }

    @Test
    fun `从未登记的 id remove 不炸不留痕`() {
        val store = open()
        store.remove("ghost")
        assertTrue(store.loadAll().isEmpty(), "删除不存在的任务不炸")
        store.close()
    }

    @Test
    fun `loadAll 按 id 升序`() {
        val store = open()
        store.put(once("c"))
        store.put(once("a"))
        store.put(once("b"))
        assertEquals(listOf("a", "b", "c"), store.loadAll().map { it.id }, "收敛视图必须有序（确定性读口）")
        store.close()
    }

    @Test
    fun `撤销后重新登记即复活`() {
        // 这一条是 SQLite 侧最容易错的一处：`INSERT OR REPLACE` 若不显式把 `revoked_at`
        // 置 NULL，就会保留旧行的墓碑值 → 「撤销后再登记」的任务永远不续排。
        val store = open()
        store.put(once("t1"))
        store.remove("t1")
        assertTrue(store.loadAll().isEmpty())
        store.put(once("t1", 120))
        assertEquals(
            listOf("t1"), store.loadAll().map { it.id },
            "重新登记必须复活（那是调用方的意图），不能停在墓碑态",
        )
        assertEquals(TimedSchedule.Once(120), store.loadAll().single().schedule, "复活的是后写的那份")
        store.close()
    }

    @Test
    fun `重复撤销幂等`() {
        val store = open()
        store.put(once("t1"))
        store.remove("t1")
        store.remove("t1")           // 幂等：二次撤销不炸、不改变收敛视图
        assertTrue(store.loadAll().isEmpty())
        store.close()
    }

    @Test
    fun `空表 loadAll 回空表`() {
        val store = open()
        assertEquals(emptyList<ScheduledTask>(), store.loadAll(), "从未登记 → 空表（不是 null、不是异常）")
        store.close()
    }

    @Test
    fun `disabled 与 cron 形态同样往返`() {
        val store = open()
        store.put(once("t1", 60).copy(enabled = false))
        store.put(cron("t2"))
        val got = store.loadAll().associateBy { it.id }
        assertFalse(got["t1"]!!.enabled, "enabled=false 要保住（恢复后留名由 rearm 决定续不续排）")
        assertEquals(TimedSchedule.Cron("0 9 * * 1"), got["t2"]!!.schedule, "cron 形态不丢排期")
        store.close()
    }
}

/**
 * [TaskStore] 的**持久性**那一半（§8.6）：只由持久实现继承（理由同
 * [RunArchivePersistenceContract] 的 KDoc —— 契约没有要求持久，零 IO 形态重开即清零）。
 */
abstract class TaskStorePersistenceContract : TaskStoreContract() {

    @Test
    fun `重开新实例仍读得回（持久性）`() {
        val first = open()
        first.put(once("t1"))
        first.put(daily("t2"))
        first.close()

        val second = open()
        assertEquals(setOf("t1", "t2"), second.loadAll().map { it.id }.toSet())
        assertEquals(daily("t2"), second.loadAll().first { it.id == "t2" })
        second.close()
    }

    @Test
    fun `remove 落墓碑：重开重放后仍无该任务`() {
        val store = open()
        store.put(once("t1"))
        store.put(once("t2"))
        store.remove("t1")
        assertEquals(listOf("t2"), store.loadAll().map { it.id })
        store.close()

        val reopened = open()
        assertEquals(listOf("t2"), reopened.loadAll().map { it.id }, "墓碑重放后仍收敛")
        reopened.close()
    }

    @Test
    fun `从未登记的 id remove 不炸不留痕（跨重开无影响）`() {
        val store = open()
        store.remove("ghost")
        assertTrue(store.loadAll().isEmpty())
        store.close()

        val reopened = open()
        assertTrue(reopened.loadAll().isEmpty(), "幽灵墓碑重放无影响")
        reopened.close()
    }

    @Test
    fun `墓碑跨重开仍在（撤销不被重开抹掉）`() {
        val first = open()
        first.put(once("t1"))
        first.put(once("t2"))
        first.remove("t1")
        first.close()

        val second = open()
        assertEquals(listOf("t2"), second.loadAll().map { it.id }, "撤销后重开，被撤销的不得复活")
        second.close()
    }
}
