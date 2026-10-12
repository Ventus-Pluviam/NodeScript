package com.autoscript.ui.state

import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.scripts.ScriptRuntimeLimits
import com.autoscript.ui.screens.ScriptLimitsDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ScriptLimitsState] / ops 的契约（批 107；§8.1 脚本资源限额）。
 *
 * 形态照 [ScriptEnvStateTest]：嵌套一个只覆盖本用例用到那几条 `HostSummary` 的替身
 * （`FakeHost` 其余成员**大声抛**，故这里必须逐条覆盖用到的，见该类的 KDoc）。
 */
class ScriptLimitsStateTest {

    private val edited = ScriptRuntimeLimits(
        maxHeapMb = 128,
        uvThreadPoolSize = 16,
        bridgeInFlightPerConnection = 16,
        bridgeBytesPerConnectionMb = 8,
        bridgeReadThreadsPerConnection = 4,
    )

    /** 只覆盖限额三条读口的替身；`failRead`/`failWrite` 分别模拟两侧故障。 */
    private class LimitsHost(
        var value: ScriptRuntimeLimits = ScriptRuntimeLimits.DEFAULTS,
        var failRead: Boolean = false,
        var failWrite: Boolean = false,
    ) : FakeHost() {
        var saves = 0

        override suspend fun scriptLimits(): ScriptRuntimeLimits {
            if (failRead) require(false) { "限额表打不开（模拟）" }
            return value
        }

        override suspend fun saveScriptLimits(limits: ScriptRuntimeLimits) {
            if (failWrite) require(false) { "堆上限 不得超过 1024（模拟）" }
            value = limits
            saves++
        }
    }

    @Test
    fun `读到了就把五格草稿填成读到的值`() = runBlocking {
        val host = LimitsHost(value = edited)
        val s = loadScriptLimits(host, ScriptLimitsState.NOT_LOADED)
        assertEquals("128", s.draftHeapMb)
        assertEquals("16", s.draftUvThreads)
        assertEquals("16", s.draftConnInflight)
        assertEquals("8", s.draftConnBytesMb)
        assertEquals("4", s.draftConnThreads)
        assertEquals(edited, s.limits)
    }

    @Test
    fun `读失败保留上次读到的值而不是显示出厂`() = runBlocking {
        val host = LimitsHost(value = edited)
        val loaded = loadScriptLimits(host, ScriptLimitsState.NOT_LOADED)
        host.failRead = true
        val failed = loadScriptLimits(host, loaded)
        assertEquals(edited, failed.limits, "一次瞬时失败不该把用户编好的值显示成出厂")
        assertEquals("128", failed.draftHeapMb)
        assertTrue((failed.load as LoadState.Failed).reason.contains("模拟"))
    }

    @Test
    fun `读口未接线如实失败而不是冒充出厂值`() = runBlocking {
        val s = loadScriptLimits(null, ScriptLimitsState.NOT_LOADED)
        assertTrue(s.load is LoadState.Failed, "出厂值长得像「用户填的」，冒充它就是撒谎")
        assertNull(s.limits)
    }

    @Test
    fun `保存成功后现读一次且草稿与盘上一致`() = runBlocking {
        val host = LimitsHost()
        val loaded = loadScriptLimits(host, ScriptLimitsState.NOT_LOADED)
        val editedState = loaded
            .setDraft(ScriptLimitsDraft.HEAP, "256")
            .setDraft(ScriptLimitsDraft.THREADS, "16")
        val saved = saveScriptLimits(host, editedState)
        assertEquals(1, host.saves)
        assertEquals(256, host.value.maxHeapMb, "宿主表是权威，不累积")
        assertEquals(16, host.value.bridgeReadThreadsPerConnection)
        assertEquals("256", saved.draftHeapMb, "保存后现读，草稿与盘上逐字一致")
        assertTrue((saved.opNotice ?: "").contains("立即生效"))
    }

    @Test
    fun `宿主拒绝时不落盘且保留草稿`() = runBlocking {
        val host = LimitsHost(failWrite = true)
        val loaded = loadScriptLimits(host, ScriptLimitsState.NOT_LOADED)
        val editedState = loaded.setDraft(ScriptLimitsDraft.HEAP, "256")
        val failed = saveScriptLimits(host, editedState)
        assertEquals(0, host.saves)
        assertEquals("256", failed.draftHeapMb, "失败不清草稿（表没变）")
        assertTrue((failed.opError ?: "").contains("模拟"))
        assertEquals(ScriptRuntimeLimits.DEFAULTS.maxHeapMb, host.value.maxHeapMb)
    }

    @Test
    fun `恢复出厂走同一条保存路`() = runBlocking {
        val host = LimitsHost(value = edited)
        val loaded = loadScriptLimits(host, ScriptLimitsState.NOT_LOADED)
        val reset = resetScriptLimits(host, loaded)
        assertEquals(ScriptRuntimeLimits.DEFAULTS.maxHeapMb, host.value.maxHeapMb)
        assertEquals(ScriptRuntimeLimits.DEFAULTS.bridgeReadThreadsPerConnection, host.value.bridgeReadThreadsPerConnection)
        assertEquals(ScriptRuntimeLimits.DEFAULTS.maxHeapMb.toString(), reset.draftHeapMb)
    }

    @Test
    fun `空草稿拒收而不是当 0`() {
        val s = ScriptLimitsState.NOT_LOADED.copy(draftHeapMb = "  ")
        val parsed = ScriptLimitsState.draftToLimits(s)
        assertTrue(parsed.isFailure)
        assertTrue(
            parsed.exceptionOrNull()?.message?.contains("没填") == true,
            "0 在这里是合法形状但**非法值**，报「没填」更接近事实：${parsed.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun `非整数拒收并点名是哪个字段`() {
        val s = loaded().setDraft(ScriptLimitsDraft.INFLIGHT, "abc")
        val parsed = ScriptLimitsState.draftToLimits(s)
        assertTrue(parsed.isFailure)
        assertTrue(parsed.exceptionOrNull()?.message?.contains("每连接在途请求数") == true)
    }

    @Test
    fun `越界由 domain 的唯一一份判据说`() {
        val s = loaded().setDraft(ScriptLimitsDraft.HEAP, "99999")
        val parsed = ScriptLimitsState.draftToLimits(s)
        assertTrue(parsed.isFailure)
        assertTrue(
            parsed.exceptionOrNull()?.message?.contains("不得超过") == true,
            "上限的数与理由只住在 :domain，界面不抄第二份：${parsed.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun `五档预设都是合法值`() {
        CONCURRENCY_PRESETS.forEach { n ->
            val parsed = ScriptLimitsState.draftToLimits(
                loaded().setDraft(ScriptLimitsDraft.THREADS, n.toString()),
            )
            assertTrue(parsed.isSuccess, "预设档 $n 必须能保存：${parsed.exceptionOrNull()?.message}")
        }
    }

    @Test
    fun `改一格草稿清上一次的报错`() {
        val failed = loaded().copy(opError = "上一次保存被拒")
        assertNull(failed.setDraft(ScriptLimitsDraft.HEAP, "128").opError, "报错已不指向当前输入")
        assertEquals("128", failed.setDraft(ScriptLimitsDraft.HEAP, "128").draftHeapMb)
        assertEquals(
            ScriptRuntimeLimits.DEFAULTS.bridgeReadThreadsPerConnection.toString(),
            failed.setDraft(ScriptLimitsDraft.HEAP, "128").draftConnThreads,
            "改一格不动其余四格（出厂的并发档是 8，不是 4）",
        )
    }

    /** 五格填成出厂值再改一格（每条用例只关心一格）。 */
    private fun loaded(): ScriptLimitsState =
        ScriptLimitsState.of(ScriptRuntimeLimits.DEFAULTS, ScriptLimitsState.NOT_LOADED)
}
