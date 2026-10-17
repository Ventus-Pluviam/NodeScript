package com.autoscript.domain.permission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 桥面能力掩码与路由策略表（A5，§11）的**领域侧**契约。
 *
 * 这一层钉三件事，都是「错了会静默放行」的方向：
 * 1. **位运算语义**：`covers` 是全覆盖（不是有交集），`NONE` 不被任何非空要求覆盖；
 * 2. **未知位响亮失败**：位序漂移/跨版本读回不得被读成「多了几项授权」；
 * 3. **策略表的失败方向偏「拒」**：未申报命名空间 → 拒，方法级漏写 → 落重档（仍是拒）。
 *
 * 这里**不**验「哪个命名空间要哪张票」的全部细节（那是策略数据，改了要同批改 §11）——
 * 只钉那些一旦反过来就会变成提权/静默全通的判据。
 */
class CapabilityMaskTest {

    @Test
    fun `covers 是全覆盖而非有交集`() {
        val mask = CapabilityMask.of(BridgeCapability.ACCESSIBILITY)
        assertTrue(mask.covers(CapabilityMask.of(BridgeCapability.ACCESSIBILITY)))
        assertTrue(mask.covers(CapabilityMask.NONE), "NONE 恒被覆盖：不需要任何能力的面谁都能过")
        assertFalse(
            mask.covers(CapabilityMask.of(BridgeCapability.ACCESSIBILITY, BridgeCapability.SCREEN_CAPTURE)),
            "要求两项只给一项 = 未覆盖（有交集不算）",
        )
        assertFalse(mask.covers(CapabilityMask.ALL))
    }

    @Test
    fun `NONE 不被任何非空要求覆盖，ALL 覆盖一切已知位`() {
        assertFalse(CapabilityMask.NONE.covers(CapabilityMask.of(BridgeCapability.LOCAL_STORAGE)))
        assertTrue(CapabilityMask.NONE.covers(CapabilityMask.NONE))
        for (c in BridgeCapability.entries) {
            assertTrue(CapabilityMask.ALL.contains(c), "ALL 必须含 $c")
            assertTrue(CapabilityMask.ALL.covers(CapabilityMask.of(c)))
        }
        assertEquals(BridgeCapability.entries.toSet(), CapabilityMask.ALL.toSet(), "ALL 恰是已知位全集")
    }

    @Test
    fun `增删位只影响那一位`() {
        val base = CapabilityMask.of(BridgeCapability.SENSORS)
        val plus = base.plus(BridgeCapability.CLIPBOARD)
        assertTrue(plus.contains(BridgeCapability.CLIPBOARD))
        assertTrue(plus.contains(BridgeCapability.SENSORS))
        assertFalse(plus.minus(BridgeCapability.CLIPBOARD).contains(BridgeCapability.CLIPBOARD))
        assertTrue(plus.minus(BridgeCapability.CLIPBOARD).contains(BridgeCapability.SENSORS))
        assertNotEquals(base, plus)
        assertEquals(CapabilityMask.NONE, CapabilityMask.NONE.plus(CapabilityMask.NONE))
    }

    @Test
    fun `未知位响亮失败——位序漂移不得读成多了几项授权`() {
        assertThrows(IllegalArgumentException::class.java) { CapabilityMask.fromBits(-1L) }
        assertThrows(IllegalArgumentException::class.java) { CapabilityMask.fromBits(1L shl 62) }
        // 已知位图原样往返（fromBits 是宿主策略/持久化读回的口）
        assertEquals(CapabilityMask.ALL, CapabilityMask.fromBits(CapabilityMask.ALL.bits))
        assertEquals(CapabilityMask.NONE, CapabilityMask.fromBits(0L))
    }

    @Test
    fun `审计可读形区分 NONE ALL 与集合`() {
        assertEquals("NONE", CapabilityMask.NONE.toString())
        assertEquals("ALL", CapabilityMask.ALL.toString())
        assertEquals("{SENSORS}", CapabilityMask.of(BridgeCapability.SENSORS).toString())
        assertTrue(CapabilityMask.NONE.isEmpty())
        assertTrue(CapabilityMask.ALL.isNotEmpty())
    }

    @Test
    fun `未申报命名空间按拒处理——新挂的面忘登记不得静默全通`() {
        assertEquals(CapabilityMask.ALL, BridgeCapabilityCatalog.UNKNOWN_NAMESPACE_REQUIRED)
        assertEquals(
            CapabilityMask.ALL,
            BridgeCapabilityCatalog.namespaceRequired("some-new-namespace"),
            "未申报 = 要全量 = 除全量掩码外一律拒（响亮且可诊断）",
        )
        assertEquals(
            CapabilityMask.ALL,
            BridgeCapabilityCatalog.required("some-new-namespace", "anyMethod"),
        )
    }

    @Test
    fun `方法级是整体覆盖不是并集——poolStats 不该被要求控制权`() {
        val poolStats = BridgeCapabilityCatalog.required("engines", "poolStats")
        assertFalse(poolStats.contains(BridgeCapability.CROSS_SCRIPT_CONTROL), "只读池视图不得要求控制位")
        assertTrue(poolStats.contains(BridgeCapability.CROSS_SCRIPT_OBSERVE))
        // status 与 poolStats **不同档**（A5 整改第 1 条）：poolStats 是"池视图"，
        // status 要能看**自己**（`EngineSessionImpl.onExit` 轮询靠它）—— 目录里
        // 若给它观察位，窄掩码脚本连自己的状态都读不到。故 status 目录级 = NONE，
        // "看的是不是别的执行"这个判据下沉到 handler（见 BridgeCapabilityCatalog KDoc）。
        assertEquals(
            CapabilityMask.NONE,
            BridgeCapabilityCatalog.required("engines", "status"),
            "status 目录级必须 NONE：自我轮询不能要求跨脚本位",
        )
        // 未列出的方法落回命名空间级（= 更重档，失败方向仍是拒）
        assertEquals(
            BridgeCapabilityCatalog.namespaceRequired("engines"),
            BridgeCapabilityCatalog.required("engines", "someFutureMethod"),
        )
        assertTrue(BridgeCapabilityCatalog.namespaceRequired("engines").contains(BridgeCapability.CROSS_SCRIPT_CONTROL))
    }

    @Test
    fun `exec 最重：控制 + 排期写入`() {
        val exec = BridgeCapabilityCatalog.required("engines", "exec")
        assertTrue(exec.contains(BridgeCapability.CROSS_SCRIPT_CONTROL))
        assertTrue(exec.contains(BridgeCapability.SCHEDULER_WRITE))
    }

    @Test
    fun `心跳与命名通道不额外要能力位——由连接身份单独约束`() {
        for (m in listOf("heartbeat", "channel", "channelEmit", "channelDrain", "channelClose")) {
            assertEquals(
                CapabilityMask.NONE,
                BridgeCapabilityCatalog.required("engines", m),
                "engines.$m 的能力位要求应为 NONE（归属判据在 handler，不在掩码）",
            )
        }
    }

    @Test
    fun `stop 目录级 NONE——自我停止不得被路由层预拦`() {
        // A5 整改第 1 条的另一半：路由层只看得到"调用方掩码 vs 目录要求"，看不到 runId。
        // 目录若给 stop 写 CROSS_SCRIPT_CONTROL，窄掩码脚本连**自己**都停不掉
        // （`EngineSessionImpl.cancel` 就靠它）。判据下沉到 handler：只在对**别的**执行时
        // 才要 CROSS_SCRIPT_CONTROL。**代价**：engines 命名空间级的重档不再由路由层兜
        // stop 这一条，改由 handler 兜 —— 见 EnginesNamespaceHandlerTest 的目标授权用例。
        assertEquals(
            CapabilityMask.NONE,
            BridgeCapabilityCatalog.required("engines", "stop"),
            "stop 目录级必须 NONE：自我停止不能要求跨脚本控制位",
        )
    }

    @Test
    fun `stdinIdle 目录级 NONE——关自己的写端不要求跨脚本位`() {
        // 与 stop/status 同一条"自我操作不得被路由层预拦"口径：目标 runId 取自认证连接
        // 身份（payload 里根本没有这个字段），触达不到别的执行 —— 目录若给重档，
        // 窄掩码脚本连自己的 stdin 都关不掉。
        assertEquals(
            CapabilityMask.NONE,
            BridgeCapabilityCatalog.required("engines", "stdinIdle"),
            "stdinIdle 目录级必须 NONE：它关的恒是调用方自己那条执行",
        )
    }

    @Test
    fun `console 面不要求设备能力`() {
        assertEquals(CapabilityMask.NONE, BridgeCapabilityCatalog.required("console", "log"))
    }

    @Test
    fun `未申报的 engines 面方法不落 NONE`() {
        // 反向保护：若哪天有人把 METHOD_REQUIRED 写成「缺省 NONE」，这条会红。
        assertNotEquals(
            CapabilityMask.NONE,
            BridgeCapabilityCatalog.required("engines", "unknown-method"),
            "未列出的 engines 方法必须落命名空间级（控制档），不得落 NONE",
        )
    }
}
