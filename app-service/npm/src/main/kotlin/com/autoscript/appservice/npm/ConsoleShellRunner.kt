package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.host.ShellConsoleResult
import com.autoscript.domain.npm.NpmConsoleLine
import com.autoscript.domain.npm.NpmConsoleLineKind
import com.autoscript.domain.npm.ShellConsoleMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 控制台 **shell 面**的执行与渲染（2026-10-09 用户口径：控制台要能执行 shell）。
 *
 * 为什么从 [InstallCoordinator] 里拆出来：这条链与依赖树**无关** —— 不建事务、
 * 不占安装会话、不碰项目锁（敲一条 `ls` 不该占住全局安装会话）。而协调器那八百行
 * 讲的全是「怎么把一个依赖树换掉」，把 shell 面塞进去既撑爆 `LargeClass`，
 * 也让读的人以为两者共享编排。本类只做一件事：**把一条命令跑掉、把结果落成控制台行**。
 *
 * 四条纪律（逐条对应用户可见的行为）：
 * - **[ShellConsoleMode.DEFAULT] 一律拒**：默认模式下裸首词是 npm bin，能走到这里的
 *   DEFAULT 只可能来自显式构造。拒绝比替用户猜一条通道安全 —— root 与 Shizuku 是两条
 *   **不同身份**的通道，静默挑一条 = 让「我以为我在用 root」不可分辨（§9.3）。
 *   拒的时候**先落一行 RESULT 再抛**：抛是因为「没跑」与「跑了但非零退出」是两件事
 *   （后者是结果，前者是拒绝），折成同一个 DTO 会让调用方无从分辨；落行是因为
 *   用户敲完 `ls` 之后总得看见「为什么没跑」，而不是一行 ECHO 后面什么都没有。
 * - **非零退出不是异常**：命令跑了、退出了、退成非零 —— 那是**结果**（`code != 0`），
 *   照原样进 RESULT 行（`ok = false`）。把非零退出折成抛异常会让「命令的输出」与
 *   「宿主自己出错了」在界面上长得一样。
 * - **执行体抛错才走异常**（Shizuku 没启动、`su` 不存在…）：原文进 RESULT 行再抛，
 *   用户看到的是「为什么没跑成」，不是一句笼统的失败。
 * - **超时在这里套**（[withTimeoutOrNull]，TTL 契约见 [ShellOpExecutor]）：实现方被要求
 *   合作式响应取消，但真实现是反射调 Shizuku 的 `waitForTimeout` —— 它自己也会超时，
 *   两层并存不冲突（先到的那个说了算）。超时**不是**结果：命令没跑完，把半截输出
 *   渲染成「退出码 N」就是编一个没发生过的退出。
 *
 * 执行体是 [ShellOpExecutor]（缝住 `:domain`，真实现由 `:app` 的 `PlatformWiring` 注入）——
 * 本模块看不到 `:platform:*`（依赖方向铁律）。
 */
internal class ConsoleShellRunner(
    private val executor: ShellOpExecutor,
    private val ring: SeqRing<NpmConsoleLine>,
    private val clock: () -> Long,
) {

    /** 跑一条已解析的 shell 命令，返回结果（同时把 OUTPUT/WARNING/RESULT 三行推进环）。 */
    suspend fun run(
        projectId: String,
        command: String,
        mode: ShellConsoleMode,
        timeoutMillis: Long,
    ): ShellConsoleResult {
        if (mode == ShellConsoleMode.DEFAULT) throw denied(projectId, command)
        val result = execute(projectId, command, mode, timeoutMillis)
        render(projectId, result)
        return result
    }

    /**
     * 交给执行体并处理两条异常路径（超时 / 执行体抛错）。
     *
     * 取消**原样上抛**：`withTimeoutOrNull` 之外还有别人在取消这个协程（界面退出、
     * 宿主收摊），把 CancellationException 吞成一行「失败」会让取消变成"命令跑失败了"
     * —— 那是两回事，而且协程取消必须继续传播。
     */
    private suspend fun execute(
        projectId: String,
        command: String,
        mode: ShellConsoleMode,
        timeoutMillis: Long,
    ): ShellConsoleResult {
        val result = try {
            withTimeoutOrNull(timeoutMillis) { executor.execute(command, mode, timeoutMillis) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failLine(projectId, e)
        }
        return result ?: throw timedOut(command, timeoutMillis)
    }

    private fun render(projectId: String, result: ShellConsoleResult) {
        // stdout / stderr 各一行（null = 该流没产出：不画一行空的冒充"有输出"）。
        result.stdout?.takeIf { it.isNotBlank() }?.let {
            ring.push(projectId, NpmConsoleLine(NpmConsoleLineKind.OUTPUT, it.trimEnd(), clock()))
        }
        result.stderr?.takeIf { it.isNotBlank() }?.let {
            ring.push(projectId, NpmConsoleLine(NpmConsoleLineKind.WARNING, it.trimEnd(), clock()))
        }
        val trunc = if (result.truncated) "（输出超过上限，已截断）" else ""
        resultLine(projectId, "退出码 ${result.code}$trunc", ok = result.isSuccess)
    }

    /** 默认模式的拒绝：落一行 RESULT，再把异常交回给 [run] 抛（判据与话术只此一份）。 */
    private fun denied(projectId: String, command: String): AutojsException {
        val why = "shell 命令需要 root 或 Shizuku：先敲 su 或 shizuku 进入特权模式（或在命令前加其中之一）"
        resultLine(projectId, why, ok = false)
        return AutojsException(ErrorCode.ERR_PERMISSION_DENIED, "$why（命令未执行：$command）")
    }

    /** 执行体抛错：原文进 RESULT 行，再把原异常抛回去（调用方看到的是真病因）。 */
    private fun failLine(projectId: String, e: Exception): Nothing {
        resultLine(projectId, "失败：${e.message}", ok = false)
        throw e
    }

    private fun timedOut(command: String, timeoutMillis: Long): AutojsException =
        AutojsException(ErrorCode.ERR_TIMEOUT, "shell 命令超时（${timeoutMillis}ms）：$command")

    private fun resultLine(projectId: String, text: String, ok: Boolean) =
        ring.push(projectId, NpmConsoleLine(NpmConsoleLineKind.RESULT, text, clock(), ok = ok))
}
