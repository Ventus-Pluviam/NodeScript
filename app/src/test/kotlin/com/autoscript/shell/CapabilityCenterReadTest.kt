package com.autoscript.shell

import com.autoscript.appservice.permissioncenter.PermissionCenter
import com.autoscript.appservice.permissioncenter.SystemStateReader
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityState
import com.autoscript.appservice.permissioncenter.GrantLauncher
import com.autoscript.domain.permission.GrantResult
import com.autoscript.domain.permission.PermissionFacade
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 能力中心快照拼装（§9.5）：能力中心那一屏的**全部**事实都出自这里，所以逐条钉死。
 *
 * 为什么值得测：这一屏的每一格错了都不会崩 —— 只会让用户按着引导去系统里开了权限、
 * 回来还是显示"被拒绝"（三态没现取），或者干脆看不到某项能力（漏枚举）。
 * 两种都是静默地不对。
 */
class CapabilityCenterReadTest {

    /** 即时三态门禁：`system` 没列的能力回 DENIED（与 `PermissionCenter` 的缺省口径一致）。 */
    private fun facade(
        system: Map<Capability, CapabilityState> = emptyMap(),
        crash: Set<Capability> = emptySet(),
    ): PermissionFacade = PermissionCenter(
        reader = SystemStateReader { ability ->
            if (ability in crash) throw IllegalStateException("ROM 查询崩了")
            system[ability] ?: CapabilityState.DENIED
        },
        launcher = object : GrantLauncher {
            override suspend fun launchGrant(ability: Capability) = GrantResult.Granted
            override fun openSettings(ability: Capability) = Unit
        },
    )

    @Test
    fun `全量枚举能力 —— 不是只列有问题的那些`() = runBlocking {
        val snap = CapabilityCenterRead.snapshot(facade(), emptyList())
        assertEquals(Capability.entries.size, snap.rows.size, "能力中心要能回答「我有哪些能力」")
        assertEquals(Capability.entries.toSet(), snap.rows.map { it.capability }.toSet())
    }

    @Test
    fun `每行都带引导文案 —— 被拒时用户要知道去哪开`() = runBlocking {
        val snap = CapabilityCenterRead.snapshot(facade(), emptyList())
        for (row in snap.rows) {
            assertTrue(row.guide.isNotBlank(), "${row.capability} 没有引导文案")
        }
    }

    @Test
    fun `三态现取不缓存 —— 同一次拼装里逐项各自问系统`() = runBlocking {
        var asked = 0
        val counting = PermissionCenter(
            reader = SystemStateReader { asked++; CapabilityState.GRANTED },
            launcher = object : GrantLauncher {
                override suspend fun launchGrant(ability: Capability) = GrantResult.Granted
                override fun openSettings(ability: Capability) = Unit
            },
        )
        CapabilityCenterRead.snapshot(counting, emptyList())
        assertEquals(
            Capability.entries.size, asked,
            "逐项问一次：缓存三态 = 「用户在系统里授了权、界面还说没授权」的来源",
        )
    }

    @Test
    fun `降级任务账原样带上 —— 与三态分开记账`() = runBlocking {
        val snap = CapabilityCenterRead.snapshot(facade(), listOf("task-b", "task-a"))
        assertEquals(listOf("task-b", "task-a"), snap.degradedAlarmTaskIds, "顺序是调用方的事，本对象不改写")
    }

    @Test
    fun `查询崩了的那一项收敛成 DEGRADED —— 不炸整份快照也不伪造 GRANTED`() = runBlocking {
        // 判据的唯一出处是 `PermissionCenter.state`：它兜住 reader 的异常并收敛 DEGRADED
        //（可用性未知即受限）。本对象**不重复兜**（再兜一层就分不清"ROM 崩了"与"拒绝"），
        // 也不改成 DENIED —— 那会让用户按引导去开一个其实已经开了的权限。
        val snap = CapabilityCenterRead.snapshot(facade(crash = setOf(Capability.ROOT)), emptyList())
        assertEquals(
            CapabilityState.DEGRADED,
            snap.rows.single { it.capability == Capability.ROOT }.state,
        )
        assertEquals(
            Capability.entries.size, snap.rows.size,
            "单项查询崩了不带走整份快照：其余能力照常呈现",
        )
    }

    @Test
    fun `门面自身抛出时穿透上来（呈现层据此如实显示读失败）`() {
        // `PermissionCenter` 只收敛 `Exception`（reader 查询崩），**不**收敛 Error 与
        // 门面自身的抛。那条路要穿到 `:ui` 的 `CapabilityCenterState.failed`：
        // 在那里吞成"全 DENIED"会让用户以为授权全丢了。
        val blowingUp = object : PermissionFacade {
            override suspend fun state(ability: Capability): CapabilityState =
                throw AssertionError("门面装配坏了")
            override suspend fun ensure(ability: Capability) = CapabilityState.GRANTED
            override suspend fun requestGrant(ability: Capability) = GrantResult.Granted
            override fun openSystemSettings(ability: Capability) = Unit
        }
        val e = assertThrows(AssertionError::class.java) {
            runBlocking { CapabilityCenterRead.snapshot(blowingUp, emptyList()) }
        }
        assertTrue(e.message!!.contains("门面"), "原异常原样穿出，本对象不做第二层兜底")
    }
}
