package com.autoscript.ui.state

import com.autoscript.domain.host.HostSummary
import kotlinx.coroutines.CancellationException

/**
 * 环境变量页的读/写操作（挂起；**顶层函数而不是 `MainActivity` 的成员**）。
 *
 * 为什么搬出 Activity：一是 `MainActivity` 的函数数贴着 detekt 的 `TooManyFunctions`
 * 线（每加一个操作面就多一个成员），二是这三段本身就是"拿读口算下一份状态"的纯逻辑
 * —— 放这里能用 `FakeHost` 直接测（`:ui` 的单测门跑 JVM，`MainActivity` 构造不出来）。
 * 与 `RegistrationForm` 把解析搬出 Compose 是同一条理由。
 *
 * 三落点与 `reloadNpm`/`reloadTaskLog` 同构，不另立口径：
 * - 读口未接线 → 失败态带原因（**不冒充**「一条都没设过」）；
 * - 抛错 → 失败态**保留已读到的那份**（一次瞬时 IO 失败不该把用户编好的表显示成空表）；
 * - 成功 → 全量覆盖（宿主表是权威，不累积）。
 *
 * 写操作的失败**不清表**（表没变），只进 `opError`；成功则清草稿 + 回执 + 现取一次。
 * **不自己先改表**：宿主拒绝时先改会出现"列表上没了但盘上还在"。
 */

/** 现取脚本环境变量表。 */
internal suspend fun loadScriptEnv(host: HostSummary?, previous: ScriptEnvState): ScriptEnvState = try {
    if (host == null) {
        ScriptEnvState.failed(
            IllegalStateException("宿主摘要未接线（Application 未实现 HostSummary）"),
            previous,
        )
    } else {
        ScriptEnvState.of(host.scriptEnv(), previous)
    }
} catch (e: CancellationException) {
    throw e
} catch (t: Exception) {
    ScriptEnvState.failed(t, previous)
}

/**
 * 提交草稿行（新增/覆盖一条）。
 *
 * 校验在**提交时**跑一次（[ScriptEnvState.validate]），不过就只写 `opError` ——
 * 不静默丢弃，也不把不合法的键落进表。键与值都 trim：表格里顺手打的空格不该变成
 * 变量名的一部分（与 `RegistrationForm` 的"trim 后进 DTO"同款）。
 */
internal suspend fun addScriptEnv(host: HostSummary?, state: ScriptEnvState): ScriptEnvState {
    val key = state.draftKey.trim()
    ScriptEnvState.validate(key)?.let { return state.copy(opError = it, opNotice = null) }
    if (host == null) {
        return state.copy(opError = "宿主摘要未接线（Application 未实现 HostSummary）", opNotice = null)
    }
    return try {
        host.putScriptEnv(key, state.draftValue.trim())
        loadScriptEnv(host, state.copy(draftKey = "", draftValue = ""))
            .copy(opNotice = "已保存：下次脚本执行起生效")
    } catch (e: CancellationException) {
        throw e
    } catch (t: Exception) {
        state.copy(opError = t.message ?: t.javaClass.simpleName, opNotice = null)
    }
}

/** 删除一条。幂等（契约如此），失败只进 `opError`。 */
internal suspend fun removeScriptEnv(host: HostSummary?, state: ScriptEnvState, key: String): ScriptEnvState {
    if (host == null) {
        return state.copy(opError = "宿主摘要未接线（Application 未实现 HostSummary）", opNotice = null)
    }
    return try {
        host.removeScriptEnv(key)
        loadScriptEnv(host, state).copy(opNotice = "已删除：$key")
    } catch (e: CancellationException) {
        throw e
    } catch (t: Exception) {
        state.copy(opError = t.message ?: t.javaClass.simpleName, opNotice = null)
    }
}
