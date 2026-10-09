package com.autoscript.ui.state

import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.npm.NpmConsoleCommand
import com.autoscript.domain.npm.NpmConsoleKeys
import com.autoscript.domain.npm.ShellConsoleMode
import kotlinx.coroutines.CancellationException

/**
 * 控制台（命令面）的读/执行操作（挂起；**顶层函数而不是 `MainActivity` 的成员**）。
 *
 * 搬出 Activity 的理由与 [loadRegistry] 那三个逐字相同：`MainActivity` 的函数数贴着
 * detekt 的 `TooManyFunctions` 线，且这三段本身就是"拿读口算下一份状态"的纯逻辑 ——
 * 放这里能用 `FakeHost` 直接测（`:ui` 的单测门跑 JVM，`MainActivity` 构造不出来）。
 *
 * 三落点与 `reloadNpm`/`loadRegistry` 同构，不另立口径：
 * - 读口未接线 → 失败态带原因（**不冒充**「没有输出」）；
 * - 抛错 → 失败态**保留已读到的行与游标**；
 * - 成功 → 累积入列（宿主读数是权威）。
 *
 * **执行**那条多一条纪律（本页特有）：命令行**先在界面侧判一遍**（判据与宿主侧同一份，
 * 见 `NpmConsoleKeys`）—— 敲错的东西当场被告知，不往返一趟才拿到同一句话。
 * 判过了才发；发了之后**无论成败都再拉一次**：宿主是**先落 ECHO 行再抛**的
 * （`runConsoleCommand` 的三段式），不拉一次用户就只看到一句错误、看不到自己敲的那行。
 */

/** 单批拉取上限（与 `MainActivity.CONSOLE_PAGE` 同值：够一屏翻阅，拉满即提示续拉）。 */
internal const val CONSOLE_CMD_PAGE = 256

/**
 * 现取一轮：先问项目清单（决定在哪个项目上敲），再拉该项目的输出。
 *
 * 为什么项目清单走 `npmSnapshot()` 而不是另开一个"项目列表"读口：依赖面板读的就是
 * 这份全量快照（§10.9.1），控制台要的只是它的 `projects` 那一段 —— 为一行项目号
 * 再开一个读口，两个口的结果可以互相矛盾（且要各现取一次）。
 *
 * **换项目清行 + 游标归 0** 在 [ConsoleCmdState.withProject] 里（见那条的 KDoc：
 * seq 是环内全局单调的，归 0 才是"把这个项目还留在环里的行全取回来"）。
 */
internal suspend fun loadConsoleCmd(host: HostSummary?, previous: ConsoleCmdState): ConsoleCmdState {
    if (host == null) {
        return ConsoleCmdState.failed(
            IllegalStateException("宿主摘要未接线（Application 未实现 HostSummary）"),
            previous,
        )
    }
    return try {
        val panel = host.npmSnapshot()
        val projectIds = panel.projects.map { it.projectId }
        // 已有选择且它还在就保持（刷新不该把用户正在看的项目换掉）；没了就落到第一个。
        val projectId = previous.selectedProjectId
            ?.takeIf { id -> panel.projects.any { it.projectId == id } }
            ?: panel.projects.firstOrNull()?.projectId
        if (projectId == null) {
            // 一个项目都没有：读到了（不是失败），但控制台无处可敲 —— 清干净，
            // 别把上一个项目的行留在屏幕上冒充"这个项目的输出"。
            return previous.copy(
                load = LoadState.Loaded,
                selectedProjectId = null,
                projects = emptyList(),
                lines = emptyList(),
                nextSeq = 0L,
                running = false,
                gap = false,
                pageFull = false,
                loadError = null,
            )
        }
        val base = ConsoleCmdState.withProject(previous, projectId)
        val sinceSeq = base.nextSeq
        val snapshot = host.consoleOutput(projectId, sinceSeq, CONSOLE_CMD_PAGE)
        ConsoleCmdState.of(
            previous = base.copy(projects = projectIds),
            projectId = projectId,
            snapshot = snapshot,
            sinceSeq = sinceSeq,
            maxLines = CONSOLE_CMD_PAGE,
            nowMillis = System.currentTimeMillis(),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (t: Exception) {
        ConsoleCmdState.failed(t, previous)
    }
}

/** 换一个项目看（先清行归零，再拉一轮）。 */
internal suspend fun selectConsoleProject(
    host: HostSummary?,
    state: ConsoleCmdState,
    projectId: String,
): ConsoleCmdState = loadConsoleCmd(host, ConsoleCmdState.withProject(state, projectId))

/**
 * 执行输入框里的那行。
 *
 * 顺序是刻意的：**先判、再发、再拉**。判定用 [NpmConsoleKeys]（唯一一份判据）——
 * 界面对"敲错了"的反馈必须是**当场**的，而不是等宿主回一个异常（那句话是一样的，
 * 但中间隔着一次往返，用户看到的是"执行失败"而不是"你这行写错了"）。
 *
 * 失败时**不清草稿**（用户多半要改一改再敲）；成功才清 —— 敲过的那行已经在上方回显了，
 * 输入框里再留一份会让人以为"没执行"。
 *
 * **失败原文原样透传，界面不按错误码另编一句话**：`ERR_PERMISSION_DENIED` 那句里已经
 * 写清「请求已入队，请到管理面板 → 依赖管理的审批卡确认后重试」，`ERR_NOT_IMPLEMENTED`
 * 那句里写清了缺的是哪一段。界面再译一遍就是第二份判据，而漂掉的那一份正好是用户看到的
 * 那一份（与 `RegistryScreen` 不自己判地址合法性同一条理由）。
 */
internal suspend fun runConsoleCmd(host: HostSummary?, state: ConsoleCmdState): ConsoleCmdState {
    val projectId = state.project
        ?: return state.copy(
            opError = "还没有选中的项目：控制台命令跑在某个项目的 node_modules 上（先建一个项目）",
            opNotice = null,
        )
    NpmConsoleKeys.rejectProjectId(projectId)?.let { return state.copy(opError = it, opNotice = null) }
    // 判据读**此刻的模式**（见 ConsoleCmdState.mode）：它决定裸首词是 npm bin 还是 shell 命令。
    val parsed = NpmConsoleKeys.parse(state.draft, state.mode)
    if (parsed is NpmConsoleCommand.Rejected) return state.copy(opError = parsed.reason, opNotice = null)
    // 进/退特权模式是**界面侧的会话状态**，不派发到宿主（宿主每次只收一条已定形的命令）。
    when (parsed) {
        is NpmConsoleCommand.EnterMode -> return state.copy(
            draft = "",
            mode = parsed.mode,
            opError = null,
            opNotice = "已进入 ${modeLabel(parsed.mode)}：接下来的裸命令按 shell 解析（exit 退出）",
        )
        NpmConsoleCommand.ExitMode -> return state.copy(
            draft = "",
            mode = ShellConsoleMode.DEFAULT,
            opError = null,
            opNotice = "已退出特权模式：裸命令按 npm 依赖提供的命令解析",
        )
        else -> Unit
    }
    if (host == null) {
        return state.copy(opError = "宿主摘要未接线（Application 未实现 HostSummary）", opNotice = null)
    }
    return try {
        dispatch(host, projectId, parsed, state.draft)
        loadConsoleCmd(host, state.copy(draft = "", opError = null, opNotice = null))
            .copy(opNotice = noticeFor(parsed))
    } catch (e: CancellationException) {
        throw e
    } catch (t: Exception) {
        // 失败也要拉一次：宿主先落 ECHO 行再抛，不拉就只剩一句错误。
        loadConsoleCmd(host, state.copy(opError = null, opNotice = null))
            .copy(opError = t.message ?: t.javaClass.simpleName, opNotice = null)
    }
}

/**
 * 按解析结果派发到对应的宿主口。
 *
 * **两条面分开走**：npm 命令面（`runNpmCommand`，含审批门禁/安装会话）与
 * shell 命令面（`runShellCommand`，同步现取、与依赖树无关）。判据同一份
 * （[NpmConsoleKeys.parse]），但落到宿主的是两个口 —— 把 shell 塞进 npm 那条链
 * 会让「敲一条 `ls`」占住全局安装会话。
 *
 * npm 那条面交出去的是 [draft]**原文**（不是从解析结果拼回去的）：宿主会再解析一遍，
 * 而控制台的 ECHO 行显示的是宿主收到的那串 —— 拼回去会把用户敲的 `tsc --version`
 * 显示成 `npx tsc --version`。回显与输入不一致，是「我说的和我看到的不是一件事」。
 * shell 那条面交 [NpmConsoleCommand.Shell.command]（入口词已剥），宿主那边起 `sh -c`。
 */
private suspend fun dispatch(
    host: HostSummary,
    projectId: String,
    cmd: NpmConsoleCommand,
    draft: String,
) {
    when (cmd) {
        is NpmConsoleCommand.Shell -> host.runShellCommand(projectId, cmd.command, cmd.mode)
        else -> host.runNpmCommand(projectId, draft)
    }
}

internal fun modeLabel(mode: ShellConsoleMode): String = when (mode) {
    ShellConsoleMode.ROOT -> "root 模式（su -c）"
    ShellConsoleMode.ADB -> "Shizuku 模式（shell uid）"
    ShellConsoleMode.DEFAULT -> "默认模式"
}

/**
 * 执行成功后的回执（**不替用户宣布结果** —— 结果在输出环里，这里只说"这一行去了哪"）。
 *
 * 三条路各自说清"接下来会发生什么"：轻操作当场出结果；重操作入队等安装会话；
 * `npm run`/`npx` 走 T1 门禁 —— 未获批会在依赖管理的审批卡上等人工确认，
 * 批完**要重敲那一行**（门禁只入队不排队，见 `InstallCoordinator.runScriptOps` 的 KDoc）。
 */
private fun noticeFor(cmd: NpmConsoleCommand): String = when (cmd) {
    is NpmConsoleCommand.Npm ->
        if (cmd.sub in NpmConsoleKeys.LIGHT_SUBCOMMANDS) "已执行 npm ${cmd.sub}：结果见上方"
        else "已入队 npm ${cmd.sub}：安装会话在跑，输出见上方（完成前输入行停用）"
    is NpmConsoleCommand.Run ->
        "已提交 npm run ${cmd.script}：未获批会入队，请到依赖管理的审批卡确认后**重敲这一行**"
    is NpmConsoleCommand.Exec ->
        "已提交 npx ${cmd.bin}：未获批会入队，请到依赖管理的审批卡确认后**重敲这一行**"
    is NpmConsoleCommand.Shell ->
        "已执行（${modeLabel(cmd.mode)}）：结果见上方"
    is NpmConsoleCommand.EnterMode -> "已进入 ${modeLabel(cmd.mode)}"   // 到不了这里（上面已 return）
    NpmConsoleCommand.ExitMode -> "已退出特权模式"                       // 同上
    is NpmConsoleCommand.Rejected -> cmd.reason   // 到不了这里（上面已拦），穷尽 when 而已
}
