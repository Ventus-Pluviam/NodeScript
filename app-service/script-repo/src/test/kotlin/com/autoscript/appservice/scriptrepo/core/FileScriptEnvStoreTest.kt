package com.autoscript.appservice.scriptrepo.core

import com.autoscript.domain.scripts.ScriptEnvEntry
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [FileScriptEnvStore] 的持久化契约（docs §8.1）：
 * 后写胜 / del 真删 / 重启 replay 收敛 / 半行容忍 / 值经 JSON 编码往返逐字相等。
 *
 * 「半行容忍」与「replay 收敛」是本类唯一值得单独测的两件事 —— 它们是 append-only
 * 存储最容易写错、也最难在真机上查的地方（崩溃截断只在断电时出现）。
 */
class FileScriptEnvStoreTest {

    private fun file(dir: Path): Path = dir.resolve(FileScriptEnvStore.FILE_NAME)

    @Test
    fun `后写胜且按 key 排序`(@TempDir dir: Path) {
        val s = FileScriptEnvStore(dir)
        s.put("B", "2")
        s.put("A", "1")
        s.put("B", "3")
        assertEquals(
            listOf(ScriptEnvEntry("A", "1"), ScriptEnvEntry("B", "3")),
            s.all(),
            "同一 key 后写胜；all() 按 key 升序（排序归读口，呈现层不再排）",
        )
    }

    @Test
    fun `del 真删且幂等`(@TempDir dir: Path) {
        val s = FileScriptEnvStore(dir)
        s.put("A", "1")
        s.remove("A")
        assertTrue(s.all().isEmpty())
        s.remove("A")            // 从未设过（或已删）照样返回，不抛
        assertTrue(s.all().isEmpty())
    }

    @Test
    fun `重启 replay 与内存一致`(@TempDir dir: Path) {
        val first = FileScriptEnvStore(dir)
        first.put("KEEP", "v")
        first.put("GONE", "v")
        first.remove("GONE")
        val second = FileScriptEnvStore(dir)
        assertEquals(listOf(ScriptEnvEntry("KEEP", "v")), second.all())
    }

    @Test
    fun `最后半行被丢弃——崩溃截断不连坐完好行`(@TempDir dir: Path) {
        val s = FileScriptEnvStore(dir)
        s.put("A", "1")
        s.put("B", "2")
        // 手工追加一条没有换行的半行（模拟 append 中途断电）。
        Files.write(
            file(dir),
            """{"op":"put","k":"C","v":"3"}""".toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND,
        )
        val reopened = FileScriptEnvStore(dir)
        assertEquals(
            listOf(ScriptEnvEntry("A", "1"), ScriptEnvEntry("B", "2")),
            reopened.all(),
            "半行（无换行结尾）整条丢弃；前面两行完好",
        )
    }

    @Test
    fun `值经 JSON 编码——等号 空格 中文 引号 换行往返逐字相等`(@TempDir dir: Path) {
        val nasty = "a=b c 中文 \"quoted\" \n第二行\t制表"
        FileScriptEnvStore(dir).put("K", nasty)
        assertEquals(nasty, FileScriptEnvStore(dir).all().single().value)
    }

    @Test
    fun `空串值与没设是两回事`(@TempDir dir: Path) {
        FileScriptEnvStore(dir).put("EMPTY", "")
        val reopened = FileScriptEnvStore(dir)
        assertEquals("", reopened.all().single().value)
        assertEquals(1, reopened.all().size, "空串是一条**存在**的条目，不是缺失")
    }

    @Test
    fun `键名不合法抛原文且不落盘`(@TempDir dir: Path) {
        val s = FileScriptEnvStore(dir)
        val e = assertThrows(IllegalArgumentException::class.java) { s.put("AUTOSCRIPT_HOST_SOCKET", "x") }
        assertTrue("AUTOSCRIPT_HOST_SOCKET" in (e.message ?: ""), "拒收原文必须点名那个键：${e.message}")
        assertTrue(s.all().isEmpty(), "被拒的键不得落表")
    }

    @Test
    fun `损坏行响亮失败而非静默跳过`(@TempDir dir: Path) {
        Files.write(file(dir), "not json\n".toByteArray(StandardCharsets.UTF_8))
        assertThrows(java.io.IOException::class.java) { FileScriptEnvStore(dir) }
    }
}
