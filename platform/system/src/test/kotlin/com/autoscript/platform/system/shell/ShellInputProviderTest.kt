package com.autoscript.platform.system.shell

import com.autoscript.domain.automation.GestureInput
import com.autoscript.domain.automation.GesturePoint
import com.autoscript.domain.automation.GestureStroke
import com.autoscript.domain.automation.InputChannel
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `root` / `adb` 两条 shell 输入通道的契约测试（§9.3）。
 *
 * 用**假执行缝**（[Recorder]）而不是真进程：本类要钉的是「命令怎么拼、退出码怎么判、
 * 通道怎么映射」，与 IO 语义无关 —— 真进程那半边由 `AndroidShellExecutorTest` 守
 * （那里的替身强度问题另有一课，见其类注释）。
 *
 * **真机未验**：设备道已裁（backlog B3/E3）。这里只钉 JVM 侧能钉的部分，
 * 真机上第一次用应当拿它跟 `auto` 通道的行为对一遍。
 */
class ShellInputProviderTest {

    /** 记账执行缝：记下每条命令与退出码，按 [code] 决定成败。 */
    private class Recorder(private val code: Int = 0, private val stderr: String? = null) {
        val commands = mutableListOf<String>()
        fun asRun(): suspend (String) -> ShellResult = { cmd ->
            commands += cmd
            ShellResult(code = code, stdout = null, stderr = stderr)
        }
    }

    private fun root(rec: Recorder) = ShellInputProvider(InputChannel.ROOT, rec.asRun())
    private fun adb(rec: Recorder) = ShellInputProvider(InputChannel.ADB, rec.asRun())

    private fun tap(x: Int = 10, y: Int = 20) =
        GestureInput(listOf(GestureStroke(listOf(GesturePoint(x, y), GesturePoint(x, y)), durationMillis = 1)))

    // ── 命令拼装 ─────────────────────────────────────────────────────

    @Test
    fun `tap 拼成同点 swipe——shell 面表达按住时长的唯一写法`() = runBlocking {
        val rec = Recorder()
        assertTrue(root(rec).tap(10, 20, 0))
        // durationMillis = 0（普通点击）夹到 1ms：`input swipe` 不接受 0。
        assertEquals(listOf("input swipe 10 20 10 20 1"), rec.commands)
        Unit
    }

    @Test
    fun `长按靠时长表达——同一个坐标、不同的 ms`() = runBlocking {
        val rec = Recorder()
        root(rec).tap(10, 20, 600)
        assertEquals(listOf("input swipe 10 20 10 20 600"), rec.commands)
        Unit
    }

    @Test
    fun `多笔画手势逐条串行，只取首尾两点`() = runBlocking {
        val rec = Recorder()
        val g = GestureInput(
            listOf(
                GestureStroke(listOf(GesturePoint(1, 2), GesturePoint(3, 4), GesturePoint(5, 6)), durationMillis = 300),
                GestureStroke(listOf(GesturePoint(7, 8), GesturePoint(7, 8)), durationMillis = 50),
            ),
        )
        assertTrue(root(rec).dispatchGesture(g))
        // 中间点 (3,4) 被丢掉：`input` 只有 tap/swipe 两个原语，没有轨迹 —— 这是 shell 面的
        // **真实上限**，写在 §9.3 契约里让调用方知道，而不是假装发了轨迹。
        assertEquals(
            listOf("input swipe 1 2 5 6 300", "input tap 7 8"),
            rec.commands,
        )
        Unit
    }

    @Test
    fun `单点笔画走 tap 而不是零长度 swipe`() = runBlocking {
        val rec = Recorder()
        root(rec).dispatchGesture(GestureInput(listOf(GestureStroke(listOf(GesturePoint(9, 9))))))
        assertEquals(listOf("input tap 9 9"), rec.commands)
        Unit
    }

    // ── 通道 → shell 模式（1:1） ─────────────────────────────────────

    @Test
    fun `通道到 shell 模式是一对一，auto 不适用`() {
        assertEquals(ShellMode.ADB, ShellInputProvider.shellModeOf(InputChannel.ADB))
        assertEquals(ShellMode.ROOT, ShellInputProvider.shellModeOf(InputChannel.ROOT))
        assertThrows(IllegalArgumentException::class.java) {
            ShellInputProvider.shellModeOf(InputChannel.AUTO)
        }
        Unit
    }

    @Test
    fun `ROOT 工厂把命令交给 su 那条通道`() = runBlocking {
        val seen = mutableListOf<Pair<String, ShellMode>>()
        val executor = object : ShellExecutor {
            override suspend fun exec(command: String, mode: ShellMode, timeoutMillis: Long): ShellResult {
                seen += command to mode
                return ShellResult(0, null, null)
            }
        }
        assertTrue(ShellInputProvider.root(executor).tap(1, 1, 0))
        assertEquals(listOf("input swipe 1 1 1 1 1" to ShellMode.ROOT), seen)
        Unit
    }

    @Test
    fun `auto 不是本类的通道——构造即拒（它有无障碍原生实现）`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellInputProvider(InputChannel.AUTO) { ShellResult(0, null, null) }
        }
        Unit
    }

    // ── 退出码与失败语义 ─────────────────────────────────────────────

    @Test
    fun `非零退出码抛 ERR_SERVICE_DISABLED——不折成 false`() = runBlocking {
        val rec = Recorder(code = 1, stderr = "injecting event failed")
        val e = assertThrows(AutojsException::class.java) { runBlocking { adb(rec).tap(1, 1, 0) } }
        // false 的语义是「系统拒绝执行这次注入」，「这条通道现在没了」是另一回事 ——
        // 后者调用方该去能力中心而不是重试，所以必须抛。
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED.code, e.error.code)
        assertTrue(e.message!!.contains("adb"), "错误消息要说清是哪条通道：${e.message}")
        assertTrue(e.message!!.contains("exit=1"), "也要带上退出码：${e.message}")
        Unit
    }

    @Test
    fun `执行缝抛错原码透传——不吞不折`() = runBlocking {
        val broken = ShellInputProvider(InputChannel.ADB) {
            throw AutojsException(ErrorCode.ERR_PERMISSION_DENIED, "Shizuku 未安装", null)
        }
        val e = assertThrows(AutojsException::class.java) { runBlocking { broken.tap(1, 1, 0) } }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, e.error.code)
        Unit
    }

    @Test
    fun `手势中途失败即停——不再注入后续笔画`() = runBlocking {
        var calls = 0
        val failSecond = ShellInputProvider(InputChannel.ROOT) {
            calls += 1
            if (calls == 2) ShellResult(1, null, "boom") else ShellResult(0, null, null)
        }
        val g = GestureInput(
            listOf(
                GestureStroke(listOf(GesturePoint(1, 1), GesturePoint(2, 2))),
                GestureStroke(listOf(GesturePoint(3, 3), GesturePoint(4, 4))),
                GestureStroke(listOf(GesturePoint(5, 5), GesturePoint(6, 6))),
            ),
        )
        assertThrows(AutojsException::class.java) { runBlocking { failSecond.dispatchGesture(g) } }
        assertEquals(2, calls, "第二条挂了就不该再发第三条")
        Unit
    }

    // ── 能力位 ───────────────────────────────────────────────────────

    @Test
    fun `canPerformGestures 恒 true——shell 通道没有对应的开关`() {
        // 无障碍问的是服务能力位（系统的一个开关）；shell 问的是进程身份，而那正是
        // 本 provider 被接线的前提。真正的失败以退出码形式出现在动作调用里。
        assertTrue(root(Recorder()).canPerformGestures)
        assertTrue(adb(Recorder()).canPerformGestures)
        Unit
    }
}
