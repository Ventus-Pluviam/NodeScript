package com.autoscript.appservice.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 控制台命令历史（2026-10-10 批 90）的落盘行为。
 *
 * 这一份测的是**盘上的形状与容忍度**，不是"界面画得对不对"（那是 `:ui` 的事）：
 * - 按项目分开读（跨项目串台是这类"便利缓存"最容易犯的错）；
 * - 半行 / 截断行不让整份历史读不出来（与 `InstallHistory` 同一条容忍）；
 * - 超限**修剪**（本类与审计史唯一的纪律差别，见类 KDoc）；
 * - 凭据形态的行不记（`looksSecret`）—— 这条最要紧：历史落盘、跨重启还在。
 *
 * 刻意**不**断言"文件一定存在"：读不到就回空表是契约的一部分（[ConsoleHistory.recent]
 * 的 KDoc），把"文件在不在"写进断言会让这条契约变成"必须落过盘"。
 */
class ConsoleHistoryTest {

    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve("console-history.jsonl")

    @Test
    fun `按项目分开读：别的项目敲过的不串台`() {
        val h = ConsoleHistory(dir)
        h.record("p1", "npm ls", 1L)
        h.record("p2", "npm audit", 2L)
        h.record("p1", "npm install axios", 3L)

        assertEquals(listOf("npm install axios", "npm ls"), h.recent("p1"), "最近的在最前")
        assertEquals(listOf("npm audit"), h.recent("p2"))
        assertEquals(emptyList<String>(), h.recent("p3"), "没敲过的项目 = 空表（不是全部历史）")
    }

    @Test
    fun `去重按整行且保留最近那次的位置`() {
        val h = ConsoleHistory(dir)
        h.record("p1", "npm ls", 1L)
        h.record("p1", "npm audit", 2L)
        h.record("p1", "npm ls", 3L)

        assertEquals(listOf("npm ls", "npm audit"), h.recent("p1"), "连敲两次只留最近那次的位置")
    }

    @Test
    fun `去重不跨项目占名额`() {
        val h = ConsoleHistory(dir)
        // p2 的 `npm ls` 排在最后；若先入集合再滤项目，它会把 p1 那条同名的挤掉。
        h.record("p1", "npm ls", 1L)
        h.record("p2", "npm ls", 2L)

        assertEquals(listOf("npm ls"), h.recent("p1", limit = 1), "p1 那一格不该被 p2 的同名行占掉")
    }

    @Test
    fun `空行不记`() {
        val h = ConsoleHistory(dir)
        h.record("p1", "   ", 1L)
        h.record("p1", "", 2L)
        assertFalse(Files.exists(file), "空白行没有信息量，不该落盘")
        assertEquals(emptyList<String>(), h.recent("p1"))
    }

    @Test
    fun `凭据形态的行不记（历史落盘、跨重启还在）`() {
        val h = ConsoleHistory(dir)
        h.record("p1", "npm install --//registry.example.com/:_authToken=sekrit", 1L)
        h.record("p1", "npm config set //r/:_password=hunter2", 2L)
        h.record("p1", "npm install --otp=123456 axios", 3L)
        h.record("p1", "npm ls", 4L)

        assertEquals(listOf("npm ls"), h.recent("p1"))
        val raw = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        assertFalse(raw.contains("sekrit"), "凭据原文一个字都不该落盘：$raw")
        assertFalse(raw.contains("hunter2"), raw)
    }

    @Test
    fun `半行与认不出的行跳过，前面那些照读`() {
        val h = ConsoleHistory(dir)
        h.record("p1", "npm ls", 1L)
        // 追加一条被写了一半的行（进程被杀/断电时的真实形状）+ 一条完全不是 JSON 的。
        Files.write(
            file,
            "{\"project\":\"p1\",\"line\":\"npm ins".toByteArray(StandardCharsets.UTF_8),
            java.nio.file.StandardOpenOption.APPEND,
        )
        Files.write(file, "这不是 JSON\n".toByteArray(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND)

        assertEquals(listOf("npm ls"), h.recent("p1"), "坏行不该让前面几十条一起读不出来")
    }

    @Test
    fun `行里的引号与反斜杠原样回来（转义往返）`() {
        val h = ConsoleHistory(dir)
        val tricky = """npm run build -- --define="a\\b" """
        h.record("p1", tricky, 1L)
        assertEquals(listOf(tricky.trim()), h.recent("p1"))
    }

    @Test
    fun `超过阈值整体修剪，留最近的那些`() {
        val h = ConsoleHistory(dir, maxEntries = 3)
        // 阈值 = maxEntries × 4 = 12；写 20 条互不相同的，触发修剪。
        repeat(20) { h.record("p1", "npm ls --depth=$it", it.toLong()) }

        val raw = Files.readAllLines(file, StandardCharsets.UTF_8)
        assertTrue(raw.size <= 12, "超过阈值必须重写（实测 ${raw.size} 行）")
        assertEquals(
            listOf("npm ls --depth=19", "npm ls --depth=18", "npm ls --depth=17"),
            h.recent("p1"),
            "修剪留下的是**最近**的那些",
        )
    }

    @Test
    fun `读不存在的目录回空表而不是抛`() {
        val h = ConsoleHistory(dir.resolve("never-created"))
        assertEquals(emptyList<String>(), h.recent("p1"), "没有历史可补不是错误")
    }
}
