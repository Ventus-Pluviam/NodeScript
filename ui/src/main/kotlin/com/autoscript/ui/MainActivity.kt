package com.autoscript.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.host.TaskRegistration
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.LocalBarAction
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.TabItem
import com.autoscript.ui.components.TabBar
import com.autoscript.ui.components.ToastAction
import com.autoscript.ui.components.ToastHost
import com.autoscript.ui.components.rememberToastAction
import com.autoscript.ui.screens.ProjectScreen
import com.autoscript.ui.screens.SettingsScreen
import com.autoscript.ui.screens.ConsoleScreen
import com.autoscript.ui.screens.TaskCenterScreen
import com.autoscript.ui.state.CapabilityCenterState
import com.autoscript.ui.state.ActiveRunState
import com.autoscript.ui.state.ConsoleState
import com.autoscript.ui.state.HomeState
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.ProjectState
import com.autoscript.ui.state.RegistrationForm
import com.autoscript.ui.state.TaskCenterState
import com.autoscript.ui.state.TaskRowState
import com.autoscript.ui.theme.Theme
import com.autoscript.ui.theme.ThemeMode
import com.autoscript.ui.theme.isDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 启动入口（launcher，manifest 在本模块，随库合并进 :app）。
 *
 * 接线方式（§6「UI 拆独立模块」的落点）：不 import `:app` 的任何类型 ——
 * 把 `application` 现转 `as? HostSummary`（`:domain` 读口，`AppShellApplication`
 * 实现），未实现即 `HomeState.UNWIRED` / `CapabilityCenterState.NOT_LOADED` 如实显示。
 *
 * 四个页签：首屏（壳/保活/漏投）、任务中心（§8.6 排期 + §8.5 档案/恢复账）、
 * 控制台（§7.3 游标拉取 + 在途执行）、设置（§9.5 三态权限账，TG 设置页版式）。刷新时机分两种，**不能混**：
 * - 首屏状态是**同步**读（`shellSummary()`）：`onCreate` 首读 + 每次 `onResume` 重读 +
 *   冷启后一条**有界**的重问（见 [HomeRetryEffect]）；
 * - 能力态/任务态/控制台都是**挂起**的（`capabilityCenter()` 每次现问系统，含 root 探测的
 *   IO 切换；`taskCenter()` 要读两个持久寄存器；`console(seq, max)` 是游标增量拉取）：
 *   由 `LaunchedEffect(resumeTick, tab)` 驱动 —— 回前台、或切到该页签时重取一次。
 *   控制台尤其依赖这条：行是**累积**的，游标只进不退（见 `ConsoleState`）。
 *   这样用户从系统设置页授完权回来，看到的是**刚问过**的结论，而不是离开时那份缓存
 *   （后者正是"授权了但界面还说没授权"的来源）。
 *
 * **外壳（Telegram 式）**：四屏装进 pager 横划切页，各自的顶栏由 `ActionBar` / `ScaffoldScreen`
 * 统一（四屏顶栏长得一样靠的是同一种排版组件被同一个约定调用），最下面的页签条由外壳
 * 一处画（`MainShell`）—— 页签条是全局唯一的一条，不能跟着页里的内容一起滑走。
 * 主题档位（跟随系统/浅/深）**不挂顶栏**（批 24 起）：TG 顶栏右侧没有全局开关格，
 * 它收在项目页 ⋮ 菜单里（`themeSwitchLabel` 下发那一格的目标模式文案）。
 *
 * 这里也是本模块唯一直接持有 [HostSummary] 的类：各屏只收纯状态 DTO，
 * 因此它们各自可 JVM 测（见 `HomeStateTest`/`CapabilityCenterStateTest` 等）。
 */
class MainActivity : ComponentActivity() {

    /** 首屏状态：compose 可观察单槽（Activity 持有，配置变更随重建重读，无跨进程共享诉求）。 */
    private var homeState: HomeState by mutableStateOf(HomeState.UNWIRED)

    /** 项目页（文件列表）状态（同上；挂起读口，由页签切换/回前台驱动）。 */
    private var projectState: ProjectState by mutableStateOf(ProjectState.NOT_LOADED)

    /** 设置页的状态（同上；数据面仍是能力快照）。 */
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
        // 边到边（§6）：**显式开**，而不是等 Android 15 因为 targetSdk 35 替我们开 ——
        // 两条渲染路径必须是同一条，否则"只在新系统上暴露的版式问题"在老机器上永远看不见
        // （本仓的开发机与云手机都还在 13/14）。两条系统栏一律透明：状态栏那一条归顶栏、
        // 导航栏那一条归底栏，各自把自己铺到屏幕边缘（见 ActionBar/TabBar 的 inset）。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        homeState = HomeState.read(hostSummary())
        setContent {
            val scope = rememberCoroutineScope()
            val pagerState = rememberPagerState(pageCount = { Tab.entries.size })
            // 浮层口（批 44）：**一处**建、一处挂（[MainShell] 里那个 ToastHost），
            // 四屏的复制回执与操作/停止回执都经 `LocalToast` 落到它上面 ——
            // 此前那些回执是各屏列表里的一行，弹一条就把内容往下推一次。
            val toast = rememberToastAction()
            // 系统栏图标的明暗跟**本 App 的主题档位**走，不是跟系统深色开关走：
            // 用户在顶栏把主题切成浅色、而系统还是深色时，状态栏图标必须转深色，
            // 否则白底上画一排白图标 = 看不见。`enableEdgeToEdge` 的 auto 只认系统档位，
            // 这里每次重组按当前档位覆盖一次。
            val dark = themeMode.isDark()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            Theme(mode = themeMode) {
                // 主题档位不再挂顶栏（2026-10-03 批 24）：TG 顶栏右侧没有全局开关格，
                // 四屏顶栏只放本屏动作 —— 主题切换收进各处自己的菜单/设置面。
                MainShell(
                    pagerState = pagerState,
                    // 点页签 = 让 pager 自己滑过去。**不直接改状态**：pager 的滚动位置是
                    // 唯一事实来源，页签条与重读都从它派生，绕过去就又会漂移。
                    onSelectTab = { scope.launch { pagerState.animateScrollToPage(it) } },
                    toast = toast,
                ) { shellModifier ->
                        // 四屏装进 **HorizontalPager**：这是 TG 主页签的做法
                        // （`MainTabsActivity extends ViewPagerActivity`），换来两件事 ——
                        // ① 点页签是**横向滑动**过去，不是淡入淡出；② 内容可以**横划切页**。
                        HorizontalPager(
                            state = pagerState,
                            modifier = shellModifier,
                            // 邻页预组合：点页签滑动时邻页已经在了，不会划到一半才现画。
                            beyondViewportPageCount = 1,
                            // **禁横划**（批 25）：屏幕里横划是各屏自己的手势域（列表回弹、
                            // 未来 Dochirō 式侧滑菜单），pager 抢掉它们就全是误切页。
                            // 切页只走页签点按（onSelectTab 的 animateScrollToPage）。
                            userScrollEnabled = false,
                        ) { current ->
                            // 四屏收 **Modifier**（自己那份布局意图）而不是 pager 的修饰符：
                            // pager 的修饰符是它自己的（滚动/裁剪/尺寸），发给页内容等于
                            // 把同一份约束套两层。
                            when (Tab.entries[current]) {
                                Tab.HOME -> ProjectScreen(
                                    state = projectState,
                                    onSwitchTheme = { themeMode = themeMode.next(dark) },
                                    // 菜单项写**目标模式**（TG 的日夜项同款）：
                                    // 冷启缺省跟随系统，此时按"当下是不是深色"定文案。
                                    themeSwitchLabel = themeSwitchLabel(dark),
                                    onCreate = { projectId, name, isFolder ->
                                        scope.launch { createEntryOp(projectId, name, isFolder) }
                                    },
                                    onSortChange = { sort, reversed ->
                                        projectState = projectState.copy(sort = sort, reversed = reversed, opError = null, opNotice = null)
                                    },
                                    modifier = Modifier,
                                )
                                Tab.TASKS -> TaskCenterScreen(
                                    state = taskState,
                                    console = consoleState,
                                    onRunNow = { task -> scope.launch { runTaskNowOp(task) } },
                                    onCancel = { task -> scope.launch { cancelTaskOp(task) } },
                                    onRegister = { form -> scope.launch { registerTaskOp(form) } },
                                    onStopRun = { run -> scope.launch { stopRunOp(run) } },
                                    modifier = Modifier,
                                )
                                Tab.CONSOLE -> ConsoleScreen(
                                    state = consoleState,
                                    onRefresh = { reloadConsole() },
                                    onStopRun = { run -> scope.launch { stopRunOp(run) } },
                                    modifier = Modifier,
                                )
                                Tab.SETTINGS -> SettingsScreen(
                                    state = capabilityState,
                                    onRefresh = { reloadCapabilities() },
                                    onOpenSettings = { hostSummary()?.openCapabilitySettings(it) },
                                    modifier = Modifier,
                                )
                        }
                    }
                }
            // 键里带页签：切到本页签本身就该现取，而不是显示上次离开时的快照。
            // 用 **settledPage** 而不是 currentPage：横划跨多页时 currentPage 会途经
            // 中间每一页，那样划一次会连读三遍；settledPage 只在停稳后变一次。
            // 重读的触发权只在这两条（回前台/切页签）与手动刷新手里 —— 读失败不会
            // 反过来改 resumeTick 形成自激（见 reloadCapabilities）。
            // 冷启那几秒：装配在 IO 域异步完成，onCreate 的首读大概率赶在它前面。
            HomeRetryEffect(state = { homeState }) { homeState = HomeState.read(hostSummary()) }
            TabReloadEffect(resumeTick, pagerState) { tab ->
                when (tab) {
                    Tab.HOME -> reloadProjectFiles()
                    // 任务屏现在也画在途执行（控制台的运行列表）：切到本页签两侧都现取，
                    // 否则运行中那组会停在离开时的快照上（与"切页签即现取"同一条纪律）。
                    Tab.TASKS -> { reloadTasks(); reloadConsole() }
                    Tab.CONSOLE -> reloadConsole()
                    Tab.SETTINGS -> reloadCapabilities()
                }
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
    /**
     * 现取脚本文件清单（项目页；挂起；只写 [projectState]）。
     *
     * 与 [reloadTasks] 同构的三落点，差异一处：读口未接线**也不冒充空目录** ——
     * 留 `NOT_LOADED`（"还没读到"），因为"读成功且没有文件"与"根本没读到"是两句
     * 不同的话（后者装配完成后重取就会变，前者不会）。
     */
    private suspend fun reloadProjectFiles() {
        val host = hostSummary()
        if (host == null) {
            projectState = ProjectState.NOT_LOADED
            return
        }
        projectState = try {
            ProjectState.of(
                snapshot = host.scriptFiles(),
                nowMillis = System.currentTimeMillis(),
                // 排序/回执是用户的呈现偏好与刚才的操作结论，重读不重置
                // （回执在刷新**之后**盖上去会自相矛盾，见 performTaskOp 同一条）。
                previous = projectState.takeIf { it.load is LoadState.Loaded },
            )
        } catch (t: Throwable) {
            ProjectState.failed(t)
        }
    }

    /**
     * 新建文件/文件夹（项目页 FAB 操作面）。
     *
     * 与 [performTaskOp] 同一条纪律的三落点：未接线 → opError；抛（名字非法/撞名）
     * → opError 原文、**不清清单**；成功 → opNotice 回执 + 现取一次（新条目要出现在
     * 列表里，回执在刷新之后盖上去）。成功时收掉创建对话框（[ProjectState.creating]）。
     */
    private suspend fun createEntryOp(projectId: String, name: String, isFolder: Boolean) {
        val host = hostSummary()
        if (host == null) {
            projectState = projectState.copy(
                creating = null,
                opError = "宿主摘要未接线（Application 未实现 HostSummary）",
            )
            return
        }
        try {
            host.createEntry(projectId, name, isFolder)
        } catch (t: Throwable) {
            projectState = projectState.copy(
                creating = null,
                opError = t.message ?: t.javaClass.simpleName,
            )
            return
        }
        reloadProjectFiles()
        val kind = if (isFolder) "文件夹" else "文件"
        projectState = projectState.copy(
            creating = null,
            opNotice = "已新建$kind「$name」",
        )
    }

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
    private suspend fun runTaskNowOp(task: TaskRowState) {
        // 挂起目标先落账：那一行的播放钮要画成"转圈的开口弧"（其余行不受影响）。
        taskState = taskState.copy(opTargetTaskId = task.id)
        try {
            performTaskOp { host ->
                host.runTaskNow(task.id)
                if (task.once) {
                    "已执行「${task.name}」并出册（一次性任务；执行成败见控制台）"
                } else {
                    "已触发「${task.name}」（执行成败见控制台）"
                }
            }
        } finally {
            taskState = taskState.copy(opTargetTaskId = null)
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

    /**
     * 四个页签。`short`（页签条两字短名）与全称（顶栏标题）分开 —— 窄屏塞不下全称；
     * `glyph` 是页签条上那个画出来的图标（见 `Glyphs.kt`）。
     */
    enum class Tab(val short: String, val glyph: GlyphKind) {
        HOME("项目", GlyphKind.HOME),
        TASKS("任务", GlyphKind.TASKS),
        CONSOLE("管理", GlyphKind.CONSOLE),
        SETTINGS("设置", GlyphKind.SETTINGS),
    }

    private companion object {
        /** 控制台单批上限：够一屏翻阅，拉满时 `pageFull` 提示续拉。 */
        const val CONSOLE_PAGE = 256
    }
}

/**
 * 主题档位切换（TG 的日/夜同款：两态对翻 —— 再点一次回原档）。
 * [ThemeMode.SYSTEM] 只在**冷启**当缺省（跟随系统），用户一切换就落 LIGHT/DARK 两档。
 *
 * **判据取"当下实际明暗"而不是枚举名**：冷启缺省是 [ThemeMode.SYSTEM]，它本身不含明暗。
 * 按枚举名写（`SYSTEM -> DARK`）会在"系统是深色"时让菜单写着"日间模式"、点下去却仍是深色
 * —— 一次点了没反应的切换。按 [isDark] 取反则三档归一：SYSTEM 时切到系统明暗的对面，
 * LIGHT/DARK 时就是两态对翻。
 */
private fun ThemeMode.next(isDark: Boolean): ThemeMode =
    if (isDark) ThemeMode.LIGHT else ThemeMode.DARK

/**
 * ⋮ 菜单那一格的文案 = **点它切到的那一档**，不是当前档。
 *
 * TG 的日夜项就是这个口径（`DialogsActivity`：`isCurrentThemeDark ? SwitchThemeToDay
 * : SwitchThemeToNight`，`strings.xml` 里两句分别是 "Day Mode" / "Night Mode"）——
 * 菜单项写"点了会变成什么"，比写"现在是什么"少一次心算。
 *
 * 判据取**当下实际明暗**（[isDark]）而不是枚举名：冷启缺省是 [ThemeMode.SYSTEM]，
 * 它本身不含明暗，只有问过系统才知道该写哪句。
 */
private fun themeSwitchLabel(isDark: Boolean): String =
    if (isDark) "日间模式" else "夜间模式"

/**
 * 外壳：内容（四屏的 pager）+ 底部页签条。
 *
 * 各屏自己画顶栏（它们各有各的副标题与行尾动作，如任务中心的「登记/刷新」），
 * 这里不套一层顶栏 —— 套了每屏就被塞一个重复的标题栏，反而失去"四屏顶栏长得一样"
 * 的一致性来源（同一种排版组件被四屏用同一个约定调用，比被套在一个壳里更可控）。
 *
 * **本函数不读 pager 的滚动位置**（这一点是硬约束，不是风格）：`pagerState` 只往下
 * 传给页签条。这里若读一次 `currentPageOffsetFraction`，横划的每一帧都会把本函数
 * 连同它下面的 pager 子树重组一遍 —— 四个屏各自带着自己的列表子树，
 * 那是"切页卡死"的根因（2026-10-02 实测）。页签条自己在**它那一层**读
 * `currentPage`（每翻一页变一次），跟手的观感由颜色动画补完。
 *
 * 底栏是 TG 的**悬浮胶囊**（2026-10-03 批 21 起）：不再占版面的一行，而是与内容
 * 同层、盖在内容之上 —— 所以 Column 不再需要给自己铺底色（每屏自己铺），也不用
 * 给 pager 让出高度。主题切换曾经是页签条之上的一条 28dp 细行，已改挂各屏顶栏
 * （见 [LocalBarAction]）。
 *
 * **浮层（toast）也挂在这一层**（批 44）：提示是"浮在内容之上、自己消失"的一条，
 * 宿主只有这一处 —— 四屏的回执经 [LocalToast] 落上来，各屏不必自己摆位置，
 * 也不必再往列表里插行（这正是批 44 要修的病）。
 */
@Composable
private fun MainShell(
    pagerState: PagerState,
    onSelectTab: (Int) -> Unit,
    toast: ToastAction,
    content: @Composable (Modifier) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // pager 占满整个屏高（**不留**底栏的那份）：底栏改成 TG 的悬浮胶囊后它不再
        // 是"占一行的一块版面"，而是浮在内容之上的一条 —— 与内容同层（Box），内容
        // 滚动时会从胶囊底下穿过（TG 同款：会话列表从底栏下面滚过去）。
        Box(Modifier.weight(1f)) {
            // 浮层宿主包住内容（而不是并列摆一条）：`LocalToast` 要供到四屏里面去。
            // 位置让出悬浮胶囊与导航栏 —— 提示贴在胶囊**上方**，不压住页签。
            ToastHost(
                action = toast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = TabBarBottomClearance()),
            ) {
                content(Modifier.fillMaxSize())
            }
            // 胶囊盖在内容之上：后画的在上层。它自己的 8dp 外边距让四周露出内容。
            TabBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                // 四格恒为「项目 / 任务 / 管理 / 设置」（`MainActivity.Tab`）。**不挂徽标**：
                // 徽标在 TG 里是"未读"语义，与本仓的"在途 / 漏投 / 未结算"三种账都不是
                // 一回事，混上去等于造出第四种读法 —— 计数一律在各自屏内说。`TabItem`
                // 因此只有 label + glyph 两个字段，没有"留着将来用"的空槽位。
                tabs = MainActivity.Tab.entries.map {
                    TabItem(label = it.short, glyph = it.glyph)
                },
                pagerState = pagerState,
                onSelect = onSelectTab,
            )
        }
    }
}

/** 冷启重问的间隔（见 [HomeRetryEffect]）。 */
private const val HOME_RETRY_INTERVAL_MILLIS = 500L

/**
 * 冷启重问的次数上限（10 × 500ms ≈ 5s：够装配跑完；真失败就以红字收尾，不无限等）。
 *
 * 与 [HOME_RETRY_INTERVAL_MILLIS] 放在文件级而不是 `MainActivity` 的伴生对象里：
 * 读它们的是文件级的 [HomeRetryEffect]，伴生对象的 `private` 成员出了类就看不见。
 */
private const val HOME_RETRY_ATTEMPTS = 10

/**
 * 冷启重问：壳还没就绪就过一会儿再问一次，**有界**。
 *
 * 为什么需要（2026-10-02 真机实测）：装配在 IO 域异步完成，而 `onCreate` 的首读
 * 大概率赶在它前面 —— 那一刻首屏显示「壳未就绪（装配中或失败）」与「保活未生效」
 * 两行红字，而首屏的读口只在 `onCreate` / `onResume` / 手动刷新三处被调，
 * **它不会自己变绿**：用户装完 App 一打开看到的就是"这 App 坏了"。
 *
 * 两条自我约束，免得把"重试"变成"粉饰"：
 * - **有界**：问满 [HOME_RETRY_ATTEMPTS] 次就停 —— 真失败照样以红字收尾，
 *   只是不再把过渡态（装配中）当终态显示；
 * - **接线与否另说**：[HomeState.summaryWired] 为 false 是"宿主没实现读口"，
 *   再问多少次都是这个答案，立即停（否则白等一轮）。
 *
 * @param state 现读当前状态（读的是 Activity 上那个可观察单槽）。
 * @param onReload 重问一次（由调用方给，本函数不碰 `hostSummary()`）。
 */
@Composable
private fun HomeRetryEffect(state: () -> HomeState, onReload: () -> Unit) {
    LaunchedEffect(Unit) {
        repeat(HOME_RETRY_ATTEMPTS) {
            val now = state()
            if (now.shellReady || !now.summaryWired) return@LaunchedEffect
            delay(HOME_RETRY_INTERVAL_MILLIS)
            onReload()
        }
    }
}

/**
 * 「切到本页签就现取一次」的驱动（回前台、或停稳到某一页）。
 *
 * 单开一个小 Composable 不是洁癖：`pagerState.settledPage` 是在**组合里**读的，
 * 谁读谁就在它变化时重组。写在 `setContent` 顶层 = 每次停稳都把整个外壳（连同 pager）
 * 重组一遍；收在这里，变的只有这个空壳。重读本身仍是挂起的（`onSettled` 是 suspend）。
 */
@Composable
private fun TabReloadEffect(
    resumeTick: Int,
    pagerState: PagerState,
    onSettled: suspend (MainActivity.Tab) -> Unit,
) {
    LaunchedEffect(resumeTick, pagerState.settledPage) {
        onSettled(MainActivity.Tab.entries[pagerState.settledPage])
    }
}
