package com.autoscript.ui

import com.autoscript.domain.host.CapabilityCenterSnapshot
import com.autoscript.domain.host.ConsoleSnapshot
import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.host.ShellSummary
import com.autoscript.domain.host.TaskCenterSnapshot
import com.autoscript.domain.permission.Capability

/**
 * [HostSummary] 的测试替身：只关心首屏那几条事实的用例，不该被迫实现整张读口。
 *
 * **未覆盖的成员一律响亮失败**（`UnsupportedOperationException`），不回一份空快照 ——
 * 空快照是"读成功但一条都没有"的样子，让用例在误用替身时拿到假绿，正是本仓库反复
 * 吃亏的形态（见 `CapabilityCenterReadTest` 里那条"读取崩溃不吞成假快照"的教训）。
 * 哪天 `HostSummary` 又长出新成员，也只在这一处补，而不是散在四个匿名对象里。
 */
open class FakeHost(
    private val summary: () -> ShellSummary = { ShellSummary(shellReady = false, missedAlarms = 0, keepAliveActive = false) },
) : HostSummary {

    override fun shellSummary(): ShellSummary = summary()

    override suspend fun capabilityCenter(): CapabilityCenterSnapshot =
        throw UnsupportedOperationException("本替身未提供 capabilityCenter（用例按需覆盖）")

    override fun openCapabilitySettings(capability: Capability): Unit =
        throw UnsupportedOperationException("本替身未提供 openCapabilitySettings（用例按需覆盖）")

    override suspend fun taskCenter(): TaskCenterSnapshot =
        throw UnsupportedOperationException("本替身未提供 taskCenter（用例按需覆盖）")

    override suspend fun console(sinceSeq: Long, maxLines: Int): ConsoleSnapshot =
        throw UnsupportedOperationException("本替身未提供 console（用例按需覆盖）")
}
