package com.autoscript.shell

import com.autoscript.bridge.ConsoleLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 系统日志落盘/回读（2026-10-17 用户令「系统日志不要每次进 app 都清除」）。
 *
 * 全部用 [TempDir]（禁写死宿主路径 —— 写死的路径在 CI 上不是 root，本机会真建出宿主目录）。
 */
class HostLogStoreTest {

    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve(".ns").resolve(HostLogStore.FILE_NAME)
    private fun store() = HostLogStore(file)

    private fun line(text: String, level: String = "info", at: Long = 7L, runId: Long = 0L) =
        ConsoleLine(seq = 5L, runId = runId, level = level, text = text, atMillis = at)

    @Test
    fun `落盘再读回：三字段逐字保住（时刻也保住）`() {
        store().append(line("装配启动：壳创建中", level = "info", at = 111L))
        store().append(line("npm 执行体未接线", level = "warn", at = 222L))
        val back = store().load()
        assertEquals(listOf("装配启动：壳创建中", "npm 执行体未接线"), back.map { it.text })
        assertEquals(listOf("info", "warn"), back.map { it.level })
        assertEquals(listOf(111L, 222L), back.map { it.atMillis }, "时刻要原样回（日志页显示的是它）")
        assertTrue(back.all { it.runId == 0L }, "这一族恒为宿主行")
    }

    @Test
    fun `脚本行不落这份盘（runId 非 0 直接回）`() {
        store().append(line("脚本自己打的", runId = 42L))
        assertFalse(Files.exists(file), "一行都不该写出去（连文件都不建）")
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `文本里的引号换行反斜杠不走坏 JSON`() {
        val thorny = "他说\"你好\"\\ 然后\n换了一行"
        store().append(line(thorny))
        assertEquals(listOf(thorny), store().load().map { it.text }, "转义由 DomainJson 管，回读要一比一")
    }

    @Test
    fun `半行与坏行跳过，不拖垮整份日志`() {
        store().append(line("好行甲", at = 1L))
        store().append(line("好行乙", at = 2L))
        // 模拟"写到一半崩了"：直接往文件尾追加半行 + 一行不像 JSON 的东西。
        Files.write(
            file,
            "{\"at\":3,\"level\":\"in".toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND,
        )
        Files.write(file, "\n这不是 JSON\n".toByteArray(StandardCharsets.UTF_8), StandardOpenOption.APPEND)
        store().append(line("好行丙", at = 4L))

        assertEquals(
            listOf("好行甲", "好行乙", "好行丙"),
            store().load().map { it.text },
            "半行/坏行只丢它自己 —— 与 ConsoleHistory 同一条容忍",
        )
    }

    @Test
    fun `文件不存在时回空表（首启不是错）`() {
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `回读只取尾部一段：更早的行仍在文件里，只是不参与回放`() {
        // UTF-8 下一个汉字 3 字节：半个上限的字数 ⇒ 落盘 1.5 MB > 回放上限。
        val big = "长".repeat(HostLogStore.MAX_REPLAY_BYTES.toInt() / 2)
        store().append(line("很远以前的一行", at = 1L))
        store().append(line(big, at = 2L))
        store().append(line("最近的一行", at = 3L))

        val back = store().load()
        assertTrue(
            back.size < 3,
            "回放读的是尾部 ${HostLogStore.MAX_REPLAY_BYTES} 字节（环只装 2000 行），实读 ${back.size} 行",
        )
        assertEquals("最近的一行", back.last().text, "最新那行必须在（用户重开要看的是它）")
        assertEquals(
            listOf("最近的一行"),
            back.map { it.text },
            "截断点落在那一大行中间 ⇒ 那半行解析失败被跳过（不写\"跳过首行\"的特判），" +
                "更早的\"很远以前的一行\"整行落在窗口之外，自然不参与回放",
        )
        assertTrue(
            Files.readAllBytes(file).size > HostLogStore.MAX_REPLAY_BYTES,
            "文件里那批一个字都没少 —— 少的是回放，不是落盘",
        )
    }
}
