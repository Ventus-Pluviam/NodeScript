package com.autoscript.platform.system.datastore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * kv 升版策略（2026-10-11 批 97，外审 M8）。
 *
 * 钉的是三条口径，其中前两条是**反**口径 —— 它们防的正是原来的行为：
 * 1. **不静默丢数据**：找不到 old → new 的路径必须抛，不能「DROP 重建」；
 * 2. **不静默降级**：new < old 抛，不假装能读；
 * 3. 路径是**逐步走**的（1→2→3 要经过两步），漏掉中间一步要报错而不是跳过去。
 *
 * 纯函数所以本机可测：Android 面（[SqliteKvOps]）在本模块只编译不执行（README ops 表）。
 */
class KvSchemaMigrationsTest {

    @Test
    fun `同版本无需迁移`() {
        assertEquals(emptyList<KvSchemaMigrations.Step>(), KvSchemaMigrations.upgradeSteps(1, 1))
    }

    @Test
    fun `无对应迁移时抛 UnknownMigration 不静默重建（外审 M8）`() {
        val e = assertThrows(KvSchemaMigrations.UnknownMigration::class.java) {
            KvSchemaMigrations.upgradeSteps(1, 2)
        }
        assertTrue(e.message!!.contains("1 → 2"), "话术要点名是哪段路径缺：${e.message}")
        assertTrue(
            e.message!!.contains("拒绝静默重建"),
            "话术要点名被拒的是「重建」——那正是本条修掉的行为",
        )
    }

    @Test
    fun `降版抛而不是假装能读`() {
        val e = assertThrows(IllegalStateException::class.java) {
            KvSchemaMigrations.upgradeSteps(2, 1)
        }
        assertInstanceOf(IllegalStateException::class.java, e)
        assertTrue(e.message!!.contains("拒绝降级"), "话术要点名：${e.message}")
    }
}
