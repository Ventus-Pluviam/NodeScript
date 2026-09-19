package com.autoscript.appservice.permissioncenter

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityLifecycle
import com.autoscript.domain.permission.CapabilityState
import com.autoscript.domain.permission.GrantResult
import com.autoscript.domain.permission.requireGrantedOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionCenterTest {

    private fun center(
        system: Map<Capability, CapabilityState> = emptyMap(),
        fault: Set<Capability> = emptySet(),
        grant: GrantResult = GrantResult.Granted,
        launched: MutableList<Capability> = mutableListOf(),
        settings: MutableList<Capability> = mutableListOf(),
    ) = PermissionCenter(
        reader = SystemStateReader { ability ->
            if (ability in fault) throw IllegalStateException("ROM 查询崩溃")
            system[ability] ?: CapabilityState.DENIED
        },
        launcher = object : GrantLauncher {
            override suspend fun launchGrant(ability: Capability): GrantResult {
                launched += ability
                return grant
            }
            override fun openSettings(ability: Capability) {
                settings += ability
            }
        },
    )

    @Test
    fun `state 直读系统态`() = runBlocking {
        val c = center(mapOf(Capability.ACCESSIBILITY to CapabilityState.GRANTED))
        assertEquals(CapabilityState.GRANTED, c.state(Capability.ACCESSIBILITY))
        assertEquals(CapabilityState.DENIED, c.state(Capability.OVERLAY))
    }

    @Test
    fun `state 读取崩溃诚实降级 DEGRADED 而非伪造 GRANTED`() = runBlocking {
        val c = center(fault = setOf(Capability.ACCESSIBILITY))
        assertEquals(CapabilityState.DEGRADED, c.state(Capability.ACCESSIBILITY))
    }

    @Test
    fun `ensure DENIED 抛 ERR_PERMISSION_DENIED 且 detail 带引导文案`() = runBlocking {
        val c = center()
        val e = assertThrows(AutojsException::class.java) {
            runBlocking { c.ensure(Capability.ACCESSIBILITY) }
        }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, e.error)
        assertTrue(e.message!!.contains("无障碍"), "detail 须携带引导页文案: ${e.message}")
    }

    @Test
    fun `ensure GRANTED 与 DEGRADED 如实返回`() = runBlocking {
        val c = center(
            mapOf(
                Capability.ACCESSIBILITY to CapabilityState.GRANTED,
                Capability.OVERLAY to CapabilityState.DEGRADED,
            ),
        )
        assertEquals(CapabilityState.GRANTED, c.ensure(Capability.ACCESSIBILITY))
        assertEquals(CapabilityState.DEGRADED, c.ensure(Capability.OVERLAY))
    }

    @Test
    fun `requestGrant 已授权不再打扰`() = runBlocking {
        val launched = mutableListOf<Capability>()
        val c = center(
            mapOf(Capability.ACCESSIBILITY to CapabilityState.GRANTED),
            launched = launched,
        )
        assertSame(GrantResult.Granted, c.requestGrant(Capability.ACCESSIBILITY))
        assertTrue(launched.isEmpty(), "GRANTED 不得再拉起系统页")
    }

    @Test
    fun `requestGrant 拒绝态委托 launcher 并原样返回`() = runBlocking {
        val launched = mutableListOf<Capability>()
        val c = center(grant = GrantResult.Deferred, launched = launched)
        assertSame(GrantResult.Deferred, c.requestGrant(Capability.SCHEDULE_EXACT_ALARM))
        assertEquals(listOf(Capability.SCHEDULE_EXACT_ALARM), launched)
    }

    @Test
    fun `requireGrantedOrThrow 守卫与门禁同口径`() = runBlocking {
        val c = center(mapOf(Capability.ROOT to CapabilityState.DEGRADED))
        assertEquals(CapabilityState.DEGRADED, c.requireGrantedOrThrow(Capability.ROOT, "输入"))
        assertThrows(AutojsException::class.java) {
            runBlocking { c.requireGrantedOrThrow(Capability.OVERLAY, "弹窗") }
        }
    }

    @Test
    fun `guideText 全能力覆盖`() {
        for (ability in Capability.entries) {
            val text = PermissionCenter.guideText(ability)
            assertTrue(text.isNotBlank(), "$ability 缺引导文案")
        }
    }

    @Test
    fun `openSystemSettings 透传 launcher`() {
        val settings = mutableListOf<Capability>()
        val c = center(settings = settings)
        c.openSystemSettings(Capability.NOTIFICATION)
        assertEquals(listOf(Capability.NOTIFICATION), settings)
    }

    @Test
    fun `三态转移矩阵由 CapabilityLifecycle 消费一致`() {
        // DENIED 经 GRANTED 事件回 GRANTED（重新授权唯一路径）
        assertEquals(
            CapabilityState.GRANTED,
            CapabilityLifecycle.onEvent(
                CapabilityState.DENIED,
                com.autoscript.domain.permission.CapabilityEvent.GRANTED,
            ),
        )
    }
}
