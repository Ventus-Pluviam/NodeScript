package com.autoscript.platform.capabilities.device

import com.autoscript.domain.automation.MediaProjectionSessionOwner
import com.autoscript.domain.engine.EngineId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [OpeningGate] 的占位判据（§9.2）：同一时刻只允许一次开启。
 *
 * 旧实现 `begin` 无条件置位：第二个并发 `open` 不但进了门，还把第一次的归属/腿
 * 记账踩掉、`abandoned` 重置回 false —— 两条腿各开一条 `MediaProjection` 赛跑提交，
 * 后到者把 `current` 槽整个顶替，被顶替那条从此没有任何收口路径找得到它
 * （投影照跑、前台照挂，直到进程死）。本类钉住「拒绝发生在置位之前」。
 */
class OpeningGateTest {

    private val lock = Any()
    private val gate = OpeningGate(lock)

    private fun owner(connectionId: Long) = MediaProjectionSessionOwner(
        engineId = EngineId(0),
        engineRunId = 1L,
        connectionId = connectionId,
    )

    @Test
    fun `开启进行中第二次占位被如实拒绝 且不踩第一次的记账`() {
        assertTrue(gate.tryBegin(owner(1), recording = false))
        assertFalse(gate.tryBegin(owner(2), recording = true), "已有一次开启在进行：第二次必须被拒")

        // 第一次的记账没被第二次踩掉：按腿+按归属的放弃仍认得出第一次。
        assertFalse(gate.abandon(recording = true, owner = owner(2)), "第二次没占上位，放弃它该是 no-op")
        assertTrue(gate.abandon(recording = false, owner = owner(1)), "第一次的腿与归属必须原样在册")
        assertTrue(gate.abandoned())
    }

    @Test
    fun `拒绝后再来一次仍被拒 直到第一次收工`() {
        assertTrue(gate.tryBegin(owner(1), recording = true))
        assertFalse(gate.tryBegin(owner(2), recording = false))
        assertFalse(gate.tryBegin(owner(3), recording = true))

        gate.end()
        assertTrue(gate.tryBegin(owner(4), recording = false), "收工后新一次开启必须能占位")
    }

    @Test
    fun `被放弃的开启收工后 下一次占位带着干净的放弃标记`() {
        assertTrue(gate.tryBegin(owner(1), recording = false))
        assertTrue(gate.abandon(recording = false))
        assertTrue(gate.abandoned())

        gate.end()
        assertTrue(gate.tryBegin(owner(2), recording = false))
        assertFalse(gate.abandoned(), "abandoned 是上一次开启的账，不许遗传给下一次")
    }
}
