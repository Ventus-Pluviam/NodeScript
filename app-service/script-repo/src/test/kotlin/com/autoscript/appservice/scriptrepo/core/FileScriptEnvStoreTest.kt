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
    fun `半行截在多字节字符中间——整条丢弃且不响亮`(@TempDir dir: Path) {
        val s = FileScriptEnvStore(dir)
        s.put("A", "1")
        // 手工追加：JSON 头部 + 一个**腰斩的中文**（半个 UTF-8 序列），且**没有行尾换行**。
        val head = """{"op":"put","k":"C","v":"中"""
        val partial = head.toByteArray(StandardCharsets.UTF_8)
        Files.write(file(dir), partial.copyOf(partial.size - 1), StandardOpenOption.APPEND)

        assertEquals(
            listOf(ScriptEnvEntry("A", "1")),
            FileScriptEnvStore(dir).all(),
            "断在半行（半个 UTF-8 码点）的记录整条丢弃、不连坐前行，也不响亮 —— " +
                "它就是崩溃截断，语义与「最后半行」同一条",
        )
    }

    @Test
    fun `损坏行响亮失败而非静默跳过`(@TempDir dir: Path) {
        Files.write(file(dir), "not json\n".toByteArray(StandardCharsets.UTF_8))
        assertThrows(java.io.IOException::class.java) { FileScriptEnvStore(dir) }
    }

    // ── 压实（外审 B3 的「无压缩」那半，批 106）───────────────────────────────

    /**
     * 小阈值替身（2 KiB）：压实判据走的是**真代码**，测试不去凑 256 KiB。
     *
     * 用 `MAX_LIVE_BYTES` 本值写测试的坏处是「阈值写错」这件事只有当历史恰好涨到
     * 那个数时才暴露 —— 而 40 次改一条键远不到，于是「压实根本没触发」的测试会
     * 假绿。这里把阈值压到两位数，让压实在第一屏用例里就发生。
     */
    private fun store(dir: Path, threshold: Long = 2L * 1024) = FileScriptEnvStore(dir, threshold)

    /**
     * 压实是**每次写后判一次**，不是攒到末尾才做一次 —— 所以「撑过阈值」这件事在循环里
     * 就已经发生过并被收掉了，盘上永远不会停在超阈值的状态。这一点是刻意的：盘占用
     * 的上界因此就是「阈值 + 一次写入」，而不是「阈值一直不被检查直到下次冷启动」。
     * （第一版守卫反过来断言「循环结束后文件仍超阈值」，当场红 —— 那才是压实没生效。）
     */
    @Test
    fun `反复改写后盘上恒在阈值内 收敛结果逐字不变`(@TempDir dir: Path) {
        val s = store(dir)
        // 同一个键反复改 + 一个反复写的键：append-only 下历史必然涨，收敛视图恒定两条。
        repeat(40) { s.put("KEEP", "值".repeat(60) + it) }
        repeat(40) { s.put("GONE", "墓碑前的值") }
        s.remove("GONE")
        val before = s.all()
        assertEquals(listOf("KEEP"), before.map { it.key }, "GONE 已删，收敛视图只剩 KEEP")

        val reopened = store(dir)
        assertEquals(before, reopened.all(), "压实是重写，收敛结果必须逐条相等")
        assertTrue(
            Files.size(file(dir)) <= s.maxLiveBytes(),
            "反复改写 80 次之后盘上仍在阈值内（实测 ${Files.size(file(dir))} B / 阈值 ${s.maxLiveBytes()} B）",
        )
    }

    @Test
    fun `压实不丢最后一次写入——最后一轮改值才是终态`(@TempDir dir: Path) {
        val s = store(dir)
        repeat(40) { s.put("K", "第 $it 次") }
        s.put("K", "收官这一版")
        assertEquals(
            "收官这一版",
            store(dir).all().single().value,
            "压实重写的必须是**收敛视图**（最后状态），不是历史里的某一版",
        )
    }

    @Test
    fun `没超阈值就不动盘——小表走纯追加`(@TempDir dir: Path) {
        val s = store(dir)
        s.put("A", "1")
        val sizeBefore = Files.size(file(dir))
        s.put("B", "2")
        assertTrue(
            Files.size(file(dir)) > sizeBefore,
            "正常形态是**纯追加**：小表不该被改写（整份重写的 fsync 代价高于省下的空间）",
        )
    }

    @Test
    fun `压实后继续写仍被重开读到 临时文件不进账`(@TempDir dir: Path) {
        val s = store(dir)
        repeat(40) { s.put("K", "值".repeat(60) + it) }
        s.put("LATER", "压实之后才写的")
        assertEquals(
            listOf("K", "LATER"),
            store(dir).all().map { it.key },
            "压实之后新写的键得能在重开后读到（tmp 改写没有把账改道到临时文件上）",
        )
    }

    @Test
    fun `值里的换行与引号压实后仍逐字往返`(@TempDir dir: Path) {
        val nasty = "a=b c 中文 \"quoted\" \n第二行\t制表"
        val s = store(dir)
        s.put("N", nasty)
        repeat(40) { s.put("FILL", "撑阈值的行".repeat(20)) }
        assertEquals(nasty, store(dir).all().first { it.key == "N" }.value)
    }

    @Test
    fun `压实把已删键的墓碑行收掉——收敛视图是唯一判据`(@TempDir dir: Path) {
        val s = store(dir)
        s.put("A", "第 0 版")
        repeat(40) { s.put("A", "第 $it 版") }
        s.put("FILL", "撑阈值的行".repeat(20))
        s.remove("A")
        assertEquals(listOf("FILL"), s.all().map { it.key }, "删过的键在收敛视图里没了")
        assertEquals(
            listOf("FILL"),
            store(dir).all().map { it.key },
            "重开也没有 —— 墓碑没被压实误当成一条 put 复活回来",
        )
    }
}
