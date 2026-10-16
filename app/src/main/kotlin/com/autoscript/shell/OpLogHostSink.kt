package com.autoscript.shell

import com.autoscript.appservice.npm.OpLogSink

/**
 * [OpLogSink] 的**生产**实现：npm 操作事实打**系统日志**。
 *
 * 2026-10-17 用户令：**去掉审计**（依赖管理页那颗「审计」进的那个子页，以及它背后那份
 * 只追加的落盘账 `files/.ns/install-history.jsonl`），这些记录改打系统日志。
 * 「系统日志」在本仓有确切所指 —— 管理面板 → 日志管理页的第一段，即
 * `ConsoleCollector` 里 `runId == 0` 的那些行（"引擎外"）；[HostLog] 正是往那儿写的口，
 * 它同时照写 logcat，所以这一处调用同时满足了「页面里看得见」与「adb logcat 拿得到」。
 *
 * **为什么实现住 `:app` 而不是 `:app-service:npm`**：那条缝的写侧是纯 JVM 模块
 * （碰不到 `android.util.Log`），而 `HostLog` 住这里。装配层把两者接起来 ——
 * 与执行体（[com.autoscript.appservice.npm.HeavyOpExecutor] 等三条缝）同一条分工。
 *
 * **级别按成败分**：成功 `i`、失败 `w`。这不是装饰 —— logcat 与日志页都按级别显示，
 * 而"装失败"是用户在日志里要找的那一类。
 */
internal object OpLogHostSink : OpLogSink {
    /**
     * logcat tag。
     *
     * 与 [HostLog] 的其余调用点一样用一个稳定短名：`npm` —— 用户拿着 logcat 时
     * `adb logcat -s npm` 就是"依赖管理这一路都发生了什么"。
     */
    const val TAG: String = "npm"

    override fun log(op: String, projectId: String, success: Boolean, detail: String?) {
        val text = opLogLine(op, projectId, success, detail)
        if (success) HostLog.i(TAG, text) else HostLog.w(TAG, text)
    }
}

/**
 * 一行的拼法（**纯函数**，与 IO 分开写是为了能 JVM 单测）。
 *
 * 形状：`<op> <作用域> <结局>[：<明细>]`，例如
 * `install 项目 demo 成功：bundle 合入缓存 imported=3 …`。
 *
 * **`projectId` 空串读作「全局」**：全局镜像源那条没有项目维度，写侧如实给空串
 * （见 `InstallCoordinator.setGlobalRegistry` 的 KDoc）。判据只有这一处 ——
 * 写侧不另造哨兵值，去向侧不猜。
 *
 * **明细原样带**：那是"为什么失败"的唯一来处（错误码之外的原文），
 * 截断由 [HostLog] 自己那道界（`HostLogWriter.MAX_TEXT_CHARS`）管，这里不另设一刀。
 */
internal fun opLogLine(op: String, projectId: String, success: Boolean, detail: String?): String =
    buildString {
        append(op)
        append(if (projectId.isEmpty()) " 全局 " else " 项目 $projectId ")
        append(if (success) "成功" else "失败")
        if (!detail.isNullOrBlank()) append("：").append(detail)
    }
