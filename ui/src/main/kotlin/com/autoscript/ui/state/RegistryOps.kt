package com.autoscript.ui.state

import com.autoscript.domain.host.HostSummary
import kotlinx.coroutines.CancellationException

/**
 * 镜像源管理页的读/写操作（挂起；**顶层函数而不是 `MainActivity` 的成员**）。
 *
 * 搬出 Activity 的理由与 [loadScriptEnv] 那三个逐字相同：`MainActivity` 的函数数贴着
 * detekt 的 `TooManyFunctions` 线，且这三段本身就是"拿读口算下一份状态"的纯逻辑 ——
 * 放这里能用 `FakeHost` 直接测（`:ui` 的单测门跑 JVM，`MainActivity` 构造不出来）。
 *
 * 三落点与 `reloadNpm`/`loadScriptEnv` 同构，不另立口径：
 * - 读口未接线 → 失败态带原因（**不冒充**「用的就是出厂源」）；
 * - 抛错 → 失败态**保留已读到的那份**；
 * - 成功 → 全量覆盖（宿主读数是权威）。
 *
 * 写操作的失败**不清值**（盘上没变），只进 `opError`；成功则回执 + 现取一次。
 * **不自己先改值**：宿主拒绝时先改会出现"界面显示生效了但 npm 还去老地方"。
 */

/** 现取全局镜像源读数。 */
internal suspend fun loadRegistry(host: HostSummary?, previous: RegistryState): RegistryState = try {
    if (host == null) {
        RegistryState.failed(
            IllegalStateException("宿主摘要未接线（Application 未实现 HostSummary）"),
            previous,
        )
    } else {
        RegistryState.of(host.npmRegistry(), previous)
    }
} catch (e: CancellationException) {
    throw e
} catch (t: Exception) {
    RegistryState.failed(t, previous)
}

/**
 * 保存输入框里的地址。**空输入 = 恢复出厂缺省**（判据在 [RegistryState.validate]，
 * 它对空白回 null = 放行，宿主侧把 null/空白译成「删键」）。
 */
internal suspend fun saveRegistry(host: HostSummary?, state: RegistryState): RegistryState {
    RegistryState.validate(state.draft)?.let { return state.copy(opError = it, opNotice = null) }
    if (host == null) {
        return state.copy(opError = "宿主摘要未接线（Application 未实现 HostSummary）", opNotice = null)
    }
    return try {
        host.setNpmRegistry(state.draft.trim())
        val saved = state.draft.trim()
        loadRegistry(host, state).copy(
            opNotice = if (saved.isEmpty()) "已恢复出厂缺省" else "已保存：下次安装起生效",
        )
    } catch (e: CancellationException) {
        throw e
    } catch (t: Exception) {
        state.copy(opError = t.message ?: t.javaClass.simpleName, opNotice = null)
    }
}

/**
 * 恢复出厂缺省（清空草稿后走同一条保存路径）。
 *
 * 与 [saveRegistry] 共用一条路而不是另写一条：两者在宿主侧是**同一个动作**
 * （`setGlobalRegistry(null)`），分成两条实现就会漂出"按钮说恢复出厂、实际写了空串"。
 */
internal suspend fun resetRegistry(host: HostSummary?, state: RegistryState): RegistryState =
    saveRegistry(host, state.copy(draft = ""))
