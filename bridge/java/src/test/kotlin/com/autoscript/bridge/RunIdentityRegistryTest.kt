package com.autoscript.bridge

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.permission.BridgeCapability
import com.autoscript.domain.permission.CapabilityMask
import com.autoscript.domain.permission.ScriptAuthorizationSnapshot
import com.autoscript.domain.permission.TrustTier
import com.autoscript.domain.permission.TrustTierMasks
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RunIdentityRegistryTest {
    /**
     * 本类夹具的项目号。A5 起 `issue` 收的是**上游算好的授权快照**（不再自己按来源算），
     * 而本类钉的是「票据/连接生命周期」，与授权内容无关 —— 固定项目号 + 一个显式快照即可。
     * **掩码/来源怎么来**的断言分别在「授权快照原样随连接下发」与 `:app` 的
     * `AppShellAuthorizationTest`（按项目分级）里钉，不靠这里顺带。
     */
    private val testProject = "test-project"

    /** 生命周期用例的通用快照：全量掩码、无来源档。内容与本类断言无关。 */
    private fun snapshot() = ScriptAuthorizationSnapshot(mask = CapabilityMask.ALL)

    @Test
    fun `hello 早于 spawn 确认则挂起，确认之后绑定且不重复消费`() = runBlocking {
        RunIdentityRegistry().use { registry ->
            val lease = registry.issue(EngineId(3), 42, testProject, snapshot())
            val auth = async(start = CoroutineStart.UNDISPATCHED) {
                registry.authenticate(lease.token, 123, 7, {}, {})
            }
            assertFalse(auth.isCompleted)
            lease.confirmSpawn(123) { true }
            val binding = withTimeout(1_000) { auth.await() }
            assertEquals(42L, binding.caller.engineRunId)
            assertEquals(EngineId(3), binding.caller.engineId)
            assertThrows(AutojsException::class.java) {
                runBlocking { registry.authenticate(lease.token, 123, 8, {}, {}) }
            }
            binding.close()
            assertEquals(0, registry.size())
        }
        Unit
    }

    @Test
    fun `noPid 两边任一不可得仍认证，已知不符以及死执行拒绝`() = runBlocking {
        RunIdentityRegistry().use { registry ->
            for ((pid, peer) in listOf(null to 4, 4 to null, 4 to 4)) {
                val lease = registry.issue(EngineId(0), 1, testProject, snapshot()).also { it.confirmSpawn(pid) { true } }
                registry.authenticate(lease.token, peer, 1, {}, {}).close()
            }
            val mismatch = registry.issue(EngineId(0), 2, testProject, snapshot()).also { it.confirmSpawn(5) { true } }
            assertThrows(AutojsException::class.java) { runBlocking { registry.authenticate(mismatch.token, 6, 2, {}, {}) } }
            val dead = registry.issue(EngineId(0), 3, testProject, snapshot()).also { it.confirmSpawn(null) { false } }
            assertThrows(AutojsException::class.java) { runBlocking { registry.authenticate(dead.token, null, 3, {}, {}) } }
            assertEquals(0, registry.size())
        }
        Unit
    }

    @Test
    fun `期限只约束未认证票据，已绑定连接自然退出只通知一次排空`() = runBlocking {
        val clock = FakeClock()
        RunIdentityRegistry(clock, 50).use { registry ->
            var drains = 0
            var revokes = 0
            val unused = registry.issue(EngineId(0), 1, testProject, snapshot())
            val live = registry.issue(EngineId(0), 2, testProject, snapshot()).also { it.confirmSpawn(null) { true } }
            val bound = registry.authenticate(live.token, null, 2, { drains++ }, { revokes++ })
            clock.now = 50
            registry.expireDue()
            assertEquals(1, registry.size())
            assertThrows(AutojsException::class.java) { runBlocking { registry.authenticate(unused.token, null, 1, {}, {}) } }
            live.naturalExit(); live.naturalExit()
            assertEquals(1, drains)
            assertEquals(0, revokes)
            assertEquals(2L, bound.caller.engineRunId)
            live.revoke(); live.revoke()
            assertEquals(1, revokes)
            assertEquals(0, registry.size())
        }
        Unit
    }

    @Test
    fun `撤销与握手确认竞态不复活，同槽新票据不被旧清理误伤`() = runBlocking {
        RunIdentityRegistry().use { registry ->
            val old = registry.issue(EngineId(0), 1, testProject, snapshot())
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { registry.authenticate(old.token, null, 1, {}, {}) }
            old.revoke()
            old.confirmSpawn(null) { true }
            assertThrows(CancellationException::class.java) { runBlocking { waiting.await() } }
            val fresh = registry.issue(EngineId(0), 2, testProject, snapshot()).also { it.confirmSpawn(null) { true } }
            val bound = registry.authenticate(fresh.token, null, 2, {}, {})
            old.revoke(); old.naturalExit()
            assertEquals(1, registry.size())
            assertEquals(2L, bound.caller.engineRunId)
            bound.close()
        }
        Unit
    }

    @Test
    fun `占票后尚未confirm也受建连期限约束，取消不泄漏票据`() = runBlocking {
        val clock = FakeClock()
        RunIdentityRegistry(clock, 50).use { registry ->
            var revoked = 0
            val lease = registry.issue(EngineId(0), 1, testProject, snapshot())
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                registry.authenticate(lease.token, null, 1, {}, { revoked++ })
            }
            clock.now = 50
            registry.expireDue()
            withTimeout(1_000) { waiting.join() }
            assertTrue(waiting.isCancelled)
            assertEquals(1, revoked)
            assertEquals(0, registry.size())
            lease.confirmSpawn(null) { true }
            assertEquals(0, registry.size(), "迟到 confirm 不复活过期票据")

            val next = registry.issue(EngineId(0), 2, testProject, snapshot())
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                registry.authenticate(next.token, null, 2, {}, {})
            }
            cancelled.cancel()
            withTimeout(1_000) { cancelled.join() }
            assertEquals(0, registry.size())
        }
        Unit
    }

    @Test
    fun `活性探针进行中撤销，探针返回true也不能完成认证`() = runBlocking {
        RunIdentityRegistry().use { registry ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val lease = registry.issue(EngineId(0), 1, testProject, snapshot()).also {
                it.confirmSpawn(123) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    true
                }
            }
            try {
                val result = async(Dispatchers.Default) {
                    runCatching { registry.authenticate(lease.token, 123, 1, {}, {}) }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                lease.revoke()
                release.countDown()
                assertTrue(withTimeout(1_000) { result.await() }.exceptionOrNull() is AutojsException)
                assertEquals(0, registry.size())
            } finally {
                release.countDown()
            }
        }
        Unit
    }

    @Test
    fun `凭据形状随机且不进入诊断，关闭后不签发`() {
        val registry = RunIdentityRegistry()
        try {
            val a = registry.issue(EngineId(0), 1, testProject, snapshot())
            val b = registry.issue(EngineId(0), 2, testProject, snapshot())
            assertTrue(a.token.matches(Regex("[0-9a-f]{64}")))
            assertNotEquals(a.token, b.token)
            assertFalse(a.toString().contains(a.token))
        } finally { registry.close() }
        assertThrows(IllegalStateException::class.java) { registry.issue(EngineId(0), 3, testProject, snapshot()) }
    }

    @Test
    fun `hello 控制帧金样和未知字段拒绝，不占业务 requestId`() {
        assertEquals("{\"t\":\"helloAck\",\"v\":1}\n", BridgeHandshake.ack().toString(Charsets.UTF_8))
        val token = "a".repeat(64)
        assertEquals(token, BridgeHandshake.decodeHello(BridgeHandshake.hello(token)))
        assertThrows(IllegalArgumentException::class.java) {
            BridgeHandshake.decodeHello("""{"t":"hello","v":1,"token":"$token","runId":9}""".toByteArray())
        }
    }

    // ── A5：授权快照由上游算好，签发方只搬运 ──────────────────────────────

    @Test
    fun `无来源元数据的保守档原样下发——本批生产口径`() = runBlocking {
        // 生产链上游给的就是 UNKNOWN_DEFAULT（TrustTier.UNKNOWN 档）；这里钉「它确实到了
        // 连接上」。策略本身（哪档拿哪个掩码）在 :domain 的 TrustTierMasks 测里钉。
        val unknownDefault = TrustTierMasks.UNKNOWN_DEFAULT
        RunIdentityRegistry().use { registry ->
            val lease = registry.issue(
                EngineId(0), 1, "unknown-project",
                ScriptAuthorizationSnapshot(mask = unknownDefault, tierOrdinal = TrustTier.UNKNOWN.ordinal),
            ).also { it.confirmSpawn(null) { true } }
            val bound = registry.authenticate(lease.token, null, 1, {}, {})
            val mask = bound.caller.capabilityMask
            assertEquals(TrustTierMasks.UNKNOWN_DEFAULT, mask, "缺来源 = 最保守档（UNKNOWN），不升级成全量")
            assertFalse(mask.contains(BridgeCapability.CROSS_SCRIPT_CONTROL))
            assertFalse(mask.contains(BridgeCapability.CROSS_SCRIPT_OBSERVE))
            // 批 78：SCHEDULER_WRITE 已从保守档放回 —— 脚本可自建定时任务
            // （`workManager` 是 §14 P0 闭环；跨脚本那一位仍由 authorizeCreate 的
            //  authorizeStart 单独拦着，不靠这一位）。
            assertTrue(mask.contains(BridgeCapability.SCHEDULER_WRITE))
            // 现行口径：其余面（a11y/截图/文件/npm）**保持全量**，本批只收跨脚本那条路。
            assertTrue(mask.contains(BridgeCapability.ACCESSIBILITY))
            assertTrue(mask.contains(BridgeCapability.SCREEN_CAPTURE))
            assertTrue(mask.contains(BridgeCapability.FILESYSTEM_ACCESS))
            bound.close()
        }
        Unit
    }

    @Test
    fun `授权快照原样随连接下发——签发方不重算`() = runBlocking {
        // 关键点：本类**没有**策略/解析器，掩码只能来自 issue 的入参。若签发方偷偷按
        // projectId 重算（比如退回全量），这里就会红 —— 这正是 A5 整改第 4 条要钉的。
        val narrow = CapabilityMask.of(BridgeCapability.LOCAL_STORAGE)
        val passed = ScriptAuthorizationSnapshot(mask = narrow, tierOrdinal = TrustTier.THIRD_PARTY.ordinal)
        RunIdentityRegistry().use { registry ->
            val lease = registry.issue(EngineId(0), 1, "any-project", passed).also { it.confirmSpawn(null) { true } }
            val bound = registry.authenticate(lease.token, null, 1, {}, {})
            assertEquals(narrow, bound.caller.capabilityMask, "下发掩码 = 上游给的那一份，不是重算的")
            assertFalse(bound.caller.capabilityMask.contains(BridgeCapability.ACCESSIBILITY))
            assertEquals(TrustTier.THIRD_PARTY, passed.tierOf())
            bound.close()
        }
        Unit
    }

    @Test
    fun `掩码随票据定死：票据一次性，换连接重放被拒而非换一套掩码`() = runBlocking {
        val granted = CapabilityMask.of(BridgeCapability.SENSORS)
        RunIdentityRegistry().use { registry ->
            val lease = registry.issue(
                EngineId(0), 7, "p", ScriptAuthorizationSnapshot(mask = granted),
            ).also { it.confirmSpawn(null) { true } }
            val first = registry.authenticate(lease.token, null, 1, {}, {})
            assertEquals(granted, first.caller.capabilityMask)
            first.close()
            // 票据一次性：重放必须被拒（不是"换个连接就换一套掩码"）
            assertThrows(AutojsException::class.java) {
                runBlocking { registry.authenticate(lease.token, null, 2, {}, {}) }
            }
        }
        Unit
    }
}
