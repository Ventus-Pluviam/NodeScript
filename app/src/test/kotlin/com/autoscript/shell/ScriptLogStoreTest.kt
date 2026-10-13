package com.autoscript.shell

import com.autoscript.bridge.ConsoleLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * 脚本输出日志落盘（2026-10-13 用户令）：index 与 `<runId>.log` 的写侧纪律。
 *
 * 钉的是三条"静默地不对"：宿主行混进执行文件（详情页分不清谁说的）、
 * 写失败掀翻执行（诊断面绝不能比执行面更脆弱）、index 与日志文件分家。
 */
class ScriptLogStoreTest {

    @TempDir
    lateinit var tmp: Path

    private fun store(): ScriptLogStore = ScriptLogStore(tmp.resolve("script-logs"))

    @Test
    fun `begin 写 index 行 append 落对应 run 文件`() {
        val s = store()
        s.begin(7L, "demo", "data/test.js", 1_000L)
        s.appendLine(ConsoleLine(seq = 1L, runId = 7L, level = "log", text = "hi", atMillis = 1_001L))
        val snap = ScriptLogRead.snapshot(tmp, activeRunIds = setOf(7L))
        assertEquals(1, snap.rows.size)
        val row = snap.rows.single()
        assertEquals(7L, row.runId)
        assertEquals("demo", row.projectId)
        assertEquals("data/test.js", row.scriptPath)
        assertEquals(1_000L, row.startedAtMillis)
        assertTrue(row.running, "runId 在在途集里 = Running 中，呈现层画「运行中」不画大小")
        assertTrue(row.sizeBytes > 0L)
        val content = ScriptLogRead.content(tmp, 7L)
        assertEquals(listOf("hi"), content.map { it.text })
        assertEquals(listOf("log"), content.map { it.level })
    }

    @Test
    fun `宿主行 runId0 不落盘`() {
        val s = store()
        s.begin(7L, "demo", "a.js", 1L)
        s.appendLine(ConsoleLine(seq = 1L, runId = 0L, level = "log", text = "宿主侧", atMillis = 1L))
        val content = ScriptLogRead.content(tmp, 7L)
        assertTrue(content.isEmpty(), "runId 0 是引擎外日志，不进执行文件")
    }

    @Test
    fun `index 重复行后写赢 且列表倒序`() {
        val s = store()
        s.begin(7L, "demo", "old.js", 1L)
        s.begin(7L, "demo", "new.js", 2L)
        s.begin(9L, "demo", "other.js", 3L)
        val snap = ScriptLogRead.snapshot(tmp)
        assertEquals(listOf(9L, 7L), snap.rows.map { it.runId }, "最近执行的在前")
        assertEquals("new.js", snap.rows.first { it.runId == 7L }.scriptPath, "同一 runId 后写赢")
    }

    @Test
    fun `坏行与半行不掀翻列表与详情`() {
        val s = store()
        s.begin(7L, "demo", "a.js", 1L)
        s.appendLine(ConsoleLine(seq = 1L, runId = 7L, level = "log", text = "ok", atMillis = 1L))
        val dir = tmp.resolve(".ns").resolve("script-logs")
        java.nio.file.Files.write(
            dir.resolve(ScriptLogStore.INDEX_NAME),
            "半截行\n".toByteArray(),
            java.nio.file.StandardOpenOption.APPEND,
        )
        java.nio.file.Files.write(
            dir.resolve(ScriptLogStore.logName(7L)),
            "{broken\n".toByteArray(),
            java.nio.file.StandardOpenOption.APPEND,
        )
        val snap = ScriptLogRead.snapshot(tmp)
        assertEquals(1, snap.rows.size, "半截 index 行跳过，整份列表照常可读")
        val content = ScriptLogRead.content(tmp, 7L)
        assertEquals(listOf("ok"), content.map { it.text }, "坏日志行跳过")
    }

    @Test
    fun `空目录回空表 不抛`() {
        val snap = ScriptLogRead.snapshot(tmp)
        assertTrue(snap.rows.isEmpty())
    }

    @Test
    fun `缺失日志文件 size 记 0 且 running 为 false`() {
        val s = store()
        s.begin(7L, "demo", "a.js", 1L)
        val snap = ScriptLogRead.snapshot(tmp)
        val row = snap.rows.single()
        assertEquals(0L, row.sizeBytes, "index 先落而行没写（崩溃窗口）= 0，不编一个数")
        assertFalse(row.running)
    }

    @Test
    fun `详情截尾只留后部`() {
        val s = store()
        s.begin(7L, "demo", "a.js", 1L)
        repeat(10) { i ->
            s.appendLine(ConsoleLine(seq = i + 1L, runId = 7L, level = "log", text = "l$i", atMillis = i.toLong()))
        }
        val content = ScriptLogRead.content(tmp, 7L, maxLines = 3)
        assertEquals(listOf("l7", "l8", "l9"), content.map { it.text }, "截尾掐头部、留尾部，时间升序")
    }
}
