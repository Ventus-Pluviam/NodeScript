package com.autoscript.ui.state

import com.autoscript.domain.host.ShellSummary
import com.autoscript.domain.scripts.ScriptEnvEntry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 环境变量页的用例：断的是"界面会怎么说/怎么做"，不是存储的行为（那在
 * `FileScriptEnvStoreTest`）。
 *
 * 重点四条**不许冒充**：读失败 ≠ 空表、键名不合法 ≠ 静默丢弃、空串值 ≠ 没设、
 * 写失败不清表（宿主拒绝时先改表会出现"列表上没了但盘上还在"）。
 */
class ScriptEnvStateTest {

    /** 只实现环境变量那三条的替身；其余成员继承 [FakeHost] 的响亮失败。 */
    private class EnvHost(
        private val rows: MutableList<ScriptEnvEntry> = mutableListOf(),
        private val failRead: Exception? = null,
        private val failWrite: Exception? = null,
    ) : FakeHost() {
        override suspend fun scriptEnv(): List<ScriptEnvEntry> {
            failRead?.let { throw it }
            return rows.sortedBy { it.key }
        }

        override suspend fun putScriptEnv(key: String, value: String) {
            failWrite?.let { throw it }
            rows.removeAll { it.key == key }
            rows.add(ScriptEnvEntry(key, value))
        }

        override suspend fun removeScriptEnv(key: String) {
            failWrite?.let { throw it }
            rows.removeAll { it.key == key }
        }
    }

    @Test
    fun `首帧与读到的空表分开`() {
        assertFalse(ScriptEnvState.NOT_LOADED.load.isLoaded)
        assertTrue(ScriptEnvState.NOT_LOADED.entries.isEmpty())

        val empty = ScriptEnvState.of(emptyList())
        assertTrue(empty.load.isLoaded, "读到了但为空 ≠ 还没读")
        assertTrue(empty.entries.isEmpty())
    }

    @Test
    fun `读失败保留已读到的表`() {
        val previous = ScriptEnvState.of(listOf(ScriptEnvEntry("A", "1")))
        val failed = ScriptEnvState.failed(IllegalStateException("盘读不出来"), previous)
        assertFalse(failed.load.isLoaded)
        assertEquals("盘读不出来", failed.loadError)
        assertEquals(listOf(ScriptEnvEntry("A", "1")), failed.entries, "一次瞬时失败不该把用户编好的表显示成空表")
    }

    @Test
    fun `读失败经操作面也保留旧表`() = runBlocking {
        val previous = ScriptEnvState.of(listOf(ScriptEnvEntry("A", "1")))
        val next = loadScriptEnv(EnvHost(failRead = java.io.IOException("boom")), previous)
        assertEquals(listOf(ScriptEnvEntry("A", "1")), next.entries)
        assertEquals("boom", next.loadError)
    }

    @Test
    fun `读口未接线时是失败态而不是空表`() = runBlocking {
        val next = loadScriptEnv(null, ScriptEnvState.NOT_LOADED)
        assertFalse(next.load.isLoaded)
        assertTrue(next.entries.isEmpty())
        assertTrue("未接线" in (next.loadError ?: ""), "原因要能读出来：${next.loadError}")
    }

    @Test
    fun `键名不合法只进 opError 不落表`() = runBlocking {
        val host = EnvHost()
        val state = ScriptEnvState.of(emptyList()).copy(draftKey = "AUTOSCRIPT_HOST_SOCKET", draftValue = "x")
        val next = addScriptEnv(host, state)
        assertTrue("AUTOSCRIPT_HOST_SOCKET" in (next.opError ?: ""), "拒收原文要点名：${next.opError}")
        assertTrue(next.entries.isEmpty(), "不合法的键不得落表")
        assertEquals("AUTOSCRIPT_HOST_SOCKET", next.draftKey, "被拒时草稿要留着 —— 清掉等于把用户打的字吞了")
        assertTrue(host.scriptEnv().isEmpty(), "也不得写进宿主")
    }

    @Test
    fun `空变量名有自己的一句话`() = runBlocking {
        val next = addScriptEnv(EnvHost(), ScriptEnvState.of(emptyList()).copy(draftKey = "   ", draftValue = "x"))
        assertEquals("请填变量名", next.opError)
    }

    @Test
    fun `提交成功清草稿 回执 并现取一次`() = runBlocking {
        val host = EnvHost()
        val state = ScriptEnvState.of(emptyList()).copy(draftKey = " TOKEN ", draftValue = " v1 ")
        val next = addScriptEnv(host, state)
        assertNull(next.opError)
        assertTrue("下次脚本执行起生效" in (next.opNotice ?: ""), "回执要写清生效时机：${next.opNotice}")
        assertEquals(listOf(ScriptEnvEntry("TOKEN", "v1")), next.entries, "键与值都 trim 后进表")
        assertEquals("", next.draftKey)
        assertEquals("", next.draftValue)
    }

    @Test
    fun `空串值是合法值——提交后是一条存在的条目`() = runBlocking {
        val host = EnvHost()
        val next = addScriptEnv(host, ScriptEnvState.of(emptyList()).copy(draftKey = "EMPTY", draftValue = ""))
        assertEquals(listOf(ScriptEnvEntry("EMPTY", "")), next.entries)
    }

    @Test
    fun `写失败不清表也不回执`() = runBlocking {
        val host = EnvHost(
            rows = mutableListOf(ScriptEnvEntry("A", "1")),
            failWrite = IllegalStateException("表打不开"),
        )
        val state = ScriptEnvState.of(host.scriptEnv()).copy(draftKey = "B", draftValue = "2")
        val next = addScriptEnv(host, state)
        assertEquals("表打不开", next.opError)
        assertNull(next.opNotice, "失败不得给回执")
        assertEquals(listOf(ScriptEnvEntry("A", "1")), next.entries, "表没变，界面也不许先改")
    }

    @Test
    fun `删除成功回执并现取`() = runBlocking {
        val host = EnvHost(rows = mutableListOf(ScriptEnvEntry("A", "1"), ScriptEnvEntry("B", "2")))
        val next = removeScriptEnv(host, ScriptEnvState.of(host.scriptEnv()), "A")
        assertEquals(listOf(ScriptEnvEntry("B", "2")), next.entries)
        assertTrue("A" in (next.opNotice ?: ""), "回执点名删了哪个：${next.opNotice}")
    }

    @Test
    fun `校验复用 domain 的唯一一份判据`() {
        // 界面放行的写入侧必然也放行：两侧调的是同一个 reject。
        for (k in listOf("TOKEN", "my.token", "HTTP_PROXY")) assertNull(ScriptEnvState.validate(k))
        assertTrue(ScriptEnvState.validate("AUTOSCRIPT_RUN_ID") != null)
        assertTrue(ScriptEnvState.validate("A=B") != null)
    }

    @Test
    fun `ShellSummary 缺省替身仍可用（本用例只碰环境变量面）`() {
        // 顺带钉住 FakeHost 的未覆盖成员响亮失败这条纪律没被本批改坏。
        val host = FakeHost()
        assertFalse(host.shellSummary().shellReady)
        assertEquals(ShellSummary(shellReady = false, missedAlarms = 0, keepAliveActive = false), host.shellSummary())
    }
}
