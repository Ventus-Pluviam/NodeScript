package com.autoscript.ui.state

import com.autoscript.domain.npm.NpmRegistryKeys
import com.autoscript.domain.npm.NpmRegistrySnapshot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 镜像源页的用例：断的是"界面会怎么说/怎么做"，不是存储的行为（那在
 * `NpmGlobalConfigTest`）。
 *
 * 重点四条**不许冒充**：读失败 ≠ 恢复出厂、地址不合法 ≠ 静默丢弃、
 * **空输入 = 恢复出厂**（不是"设为空串"）、写失败不清值（宿主拒绝时先改会出现
 * "界面显示生效了但 npm 还去老地方"）。
 */
class RegistryStateTest {

    private fun snap(configured: String? = null) = NpmRegistrySnapshot(
        configured = configured,
        defaultRegistry = NpmRegistryKeys.OFFICIAL,
        secondaryRegistry = NpmRegistryKeys.MIRROR,
    )

    /** 只实现镜像源那两条的替身；其余成员继承 [FakeHost] 的响亮失败。 */
    private class RegistryHost(
        private var value: String? = null,
        private val failRead: Exception? = null,
        private val failWrite: Exception? = null,
    ) : FakeHost() {
        override suspend fun npmRegistry(): NpmRegistrySnapshot {
            failRead?.let { throw it }
            return NpmRegistrySnapshot(value, NpmRegistryKeys.OFFICIAL, NpmRegistryKeys.MIRROR)
        }

        override suspend fun setNpmRegistry(raw: String?) {
            failWrite?.let { throw it }
            value = raw?.takeIf { it.isNotBlank() }
        }
    }

    @Test
    fun `首帧与读到的没设过分开`() {
        assertFalse(RegistryState.NOT_LOADED.load.isLoaded)
        assertNull(RegistryState.NOT_LOADED.effective, "没读到时没有「生效值」可言")

        val empty = RegistryState.of(snap())
        assertTrue(empty.load.isLoaded, "读到了但没设过 ≠ 还没读")
        assertEquals(NpmRegistryKeys.OFFICIAL, empty.effective, "没设过时生效的就是出厂缺省")
        assertFalse(empty.customized)
    }

    @Test
    fun `读失败保留已读到的值`() {
        val previous = RegistryState.of(snap("https://registry.npmmirror.com"))
        val failed = RegistryState.failed(IllegalStateException("盘读不出来"), previous)
        assertFalse(failed.load.isLoaded)
        assertEquals("盘读不出来", failed.loadError)
        assertEquals("https://registry.npmmirror.com", failed.effective, "一次瞬时失败不该显示成「恢复出厂了」")
    }

    @Test
    fun `读失败经操作面也保留旧值`() = runBlocking {
        val previous = RegistryState.of(snap("https://registry.npmmirror.com"))
        val next = loadRegistry(RegistryHost(failRead = java.io.IOException("boom")), previous)
        assertEquals("https://registry.npmmirror.com", next.effective)
        assertEquals("boom", next.loadError)
    }

    @Test
    fun `读口未接线时是失败态而不是出厂值`() = runBlocking {
        val next = loadRegistry(null, RegistryState.NOT_LOADED)
        assertFalse(next.load.isLoaded)
        assertNull(next.effective, "未接线不得显示成「你用的就是官方源」")
        assertTrue("未接线" in (next.loadError ?: ""), "原因要能读出来：${next.loadError}")
    }

    @Test
    fun `地址不合法只进 opError 不落值`() = runBlocking {
        val host = RegistryHost(value = "https://registry.npmmirror.com")
        val state = RegistryState.of(snap("https://registry.npmmirror.com")).copy(draft = "http://plain.example.com")
        val next = saveRegistry(host, state)
        assertTrue("http://plain.example.com" in (next.opError ?: ""), "拒收原文要点名输入：${next.opError}")
        assertEquals("https://registry.npmmirror.com", next.effective, "不合法的值不得落下去")
        assertEquals("http://plain.example.com", next.draft, "被拒时草稿要留着 —— 清掉等于把用户打的字吞了")
    }

    @Test
    fun `空输入是恢复出厂而不是设为空串`() = runBlocking {
        val host = RegistryHost(value = "https://registry.npmmirror.com")
        val state = RegistryState.of(snap("https://registry.npmmirror.com")).copy(draft = "   ")
        val next = saveRegistry(host, state)
        assertNull(next.opError)
        assertFalse(next.customized, "清空保存 = 没设过")
        assertEquals(NpmRegistryKeys.OFFICIAL, next.effective)
        assertTrue("恢复出厂" in (next.opNotice ?: ""), "回执要说清是恢复出厂：${next.opNotice}")
    }

    @Test
    fun `恢复出厂按钮走的是同一条保存路径`() = runBlocking {
        val host = RegistryHost(value = "https://registry.npmmirror.com")
        val state = RegistryState.of(snap("https://registry.npmmirror.com"))
        val next = resetRegistry(host, state)
        assertFalse(next.customized)
        assertEquals(NpmRegistryKeys.OFFICIAL, next.effective)
        assertTrue("恢复出厂" in (next.opNotice ?: ""))
    }

    @Test
    fun `保存成功回执并现取`() = runBlocking {
        val host = RegistryHost()
        val state = RegistryState.of(snap()).copy(draft = "  https://harbor.example.com/registry  ")
        val next = saveRegistry(host, state)
        assertNull(next.opError)
        assertTrue("下次安装起生效" in (next.opNotice ?: ""), "回执要写清生效时机：${next.opNotice}")
        assertEquals("https://harbor.example.com/registry", next.effective, "键与值都 trim 后落下去")
        assertEquals("https://harbor.example.com/registry", next.draft, "存完输入框要显示存进去的那个值，不是用户打的带空白原样")
    }

    @Test
    fun `写失败不清值也不回执`() = runBlocking {
        val host = RegistryHost(value = "https://registry.npmmirror.com", failWrite = IllegalStateException("盘只读"))
        val state = RegistryState.of(snap("https://registry.npmmirror.com")).copy(draft = "https://new.example.com")
        val next = saveRegistry(host, state)
        assertEquals("盘只读", next.opError)
        assertNull(next.opNotice, "失败不得给回执")
        assertEquals("https://registry.npmmirror.com", next.effective, "盘上没变，界面也不许先改")
    }

    @Test
    fun `刷新不冲掉用户正在打的半截地址`() = runBlocking {
        val host = RegistryHost(value = "https://registry.npmmirror.com")
        val typing = RegistryState.of(snap("https://registry.npmmirror.com")).copy(draft = "https://half")
        val next = loadRegistry(host, typing)
        assertEquals("https://half", next.draft, "用户正在输入时来一次刷新，把他打的字冲掉是最烦人的那种「帮忙」")
    }

    @Test
    fun `首次读到值会灌进草稿——输入框不该是空的`() {
        val s = RegistryState.of(snap("https://registry.npmmirror.com"))
        assertEquals("https://registry.npmmirror.com", s.draft)
    }

    @Test
    fun `校验复用 domain 的唯一一份判据`() {
        for (k in listOf("https://registry.npmjs.org", "https://harbor.example.com/registry/")) {
            assertNull(RegistryState.validate(k), "合法地址被误拒：$k")
        }
        assertTrue(RegistryState.validate("http://x.example.com") != null)
        assertNull(RegistryState.validate(""), "空 = 恢复出厂，不是错误")
    }
}
