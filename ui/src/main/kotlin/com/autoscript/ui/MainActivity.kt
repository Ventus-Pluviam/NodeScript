package com.autoscript.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.host.TaskRegistration
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.TabItem
import com.autoscript.ui.components.TabBar
import com.autoscript.ui.screens.CapabilityScreen
import com.autoscript.ui.screens.ConsoleScreen
import com.autoscript.ui.screens.HomeScreen
import com.autoscript.ui.screens.TaskCenterScreen
import com.autoscript.ui.state.CapabilityCenterState
import com.autoscript.ui.state.ConsoleState
import com.autoscript.ui.state.HomeState
import com.autoscript.ui.state.RegistrationForm
import com.autoscript.ui.state.TaskCenterState
import com.autoscript.ui.state.TaskRowState
import com.autoscript.ui.state.ActiveRunState
import com.autoscript.ui.theme.Theme
import com.autoscript.ui.theme.ThemeColors
import com.autoscript.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/**
 * 启动入口（launcher，manifest 在本模块，随库合并进 :app）。
 *
 * 接线方式（§6「UI 拆独立模块」的落点）：不 import `:app` 的任何类型 ——
 * 把 `application` 现转 `as? HostSummary`（`:domain` 读口，`AppShellApplication`
 * 实现），未实现即 `HomeState.UNWIRED` / `CapabilityCenterState.NOT_LOADED` 如实显示。
 *
 * 四个页签：首屏（壳/保活/漏投）、任务中心（§8.6 排期 + §8.5 档案/恢复账）、
 * 控制台（§7.3 游标拉取 + 在途执行）、能力中心（§9.5 三态）。刷新时机分两种，**不能混**：
 * - 首屏状态是**同步**读（`shellSummary()`）：`onCreate` 首读 + 每次 `onResume` 重读；
 * - 能力态/任务态/控制台都是**挂起**的（`capabilityCenter()` 每次现问系统，含 root 探测的
 *   IO 切换；`taskCenter()` 要读两个持久寄存器；`console(seq, max)` 是游标增量拉取）：
 *   由 `LaunchedEffect(resumeTick, tab)` 驱动 —— 回前台、或切到该页签时重取一次。
 *   控制台尤其依赖这条：行是**累积**的，游标只进不退（见 `ConsoleState`）。
 *   这样用户从系统设置页授完权回来，看到的是**刚问过**的结论，而不是离开时那份缓存
 *   （后者正是"授权了但界面还说没授权"的来源）。
 *
 * **外壳（Telegram 式）**：顶栏 + 底部页签条由 `ScaffoldScreen` 统一，四屏只画内容；
 * 顶栏右侧挂 [THEME_ACTION] 一个主题切换动作（浅/深/跟随系统三档），主题选择
 * `rememberSaveable` 保留 —— 进程重建后不回弹，是"用户设过一次"的承诺。
 *
 * 这里也是本模块唯一直接持有 [HostSummary] 的类：各屏只收纯状态 DTO，
 * 因此它们各自可 JVM 测（见 `HomeStateTest`/`CapabilityCenterStateTest` 等）。
 */
class MainActivity : ComponentActivity() {

    /** 首屏状态：compose 可观察单槽（Activity 持有，配置变更随重建重读，无跨进程共享诉求）。 */
    private var homeState: HomeState by mutableStateOf(HomeState.UNWIRED)

    /** 能力中心状态（同上）。 */
    private var capabilityState: CapabilityCenterState by mutableStateOf(CapabilityCenterState.NOT_LOADED)

    /** 任务中心状态（同上）。 */
    private var taskState: TaskCenterState by mutableStateOf(TaskCenterState.NOT_LOADED)

    /** 控制台状态（同上；行与游标随失败保留 —— 见 `ConsoleState.failed`）。 */
    private var consoleState: ConsoleState by mutableStateOf(ConsoleState.NOT_LOADED)

    // 「当前页签」不再是一个字段：它由 pager 的滚动位置派生（见 setContent 里的 pagerState）。
    // 存两份必然漂移 —— 手指划过去时字段说 A、pager 说 B。

    /** 主题档位（跟随系统/浅/深）。 */
    private var themeMode: ThemeMode by mutableStateOf(ThemeMode.SYSTEM)

    /** 每次 `onResume` +1：驱动 [LaunchedEffect] 重问系统（回前台即重读）。 */
    private var resumeTick: Int by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        homeState = HomeState.read(hostSummary())
        setContent {
            val scope = rememberCoroutineScope()
            val pagerState = rememberPagerState(pageCount = { Tab.entries.size })
            Theme(mode = themeMode) {
                MainShell(
                    pagerState = pagerState,
                    // 点页签 = 让 pager 自己滑过去。**不直接改状态**：pager 的滚动位置是
                    // 唯一事实来源，指示线/重读/内容三者都从它派生，绕过去就又会漂移。
                    onSelectTab = { scope.launch { pagerState.animateScrollToPage(it) } },
                    themeMode = themeMode,
                    onCycleTheme = { themeMode = themeMode.next() },
                ) { shellModifier ->
                    // 四屏装进 **HorizontalPager**：这是 TG 主页签的做法
                    // （`MainTabsActivity extends ViewPagerActivity`），换来两件事 ——
                    // ① 点页签是**横向滑动**过去，不是淡入淡出；② 内容可以**横划切页**。
                    // 页签条上的指示线也因此能跟手（见 TabBar 的 pageOffsetFraction）。
                    HorizontalPager(
                        state = pagerState,
                        modifier = shellModifier,
                        // 邻页预组合：横划时邻页已经在了，不会划到一半才现画。
                        beyondViewportPageCount = 1,
                    ) { current ->
                        when (Tab.entries[current]) {
                            Tab.HOME -> HomeScreen(
                                state = homeState,
                                onRefresh = { homeState = HomeState.read(hostSummary()) },
                                modifier = shellModifier,
                            )
                            Tab.TASKS -> TaskCenterScreen(
                                state = taskState,
                                onRefresh = { reloadTasks() },
                                onRunNow = { task -> scope.launch { runTaskNowOp(task) } },
                                onCancel = { task -> scope.launch { cancelTaskOp(task) } },
                                onRegister = { form -> scope.launch { registerTaskOp(form) } },
                                modifier = shellModifier,
                            )
                            Tab.CONSOLE -> ConsoleScreen(
                                state = consoleState,
                                onRefresh = { reloadConsole() },
                                onStopRun = { run -> scope.launch { stopRunOp(run) } },
                                modifier = shellModifier,
                            )
                            Tab.CAPABILITIES -> CapabilityScreen(
                                state = capabilityState,
                                onRefresh = { reloadCapabilities() },
                                onOpenSettings = { hostSummary()?.openCapabilitySettings(it) },
                                modifier = shellModifier,
                            )
                        }
                    }
                }
            }
            // 键里带页签：切到本页签本身就该现取，而不是显示上次离开时的快照。
            // 用 **settledPage** 而不是 currentPage：横划跨多页时 currentPage 会途经
            // 中间每一页，那样划一次会连读三遍；settledPage 只在停稳后变一次。
            // 重读的触发权只在这两条（回前台/切页签）与手动刷新手里 —— 读失败不会
            // 反过来改 resumeTick 形成自激（见 reloadCapabilities）。
            LaunchedEffect(resumeTick, pagerState.settledPage) {
                when (Tab.entries[pagerState.settledPage]) {
                    Tab.HOME -> Unit
                    Tab.TASKS -> reloadTasks()
                    Tab.CONSOLE -> reloadConsole()
                    Tab.CAPABILITIES -> reloadCapabilities()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        homeState = HomeState.read(hostSummary())
        resumeTick++
    }

    /**
     * 现问系统（挂起；只写 [capabilityState]，不触发重读）。
     *
     * 三种落点都不撒谎：
     * - 读口未接线（宿主没实现 `HostSummary`）→ 留 `CapabilityCenterState.NOT_LOADED`，
     *   **不冒充**"一个能力都没有"（那是"读成功且清单为空"，而 `Capability.entries`
     *   恒非空 —— 空行集只可能是没读到）；
     * - 查询抛错（ROM 奇异实现）→ `CapabilityCenterState.failed`，原异常文案带上
     *   （现场要靠它区分"ROM 查询崩了"与"装配没接线"）；
     * - 成功 → 全量行 + 降级任务账。
     */
    private suspend fun reloadCapabilities() {
        val host = hostSummary()
        if (host == null) {
            capabilityState = CapabilityCenterState.NOT_LOADED
            return
        }
        capabilityState = try {
            CapabilityCenterState.of(host.capabilityCenter())
        } catch (t: Throwable) {
            CapabilityCenterState.failed(t)
        }
    }

    /**
     * 现取任务中心（挂起；只写 [taskState]，不触发重读）。
     *
     * 三种落点都不撒谎（与 [reloadCapabilities] 同构）：
     * - 读口未接线 → 留 `TaskCenterState.NOT_LOADED`，**不冒充**"没有任务"；
     * - 读取抛错（壳未装配/寄存器读崩）→ `TaskCenterState.failed`，原异常文案带上；
     * - 成功 → 任务 + 未结算执行 + 恢复账。
     *
     * 取 `nowMillis` 一次传进去（不在状态类里现取）：同一帧里所有相对时间共用同一个 now。
     */
    private suspend fun reloadTasks() {
        val host = hostSummary()
        if (host == null) {
            taskState = TaskCenterState.NOT_LOADED
            return
        }
        taskState = try {
            TaskCenterState.of(host.taskCenter(), nowMillis = System.currentTimeMillis())
        } catch (t: Throwable) {
            TaskCenterState.failed(t)
        }
    }

    /**
     * 操作面统一通道（登记/取消/立即执行）：成功回执 + 现取刷新，失败**保留旧清单**。
     *
     * 三种落点都不撒谎：
     * - 读口未接线 → [opError] 如实说（不动清单 —— 没读到 ≠ 一条任务都没有）；
     * - 操作抛（校验不过/壳未装配/任务不存在/调度已收口）→ `opError`
     *   保留**原异常文案**（区分现场的唯一线索），`TaskCenterState.of` 之前的清单原样留着 ——
     *   操作失败把已读到的任务一并抹掉，会让用户以为任务全没了；
     * - 成功 → `opNotice` 回执，随后**现取**一次（登记/取消/Once 终态化都改了注册表，
     *   不刷新就与事实脱节）；现取经 `TaskCenterState.of` 会把 op 字段归零，故回执
     *   在刷新**之后**盖上去（现取纪律：列表是最新的，回执是刚才那次操作的）。
     *
     * @param op 执行操作并返回成功回执文案（失败抛 —— 由本函数收进 opError）。
     */
    private suspend fun performTaskOp(op: suspend (HostSummary) -> String) {
        if (taskState.opInFlight) return // 双保险：按钮已禁用，这里兜住并发入口
        val host = hostSummary()
        if (host == null) {
            taskState = taskState.copy(
                opInFlight = false,
                opError = "宿主摘要未接线（Application 未实现 HostSummary）",
            )
            return
        }
        taskState = taskState.copy(opInFlight = true, opError = null, opNotice = null)
        val notice = try {
            op(host)
        } catch (t: Throwable) {
            taskState = taskState.copy(
                opInFlight = false,
                opError = t.message ?: t.javaClass.simpleName,
            )
            return
        }
        reloadTasks()
        taskState = taskState.copy(opNotice = notice, opInFlight = false)
    }

    /** 登记：解析（形状非法在此抛）→ 写口 → 回执带分配到的 id。 */
    private suspend fun registerTaskOp(form: RegistrationForm) = performTaskOp { host ->
        val registration: TaskRegistration = form.toRegistration()
        val id = host.registerTask(registration)
        "已登记「${registration.name}」（$id）"
    }

    /** 取消（幂等）：回执只说"已请求取消" —— 以刷新后的清单为准，不赌 tombstone 落没落。 */
    private suspend fun cancelTaskOp(task: TaskRowState) = performTaskOp { host ->
        host.cancelTask(task.id)
        "已取消「${task.name}」"
    }

    /**
     * 立即执行（`USER_CLICK`）：**挂起到执行结算**（排队 10s + 脚本超时/默认 30s，
     * 见 `HostSummary.runTaskNow` KDoc），期间 `opInFlight` 禁用按钮。
     *
     * 回执措辞点破两条语义：成败不在本口（在意图日志/控制台）；Once 触发即出册
     * （刷新后卡片消失是调度器语义，不是被取消了）。
     */
    private suspend fun runTaskNowOp(task: TaskRowState) = performTaskOp { host ->
        host.runTaskNow(task.id)
        if (task.once) {
            "已执行「${task.name}」并出册（一次性任务；执行成败见控制台）"
        } else {
            "已触发「${task.name}」（执行成败见控制台）"
        }
    }

    /**
     * 现拉控制台（挂起；只写 [consoleState]，不触发重读）。
     *
     * 游标取 `consoleState.nextSeq`（只进不退；首读 0），成功经 `ConsoleState.of`
     * **累积**入列，失败经 `ConsoleState.failed` **保留旧行与游标** —— 瞬时失败不清缓冲，
     * 下次从上次成功处续拉。读口未接线仍留 `ConsoleState.NOT_LOADED`。
     */
    private suspend fun reloadConsole() {
        val host = hostSummary()
        if (host == null) {
            consoleState = ConsoleState.NOT_LOADED
            return
        }
        val previous = consoleState
        consoleState = try {
            ConsoleState.of(
                previous = previous,
                added = host.console(sinceSeq = previous.nextSeq, maxLines = CONSOLE_PAGE),
                nowMillis = System.currentTimeMillis(),
            )
        } catch (t: Throwable) {
            ConsoleState.failed(t, previous)
        }
    }

    /**
     * 停止一次在途执行（控制台在途行「停止」按钮）：按 runId 精确停（§8.2 池四步 quiesce）。
     *
     * 与 [performTaskOp] 同一条纪律：未接线/抛错进 `stopError`
     * （原文透传，**不清已读到的行与游标**）；成功回执随后现取一次
     * （在途表是最新的，回执是刚才那次停止的 —— 回执在刷新**之后**盖上去，
     * 因 `ConsoleState.of` 会把 stop 字段归零）。
     * 回执措辞点破：true = 已请求停止（quiesce 异步走，不赌停没停）；
     * false = 点的时候已不在途（`AlreadyGone` 诚实投影：已结算/从未存在，不是失败）。
     */
    private suspend fun stopRunOp(run: ActiveRunState) {
        if (consoleState.stopInFlight) return // 双保险：按钮已禁用，这里兜住并发入口
        val host = hostSummary()
        if (host == null) {
            consoleState = consoleState.copy(
                stopInFlight = false,
                stopError = "宿主摘要未接线（Application 未实现 HostSummary）",
            )
            return
        }
        consoleState = consoleState.copy(stopInFlight = true, stopError = null, stopNotice = null)
        val notice = try {
            val stopped = host.stopRun(run.runId)
            if (stopped) "已请求停止 #${run.runId}（停止成败见控制台与在途表）"
            else "#${run.runId} 已不在途（此前已结算或从未存在）"
        } catch (t: Throwable) {
            consoleState = consoleState.copy(
                stopInFlight = false,
                stopError = t.message ?: t.javaClass.simpleName,
            )
            return
        }
        reloadConsole()
        consoleState = consoleState.copy(stopNotice = notice, stopInFlight = false)
    }

    private fun hostSummary(): HostSummary? = application as? HostSummary

    /** 四个页签。`label`（页签条两字短名）与全称（顶栏标题）分开——窄屏塞不下全称。 */
    enum class Tab(val short: String) {
        HOME("首屏"),
        TASKS("任务"),
        CONSOLE("控制台"),
        CAPABILITIES("能力"),
    }

    private companion object {
        /** 控制台单批上限：够一屏翻阅，拉满时 `pageFull` 提示续拉。 */
        const val CONSOLE_PAGE = 256
    }
}

/** 主题三档轮转：跟随系统 → 浅 → 深 → 回跟随系统。 */
private fun ThemeMode.next(): ThemeMode = when (this) {
    ThemeMode.SYSTEM -> ThemeMode.LIGHT
    ThemeMode.LIGHT -> ThemeMode.DARK
    ThemeMode.DARK -> ThemeMode.SYSTEM
}

/** 当前档位在顶栏上的那一句（用户看得懂，不说枚举名）。 */
private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "主题：跟随系统"
    ThemeMode.LIGHT -> "主题：浅色"
    ThemeMode.DARK -> "主题：深色"
}

/**
 * 外壳：内容 + 主题行 + 底部页签条。
 *
 * 各屏自己画顶栏（它们各有各的副标题与行尾动作，如任务中心的「登记/刷新」），
 * 这里不套一层顶栏 —— 套了每屏就被塞一个重复的标题栏，反而失去"四屏顶栏长得一样"
 * 的一致性来源（同一种排版组件被四屏用同一个约定调用，比被套在一个壳里更可控）。
 *
 * 主题切换放**页签条之上的一条细行**而不是顶栏右侧：顶栏右侧四屏各有各的动作
 * （刷新 / 登记+刷新 / 刷新 / 刷新），插一个共同动作进去就得改四屏的签名；
 * 而主题是一条**全局**设置，放在离页签更近的位置（随手可及、不抢顶栏）更合适。
 * 一行只 28dp，不喧宾夺主。
 */
@Composable
private fun MainShell(
    pagerState: PagerState,
    onSelectTab: (Int) -> Unit,
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(ThemeColors.background)) {
        Box(Modifier.weight(1f)) {
            content(Modifier.fillMaxSize())
        }
        Row(
            Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionBarAction(themeMode.label(), onCycleTheme)
        }
        TabBar(
            tabs = MainActivity.Tab.entries.map {
                // 刻意**不挂页签徽标**：徽标在 TG 里是"未读"语义，
                // 与本仓的"在途 / 漏投 / 未结算"三种账都不是一回事 ——
                // 混上去等于造出第四种读法。计数一律在各自屏内说。
                TabItem(label = it.short, badge = null)
            },
            page = pagerState.currentPage,
            // 跟手：pager 的连续位置 = currentPage + currentPageOffsetFraction
            // （偏移在滚向下一页时为正，故两者相加在世界坐标里是连续的）。
            pageOffsetFraction = pagerState.currentPageOffsetFraction,
            onSelect = onSelectTab,
        )
    }
}
