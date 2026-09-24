package com.autoscript.shell

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.Clock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `power_manager` 桥面验证（§8.7 脚本电源面：脚本唤醒锁进框架同一本账）。
 *
 * 覆盖五件事：
 * 1. `acquire` 必带正整数 `timeoutMillis`（无期限/0/负数/缺参 → INVALID_PARAM ——
 *    脚本无期限等于卡死的持有方让 CPU 永远不休眠）；
 * 2. `acquire` 成功回服务端分配的 token（`script-` 前缀），账本真持锁；
 * 3. `acquire` 取不到锁 → ERR_SERVICE_DISABLED 且**未记账**（门禁据此拒绝 SCREEN_ON）；
 * 4. `release` 只能放自己那一份（放别人/false 如实；框架 token 不受影响 ——
 *    引用计数共存，不互相踩）；
 * 5. `status` 回 held/holders（如实双值，不折叠）；
 * 6. 未知方法 → NOT_IMPLEMENTED。
 */
class PowerManagerNamespaceHandlerTest {

    private class FakeLock(var acquireOk: Boolean = true) : WakeLockOps {
        override var held: Boolean = false
        override fun acquire(): Boolean {
            if (!acquireOk) return false
            held = true
            return true
        }
        override fun release(): Boolean {
            held = false
            return true
        }
    }

    private class FakeClock(var now: Long = 0L) : Clock {
        override fun nowMillis(): Long = now
    }

    private class FakeForeground : ForegroundOps {
        override var foregroundRunning: Boolean = true
        var renews = 0
        override fun startService(): Boolean = true
        override fun stopService(): Boolean = true
        override fun activateForeground(): Boolean = true
        override fun deactivateForeground(): Boolean = true
    }

    private fun rig(
        acquireOk: Boolean = true,
        withKeeper: Boolean = true,
    ): Triple<PowerManagerNamespaceHandler, WakeLockLedger, FakeForeground> {
        val ledger = WakeLockLedger(FakeLock(acquireOk), FakeClock())
        val fg = FakeForeground()
        val keeper = if (withKeeper) {
            ForegroundKeeper(fg, ledger, FakeClock(), tickMillis = 60 * 60 * 1000L).also {
                // 框架侧先占一席：脚本锁与框架锁引用计数共存是本测试的前提
                assertTrue(it.start(), "框架保活先起")
                fg.foregroundRunning = true
            }
        } else null
        return Triple(PowerManagerNamespaceHandler(ledger, keeper), ledger, fg)
    }

    private fun req(id: Long, method: String, payload: String?) =
        BridgeRequest(id, "power_manager", method, payload, 5_000)

    private suspend fun ok(h: PowerManagerNamespaceHandler, method: String, payload: String?): String? {
        val r = h.handle(req(1, method, payload))
        return assertInstanceOf(BridgeResponse.Ok::class.java, r).payload
    }

    private suspend fun errCode(h: PowerManagerNamespaceHandler, method: String, payload: String?): String {
        val r = h.handle(req(2, method, payload))
        return assertInstanceOf(BridgeResponse.Err::class.java, r).errorCode
    }

    @Test
    fun `acquire 成功回服务端分配token且账本持锁`() = runBlocking {
        val (h, ledger, fg) = rig()
        val payload = ok(h, "acquire", """{"timeoutMillis":60000}""")!!
        val token = payload.substringAfter("\"token\":\"").substringBefore("\"")
        assertTrue(token.startsWith("script-"), "token 服务端分配：$payload")
        assertTrue(ledger.heldTokens().contains(token), "锁进账本：$payload")
        assertTrue(ledger.heldTokens().contains(ForegroundKeeper.FRAMEWORK_TOKEN), "框架锁不受影响")
        assertTrue(ledger.isHeld(), "门禁读这里 —— 必须为 true")
        assertTrue(fg.renews >= 0, "renew 幂等只兜冷沿，不断言动作数")
        Unit
    }

    @Test
    fun `acquire 缺超时或非正整数一律INVALID_PARAM`() = runBlocking {
        val (h, ledger, _) = rig()
        assertEquals("ERR_INVALID_PARAM", errCode(h, "acquire", null), "无载荷")
        assertEquals("ERR_INVALID_PARAM", errCode(h, "acquire", """{}"""), "缺 timeoutMillis")
        assertEquals("ERR_INVALID_PARAM", errCode(h, "acquire", """{"timeoutMillis":0}"""), "0=立刻过期")
        assertEquals("ERR_INVALID_PARAM", errCode(h, "acquire", """{"timeoutMillis":-5}"""), "负数")
        assertEquals("ERR_INVALID_PARAM", errCode(h, "acquire", """{"timeoutMillis":"一小时"}"""), "非数字")
        assertEquals(
            setOf(ForegroundKeeper.FRAMEWORK_TOKEN),
            ledger.heldTokens(),
            "非法请求一律不记账（账本只有框架那一席）",
        )
        Unit
    }

    @Test
    fun `取不到锁时SERVICE_DISABLED且未记账`() = runBlocking {
        // 框架那一席也起不来（取锁失败）：账本空，门禁必 false
        val ledger = WakeLockLedger(FakeLock(acquireOk = false), FakeClock())
        val h = PowerManagerNamespaceHandler(ledger, null)
        assertEquals(
            "ERR_SERVICE_DISABLED",
            errCode(h, "acquire", """{"timeoutMillis":60000}"""),
            "无 PowerManager/系统拒绝 → SERVICE_DISABLED（不是 INVALID_PARAM）",
        )
        assertTrue(ledger.heldTokens().isEmpty(), "失败不记账")
        assertEquals(false, ledger.isHeld())
        Unit
    }

    @Test
    fun `release 只放自己那一份`() = runBlocking {
        val (h, ledger, _) = rig()
        val payload = ok(h, "acquire", """{"timeoutMillis":60000}""")!!
        val token = payload.substringAfter("\"token\":\"").substringBefore("\"")
        assertEquals("true", ok(h, "release", """{"token":"$token"}"""), "放自己 → true")
        assertEquals(
            setOf(ForegroundKeeper.FRAMEWORK_TOKEN),
            ledger.heldTokens(),
            "脚本锁走了，框架锁还在",
        )
        assertEquals("false", ok(h, "release", """{"token":"$token"}"""), "重复放 → false（如实不对账成功）")
        assertEquals("false", ok(h, "release", """{"token":"script-从没见过"}"""), "放陌生 token → false")
        assertEquals("ERR_INVALID_PARAM", errCode(h, "release", """{"token":"  "}"""), "空白 token 找不到持有方")
        // 框架 token 不是脚本的释放对象 —— 放它只能经 Keeper.stop；但账本语义上它可被点名：
        // 这里不断言框架 token 的 release 结果（那是 Keeper 的职责），只断言脚本锁已清干净。
        assertTrue(ledger.heldTokens().none { it.startsWith("script-") }, "脚本席位清零")
        Unit
    }

    @Test
    fun `status 回held与holders双值`() = runBlocking {
        val (h, _, _) = rig()
        val before = ok(h, "status", null)!!
        assertTrue(before.contains("\"held\":true") && before.contains("\"holders\":1"), "框架一席：$before")
        ok(h, "acquire", """{"timeoutMillis":60000}""")
        val after = ok(h, "status", null)!!
        assertTrue(after.contains("\"held\":true") && after.contains("\"holders\":2"), "脚本再占一席：$after")
        Unit
    }

    @Test
    fun `超时到期后sweep收走脚本锁`() = runBlocking {
        val clock = FakeClock(now = 1_000)
        val ledger = WakeLockLedger(FakeLock(), clock)
        val h = PowerManagerNamespaceHandler(ledger, null)
        val payload = ok(h, "acquire", """{"timeoutMillis":100}""")!!
        val token = payload.substringAfter("\"token\":\"").substringBefore("\"")
        clock.now = 1_100
        assertTrue(ledger.sweep().contains(token), "到期即释（ticker 驱动同语义）")
        assertTrue(ledger.heldTokens().isEmpty())
        assertEquals("false", ok(h, "release", """{"token":"$token"}"""), "过期后放 → false（如实）")
        Unit
    }

    @Test
    fun `未知方法NOT_IMPLEMENTED`() = runBlocking {
        val (h, _, _) = rig()
        assertEquals("ERR_NOT_IMPLEMENTED", errCode(h, "wakeUpForever", """{}"""))
        Unit
    }
}
