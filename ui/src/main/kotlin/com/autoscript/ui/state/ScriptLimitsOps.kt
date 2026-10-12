package com.autoscript.ui.state

import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.scripts.ScriptRuntimeLimits
import kotlinx.coroutines.CancellationException

/**
 * 脚本设置页的读/写操作（挂起；**顶层函数而不是 `MainActivity` 的成员**）。
 *
 * 理由与 [loadScriptEnv] 等逐字同款：`MainActivity` 的函数数贴着 detekt 的
 * `TooManyFunctions` 线；这三段是「拿读口算下一份状态」的纯逻辑，能用 `FakeHost` 直接测
 * （`:ui` 的单测门跑 JVM，`MainActivity` 构造不出来）。
 *
 * 三落点与 `loadScriptEnv` / `loadRegistry` 同构，不另立口径：
 * - 读口未接线 → 失败态带原因（**不冒充**「出厂值」—— 出厂值长得像「用户填的」）；
 * - 抛错 → 失败态**保留上次读到的值**（一次瞬时失败不该把用户编好的设置显示成出厂）；
 * - 成功 → 全量覆盖 + 现读一次（宿主是权威，不累积）。
 *
 * 写操作**不清草稿**（表没变就不清）；成功则回执 + 现读一次。**不自己先改表**：
 * 宿主拒绝时先改会出现「界面上是新值、盘上还是旧值」。
 */
@Suppress("TooGenericExceptionCaught") // 失败面就是「读口抛什么」：限额表打不开是 IOException、壳未装配是 IllegalStateException，两者都要原样带回界面，分类反而会漏掉一种
internal suspend fun loadScriptLimits(host: HostSummary?, previous: ScriptLimitsState): ScriptLimitsState = try {
    if (host == null) {
        ScriptLimitsState.failed(
            IllegalStateException("宿主摘要未接线（Application 未实现 HostSummary）"),
            previous,
        )
    } else {
        ScriptLimitsState.of(host.scriptLimits(), previous)
    }
} catch (e: CancellationException) {
    throw e
} catch (t: Exception) {
    ScriptLimitsState.failed(t, previous)
}

/**
 * 保存五行草稿。
 *
 * 校验在**提交时**跑一次（[ScriptLimitsState.draftToLimits]），不过就只写 `opError` ——
 * 不静默丢弃，也不把不合法的值落进表。
 */
@Suppress("TooGenericExceptionCaught") // 同 [loadScriptLimits]：越界是 IllegalArgumentException、写盘失败是 IOException，都要原文带回
internal suspend fun saveScriptLimits(
    host: HostSummary?,
    state: ScriptLimitsState,
): ScriptLimitsState {
    val parsed = ScriptLimitsState.draftToLimits(state)
    val limits = parsed.getOrElse { return state.copy(opError = it.message, opNotice = null) }
    if (host == null) {
        return state.copy(opError = "宿主摘要未接线（Application 未实现 HostSummary）", opNotice = null)
    }
    return try {
        host.saveScriptLimits(limits)
        loadScriptLimits(host, state).copy(opNotice = "已保存：立即生效（单脚本下次执行起，桥侧下一次请求起）")
    } catch (e: CancellationException) {
        throw e
    } catch (t: Exception) {
        state.copy(opError = t.message ?: t.javaClass.simpleName, opNotice = null)
    }
}

/**
 * 恢复出厂（草稿先回默认，再走同一条保存路 —— **不复用一份「直接落盘」的旁路**）。
 *
 * 为什么绕这一圈：保存那条路上有校验、有宿主确认、有回执，而「直接落盘」的旁路没有 ——
 * 两条写路迟早漂。代价是「恢复出厂」也要过一次校验（出厂值必然合法，故不会红）。
 */
internal suspend fun resetScriptLimits(host: HostSummary?, state: ScriptLimitsState): ScriptLimitsState =
    saveScriptLimits(host, ScriptLimitsState.resetDrafts(state).copy(opNotice = null))

/** 读线程那一行的**预设档**（界面按钮用；不是「允许的全部取值」—— 见各档的取舍注）。 */
internal val CONCURRENCY_PRESETS: List<Int> = listOf(2, 4, 8, 16)

/**
 * 每项的出厂值（界面「恢复出厂」的文案来源；判据唯一一份在 `:domain` 的
 * [ScriptRuntimeLimits.DEFAULTS]，这里只是把它读出来显示，**不另抄一份数**）。
 */
internal fun defaultsOf(): ScriptRuntimeLimits = ScriptRuntimeLimits.DEFAULTS
