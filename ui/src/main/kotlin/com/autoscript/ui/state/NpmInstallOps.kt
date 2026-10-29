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
 * 面板提交的每一条安装命令都带上的旗标。
 *
 * **`--prefer-offline` 从 2026-10-17 起是缺省**（用户口径「去掉开发依赖，和离线优先。
 * 默认开启离线优先」）：界面上那两颗旗标整排下线，其中「离线优先」不再是一个选项，
 * 而是**这一屏安装的一贯行为** —— 装上几十个包时先翻本地缓存的收益最明显，而它的代价
 * （可能拿到略微陈旧的元数据）在"依赖面板"这个场景里没有对象。
 *
 * **`-D` 那句提示下线后不再拼**：装什么都记进 `dependencies`（npm 的缺省）。界面不再问
 * "这算不算开发依赖" —— 那是个多数人答不上来、答错了要重装一遍的问题。
 */
private const val INSTALL_FLAGS = " --prefer-offline"

/**
 * 「提交动作发生」那一刻的在途写回（2026-10-29 修 —— 见 [runPanelCommand] 的 KDoc）。
 *
 * 三个安装/卸载/重装入口在 **launch 之前**先同步写回这一份：宿主侧 `runNpmPanelCommand`
 * 是**入队即返回**的假设已经过时 —— `enqueueHeavy` 是协程内联执行，调用要挂到会话
 * 跑完才返回。挂起期间若什么都不写回，屏幕就停在按下前的那一刻：阶段条不出现、
 * 按钮不置灰、爱连点几下点几下 —— 而 [NpmScreen] 侧兜底轮询
 * （`LaunchedEffect(page, npmState.installing)`）的键也就永远不变化、永远不启动。
 * 把在途先写回，这三件事各有归属：阶段条立现（QUEUED 起）、按钮即时置灰，
 * 兜底轮询随即接管「挂起期间每 400ms 续拉事件 / 快照 / 日志」。
 *
 * 与 [runPanelCommand] 内部那份 busy **逐字同形**（那边复用本函数，不允许两处各写一份）。
 */
internal fun NpmState.withInstallInFlight(): NpmState = copy(
    installing = true,
    opError = null,
    opNotice = null,
    installProgress = InstallProgressState(InstallEvent.Phase.QUEUED),
)

/**
 * 把输入框里的一行 + 两个旗标拼成一条命令（**纯函数**，可单测）。
 *
 * 返回 `null` = 拼不出（空输入）；否则是给 [HostSummary.runNpmPanelCommand] 的原文。
 *
 * **拼装为什么在呈现层而不是 `:domain`**：拼装规则要看得见"界面上有哪些开关"，
 * 而契约不该知道这件事。判据（`parse`/白名单/git 拒收）仍在 `:domain`，
 * 界面拼完仍要过它 —— 于是「面板能拼出来的东西」与「控制台能敲的东西」判据同源。
 *
 * 旗标**追加在包名之后**（`npm install axios --prefer-offline`）：npm 两种位置都认，
 * 而后置的形态在用户眼里就是「装了 axios，带这些旗标」。
 *
 * 2026-10-17 前这里还有 `dev`/`offline` 两个入参（对应界面上那两颗勾选框）—— 两颗都
 * 下线了，见 [INSTALL_FLAGS] 的说明。
 */
internal fun buildInstallCommand(draft: String): String? {
    val spec = draft.trim()
    if (spec.isEmpty()) return null
    return "npm install $spec$INSTALL_FLAGS"
}

/** 卸载一行的命令（§10.9 第 1 条；包名来自已装清单，不是用户敲的）。 */
internal fun buildRemoveCommand(name: String): String = "npm uninstall $name"

/**
 * **重装某一个包**（2026-10-17 用户口径：「在依赖列表里，加一个重装的图标按钮」）。
 *
 * `npm install <包名>@<版本> --no-save`，三条各自有理由：
 * - **带版本**：清单里那个版本就是宿主读 lockfile 读出来的"现在装的应该是哪一个"，
 *   不带版本会让 npm 去解一次范围、可能装上一个新的；
 * - **`--no-save`**：这一颗是**修复**动作（这个包坏了/目录缺了），不是"再声明一次依赖"。
 *   不带它的话 npm 会把这个包写进 `package.json` 的 `dependencies` —— 而清单里绝大多数是
 *   **传递依赖**，点一下重装就让一个传递依赖变成你亲手声明的直接依赖，这个副作用用户
 *   在按之前完全看不见；
 * - **留在这个作用域**：与卸载同一条 —— 清单显示的是哪个作用域，按钮就动哪一棵。
 *
 * **已知边界（如实记）**：`--no-save` 只保证**不动 manifest**，不保证目录结构一模一样 ——
 * npm 会把它放在顶层。原本嵌在 `node_modules/<别的包>/node_modules/` 里的那份，
 * 重装后就多出一份顶层的。条目本身没变（同名同版本），多占的那点地方由下一次
 * 「依赖去重」/「清理未声明依赖」收掉。
 */
internal fun buildReinstallCommand(name: String, version: String): String =
    "npm install $name@$version --no-save$INSTALL_FLAGS"

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
    val line = buildInstallCommand(state.installDraft)
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

/**
 * 重装清单里的某一个包（那行右边的重装图标）。
 *
 * 走的是与安装/卸载**完全同一条**命令通道（[runPanelCommand] → `runNpmPanelCommand`）：
 * 判据、多镜像交叉校验、磁盘预检、安装会话一样不少 —— 门禁强度不该取决于用户按的是
 * 哪一颗按钮。
 *
 * 失败**不清草稿**（这条本来就不吃草稿），成功也不清：草稿是用户为"安装"那一行准备的，
 * 与这颗粒按钮无关。
 */
internal suspend fun reinstallInstalled(host: HostSummary?, state: NpmState, name: String, version: String): NpmState {
    val projectId = state.selectedProjectId
        ?: return state.copy(opError = "还没有选中的项目：依赖装在那个作用域的 node_modules 里", opNotice = null)
    return runPanelCommand(host, state, projectId, buildReinstallCommand(name, version), clearDraft = false)
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
    val busy = state.withInstallInFlight()
    return try {
        host.runNpmPanelCommand(projectId, line)
        // 挂起返回之后**再拉到它跑完**（2026-10-10 批 90；2026-10-29 把话说准：
        // `enqueueHeavy` 是协程内联执行，这一行其实要**挂到会话跑完才返回** —
        // 阶段条实时走的那一路是」在途写回之后 [NpmScreen] 的兜底轮询
        // （`LaunchedEffect(page, npmState.installing)`）拉着的。本条只是再追一遍，
        // 兜住挂起返回时会话仍在跑的窗口。
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
            // 逐格版而不是单次版：客户看到的安装瓶颈不在"有没有事件"，在"事件都挤在同一
            // 批里被一口气折完"（见 `pollInstallEventsStepped` 的 KDoc）。
            val withEvents = pollInstallEventsStepped(host, current, projectId)
            if (withEvents.selectedProjectId == projectId) loadNpmSnapshot(host, withEvents) else withEvents
        },
        shouldContinue = { it.installing && it.selectedProjectId == projectId },
    )

/**
 * 轮询版的事件拉取 —— **逐格步进**（2026-10-29 用户口径「进度条只显示排队中，
 * 直到完成才一次性出现」）。
 *
 * 一根：[pollInstallEvents] 是"拉一次"（进页面/刷新），它整批折叠 —— 那是对的，现取
 * 要的就是"此刻宿主报到哪一步"。但轮询循环若也整批折，观感就成了另一个东西：
 * 宿主在**一条协程里同步内联跑完整个安装会话**（`enqueueHeavy` 协程内联执行，
 * QUEUED → RESOLVE → POST_CHECK → FINISHED 毫秒级全发，事件环里它们本来就排成
 * 一串），400ms 才回来拉一次的轮询大概率一次端回**整串** —— 整批折完，阶段条
 * 从「排队中」直接跳「完成」，中间五档一格都没露过脸，就是用户说的"不刷新，
 * 直到完成时一次性全部出现"。
 *
 * 这里把节奏闸在 UI 侧立起来：**一个 tick 只消费一条事件**，批里剩下的进
 * [NpmState.pendingProgressEvents] 缓冲，等下个 tick 再放一条 —— 阶段条逐格走完
 * 六档（400ms/格 × 6 格 ≈ 2.4 秒，一次安装也就这个量级）。缓冲里没有可消费的
 * 才去拉新的（否则每 tick 都拉新批，消费节流就白立了）。
 *
 * 游标纪律与 [pollInstallEvents] 相同：`batch.lastSeq` 是那次所拉全部事件的最尾
 * seq，**即使只展示了一条**，游标也要推进到批尾 —— 事件只消费一遍，展示慢一点
 * 是节奏，重复消费就是重放了。
 */
internal suspend fun pollInstallEventsStepped(
    host: HostSummary,
    state: NpmState,
    projectId: String,
): NpmState {
    if (state.selectedProjectId != projectId || state.installProgress == null) return state
    // 上一批还有没放出来的：本 tick 只放一条，不动游标（游标已在拉批那拍推到底）。
    if (state.pendingProgressEvents.isNotEmpty()) {
        val folded = foldProgress(state.installProgress, listOf(state.pendingProgressEvents.first()))
        return state.copy(
            installProgress = folded,
            pendingProgressEvents = state.pendingProgressEvents.drop(1),
            installing = if (folded?.done == true) false else state.installing,
        )
    }
    val since = state.installSeq
    return try {
        val batch = host.npmInstallEvents(projectId, since, INSTALL_EVENT_PAGE)
        if (batch.events.isEmpty() && batch.lastSeq == since) return state
        val first = batch.events.firstOrNull()
        val folded = if (first != null) foldProgress(state.installProgress, listOf(first)) else state.installProgress
        state.copy(
            installSeq = batch.lastSeq,
            installProgress = folded,
            pendingProgressEvents = batch.events.drop(1),
            installing = if (folded?.done == true) false else state.installing,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 与 [pollInstallEvents] 同一条：进度是装饰性读数，读失败不动已有进度、不抬成报错。
        state
    }
}

/**
 * 拉一次安装事件（阶段进度条的数据源，§10.9 第 1 条）。
 *
 * **为什么只拉一次而不是轮询到终态**：`:ui` 的每个读口都是"进页面/手动刷新时现取一次"
 * （见 `ConsoleCmdState` 的同款纪律），没有常驻轮询循环 ——
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
        // **游标没动、阶段也没变 → 原样返回这一份**（2026-10-28）：等值重建照样
        // 让展开的可见行重组合一次 —— LazyColumn 的 item lambda 捕获的是 state
        // **引用**，换一份等值的也是换。安装长跑在安静的阶段（写入 node_modules
        // 那种没有事件可报的窗口）时，每 400ms 一 tick 全是白重画。
        if (batch.lastSeq == since && folded == state.installProgress) return state
        state.copy(
            installSeq = batch.lastSeq,
            installProgress = folded,
            // 单次现取**整批折完**：逐格桥的缓冲不随这次带过来（copy 缺省即空）——
            // 阶段条已被折到批尾，缓冲里没有比它更新的东西（见 `pollInstallEventsStepped`）。
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

/**
 * 提交后的回执（**动作已完成的那一句** —— `runPanelCommand` 等到宿主挂起返回才构造它，
 * `enqueueHeavy` 是协程内联执行，这一行从回执构造那一刻起说的就是结果，不是"刚提交"）。
 *
 * 第十七批第 41 条改口：原文案「已提交 npm uninstall：安装会话在跑」是批 90「入队即返回」
 * 假设时代的措辞 —— 宿主早已同步执行到跑完才回来，回执却还在说"在跑"，用户把"卸载完成"
 * 读成"刚提交、东西还在装"，正是「卸载没生效」观感的一半来源。改成**动作名 + 对象 + 结果**
 * 的完成句式：卸载说「已卸载 xx」，安装/重装说「已安装 xx」。对象取命令里的**第一个包名**
 * （`-` 开头的旗标跳过；重装的 `npm install axios@1.7.9 --no-save` 取到 `axios@1.7.9`）。
 */
private fun noticeForPanel(cmd: NpmConsoleCommand): String = when (cmd) {
    is NpmConsoleCommand.Npm -> {
        val target = cmd.args.firstOrNull { !it.startsWith("-") }
        if (cmd.sub == "uninstall") "已卸载 ${target ?: "所选包"}：清单已更新"
        else "已安装 ${target ?: "所选依赖"}：清单已更新"
    }
    is NpmConsoleCommand.Run ->
        "已提交 npm run ${cmd.script}：结果见上方"
    is NpmConsoleCommand.Exec ->
        "已提交 npx ${cmd.bin}：结果见上方"
    // 下面三类**到不了这里**（依赖面板的输入行不解析 shell/模式命令，上面已拦），
    // 穷尽 when 而已 —— 但话术不糊弄：真走到这里说明界面的拦截漏了。
    is NpmConsoleCommand.Shell -> "依赖面板不执行 shell 命令：请到控制台用 su/shizuku 模式"
    is NpmConsoleCommand.EnterMode -> "依赖面板不切特权模式：请到控制台"
    NpmConsoleCommand.ExitMode -> "依赖面板不切特权模式：请到控制台"
    is NpmConsoleCommand.Rejected -> cmd.reason   // 到不了这里（上面已拦），穷尽 when 而已
}
