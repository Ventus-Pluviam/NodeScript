package com.autoscript.ui.state

import com.autoscript.domain.host.CapabilityCenterSnapshot
import com.autoscript.domain.host.ConsoleSnapshot
import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.host.ScriptFilesSnapshot
import com.autoscript.domain.host.ShellSummary
import com.autoscript.domain.host.TaskCenterSnapshot
import com.autoscript.domain.host.TaskLogSnapshot
import com.autoscript.domain.host.TaskRegistration
import com.autoscript.domain.npm.InstallHistoryEntry
import com.autoscript.domain.npm.InstallEventBatch
import com.autoscript.domain.npm.InstallHandle
import com.autoscript.domain.npm.NpmCacheReclaimReport
import com.autoscript.domain.npm.NpmMaintenanceAction
import com.autoscript.domain.npm.NpmConsoleHandle
import com.autoscript.domain.npm.NpmConsoleSnapshot
import com.autoscript.domain.npm.NpmPanelSnapshot
import com.autoscript.domain.npm.NpmRegistrySnapshot
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.scripts.ScriptEnvEntry

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

    override suspend fun taskLog(): TaskLogSnapshot =
        throw UnsupportedOperationException("本替身未提供 taskLog（用例按需覆盖）")

    override suspend fun console(sinceSeq: Long, maxLines: Int): ConsoleSnapshot =
        throw UnsupportedOperationException("本替身未提供 console（用例按需覆盖）")

    override suspend fun registerTask(registration: TaskRegistration): String =
        throw UnsupportedOperationException("本替身未提供 registerTask（用例按需覆盖）")

    override suspend fun cancelTask(taskId: String): Unit =
        throw UnsupportedOperationException("本替身未提供 cancelTask（用例按需覆盖）")

    override suspend fun runTaskNow(taskId: String): Unit =
        throw UnsupportedOperationException("本替身未提供 runTaskNow（用例按需覆盖）")

    override suspend fun stopRun(runId: Long): Boolean =
        throw UnsupportedOperationException("本替身未提供 stopRun（用例按需覆盖）")

    override suspend fun scriptFiles(): ScriptFilesSnapshot =
        throw UnsupportedOperationException("本替身未提供 scriptFiles（用例按需覆盖）")

    override suspend fun createEntry(projectId: String, name: String, isFolder: Boolean): Unit =
        throw UnsupportedOperationException("本替身未提供 createEntry（用例按需覆盖）")

    override suspend fun readScriptFile(projectId: String, relPath: String): String =
        throw UnsupportedOperationException("本替身未提供 readScriptFile（用例按需覆盖）")

    override suspend fun saveScriptFile(projectId: String, relPath: String, content: String): Unit =
        throw UnsupportedOperationException("本替身未提供 saveScriptFile（用例按需覆盖）")

    override suspend fun npmSnapshot(): NpmPanelSnapshot =
        throw UnsupportedOperationException("本替身未提供 npmSnapshot（用例按需覆盖）")


    override suspend fun scriptEnv(): List<ScriptEnvEntry> =
        throw UnsupportedOperationException("本替身未提供 scriptEnv（用例按需覆盖）")

    override suspend fun putScriptEnv(key: String, value: String): Unit =
        throw UnsupportedOperationException("本替身未提供 putScriptEnv（用例按需覆盖）")

    override suspend fun removeScriptEnv(key: String): Unit =
        throw UnsupportedOperationException("本替身未提供 removeScriptEnv（用例按需覆盖）")

    override suspend fun npmRegistry(): NpmRegistrySnapshot =
        throw UnsupportedOperationException("本替身未提供 npmRegistry（用例按需覆盖）")

    override suspend fun setNpmRegistry(raw: String?): Unit =
        throw UnsupportedOperationException("本替身未提供 setNpmRegistry（用例按需覆盖）")

    override suspend fun runNpmCommand(projectId: String, line: String): NpmConsoleHandle =
        throw UnsupportedOperationException("本替身未提供 runNpmCommand（用例按需覆盖）")

    override suspend fun consoleOutput(projectId: String, sinceSeq: Long, maxLines: Int): NpmConsoleSnapshot =
        throw UnsupportedOperationException("本替身未提供 consoleOutput（用例按需覆盖）")

    override suspend fun npmHistory(): List<InstallHistoryEntry> =
        throw UnsupportedOperationException("本替身未提供 npmHistory（用例按需覆盖）")

    override suspend fun runNpmMaintenance(
        projectId: String,
        action: NpmMaintenanceAction,
    ): InstallHandle = throw UnsupportedOperationException("本替身未提供 runNpmMaintenance（用例按需覆盖）")

    override suspend fun reclaimNpmCache(): NpmCacheReclaimReport =
        throw UnsupportedOperationException("本替身未提供 reclaimNpmCache（用例按需覆盖）")

    override suspend fun runNpmPanelCommand(projectId: String, line: String): NpmConsoleHandle =
        throw UnsupportedOperationException("本替身未提供 runNpmPanelCommand（用例按需覆盖）")

    override suspend fun npmInstallEvents(projectId: String, sinceSeq: Long, maxBatch: Int): InstallEventBatch =
        throw UnsupportedOperationException("本替身未提供 npmInstallEvents（用例按需覆盖）")
}
