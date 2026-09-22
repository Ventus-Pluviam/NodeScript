package com.autoscript.shell

import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.GrantResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 授权拉起验证（§9.5）：能力 → 系统页的去向表 + [GrantResult] 的口径。
 *
 * 走 [GrantPageOpener] 缝而不是真 `Intent`：JVM 上构造不了 `Intent`，
 * 而"哪个能力送去哪个页面"这条判断必须可单测 —— 送错页的表现是
 * 用户按引导点了半天，回到应用发现能力还是没开。
 */
class AndroidGrantLauncherTest {

    /** 记录被打开的页面；[ok] 控制"系统里有没有这个页"。 */
    private class FakeOpener(private val ok: Boolean = true) : GrantPageOpener {
        val opened = mutableListOf<GrantPage>()
        override fun open(page: GrantPage): Boolean {
            opened += page
            return ok
        }
    }

    @Test
    fun `能力到系统页的去向表`() {
        assertEquals(GrantPage.ACCESSIBILITY, AndroidGrantLauncher.pageFor(Capability.ACCESSIBILITY))
        assertEquals(GrantPage.OVERLAY, AndroidGrantLauncher.pageFor(Capability.OVERLAY))
        assertEquals(GrantPage.NOTIFICATIONS, AndroidGrantLauncher.pageFor(Capability.NOTIFICATION))
        assertEquals(GrantPage.NOTIFICATIONS, AndroidGrantLauncher.pageFor(Capability.POST_NOTIFICATIONS))
        assertEquals(GrantPage.EXACT_ALARM, AndroidGrantLauncher.pageFor(Capability.SCHEDULE_EXACT_ALARM))
        // 没有专门授权页的三个：一律送应用详情页，不编造快捷入口。
        assertEquals(GrantPage.APP_DETAILS, AndroidGrantLauncher.pageFor(Capability.SCREEN_CAPTURE))
        assertEquals(GrantPage.APP_DETAILS, AndroidGrantLauncher.pageFor(Capability.ROOT))
        assertEquals(GrantPage.APP_DETAILS, AndroidGrantLauncher.pageFor(Capability.ADB_INPUT))
    }

    @Test
    fun `每个能力都有去向 —— 新增能力时这张表必须跟着长`() {
        val pages = Capability.entries.map { it to AndroidGrantLauncher.pageFor(it) }
        assertEquals(Capability.entries.size, pages.size, "枚举遍历，未覆盖即在此炸")
    }

    @Test
    fun `成功拉起回 Deferred —— 用户切走等回执，不猜他点了什么`() = runBlocking {
        val opener = FakeOpener(ok = true)
        val r = AndroidGrantLauncher(opener).launchGrant(Capability.ACCESSIBILITY)
        assertEquals(GrantResult.Deferred, r)
        assertEquals(listOf(GrantPage.ACCESSIBILITY), opener.opened)
    }

    @Test
    fun `拉不起页面回 Denied —— 如实上报这次申请失败了`() = runBlocking {
        val opener = FakeOpener(ok = false)
        val r = AndroidGrantLauncher(opener).launchGrant(Capability.ROOT)
        assertEquals(GrantResult.Denied, r)
        assertEquals(listOf(GrantPage.APP_DETAILS), opener.opened, "失败也要真的试过那一次跳转")
    }

    @Test
    fun `openSystemSettings 走同一条去向表`() {
        val opener = FakeOpener()
        AndroidGrantLauncher(opener).openSettings(Capability.SCHEDULE_EXACT_ALARM)
        assertEquals(listOf(GrantPage.EXACT_ALARM), opener.opened)
    }

    @Test
    fun `拉起与一键跳转对同一能力落到同一页`() = runBlocking {
        // 能力中心的两个入口（"去授权"和"打开系统设置"）必须同向：
        // 落到不同页的表现是用户在两处被送到两个地方，其中一处永远开不了这个能力。
        for (ability in Capability.entries) {
            val a = FakeOpener()
            val b = FakeOpener()
            AndroidGrantLauncher(a).launchGrant(ability)
            AndroidGrantLauncher(b).openSettings(ability)
            assertEquals(a.opened, b.opened, "$ability 的两个入口去向不一致")
            assertTrue(a.opened.isNotEmpty())
        }
    }
}
