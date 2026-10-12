package com.autoscript.platform.capabilities.screen

import com.autoscript.domain.scripts.ProjectId
import com.autoscript.domain.automation.MediaProjectionOpenException
import com.autoscript.domain.automation.MediaProjectionOpenFailure
import com.autoscript.domain.automation.MediaProjectionSessionOwner
import com.autoscript.domain.automation.MediaProjectionSessionState
import com.autoscript.domain.automation.ScreenConsentBroker
import com.autoscript.domain.automation.ScreenConsentToken
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.scripts.ScriptPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 录屏腿语义层（`MediaProjectionRecorder`，§9.2）—— JVM 可测的那一半。
 *
 * 覆盖本任务点名的四条硬要求里语义层负责的部分：
 * - **三道校验**（id 在场 → 代际一致 → 归属一致），关别人的录屏 `ERR_PERMISSION_DENIED`；
 * - **幂等收口**（第二次回同一次结果，框架收口后仍答得出产物）；
 * - **落点由语义层算**（`ScriptPaths.recordingsDir`），且**开的时候就回 path**；
 * - **连接撤销收干净自己的**（含"正在开的那条"）。
 *
 * 真 `MediaRecorder` / `VirtualDisplay` / API 34+ 的时序**未在本机验证**（无设备）——
 * 那部分在 `AndroidMediaProjectionSessions` 的录屏分支里，靠代码审读 + 真机验证。
 */
class MediaProjectionRecorderTest {

    private val engine = EngineId(0)
    private val alice = MediaProjectionSessionOwner(engine, 42L, 1L)
    private val bob = MediaProjectionSessionOwner(engine, 43L, 2L)

    private val consent = ScreenConsentBroker { object : ScreenConsentToken {} }

    private fun recorder(sessions: FakeRecordingSessions, filesDir: Path) =
        MediaProjectionRecorder(sessions, filesDir)

    private fun errCode(block: () -> Unit): String =
        try {
            block()
            error("预期抛 AutojsException，实际成功返回")
        } catch (e: AutojsException) {
            e.error.code
        }

    // ── 落点 ─────────────────────────────────────────────────────────

    @Test
    fun `落点按项目号算在 recordingsDir 下，且开的时候就把 path 回给调用方`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val handle = recorder(sessions, filesDir).start(consent, alice, ProjectId("demo"))
        val expectedDir = ScriptPaths.recordingsDir(filesDir, ProjectId("demo"))
        assertEquals(expectedDir, Path.of(handle.path).parent, "落点必须是 ScriptPaths 算的那条")
        assertTrue(Files.isDirectory(expectedDir), "目录由语义层建好（设备层只做 prepare）")
        assertTrue(handle.path.endsWith(".mp4"))
        // 设备层拿到的是同一条路径（语义层决定、设备层执行）。
        assertEquals(handle.path, sessions.startCalls.single().second.path)
        Unit
    }

    @Test
    fun `同一毫秒内连开两次不撞名`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val r = recorder(sessions, filesDir)
        val a = r.start(consent, alice, ProjectId("demo"))
        r.stop(a.refId, a.generation, alice)
        val b = r.start(consent, alice, ProjectId("demo"))
        assertNotEquals(a.path, b.path, "撞名会让第二次 prepare 覆盖掉第一个文件")
        Unit
    }

    // ── 三道校验 ─────────────────────────────────────────────────────

    @Test
    fun `未知会话 id → ERR_NOT_FOUND`(@TempDir filesDir: Path) {
        val r = recorder(FakeRecordingSessions(), filesDir)
        assertEquals("ERR_NOT_FOUND", errCode { r.stop(999L, 1L, alice) })
    }

    @Test
    fun `代际不符 → ERR_STALE_HANDLE`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val r = recorder(sessions, filesDir)
        val handle = r.start(consent, alice, ProjectId("demo"))
        assertEquals(
            "ERR_STALE_HANDLE",
            errCode { r.stop(handle.refId, handle.generation + 1, alice) },
        )
        // 跨代那次**没有动手**：会话还活着，主人照常收得掉。
        assertTrue(sessions.current != null)
        assertTrue(r.stop(handle.refId, handle.generation, alice).completed)
        Unit
    }

    @Test
    fun `重开是新一代且 id 不复用 —— 旧句柄两道都不中（迟到收口打不到新会话）`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val r = recorder(sessions, filesDir)
        val first = r.start(consent, alice, ProjectId("demo"))
        r.stop(first.refId, first.generation, alice)
        val second = r.start(consent, alice, ProjectId("demo"))
        assertNotEquals(first.generation, second.generation, "重开必须 +1 代")
        assertNotEquals(first.refId, second.refId, "id 单调递增、绝不复用")
        // 迟到的旧 stop：回**它自己那一份**缓存结果（幂等），**绝不**打到新会话上
        // —— 这正是"收口后仍答得出产物"与"迟到收口打不到新会话"两条一起成立的样子。
        val late = r.stop(first.refId, first.generation, alice)
        assertEquals(first.path, late.path, "迟到 stop 回的是旧会话的产物，不是新会话的")
        assertEquals(MediaProjectionSessionState.ACTIVE, r.recordingState(), "新会话必须还活着")
        assertEquals(0, sessions.current!!.finalizeCalls, "迟到 stop 不能收口新会话")
        Unit
    }

    @Test
    fun `归属不符 → ERR_PERMISSION_DENIED，且主人的会话没被动`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val r = recorder(sessions, filesDir)
        val handle = r.start(consent, alice, ProjectId("demo"))
        assertEquals("ERR_PERMISSION_DENIED", errCode { r.stop(handle.refId, handle.generation, bob) })
        assertTrue(sessions.current != null, "越权那次绝不能动手（关别人的录屏 = 替他断流并决定他的文件收在哪）")
        val outcome = r.stop(handle.refId, handle.generation, alice)
        assertTrue(outcome.completed)
        Unit
    }

    // ── 幂等收口 ─────────────────────────────────────────────────────

    @Test
    fun `stop 幂等：第二次回同一次结果，不重复 finalize`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val r = recorder(sessions, filesDir)
        val handle = r.start(consent, alice, ProjectId("demo"))
        val first = r.stop(handle.refId, handle.generation, alice)
        val second = r.stop(handle.refId, handle.generation, alice)
        assertEquals(first, second)
        assertEquals(1, sessions.current!!.finalizeCalls, "设备层收口必须恰好一次")
        Unit
    }

    @Test
    fun `框架收口（熄屏裁剪）之后脚本仍问得到产物`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val r = recorder(sessions, filesDir)
        val handle = r.start(consent, alice, ProjectId("demo"))
        r.closeCurrent()
        // 这是录屏腿与取帧腿**刻意不同**的一条：帧死了就是死了，而文件是产物。
        val outcome = r.stop(handle.refId, handle.generation, alice)
        assertEquals(handle.path, outcome.path)
        assertTrue(outcome.completed)
        Unit
    }

    @Test
    fun `设备层报 stop 失败时如实 completed=false + detail，不伪装成功`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val r = recorder(sessions, filesDir)
        val handle = r.start(consent, alice, ProjectId("demo"))
        sessions.current!!.failOnStop = "RuntimeException: stop failed"
        val outcome = r.stop(handle.refId, handle.generation, alice)
        assertFalse(outcome.completed, "一帧都没录到就是坏 mp4，不能回成功")
        assertEquals("RuntimeException: stop failed", outcome.detail)
        assertEquals(0L, outcome.sizeBytes)
        Unit
    }

    // ── 连接撤销 ─────────────────────────────────────────────────────

    @Test
    fun `releaseConnection 只收自己的，别人的录屏不动`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val r = recorder(sessions, filesDir)
        val handle = r.start(consent, alice, ProjectId("demo"))
        assertFalse(r.releaseConnection(bob), "别的归属名下没有资源 → 无害 no-op")
        assertEquals(MediaProjectionSessionState.ACTIVE, r.recordingState())
        assertTrue(r.releaseConnection(alice))
        assertEquals(MediaProjectionSessionState.IDLE, r.recordingState())
        // 被撤销之后脚本仍问得到产物（文件已 finalize）。
        assertTrue(r.stop(handle.refId, handle.generation, alice).completed)
        Unit
    }

    @Test
    fun `撤销落在开启挂起里 —— 已建的那条被收口并 finalize，句柄不登记`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions().apply { gateOpen = true }
        val r = recorder(sessions, filesDir)
        val job = async(Dispatchers.Default) { runCatching { r.start(consent, alice, ProjectId("demo")) } }
        while (sessions.pendingOpen == null) Thread.sleep(1)
        val pending = sessions.pendingOpen!!
        r.releaseConnection(alice)
        sessions.finishOpen()
        val failure = job.await()
        assertTrue(failure.exceptionOrNull() is CancellationException, "开启中被收口 → 取消，不是普通失败")
        assertEquals(1, pending.finalizeCalls, "已建的那条必须 finalize（否则留下一个没写完的 mp4）")
        assertEquals(MediaProjectionSessionState.IDLE, r.recordingState())
        Unit
    }

    // ── 并发门 ───────────────────────────────────────────────────────

    @Test
    fun `已有未收口的录屏会话在跑 —— 再开一条如实 CAPTURE_DENIED（不替调用方收掉旧的）`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val r = recorder(sessions, filesDir)
        r.start(consent, alice, ProjectId("demo"))
        assertEquals(
            "ERR_CAPTURE_DENIED",
            errCode { runBlocking { r.start(consent, alice, ProjectId("demo")) } },
        )
        Unit
    }

    @Test
    fun `设备层开失败（用户取消）—— 分类异常折成 AutojsException`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions().apply {
            failWith = MediaProjectionOpenException(MediaProjectionOpenFailure.DENIED, "用户取消了投屏授权")
        }
        val r = recorder(sessions, filesDir)
        assertEquals("ERR_CAPTURE_DENIED", errCode { runBlocking { r.start(consent, alice, ProjectId("demo")) } })
        Unit
    }

    @Test
    fun `无录屏会话时 recordingState 是 IDLE（不拿别的会话冒充）`(@TempDir filesDir: Path) {
        assertEquals(MediaProjectionSessionState.IDLE, recorder(FakeRecordingSessions(), filesDir).recordingState())
    }
}
