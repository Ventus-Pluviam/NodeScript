package com.autoscript.ui.state

import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.NpmConsoleCommand
import com.autoscript.domain.npm.NpmConsoleKeys
import kotlinx.coroutines.CancellationException

/**
 * 依赖面板的**变更半边**（§10.9 第 1 条的安装输入行 + 旗标 + 阶段进度条，2026-10-09 批 87）。
 *
 * 搬出 `MainActivity` 的理由与 [loadRegistry]/[runNpmMaintenanceOp] 逐字相同：
 * Activity 的函数数贴着 detekt 的 `TooManyFunctions` 线，且这几段是"拿读口算下一份状态"
 * 的纯逻辑 —— 放这里能用 `FakeHost` 直接测（`:ui` 的单测门跑 JVM，`MainActivity` 构造不出来）。
 *
 * **三件刻意不做的事**（做了就是撒谎，见 [NpmState.installProgress] 与下面两条）：
 * - 不画百分比：job 数拿不到（npm 进程内的 reify 是黑盒），只画六档阶段条；
 * - 不画依赖树：`list(depth)` 的 depth 宿主侧只用 0（lockfile 是平铺闭包）；
 * - 不做 `hasInstallScript` 前置告警：那要 packument 解析面，今天没有 ——
 *   装完之后的 `SCRIPTS_SKIPPED` 警告已经在事件流里（那条是**事后**的，不是事前的）。
 *
 * **门禁强度不取决于入口**：这里拼出来的一行交给 [HostSummary.runNpmPanelCommand]，
 * 它与控制台走的是同一个 facade 入口（同一份判据、同一套装前交叉校验、同一道磁盘/配额预检、
 * 同一把项目锁与全局安装会话）。本文件**只负责拼**，不判第二遍合法性 ——
 * 判据的唯一一份在 `NpmConsoleKeys`（界面侧调用它只为"当场告知"，不是第二份判据）。
 */

/** 一行输入的最大长度（防手滑贴进来一整份文件；超了当场拒，不往返）。 */
private const val INSTALL_DRAFT_MAX = 200

/**
 * 把输入框里的一行 + 两个旗标拼成一条命令（**纯函数**，可单测）。
 *
 * 返回 `null` = 拼不出（空输入）；否则是给 [HostSummary.runNpmPanelCommand] 的原文。
 *
 * **拼装为什么在呈现层而不是 `:domain`**：拼装规则要看得见"界面上有哪些开关"，
 * 而契约不该知道这件事。判据（`parse`/白名单/git 拒收）仍在 `:domain`，
 * 界面拼完仍要过它 —— 于是「面板能拼出来的东西」与「控制台能敲的东西」判据同源。
 *
 * 旗标**追加在包名之后**（`npm install axios -D`）：npm 两种位置都认，
 * 而后置的形态在用户眼里就是「装了 axios，带 -D」，与界面上那颗勾选框的语序一致。
 */
internal fun buildInstallCommand(draft: String, dev: Boolean, offline: Boolean): String? {
    val spec = draft.trim()
    if (spec.isEmpty()) return null
    return buildString {
        append("npm install ").append(spec)
        if (dev) append(" -D")
        if (offline) append(" --prefer-offline")
    }
}

/** 卸载一行的命令（§10.9 第 1 条；包名来自已装清单，不是用户敲的）。 */
internal fun buildRemoveCommand(name: String): String = "npm uninstall $name"

/**
 * 提交安装输入行。
 *
 * 顺序与 [runConsoleCmd] 刻意一致：**先判、再发、再拉**。
 * - 判：拼出来的一行先过 [NpmConsoleKeys.parse]（唯一一份判据）—— 敲错的东西**当场**
 *   被告知，不往返一趟才拿到同一句话；
 * - 发：[HostSummary.runNpmPanelCommand]（与控制台同一个 facade 入口）；
 * - 拉：无论成败都现取一次（宿主是**先落 ECHO 行再抛**的，不拉就只剩一句错误）。
 *
 * 失败**不清草稿**（用户多半要改一改再敲）；成功才清 —— 这一行已经在控制台里回显了。
 */
internal suspend fun submitInstall(host: HostSummary?, state: NpmState): NpmState {
    val projectId = state.selectedProjectId
        ?: return state.copy(opError = "还没有选中的项目：依赖装在那个项目的 node_modules 里", opNotice = null)
    if (state.installDraft.trim().length > INSTALL_DRAFT_MAX) {
        return state.copy(opError = "输入过长（上限 $INSTALL_DRAFT_MAX 字符）：请只写包名与版本", opNotice = null)
    }
    val line = buildInstallCommand(state.installDraft, state.installDev, state.installOffline)
        ?: return state.copy(opError = "先写要装什么（例如 axios 或 axios@1.7.0）", opNotice = null)
    return runPanelCommand(host, state, projectId, line, clearDraft = true)
}

/**
 * 卸载某个已装包（§10.9 第 1 条清单行上的「卸载」）。
 *
 * 包名**来自清单**（宿主读 lockfile 的结果），不是用户敲的 —— 故这条不需要草稿、
 * 也不清草稿。它走的仍是同一条命令通道（`npm uninstall <name>`）。
 */
internal suspend fun removeInstalled(host: HostSummary?, state: NpmState, name: String): NpmState {
    val projectId = state.selectedProjectId
        ?: return state.copy(opError = "还没有选中的项目：依赖装在那个项目的 node_modules 里", opNotice = null)
    return runPanelCommand(host, state, projectId, buildRemoveCommand(name), clearDraft = false)
}

/** 两条入口共用的「判 → 发 → 拉」（差别只在草稿清不清、以及发的是哪一行）。 */
private suspend fun runPanelCommand(
    host: HostSummary?,
    state: NpmState,
    projectId: String,
    line: String,
    clearDraft: Boolean,
): NpmState {
    NpmConsoleKeys.rejectProjectId(projectId)?.let { return state.copy(opError = it, opNotice = null) }
    val parsed = NpmConsoleKeys.parse(line)
    if (parsed is NpmConsoleCommand.Rejected) return state.copy(opError = parsed.reason, opNotice = null)
    if (host == null) {
        return state.copy(opError = "宿主摘要未接线（Application 未实现 HostSummary）", opNotice = null)
    }
    // 命令已过判据 → 必然是一条 `npm <sub>`：阶段条从 `QUEUED` 起（那一格之前没有别的
    // 信息可给）。
    //
    // **草稿在这里不清**：清了就意味着"提交即视为成功"，而宿主那一侧是先落 ECHO 行
    // **再抛**的（磁盘/配额预检、验签拒绝都在发出去之后才发生）。清早了，用户看到的是
    // 一句失败 + 一个空输入框 —— 他还得把包名重打一遍。清草稿是**成功之后**的事。
    val busy = state.copy(
        installing = true,
        opError = null,
        opNotice = null,
        installProgress = InstallProgressState(InstallEvent.Phase.QUEUED),
    )
    return try {
        host.runNpmPanelCommand(projectId, line)
        // 入队之后**跟着拉到它跑完**（2026-10-10 批 90）：宿主是入队即返回的，
        // 而 DOWNLOAD/REIFY 发生在返回之后 —— 只拉一次的话阶段条永远停在 QUEUED。
        val polled = pollInstallWhileRunning(host, busy, projectId)
        loadNpmSnapshot(host, polled).copy(
            installing = false,
            installDraft = if (clearDraft) "" else state.installDraft,
            opNotice = noticeForPanel(parsed),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (t: Exception) {
        // 失败也要拉一次：宿主先落 ECHO 行再抛，不拉就只剩一句错误。
        val polled = pollInstallEvents(host, busy, projectId)
        loadNpmSnapshot(host, polled).copy(
            installing = false,
            opError = t.message ?: t.javaClass.simpleName,
            opNotice = null,
        )
    }
}

/**
 * 安装跑着的时候**自己拉**（2026-10-10 批 90：阶段条不再停在 `QUEUED`）。
 *
 * 停的条件是**宿主给的** [NpmState.installing]（句柄账 / 事件里的 `Finished`），
 * 不是界面猜的；有界见 [pollWhile]。每一轮都同时取**事件**（阶段条）与**快照**
 * （已装清单、尺寸）—— 一次安装的产物落在 node_modules 上，跑完那一下
 * 清单必须跟着变，否则用户看到的是"阶段条走完了，依赖列表还是空的"。
 *
 * **与 [pollInstallEvents] 的分工**：那个是"拉一次"（刷新按钮、进页面），
 * 这个是"拉到跑完"（提交之后）。两者共用同一个读口，不另开取数路径。
 */
internal suspend fun pollInstallWhileRunning(
    host: HostSummary,
    state: NpmState,
    projectId: String,
    intervalMillis: Long = LIVE_POLL_INTERVAL_MILLIS,
    maxTicks: Int = LIVE_POLL_MAX_TICKS,
): NpmState =
    pollWhile(
        initial = state,
        intervalMillis = intervalMillis,
        maxTicks = maxTicks,
        tick = { current ->
            val withEvents = pollInstallEvents(host, current, projectId)
            if (withEvents.selectedProjectId == projectId) loadNpmSnapshot(host, withEvents) else withEvents
        },
        shouldContinue = { it.installing && it.selectedProjectId == projectId },
    )

/**
 * 拉一次安装事件（阶段进度条的数据源，§10.9 第 1 条）。
 *
 * **为什么只拉一次而不是轮询到终态**：`:ui` 的每个读口都是"进页面/手动刷新时现取一次"
 * （见 `ConsoleCmdState`/`AuditState` 的同款纪律），没有常驻轮询循环 ——
 * 而 `runNpmPanelCommand` 是**入队即返回**的，拉一次拿到的多半只是 `QUEUED`。
 * 这不是缺陷也不是假装：**阶段条显示的是"这一刻宿主报到哪一步"**，
 * 用户点刷新（或重新进页面）会再取一次；等宿主把常驻轮询那件事做了，
 * 这个函数就是它的单次实现，不必改形状。
 *
 * 游标只进不退：`InstallEventBatch.lastSeq` 是下次该传的值，读失败也**不**清零
 * （清零会把已经显示过的阶段重放一遍，看起来像"又跑了一遍"）。
 *
 * **唯一的归零点在换项目**（[NpmState.withProject]）：seq 是**环内全局单调**的、`drain`
 * 才按 projectId 过滤，沿用上一个项目的游标会漏掉新项目 seq 更小的那些事件 —— 包括
 * `Finished`，于是阶段条永远停在「进行中」。
 */
internal suspend fun pollInstallEvents(host: HostSummary, state: NpmState, projectId: String): NpmState {
    if (state.selectedProjectId != projectId) return state
    val since = state.installSeq
    return try {
        val batch = host.npmInstallEvents(projectId, since, INSTALL_EVENT_PAGE)
        val folded = foldProgress(state.installProgress, batch.events.map { it.event })
        state.copy(
            installSeq = batch.lastSeq,
            installProgress = folded,
            // `Finished` 到了 = 这次安装**在宿主侧已经收尾**（句柄逐出）——
            // 「在途」这个旗标跟着它落，而不是等调用方那一句 `copy(installing = false)`。
            // 两者看着等价，差在**轮询**上：`pollInstallWhileRunning` 的停条件就是它，
            // 靠调用方收尾的话那条循环要一直转到 maxTicks（600 × 400ms ≈ 4 分钟），
            // 期间输入行一直是灰的 —— 明明早就装完了。
            installing = if (folded?.done == true) false else state.installing,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 进度读失败**不动**已有进度、也不报错：它是一条装饰性的读数，
        // 把它抬成 `opError` 会让「装到一半读不到进度」看起来像「安装失败」。
        // 异常对象**刻意不接**（`_`）：这句话里没有它的位置，接了就只是为了让
        // detekt 的 SwallowedException 闭嘴 —— 那正好是"装样子"。
        state
    }
}

/**
 * 把一批事件折进阶段条（**纯函数**，可单测）。
 *
 * 只认 `Progress`/`Finished` 两类：`Warning` 走的是控制台那条流（§10.9 第 3 条），
 * 在这里再显示一遍就是同一句话说两处。
 *
 * `Finished` 到了就把 [InstallProgressState.done] 置上 —— 阶段条据此收起来，
 * 否则一次失败之后屏幕上永远停着一个"进行中"的条。
 */
internal fun foldProgress(current: InstallProgressState?, events: List<InstallEvent>): InstallProgressState? {
    var acc = current
    for (e in events) {
        acc = when (e) {
            is InstallEvent.Progress -> InstallProgressState(phase = e.phase, pkg = e.pkg)
            is InstallEvent.Finished -> InstallProgressState(
                phase = acc?.phase ?: InstallEvent.Phase.DONE,
                pkg = acc?.pkg,
                done = true,
                ok = e.success,
                detail = e.detail,
            )
            is InstallEvent.Warning -> acc   // 控制台那条流负责，这里不重复
        }
    }
    return acc
}

/** 单批拉取上限（与 `ConsoleCmdState` 的页大小同量级：够一次安装的阶段事件）。 */
internal const val INSTALL_EVENT_PAGE = 64

/** 提交后的回执（**不替用户宣布结果** —— 结果在清单与阶段条里）。 */
private fun noticeForPanel(cmd: NpmConsoleCommand): String = when (cmd) {
    is NpmConsoleCommand.Npm ->
        if (cmd.sub == "uninstall") "已提交 npm uninstall：安装会话在跑，完成后清单会更新"
        else "已提交 npm install：安装会话在跑（几十秒量级），完成后清单会更新"
    is NpmConsoleCommand.Run ->
        "已提交 npm run ${cmd.script}：未获批会入队，请到下面的审批卡确认后**重敲这一行**"
    is NpmConsoleCommand.Exec ->
        "已提交 npx ${cmd.bin}：未获批会入队，请到下面的审批卡确认后**重敲这一行**"
    // 下面三类**到不了这里**（依赖面板的输入行不解析 shell/模式命令，上面已拦），
    // 穷尽 when 而已 —— 但话术不糊弄：真走到这里说明界面的拦截漏了。
    is NpmConsoleCommand.Shell -> "依赖面板不执行 shell 命令：请到控制台用 su/shizuku 模式"
    is NpmConsoleCommand.EnterMode -> "依赖面板不切特权模式：请到控制台"
    NpmConsoleCommand.ExitMode -> "依赖面板不切特权模式：请到控制台"
    is NpmConsoleCommand.Rejected -> cmd.reason   // 到不了这里（上面已拦），穷尽 when 而已
}
