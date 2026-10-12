package com.autoscript.appservice.scriptrepo.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [FileScriptLimitsStore] 的持久化契约（批 107；docs §8.1「脚本资源限额」）。
 *
 * 这份与 [FileScriptEnvStoreTest] 是**不同族**的测试：那份验的是 append-only 账
 * （重放收敛 / 半行容忍），本份验的是**当前值**的三件 —— 覆盖写、缺省回填、形状不对响亮失败。
 * 特别要盯住「**缺省回填而不是整份判废**」：老设备升级后新增字段不该让用户的配置全丢。
 */
class FileScriptLimitsStoreTest {

    private fun file(dir: Path): Path = dir.resolve(FileScriptLimitsStore.FILE_NAME)

    private fun tmpFiles(dir: Path): List<Path> =
        Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".tmp") }.toList() }

    @Test
    fun `从没改过——文件不存在即出厂值而不是抛`(@TempDir dir: Path) {
        assertEquals(
            com.autoscript.domain.scripts.ScriptRuntimeLimits.DEFAULTS,
            FileScriptLimitsStore(dir).load(),
            "「用户从没改过」是合法状态，不是「读不到」——后者才抛",
        )
    }

    @Test
    fun `保存后重开逐字段相等`(@TempDir dir: Path) {
        val saved = com.autoscript.domain.scripts.ScriptRuntimeLimits(
            maxHeapMb = 96,
            uvThreadPoolSize = 16,
            bridgeInFlightPerConnection = 8,
            bridgeBytesPerConnectionMb = 4,
            bridgeReadThreadsPerConnection = 2,
        )
        FileScriptLimitsStore(dir).save(saved)
        assertEquals(saved, FileScriptLimitsStore(dir).load())
    }

    @Test
    fun `覆盖写——第二次保存不留第一次的影子`(@TempDir dir: Path) {
        val store = FileScriptLimitsStore(dir)
        store.save(com.autoscript.domain.scripts.ScriptRuntimeLimits(96, 16, 8, 4, 2))
        store.save(com.autoscript.domain.scripts.ScriptRuntimeLimits(512, 4, 64, 32, 8))
        assertEquals(
            com.autoscript.domain.scripts.ScriptRuntimeLimits(512, 4, 64, 32, 8),
            store.load(),
            "这是「当前值」不是账本：第二次保存后读到的必须逐字是第二份",
        )
    }

    @Test
    fun `字段缺失回填那一项的出厂值——其余字段照旧`(@TempDir dir: Path) {
        // 手写一份「只有两个字段」的盘上文件：模拟老版本留下的形状。
        Files.write(
            file(dir),
            """{"maxHeapMb":96,"uvThreadPoolSize":16}""".toByteArray(StandardCharsets.UTF_8),
        )
        val loaded = FileScriptLimitsStore(dir).load()
        assertEquals(96, loaded.maxHeapMb, "有的字段照盘上的来")
        assertEquals(16, loaded.uvThreadPoolSize)
        assertEquals(
            com.autoscript.domain.scripts.ScriptRuntimeLimits.DEFAULTS.bridgeInFlightPerConnection,
            loaded.bridgeInFlightPerConnection,
            "缺的字段回出厂值，**不是**整份判废 —— 升级后新增字段不该让用户配置全丢",
        )
    }

    @Test
    fun `字段类型不对响亮失败而非猜`(@TempDir dir: Path) {
        // 字符串 "192" 在用户心里与 192 一样，但形状确实不同 —— 配置写坏了就说写坏了。
        Files.write(file(dir), """{"maxHeapMb":"192"}""".toByteArray(StandardCharsets.UTF_8))
        val e = assertThrows(java.io.IOException::class.java) { FileScriptLimitsStore(dir).load() }
        assertTrue("maxHeapMb" in (e.message ?: ""), "拒收原文必须点名是哪个字段：${e.message}")
    }

    @Test
    fun `非整数响亮失败`(@TempDir dir: Path) {
        Files.write(file(dir), """{"maxHeapMb":19.5}""".toByteArray(StandardCharsets.UTF_8))
        assertThrows(java.io.IOException::class.java) { FileScriptLimitsStore(dir).load() }
    }

    @Test
    fun `整个文件不是合法 JSON 响亮失败`(@TempDir dir: Path) {
        Files.write(file(dir), "not json".toByteArray(StandardCharsets.UTF_8))
        assertThrows(java.io.IOException::class.java) { FileScriptLimitsStore(dir).load() }
    }

    @Test
    fun `保存越界值被拒且不落盘`(@TempDir dir: Path) {
        val e = assertThrows(IllegalArgumentException::class.java) {
            FileScriptLimitsStore(dir).save(
                com.autoscript.domain.scripts.ScriptRuntimeLimits(
                    maxHeapMb = 999_999,
                    uvThreadPoolSize = 4,
                    bridgeInFlightPerConnection = 64,
                    bridgeBytesPerConnectionMb = 32,
                    bridgeReadThreadsPerConnection = 8,
                ),
            )
        }
        assertTrue("堆上限" in (e.message ?: ""), "拒收原文要点名字段：${e.message}")
        assertTrue(
            !Files.exists(file(dir)),
            "被拒的值不得落盘（否则下次启动读到的是一个用户从没确认过的数）",
        )
    }

    @Test
    fun `原子覆盖不留临时文件`(@TempDir dir: Path) {
        val store = FileScriptLimitsStore(dir)
        store.save(com.autoscript.domain.scripts.ScriptRuntimeLimits.DEFAULTS)
        store.save(com.autoscript.domain.scripts.ScriptRuntimeLimits(96, 4, 64, 32, 8))
        assertTrue(tmpFiles(dir).isEmpty(), "两次保存后目录里不该有 .tmp 残留：${tmpFiles(dir)}")
    }

    @Test
    fun `盘上文件就是那五项——没有隐藏字段`(@TempDir dir: Path) {
        FileScriptLimitsStore(dir).save(
            com.autoscript.domain.scripts.ScriptRuntimeLimits(96, 16, 8, 4, 2),
        )
        val text = String(Files.readAllBytes(file(dir)), StandardCharsets.UTF_8)
        assertEquals(5, Regex("\"[a-zA-Z]+\":").findAll(text).count(), "五个字段，一个不多：$text")
    }
}