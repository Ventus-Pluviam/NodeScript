package com.autoscript.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.autoscript.domain.automation.ScreenConsentHolder
import com.autoscript.domain.automation.ScreenConsentHost
import com.autoscript.domain.automation.ScreenConsentInbox
import com.autoscript.domain.automation.ScreenConsentOutcome
import com.autoscript.domain.automation.ScreenConsentRequests
import com.autoscript.domain.editor.SyntaxHighlighter
import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.host.TaskRegistration
import com.autoscript.ui.components.EaseInOutQuad
import com.autoscript.ui.components.EditorHighlightHost
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.LocalBarAction
import com.autoscript.ui.components.LocalEditorHighlightHost
import com.autoscript.ui.components.LocalTabBarHidden
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.TabItem
import com.autoscript.ui.components.TabBar
import com.autoscript.ui.components.ToastAction
import com.autoscript.ui.components.ToastHost
import com.autoscript.ui.components.rememberToastAction
import com.autoscript.ui.screens.ManagementScreen
import com.autoscript.ui.screens.LogManagementScreen
import com.autoscript.ui.screens.AuditScreen
import com.autoscript.ui.screens.NpmScreen
import com.autoscript.ui.screens.ProjectScreen
import com.autoscript.ui.screens.RegistryScreen
import com.autoscript.ui.screens.ScriptEnvScreen
import com.autoscript.ui.screens.SettingsScreen
import com.autoscript.ui.screens.ConsoleScreen
import com.autoscript.ui.screens.TaskCenterScreen
import com.autoscript.ui.state.CapabilityCenterState
import com.autoscript.ui.state.ActiveRunState
import com.autoscript.ui.state.ConsoleCmdState
import com.autoscript.ui.state.ConsoleState
import com.autoscript.ui.state.filterAudit
import com.autoscript.ui.state.loadAudit
import com.autoscript.ui.state.reclaimNpmCacheOp
import com.autoscript.ui.state.pollInstallEvents
import com.autoscript.ui.state.pollInstallWhileRunning
import com.autoscript.ui.state.pollConsoleWhileRunning
import com.autoscript.ui.state.removeInstalled
import com.autoscript.ui.state.submitInstall
import com.autoscript.ui.state.runNpmMaintenanceOp
import com.autoscript.ui.state.loadConsoleCmd
import com.autoscript.ui.state.runConsoleCmd
import com.autoscript.ui.state.selectConsoleProject
import com.autoscript.ui.state.HomeState
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.AuditState
import com.autoscript.ui.state.NpmState
import com.autoscript.ui.state.TaskLogState
import com.autoscript.ui.state.ProjectState
import com.autoscript.ui.state.RegistryState
import com.autoscript.ui.state.RegistrationForm
import com.autoscript.ui.state.ScriptEnvState
import com.autoscript.ui.state.addScriptEnv
import com.autoscript.ui.state.loadRegistry
import com.autoscript.ui.state.loadScriptEnv
import com.autoscript.ui.state.resetRegistry
import com.autoscript.ui.state.saveRegistry
import com.autoscript.ui.state.removeScriptEnv
import com.autoscript.ui.state.TaskCenterState
import com.autoscript.ui.state.TaskRowState
import com.autoscript.ui.state.ThemeReveal
import com.autoscript.ui.theme.DarkColors
import com.autoscript.ui.theme.LightColors
import com.autoscript.ui.theme.Theme
import com.autoscript.ui.theme.ThemeMode
import com.autoscript.ui.theme.isDark
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 启动入口（launcher，manifest 在本模块，随库合并进 :app）。
 *
 * 接线方式（§6「UI 拆独立模块」的落点）：不 import `:app` 的任何类型 ——
 * 把 `application` 现转 `as? HostSummary`（`:domain` 读口，`AppShellApplication`
 * 实现），未实现即 `HomeState.UNWIRED` / `CapabilityCenterState.NOT_LOADED` 如实显示。
 *
 * 四个页签：首屏（壳/保活/漏投）、任务中心（§8.6 排期 + §8.5 档案/恢复账）、
 * 管理面板（控制台与日志管理为子页，§7.3 游标拉取 + 在途执行；日志管理 = 系统日志 + 任务日志）、设置（§9.5 三态权限账，TG 设置页版式）。刷新时机分两种，**不能混**：
 * - 首屏状态是**同步**读（`shellSummary()`）：`onCreate` 首读 + 每次 `onResume` 重读 +
 *   冷启后一条**有界**的重问（见 [HomeRetryEffect]）；
 * - 能力态/任务态/控制台都是**挂起**的（`capabilityCenter()` 每次现问系统，含 root 探测的
 *   IO 切换；`taskCenter()` 要读两个持久寄存器；`console(seq, max)` 是游标增量拉取）：
 *   由 [TabReloadEffect] 驱动 —— 回前台、或切到该页签时重取一次；管理面板本身不读日志，
 *   打开控制台/日志管理才读，返回面板保留已读行。控制台行是**累积**的，游标只进不退（见 `ConsoleState`）。
 *   这样用户从系统设置页授完权回来，看到的是**刚问过**的结论，而不是离开时那份缓存
 *   （后者正是"授权了但界面还说没授权"的来源）。
 *
 * **外壳（Telegram 式）**：四屏装进 pager 横划切页，各自的顶栏由 `ActionBar` / `ScaffoldScreen`
 * 统一（四屏顶栏长得一样靠的是同一种排版组件被同一个约定调用），最下面的页签条由外壳
 * 一处画（`MainShell`）—— 页签条是全局唯一的一条，不能跟着页里的内容一起滑走。
 * 主题档位（跟随系统/浅/深）**不挂顶栏**（批 24 起）：TG 顶栏右侧没有全局开关格，
 * 它收在项目页与设置页的 ⋮ 菜单里（`themeSwitchLabel` 下发那一格的目标模式文案）。
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

    /**
     * 控制台（命令面）状态（§10.9 第 3 条）。
     *
     * 与 [consoleState] 是**两本账**：那本是宿主事件与脚本输出（现在画在日志管理页），
     * 这本是 npm 命令的输出环（画在控制台）。两者各有自己的游标，互不影响 ——
     * 合成一本会让"刷新日志"把命令输出也拉一遍、反之亦然。
     */
    private var consoleCmdState: ConsoleCmdState by mutableStateOf(ConsoleCmdState.NOT_LOADED)

    /** 任务日志（日志管理页第二个列表：全部项目的终态历史；读失败保留已读到的行）。 */
    private var taskLogState: TaskLogState by mutableStateOf(TaskLogState.NOT_LOADED)

    /**
     * 依赖管理页状态（依赖面板一个读口：现取一次算一份状态，拆多个读口就会
     * 各自取一次、两次结果可以互相矛盾）。
     */
    private var npmState: NpmState by mutableStateOf(NpmState.NOT_LOADED)

    /**
     * 环境变量页状态（批 82，§8.1）。读口**不依赖壳装配**（表住 `filesDir/.autojs`，
     * 与 `scriptFiles()` 同一条口径），故首帧就现读一次 —— 不等壳就绪那一档。
     */
    private var envState: ScriptEnvState by mutableStateOf(ScriptEnvState.NOT_LOADED)

    /**
     * 镜像源管理页状态（批 83，§10.9 第 8 条）。与 [envState] 同一条口径：读口不依赖
     * 壳装配（全局 `.npmrc` 住 `filesDir`），首帧就现读一次。
     */
    private var registryState: RegistryState by mutableStateOf(RegistryState.NOT_LOADED)

    /**
     * 审计页状态（批 85，§10.5-2）。与 [registryState] 同一条口径：读口不依赖壳装配
     * （`install-history.jsonl` 住 `filesDir`），首帧就现读一次。
     *
     * 与 [npmState] 是**两条读口**（那个答「此刻装了什么」，这个答「过去发生过什么」）——
     * 不合并的理由见 `PackageManagerFacade.history` 的 KDoc。
     */
    private var auditState: AuditState by mutableStateOf(AuditState.NOT_LOADED)

    // 「当前页签」不再是一个字段：它由 pager 的滚动位置派生（见 setContent 里的 pagerState）。
    // 存两份必然漂移 —— 手指划过去时字段说 A、pager 说 B。

    /** 主题档位（跟随系统/浅/深）。 */
    private var themeMode: ThemeMode by mutableStateOf(ThemeMode.SYSTEM)

    /** 每次 `onResume` +1：驱动 [LaunchedEffect] 重问系统（回前台即重读）。 */
    private var resumeTick: Int by mutableStateOf(0)

    /**
     * 投屏同意对话框的承载（§9.2）：`createScreenCaptureIntent()` 的结果只能经
     * `onActivityResult` 回来，而 `:app` 装配层没有 Activity —— 于是由本 Activity 实现
     * `:domain` 的 `ScreenConsentHost` 并在 [onCreate] 注册进 `ScreenConsentHolder`。
     *
     * **为什么挂在这一层**（而不是 `:app`）：`:app` 不许 import `:ui`（§6），
     * 而"问用户要一次投屏同意"这件事必须有界面 —— 缝在 `:domain`，实现只能落在这里。
     *
     * **结果一律经 [ScreenConsentRequests.deliver]**（不直接 complete 某个 deferred）：
     * 轮次判断、等待者撤销、迟到结果丢弃都在那个状态机里（可 JVM 测），这里只做搬运。
     */
    private val consentLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val outcome = ScreenConsentOutcome(launched = true, resultCode = result.resultCode, payload = result.data)
        // **先入待领口再交付等待者**：能力中心那次（没有等待者）的结果就是经这里留下的；
        // 两份都拿到也没关系 —— 待领口被下一次 `startCapturer` 领走时，设备层的
        // 「一次同意只换一条会话」会挡住第二份。
        ScreenConsentInbox.shared.offer(outcome)
        // 没有人等这一次（能力中心「去授权」）→ 上面那一行就是它的全部去处。
        ScreenConsentRequests.shared.deliver(outcome)
    }

    /** 脚本那次征询的轮次状态机（同一时刻至多一轮 —— 投屏是进程级单会话，见 §9.2）。 */
    private val consentRequests = ScreenConsentRequests.shared

    /** 主线程投递（`startActivityForResult` 只能在主线程调；征询可能从桥的 IO 协程进来）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 宿主实现：`ScreenConsentHolder` 只认这个形状（`:app` 那边读它）。 */
    private val consentHost = object : ScreenConsentHost {
        override suspend fun requestScreenConsent(): ScreenConsentOutcome =
            when (val outcome = awaitConsent()) {
                is ScreenConsentRequests.Outcome.Result -> outcome.outcome
                is ScreenConsentRequests.Outcome.Unavailable -> {
                    Log.w(TAG, "投屏同意征询没能走完：${outcome.reason}")
                    ScreenConsentOutcome.notLaunched(outcome.reason)
                }
            }

        override fun requestScreenConsentDetached(): Boolean {
            // 已有一轮在跑（对话框开着 / 结果还没回来）→ 不再弹第二个对话框。
            // 如实回 false（`GrantResult.Denied`：这次没能把用户送到授权页），
            // **不**编一个"已经拉起来了"。
            //
            // 调用方是 `:app` 的 `AndroidSettingsPageOpener.open(page): Boolean`（**同步**接口），
            // 而 `startActivityForResult` 只能在主线程调。两条路要分开走：
            // - **已经在主线程**：直接跑，不 post 也不等 —— post 出去的回调要等本方法让出
            //   主线程才可能执行，在这里阻塞就是**自己等自己**（必然超时，还白占主线程）；
            // - **在别的线程**（桥的 IO 协程）：post 到主线程跑，然后**有界**等一下
            //   "post 出去并跑完"（毫秒级），**不是**等对话框结果（那是用户的事）。
            if (Looper.myLooper() === Looper.getMainLooper()) return beginAndLaunch(null)
            val begun = CompletableDeferred<Boolean>()
            mainHandler.post { begun.complete(beginAndLaunch(null)) }
            return runBlocking { withTimeoutOrNull(DETACHED_LAUNCH_TIMEOUT_MILLIS) { begun.await() } ?: false }
        }
    }

    /**
     * 等一次系统同意（脚本那次征询）。轮次登记在本协程里做（**不是**在主线程的 launch 里）：
     * 等待者与轮次必须是同一份 —— 分开登记的话，取消发生在"登记完、还没 post 出去"之间时
     * 就撤不掉自己的等待者。
     */
    private suspend fun awaitConsent(): ScreenConsentRequests.Outcome =
        suspendCancellableCoroutine { cont ->
            val ticket = consentRequests.begin { cont.resume(it) }
            if (ticket == null) {
                // 已有一轮在跑：如实回"这次没能把用户送到授权页"，**不排队也不覆盖**
                // （覆盖会把前一个等待者永远挂住）。
                cont.resume(ScreenConsentRequests.Outcome.Unavailable("已有一次投屏授权征询在进行（对话框开着或结果未回）"))
                return@suspendCancellableCoroutine
            }
            // 等待者被撤销（脚本崩了/连接断了）：**只撤等待者，不清在途** ——
            // 对话框还在用户眼前，它的结果迟早要回来，在那之前不放新请求进来
            // （这正是"旧结果不会被错配给下一次执行"的机制）。
            cont.invokeOnCancellation { consentRequests.revoke(ticket) }
            // 切主线程拉起：本方法可能从桥的 IO 协程调进来（脚本 `startCapturer`），
            // 而 `startActivityForResult` 只能在主线程调。
            mainHandler.post { launchConsentDialog(ticket) }
        }

    /** 能力中心「去授权」那条：登记一轮（没有等待者，结果落待领口）再拉起。 */
    private fun beginAndLaunch(onResult: ((ScreenConsentRequests.Outcome) -> Unit)?): Boolean {
        val ticket = consentRequests.begin(onResult) ?: return false
        return launchConsentDialog(ticket)
    }

    /**
     * 拉起系统投屏同意对话框（**只许在主线程调**）。
     *
     * @return false = 拉不起（没有 MediaProjectionManager / 系统抛）—— 如实上报，
     *   不编一个假的成功。**每一条失败路径都结束本轮**：否则它会一直占着"在途"，
     *   此后所有征询都被挡掉。
     */
    // `ActivityResultLauncher.launch` 的失败面由系统给：没有投屏界面的 ROM 抛
    // `ActivityNotFoundException`，界面正在销毁时抛 `IllegalStateException`，
    // 部分厂商 ROM 还有自定义的 `RuntimeException` 子类。**这里一条都不能漏** ——
    // 漏掉的那条会让本轮征询永远停在"在途"，此后所有投屏请求都被 busy 挡掉。
    // 同文件既有基线条目对同一类调用是同一个口径。
    @Suppress("TooGenericExceptionCaught")
    private fun launchConsentDialog(ticket: ScreenConsentRequests.Ticket): Boolean {
        val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        if (manager == null) {
            consentRequests.fail(ticket, "本机没有 MediaProjectionManager（系统投屏服务缺失）")
            return false
        }
        return try {
            consentLauncher.launch(manager.createScreenCaptureIntent())
            true
        } catch (e: Exception) {
            // 系统没有这个界面 / Activity 已销毁：如实结束这一轮，不留下悬挂的等待者。
            consentRequests.fail(ticket, "系统投屏授权界面拉不起来（${e.javaClass.simpleName}）")
            false
        }
    }

    override fun onDestroy() {
        // 注销：没有存活界面就没人能问用户 —— `:app` 那边据此如实拒绝开会话（不假装问过了）。
        if (ScreenConsentHolder.host === consentHost) ScreenConsentHolder.host = null
        // 界面没了：叫醒还挂着的等待者（如实回"没能问用户"），清在途轮次。
        // **不把任何东西留进待领口** —— 销毁不是授权。
        consentRequests.abandon()
        super.onDestroy()
    }

    @OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec 在 foundation 1.7 仍是实验 API
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
        // 投屏同意对话框只能由 Activity 拉起 —— 在这里把宿主交给 `:domain` 的邮箱，
        // `:app` 装配层（没有 Activity）经它征询用户（§9.2）。注册早于任何脚本请求：
        // onCreate 在首帧之前跑完，而会话只可能在脚本执行后开。
        ScreenConsentHolder.host = consentHost
        homeState = HomeState.read(hostSummary())
        setContent {
            val scope = rememberCoroutineScope()
            val pagerState = rememberPagerState(pageCount = { Tab.entries.size })
            // 管理页的层级放在 pager 外：切页导致预组合被回收时不丢，配置重建也能恢复。
            // 日志与游标仍属于 consoleState，返回面板只关闭子页，不清读取结果。
            var consoleOpen by rememberSaveable { mutableStateOf(false) }
            val closeConsole = { consoleOpen = false }
            var logManagementOpen by rememberSaveable { mutableStateOf(false) }
            val closeLogManagement = { logManagementOpen = false }
            // 依赖管理子页（批 81）：与日志管理同一层级（pager 外，切页不丢）。
            var npmOpen by rememberSaveable { mutableStateOf(false) }
            val closeNpm = { npmOpen = false }
            // 三个子页开关的**唯一**读法：谁在前台是一个值，不是三个布尔的组合。
            // 顺序（控制台 → 依赖 → 日志）只写在这一处 —— 本页的路由、返回键让位、
            // 切页现取读的都是它，三处同序。
            // 环境变量子页（批 82）：同一层级、同一读法。
            var envOpen by rememberSaveable { mutableStateOf(false) }
            val closeEnv = { envOpen = false }
            // 镜像源管理子页（批 83）：同一层级、同一读法。
            var registryOpen by rememberSaveable { mutableStateOf(false) }
            val closeRegistry = { registryOpen = false }
            // 审计子页（批 85）：**从依赖管理页内进**（不是管理面板的第五项）——
            // 它记的就是依赖面那些操作，从面板直进会让人以为它是与依赖并列的另一件事。
            var auditOpen by rememberSaveable { mutableStateOf(false) }
            val closeAudit = { auditOpen = false }
            val subPage = when {
                consoleOpen -> ManagementPage.CONSOLE
                npmOpen -> ManagementPage.NPM
                envOpen -> ManagementPage.ENV
                registryOpen -> ManagementPage.REGISTRY
                auditOpen -> ManagementPage.AUDIT
                logManagementOpen -> ManagementPage.LOGS
                else -> null
            }
            // 浮层口（批 44）：**一处**建、一处挂（[MainShell] 里那个 ToastHost），
            // 四屏的复制回执与操作/停止回执都经 `LocalToast` 落到它上面 ——
            // 此前那些回执是各屏列表里的一行，弹一条就把内容往下推一次。
            val toast = rememberToastAction()
            // 系统栏图标的明暗跟**本 App 的主题档位**走，不是跟系统深色开关走：
            // 用户在顶栏把主题切成浅色、而系统还是深色时，状态栏图标必须转深色，
            // 否则白底上画一排白图标 = 看不见。`enableEdgeToEdge` 的 auto 只认系统档位，
            // 这里每次重组按当前档位覆盖一次。
            val dark = themeMode.isDark()
            // 主题切换的圆形揭示（抓帧 → 换主题 → 圆形裁剪）：整段接线收在一个
            // `remember…` 函数里 —— 它自带状态与协程，onCreate 已经贴着 detekt 的长函数线。
            val themeSwitch = rememberThemeSwitch(dark) { themeMode = themeMode.next(dark) }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            Theme(mode = themeMode) {
                // 主题档位不挂顶栏：TG 顶栏右侧没有全局开关格，
                // 四屏顶栏只放本屏动作 —— 主题切换收进各处自己的菜单/设置面。
                MainShell(
                    pagerState = pagerState,
                    // 点页签 = 让 pager 自己滑过去。**不直接改状态**：pager 的滚动位置是
                    // 唯一事实来源，页签条与重读都从它派生，绕过去就又会漂移。
                    onSelectTab = { scope.launch { pagerState.animateScrollToPage(it) } },
                    toast = toast,
                    modifier = themeSwitch.modifier,
                ) { shellModifier ->
                        // 页内滚动容器要用的「露出来」口径（光标入视等）：pager 自己换成
                        // [NoPagerBringIntoView]，页内容里再还原成这一份。
                        val pageBringIntoView = LocalBringIntoViewSpec.current
                        // 四屏装进 **HorizontalPager**：这是 TG 主页签的做法
                        // （`MainTabsActivity extends ViewPagerActivity`），换来两件事 ——
                        // ① 点页签是**横向滑动**过去，不是淡入淡出；② 内容可以**横划切页**。
                        CompositionLocalProvider(LocalBringIntoViewSpec provides NoPagerBringIntoView) {
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
                            CompositionLocalProvider(LocalBringIntoViewSpec provides pageBringIntoView) {
                            // 四屏收 **Modifier**（自己那份布局意图）而不是 pager 的修饰符：
                            // pager 的修饰符是它自己的（滚动/裁剪/尺寸），发给页内容等于
                            // 把同一份约束套两层。
                            when (Tab.entries[current]) {
                                Tab.HOME -> ProjectTab(
                                    scope = scope,
                                    themeSwitch = themeSwitch,
                                    dark = dark,
                                    active = pagerState.currentPage == Tab.HOME.ordinal,
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
                                // 管理页分两种形态：某个子页在前台，或者面板本身在前台
                                // （子屏那几档收在 [ManagementSubPageHost] 里 ——
                                // onCreate 已贴着 detekt 的长函数线）。
                                Tab.MANAGEMENT -> if (subPage == null) {
                                    ManagementScreen(
                                        onOpenConsole = { consoleOpen = true },
                                        onOpenLogManagement = { logManagementOpen = true },
                                        onOpenNpm = { npmOpen = true },
                                        onOpenEnv = { envOpen = true },
                                        onOpenRegistry = { registryOpen = true },
                                        modifier = Modifier,
                                    )
                                } else {
                                    ManagementSubPageHost(
                                        page = subPage,
                                        onCloseConsole = closeConsole,
                                        onCloseNpm = closeNpm,
                                        onCloseEnv = closeEnv,
                                        onCloseRegistry = closeRegistry,
                                        onCloseAudit = closeAudit,
                                        onOpenAudit = { auditOpen = true },
                                        onCloseLogManagement = closeLogManagement,
                                        scope = scope,
                                    )
                                }
                                Tab.SETTINGS -> SettingsScreen(
                                    state = capabilityState,
                                    onOpenSettings = { hostSummary()?.openCapabilitySettings(it) },
                                    // 与项目页 ⋮ 同一项：标签 = 目标模式（TG 日夜项同款口径）。
                                    themeSwitchLabel = themeSwitchLabel(dark),
                                    onSwitchTheme = themeSwitch.onSwitch,
                                    modifier = Modifier,
                                )
                        }
                            }
                    }
                        }
                }
            ManagementBackHandler(pagerState, subPage != null) {
                // 每个枚举都显式列出：留 `else` 会在加新子页时静默吞掉返回键
                // （新页按返回 = 把日志管理关了，而不是关自己）。
                when (subPage) {
                    ManagementPage.CONSOLE -> closeConsole()
                    ManagementPage.NPM -> closeNpm()
                    ManagementPage.ENV -> closeEnv()
                    ManagementPage.LOGS -> closeLogManagement()
                    ManagementPage.REGISTRY -> closeRegistry()
                    ManagementPage.AUDIT -> closeAudit()
                    // 面板本身不是子页：返回键让位给页签滑动（本 handler 只在子页开着时拦截）。
                    null -> Unit
                }
            }
            // 键里带页签和管理子页：进入控制台即现取，而不是显示上次离开时的快照。
            // 用 **settledPage** 而不是 currentPage：横划跨多页时 currentPage 会途经
            // 中间每一页，那样划一次会连读三遍；settledPage 只在停稳后变一次。
            // 重读由回前台/切页签/进入控制台与手动刷新驱动 —— 读失败不会
            // 反过来改 resumeTick 形成自激（见 reloadCapabilities）。
            // 冷启那几秒：装配在 IO 域异步完成，onCreate 的首读大概率赶在它前面。
            HomeRetryEffect(state = { homeState }) { homeState = HomeState.read(hostSummary()) }
            TabReloadEffect(resumeTick, pagerState, subPage) { tab ->
                when (tab) {
                    Tab.HOME -> reloadProjectFiles()
                    // 任务屏现在也画在途执行（控制台的运行列表）：切到本页签两侧都现取，
                    // 否则运行中那组会停在离开时的快照上（与"切页签即现取"同一条纪律）。
                    Tab.TASKS -> { reloadTasks(); reloadConsole() }
                    // 管理页按**在前台的那一个**子页现取，而不是"把所有开着的都读一遍"：
                    // 依赖面板要遍历 node_modules 算尺寸，日志管理要读档案 ——
                    // 在别的子页上白跑一遍是真金白银的 IO。面板本身（null）不读任何东西。
                    // 日志管理两个列表都要现取：系统日志是控制台游标增量，任务日志读档案。
                    Tab.MANAGEMENT -> when (subPage) {
                        ManagementPage.CONSOLE -> reloadConsoleCmd()
                        ManagementPage.NPM -> reloadNpm()
                        ManagementPage.ENV -> reloadEnv()
                        ManagementPage.REGISTRY -> reloadRegistry()
                        ManagementPage.AUDIT -> reloadAudit()
                        ManagementPage.LOGS -> { reloadConsole(); reloadTaskLog() }
                        null -> Unit
                    }
                    Tab.SETTINGS -> reloadCapabilities()
                }
                }
            }
        }
    }

    /**
     * 项目页（`Tab.HOME`）那一格。
     *
     * 抽出来只为让 [onCreate] 停在 detekt 的长函数线内。它要接的六个回调里五个是
     * 转发到本类的现取/操作（语义见各自 KDoc），真正属于组合的只有 [scope]
     * （新建条目要跑在外壳的域上，不能在子屏里 `rememberCoroutineScope()`）与
     * [themeSwitch] 那两颗 —— 编辑器语法高亮的宿主口只供到这里（编辑面在项目页里）。
     *
     * @param active 本页是不是 pager 当前停稳的那一页：预组合的邻页不拦截返回键。
     */
    @Composable
    private fun ProjectTab(scope: CoroutineScope, themeSwitch: ThemeSwitchWiring, dark: Boolean, active: Boolean) {
        CompositionLocalProvider(LocalEditorHighlightHost provides highlightHost) {
            ProjectScreen(
                state = projectState,
                active = active,
                onSwitchTheme = themeSwitch.onSwitch,
                // 菜单项写**目标模式**（TG 的日夜项同款）：
                // 冷启缺省跟随系统，此时按"当下是不是深色"定文案。
                themeSwitchLabel = themeSwitchLabel(dark),
                onCreate = { projectId, name, isFolder ->
                    scope.launch { createEntryOp(projectId, name, isFolder) }
                },
                // 点文件进编辑面：读/存都**不在这里落状态** —— 成败归编辑器自己显示
                // （清单没变），保存成功后才重读一次清单（大小/时刻变了）。
                onReadFile = { projectId, relPath -> readScriptFileOp(projectId, relPath) },
                onSaveFile = { projectId, relPath, content -> saveScriptFileOp(projectId, relPath, content) },
                onSortChange = { sort, reversed ->
                    projectState = projectState.copy(sort = sort, reversed = reversed, opError = null, opNotice = null)
                },
                modifier = Modifier,
            )
        }
    }

    /**
     * 管理页的子屏宿主：控制台 / 依赖管理 / 日志管理。
     *
     * 抽成成员函数有两个理由，都不是为了好看：
     * - [onCreate] 已贴着 detekt 的长函数线（三个子屏摊在 `setContent` 里就过线）；
     * - "子页在前台"与"面板在前台"本来就是两种形态 —— 面板是**入口清单**，
     *   子屏是**具体面**，放在同一个 `when` 里会读成四选一。
     *
     * 三份读数（[consoleState]/[npmState]/[taskLogState]）是 Activity 的字段，直接读；
     * 关闭回调与重操作要用的 [scope] 才走参数（**scope 必须由外壳传**：在这里
     * `rememberCoroutineScope()` 会让正在跑的安装/停止操作随子页离开组合而被取消）。
     */
    @Composable
    private fun ManagementSubPageHost(
        page: ManagementPage,
        onCloseConsole: () -> Unit,
        onCloseNpm: () -> Unit,
        onCloseEnv: () -> Unit,
        onCloseRegistry: () -> Unit,
        onCloseAudit: () -> Unit,
        onOpenAudit: () -> Unit,
        onCloseLogManagement: () -> Unit,
        scope: CoroutineScope,
    ) {
        // 「盯着这一页时它自己动」（2026-10-10 批 90）：`runConsoleCmdOp`/`submitInstall`
        // 已经会在**提交之后**跟到跑完，但那条链只覆盖"是我按下去的那一次"——
        // 用户切走再切回来、或进页面时命令已经在跑，就没人拉了。这里补上：
        // 只要**这一页在前台**且**宿主说在跑**，就一直拉到它停。
        //
        // 键里带 `running`/`installing`：停下来的那一刻 key 变化，效应被取消并重启一次，
        // 而重启后的第一件事就是发现 `shouldContinue` 为 false、立刻返回 —— 不会自激。
        // 页不在前台（`page` 变了）时效应随之取消：**不在看的页面不该一直问宿主**。
        LaunchedEffect(page, consoleCmdState.running) {
            if (page == ManagementPage.CONSOLE && consoleCmdState.running) {
                consoleCmdState = pollConsoleWhileRunning(hostSummary(), consoleCmdState)
            }
        }
        LaunchedEffect(page, npmState.installing) {
            val projectId = npmState.selectedProjectId
            val host = hostSummary()
            // 四个条件拧在一个 `if` 里过不了 detekt 的 `ComplexCondition` 线，
            // 而拆开读起来也更接近它的意思：「不在这一页 / 没在装 / 不知道装哪个 / 宿主没接线」
            // 四件事各自是"不轮询"的理由，不是一句合起来的判断。
            if (page != ManagementPage.NPM || !npmState.installing) return@LaunchedEffect
            if (projectId == null || host == null) return@LaunchedEffect
            npmState = pollInstallWhileRunning(host, npmState, projectId)
        }
        when (page) {
            ManagementPage.CONSOLE -> ConsoleScreen(
                state = consoleCmdState,
                projects = consoleCmdState.projects,
                onRefresh = { reloadConsoleCmd() },
                onSelectProject = { scope.launch { consoleCmdState = selectConsoleProject(hostSummary(), consoleCmdState, it) } },
                onDraft = { consoleCmdState = consoleCmdState.copy(draft = it, opError = null, opNotice = null) },
                onRun = { scope.launch { runConsoleCmdOp() } },
                onBack = onCloseConsole,
                modifier = Modifier,
                history = consoleCmdState.history,
            )
            ManagementPage.NPM -> NpmScreen(
                state = npmState,
                onRefresh = { reloadNpm() },
                onSelectProject = { npmState = NpmState.withProject(npmState, it) },
                onMaintenance = { action -> scope.launch { npmState = runNpmMaintenanceOp(hostSummary(), npmState, action) } },
                onReclaimCache = { scope.launch { npmState = reclaimNpmCacheOp(hostSummary(), npmState) } },
                onOpenAudit = onOpenAudit,
                onInstallDraft = { npmState = npmState.copy(installDraft = it, opError = null, opNotice = null) },
                onToggleDev = { npmState = npmState.copy(installDev = it) },
                onToggleOffline = { npmState = npmState.copy(installOffline = it) },
                onSubmitInstall = { scope.launch { npmState = submitInstall(hostSummary(), npmState) } },
                onRemoveInstalled = { name -> scope.launch { npmState = removeInstalled(hostSummary(), npmState, name) } },
                onBack = onCloseNpm,
                modifier = Modifier,
            )
            ManagementPage.AUDIT -> AuditScreen(
                state = auditState,
                onRefresh = { reloadAudit() },
                onFilter = { auditState = filterAudit(auditState, it) },
                onBack = onCloseAudit,
                modifier = Modifier,
            )
            ManagementPage.ENV -> ScriptEnvScreen(
                state = envState,
                onRefresh = { reloadEnv() },
                onDraftKey = { envState = envState.copy(draftKey = it, opError = null, opNotice = null) },
                onDraftValue = { envState = envState.copy(draftValue = it) },
                onAdd = { scope.launch { envState = addScriptEnv(hostSummary(), envState) } },
                onRemove = { key -> scope.launch { envState = removeScriptEnv(hostSummary(), envState, key) } },
                onBack = onCloseEnv,
                modifier = Modifier,
            )
            ManagementPage.REGISTRY -> RegistryScreen(
                state = registryState,
                onRefresh = { reloadRegistry() },
                onDraft = { registryState = registryState.copy(draft = it, opError = null, opNotice = null) },
                onSave = { scope.launch { registryState = saveRegistry(hostSummary(), registryState) } },
                onReset = { scope.launch { registryState = resetRegistry(hostSummary(), registryState) } },
                onBack = onCloseRegistry,
                modifier = Modifier,
            )
            ManagementPage.LOGS -> LogManagementScreen(
                consoleState = consoleState,
                taskLogState = taskLogState,
                onRefreshConsole = { reloadConsole() },
                onRefreshTaskLog = { reloadTaskLog() },
                onStopRun = { run -> scope.launch { stopRunOp(run) } },
                onBack = onCloseLogManagement,
                modifier = Modifier,
            )
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
                // 排序/回执是用户的呈现偏好与刚才的操作结论，重读不重置
                // （回执在刷新**之后**盖上去会自相矛盾，见 performTaskOp 同一条）。
                previous = projectState.takeIf { it.load is LoadState.Loaded },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Exception) {
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Exception) {
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

    /**
     * 读一个脚本文件（项目页点文件进编辑面）。
     *
     * **不写 [projectState]**：读取的成败归编辑器自己显示（清单没有任何变化），
     * 宿主未接线照抛 —— 编辑器把原因显示在正文上方，而不是让列表替它报错。
     */
    private suspend fun readScriptFileOp(projectId: String, relPath: String): String {
        val host = hostSummary() ?: error("宿主摘要未接线（Application 未实现 HostSummary）")
        return host.readScriptFile(projectId, relPath)
    }

    /**
     * 保存一个脚本文件（编辑器「保存」）。
     *
     * 落盘成功后再重读一次清单：文件大小/修改时刻变了，列表上那两列要跟着变
     * （不然"刚存的内容没生效"会从列表上读出来）。**失败不重读**（清单没变），
     * 原文抛给编辑器显示。
     */
    private suspend fun saveScriptFileOp(projectId: String, relPath: String, content: String) {
        val host = hostSummary() ?: error("宿主摘要未接线（Application 未实现 HostSummary）")
        host.saveScriptFile(projectId, relPath, content)
        reloadProjectFiles()
    }

    /**
     * 现取脚本环境变量表（挂起；只写 [envState]）。
     *
     * 逻辑在 [loadScriptEnv]（`:ui` 的顶层函数）：那三段"拿读口算下一份状态"是纯的，
     * 放这里能用 `FakeHost` 直接测，而 `MainActivity` 在 JVM 单测里构造不出来。
     */
    private suspend fun reloadEnv() {
        envState = loadScriptEnv(hostSummary(), envState)
    }

    /**
     * 现取全局镜像源读数（挂起；只写 [registryState]）。
     *
     * 逻辑在 [loadRegistry]（`:ui` 顶层函数），理由与 [reloadEnv] 逐字相同。
     */
    private suspend fun reloadRegistry() {
        registryState = loadRegistry(hostSummary(), registryState)
    }

    /**
     * 现取审计史（挂起；只写 [auditState]）。
     *
     * 三落点与 [reloadNpm] 同构：未接线/壳未装配 → 失败态带原因（**不冒充**
     * 「你没做过任何操作」）；抛错 → 失败态保留已读到的那份；成功 → 全量覆盖
     * （宿主读数是权威，这份读口本来就是全量历史，不累积）。
     */
    private suspend fun reloadAudit() {
        auditState = loadAudit(hostSummary(), auditState)
    }

    /**
     * 现取依赖面板读数（挂起；只写 [npmState]）。
     *
     * 三落点不撒谎（与 [reloadTaskLog] 同构）：
     * - 读口未接线 / 壳未装配 → 失败态带原因（**不冒充**「一个项目都没有」）；
     * - 抛错 → 失败态**保留已读到的那份**（一次瞬时失败不该把依赖清单抹成空）；
     * - 成功 → 全量覆盖（宿主快照是权威，不累积）。
     *
     * 保留用户当前选中的项目：刷新把用户正在看的项目换掉，是"手一滑跳走了"的经典形态
     * （[NpmState.of] 的 previous 参数就是为这条）。
     */
    private suspend fun reloadNpm() {
        val host = hostSummary()
        val previous = npmState
        npmState = try {
            if (host == null) {
                NpmState.failed(IllegalStateException("宿主摘要未接线（Application 未实现 HostSummary）"), previous)
            } else {
                // 快照与**安装进度**同一次现取（§10.9 第 1 条，2026-10-09 批 87）：
                // `:ui` 没有常驻轮询循环，宿主又是入队即返回的，故「刷新」这颗按钮
                // 同时是阶段条的续拉入口 —— 分两次现取会让两个数字来自不同时刻。
                val refreshed = NpmState.of(host.npmSnapshot(), previous)
                if (refreshed.selectedProjectId != null) {
                    pollInstallEvents(host, refreshed, refreshed.selectedProjectId)
                } else {
                    refreshed
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Exception) {
            NpmState.failed(t, previous)
        }
    }

    /**
     * 现取任务日志（挂起；只写 [taskLogState]）。三落点不撒谎：未接线 → 失败态带原因
     * （**不冒充**「暂无记录」）；抛错 → 失败态**保留已读到的行**；成功 → 全量覆盖（档案是权威，不累积）。
     */
    private suspend fun reloadTaskLog() {
        val host = hostSummary()
        val previous = taskLogState
        taskLogState = try {
            if (host == null) {
                TaskLogState.failed(IllegalStateException("宿主摘要未接线"), previous)
            } else {
                TaskLogState.of(host.taskLog())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Exception) {
            TaskLogState.failed(t, previous)
        }
    }

    private suspend fun reloadCapabilities() {
        val host = hostSummary()
        if (host == null) {
            capabilityState = CapabilityCenterState.NOT_LOADED
            return
        }
        capabilityState = try {
            CapabilityCenterState.of(host.capabilityCenter())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Exception) {
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Exception) {
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            taskState = taskState.copy(opInFlight = false)
            throw e
        } catch (t: Exception) {
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
        val sinceSeq = consoleState.nextSeq
        consoleState = try {
            val added = host.console(sinceSeq = sinceSeq, maxLines = CONSOLE_PAGE)
            // 挂起期间另一份读取可能已返回：合并现值，不拿请求前的旧快照覆盖新日志。
            ConsoleState.of(
                previous = consoleState,
                added = added,
                nowMillis = System.currentTimeMillis(),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Exception) {
            ConsoleState.failed(t, consoleState)
        }
    }

    /**
     * 现取控制台（命令面）一轮（挂起；只写 [consoleCmdState]，不触发重读）。
     *
     * 三落点与 [reloadNpm] 同构：读口未接线 → 失败态带原因（**不冒充**「还没有输出」）；
     * 抛错 → `ConsoleCmdState.failed` **保留已读到的行与游标**（一次瞬时失败不该把用户
     * 刚看到的 npm 输出抹掉，游标清了下次还会重放）；成功 → 累积入列。
     *
     * 项目清单与输出在 [loadConsoleCmd] 里是**同一次现取**（理由见那条的 KDoc）。
     */
    private suspend fun reloadConsoleCmd() {
        consoleCmdState = loadConsoleCmd(hostSummary(), consoleCmdState)
    }

    /**
     * 执行控制台输入框里那行（挂起）。
     *
     * 先判后发再拉的三段都在 [runConsoleCmd] 里（判据与宿主侧同一份 —— 界面当场拒，
     * 不往返一趟才拿到同一句话）；本函数只负责挂起中把按钮禁用防连点。
     */
    private suspend fun runConsoleCmdOp() {
        if (consoleCmdState.opInFlight) return
        consoleCmdState = consoleCmdState.copy(opInFlight = true)
        try {
            consoleCmdState = runConsoleCmd(hostSummary(), consoleCmdState)
        } finally {
            consoleCmdState = consoleCmdState.copy(opInFlight = false)
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            consoleState = consoleState.copy(stopInFlight = false)
            throw e
        } catch (t: Exception) {
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
     * 编辑器语法高亮的宿主实现：编辑器只认 [EditorHighlightHost]，不碰 [HostSummary]。
     * 读口未接线时回 [SyntaxHighlighter.NONE]（纯文本编辑，不是错误）。
     */
    private val highlightHost = EditorHighlightHost { relPath ->
        hostSummary()?.createSyntaxHighlighter(relPath) ?: SyntaxHighlighter.NONE
    }

    /**
     * 四个页签。`short`（页签条两字短名）与全称（顶栏标题）分开 —— 窄屏塞不下全称；
     * `glyph` 是页签条上那个画出来的图标（见 `Glyphs.kt`）。
     */
    /** 管理页在前台的那个子页（面板本身不是子页，用 null 表示）。 */
    enum class ManagementPage { CONSOLE, NPM, ENV, LOGS, REGISTRY, AUDIT }

    enum class Tab(val short: String, val glyph: GlyphKind) {
        HOME("项目", GlyphKind.HOME),
        TASKS("任务", GlyphKind.TASKS),
        MANAGEMENT("管理", GlyphKind.CONSOLE),
        SETTINGS("设置", GlyphKind.SETTINGS),
    }

    private companion object {
        /** 控制台单批上限：够一屏翻阅，拉满时 `pageFull` 提示续拉。 */
        const val CONSOLE_PAGE = 256

        /** 日志 tag（投屏同意征询那几条）。 */
        const val TAG = "MainActivity"

        /**
         * 「去授权」那条同步接口等主线程 post 的上限（见 [ScreenConsentHost.requestScreenConsentDetached]）。
         *
         * 只覆盖"把 launch 投到主线程并跑完"这一步，正常是毫秒级；主线程被长任务占住时
         * 宁可如实回 false（这次没能把用户送到授权页），也不把调用方无限期挂住。
         */
        const val DETACHED_LAUNCH_TIMEOUT_MILLIS = 2_000L
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
 * 主题切换那两件套：⋮ 菜单要调的动作 + 外壳要挂的绘制修饰符。
 *
 * @property onSwitch 收**那颗 ⋮ 在根坐标里的中心**（见 `centerInRoot`）。
 * @property modifier 揭示的绘制修饰符；没有揭示在跑时它什么都不做（直接放行内容）。
 */
private class ThemeSwitchWiring(
    val onSwitch: (Offset) -> Unit,
    val modifier: Modifier,
)

/**
 * 主题切换的接线：**抓帧 → 换主题 → 从 ⋮ 长出一个圆**（TG 的日夜切换同款）。
 *
 * 做法是**先把新主题换上**，再把整块界面按一个不断长大的圆裁剪 —— 圆外露出的就是
 * [ThemeSwitchReveal.fromFrame]（切换前那一帧的原样界面）。反面做法（先画一个色圆再换
 * 主题）在圆里看到的是一块死色，不是"另一个模式"。
 *
 * **揭示的那个槽刻意不在组合里读**（只被 `onSwitch` 的协程与绘制闭包读）：它一变就重组
 * 的话，下面 `Theme(...)` 会跟着重组，而 `MaterialTheme` 的 colorScheme 是 **static**
 * CompositionLocal —— 一次主题切换会被放大成三趟"整棵界面重组"（置位一趟、清位一趟、
 * 换主题一趟）。收成 draw 期读之后只剩换主题那一趟（见 [drawThemeReveal]）。
 *
 * @param dark 当下是不是深色：决定这次往哪边切、以及圆外先铺哪一档的底色。
 * @param onFlip 真正翻档（由调用方写它自己那份状态 —— 这里不碰 `ThemeMode`）。
 */
@Composable
private fun rememberThemeSwitch(dark: Boolean, onFlip: () -> Unit): ThemeSwitchWiring {
    val scope = rememberCoroutineScope()
    val reveal = remember { mutableStateOf<ThemeSwitchReveal?>(null) }
    val view = LocalView.current
    val onSwitch: (Offset) -> Unit = { origin ->
        // 重入守卫（TG `DialogsActivity.switchingTheme` 的同一处）：揭示期间再点，
        // 两次动画会抢同一个裁剪进度，观感是圆抖一下再从头长。
        if (reveal.value == null) {
            val toDark = !dark
            val fromBackground = if (dark) DarkColors.background else LightColors.background
            scope.launch {
                // **先抓帧、再换主题**：抓的是"点之前"那一帧（旧主题的原样界面）——
                // 换主题的重组还没发生。抓帧是挂起的（PixelCopy 在 GPU 侧回读），
                // 这段等待里界面仍是旧主题，用户看不到中间态。
                val frame = captureFrame(view)
                if (reveal.value != null) return@launch // 等待期间又点了一次
                val active = ThemeSwitchReveal(
                    origin = origin,
                    fromBackground = fromBackground,
                    fromFrame = frame,
                    toDark = toDark,
                    // **初值 0**：揭示的第一帧画出来就是"圆还没长"，
                    // 不指望抢在第一次绘制前 snapTo（那个先后没有保证）。
                    progress = Animatable(0f),
                )
                reveal.value = active
                onFlip()
                active.progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(THEME_REVEAL_MILLIS, easing = EaseInOutQuad),
                )
                // 圆已经走完（转深色 = 盖满整屏 / 转浅色 = 收干净），
                // 此刻撤掉裁剪与旧底片都看不出来（不会闪一下）。
                reveal.value = null
            }
        }
    }
    // 揭示挂在**外壳这一层**（而不是某一块内容上）：圆要盖住整屏，页签胶囊与 toast
    // 也在这一层里，跟着一起被裁。**修饰符只建一次**：绘制闭包在 draw 期读
    // `reveal.value` 与动画进度，于是"开始/结束一次揭示"只让这一层重绘，不触发任何重组。
    val modifier = remember(reveal) {
        Modifier.drawWithContent {
            val active = reveal.value
            if (active == null) drawContent() else drawThemeReveal(active, active.progress.value)
        }
    }
    return ThemeSwitchWiring(onSwitch = onSwitch, modifier = modifier)
}

/**
 * 一次主题切换的圆形揭示（外壳持有到动画跑完为止）。
 *
 * 是 `class` 而不是 `data class`：里面挂着一个**每帧复用**的 [circle]（Path），
 * 数据类那份 equals/hashCode 会去比这个可变对象，比出来的结论没有意义。
 *
 * @property origin 圆心 —— 那颗 ⋮ 在**根坐标**里的中心（见 `centerInRoot`；菜单本体在
 *   独立 popup 窗口里，量不到被点那一行的坐标，故取锚点）。
 * @property fromBackground 揭示期间**旧主题那一侧**铺的底色 = 切换**前**那一档的
 *   `background`（哪一侧由 [toDark] 定，见 [ThemeReveal.oldFrameInside]）。
 * @property fromFrame 切换**前**那一帧的位图（[captureFrame]）—— 旧主题那一侧画的是
 *   **旧主题的原样界面**，不是一块纯色；抓不到时是 null，退化成 [fromBackground] 那块底。
 * @property toDark 这次是往深色切还是往浅色切：**两个方向的圆走法不同**（TG 同款），
 *   见 [ThemeReveal.circleFraction]。
 * @property progress 动画进度 0 → 1（**不是半径**：半径由 [toDark] 决定的方向映射出来）；
 *   **建的时候就是 0**（见 `switchTheme` 那处的注释）。
 */
private class ThemeSwitchReveal(
    val origin: Offset,
    val fromBackground: ComposeColor,
    val fromFrame: ImageBitmap?,
    val toDark: Boolean,
    val progress: Animatable<Float, AnimationVector1D>,
) {
    /**
     * 每帧那个圆。**复用同一个 Path**：揭示跑 60~120 帧，每帧新建一个 Path 就是每帧一次
     * 分配 + 一次 GC 压力 —— 400ms 的动画里这种小分配正是"卡一下"的来源之一。
     * 只在绘制线程上读写，没有并发。
     */
    val circle: Path = Path()
}

/**
 * 把 [view] 当前这一帧抓成位图（旧主题的"底片"）。
 *
 * **为什么非得抓帧**：圆形揭示要求圆外**还是旧主题的原样界面**。只把主题换掉再裁剪，
 * 圆外就只剩一块纯色 —— 列表整块消失再从圆里长回来，那不是 TG 那个效果。TG 也是先留一张
 * 旧主题的底片再揭示新主题（`LaunchActivity` 的 `needSetDayNightTheme`：
 * `getBitmapFromWindow(getWindow())` 抓帧 → `themeSwitchImageView.setImageBitmap(bitmap)`）。
 *
 * **走 PixelCopy 而不是软件重画**（与 TG 同一条路）：`view.draw(Canvas(bitmap))` 是让
 * **整棵视图树在 CPU 上重画一遍**，在点击那一刻同步跑完 —— 这是原先揭示开头那一顿的来源。
 * PixelCopy 只是把 GPU 已经画好的这一帧**回读**出来（API 26 起可用，本仓 minSdk = 26），
 * 挂在协程上等回调，不重画任何东西。
 *
 * **失败不致命，且逐级降级**：
 * 1. PixelCopy 成功 → 用回读的帧；
 * 2. 拿不到 Window / PixelCopy 报错（部分 ROM 对 Window 源支持不全）→ 退回软件重画；
 * 3. 软件重画也抛（OOM / 硬件层不给软件画布）→ 退回 null，绘制侧先铺的
 *    [ThemeSwitchReveal.fromBackground] 顶上来 —— 圆外从"原样界面"降级成"旧主题的底色"，
 *    而不会把一次换主题升级成崩溃。
 *
 * 所以这里从头到尾是"一次最好的努力"（`runCatching`），不是错误处理。
 */
private suspend fun captureFrame(view: View): ImageBitmap? {
    val window = view.context.findActivity()?.window
    // 尺寸取**窗口根视图**（TG 也是拿 `window.getDecorView()` 的宽高建位图）：PixelCopy 的源
    // 是窗口表面，位图与它同尺寸才不会缩放/裁切。`enableEdgeToEdge` 之后根视图与 ComposeView
    // 同尺寸，取哪个都一样；真有出入时按根视图来才是"这张图确实是那一帧"。
    val decor = window?.decorView
    val width = decor?.width?.takeIf { it > 0 } ?: view.width
    val height = decor?.height?.takeIf { it > 0 } ?: view.height
    if (width <= 0 || height <= 0) return null
    val bitmap = runCatching {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }.getOrNull() ?: return null
    if (window != null) {
        // 超时兜底：`PixelCopy.request` 在窗口正被销毁之类的情况下**可能一次回调都不来**，
        // 那会把这次揭示永远挂在"等抓帧"上（用户看到的是"点了没反应"）。等不到就当抓帧
        // 失败，走下面的退路 —— 宁可圆外是纯色，也不能让按钮失灵。
        val copied = runCatching {
            withTimeoutOrNull(FRAME_CAPTURE_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { cont ->
                    PixelCopy.request(window, bitmap, { result ->
                        cont.resume(result == PixelCopy.SUCCESS)
                    }, Handler(Looper.getMainLooper()))
                }
            }
        }.getOrNull()
        if (copied == true) return bitmap.asImageBitmap()
    }
    return runCatching {
        view.draw(Canvas(bitmap))
        bitmap.asImageBitmap()
    }.getOrNull()
}

/** 抓帧的等待上限（见 [captureFrame]）：超了就退化成纯色底，不让按钮失灵。 */
private const val FRAME_CAPTURE_TIMEOUT_MILLIS = 250L

/**
 * 悬浮底栏收起/展开的时长。
 *
 * 180ms：比主题揭示（[THEME_REVEAL_MILLIS]）短一档 —— 这是"让开位置"的过渡，不是主角。
 * 再长会让人觉得"进了编辑器之后底栏还在慢慢往下爬"。
 */
private const val TAB_BAR_HIDE_MILLIS = 180

/** 从任意一层 `ContextWrapper` 里找出宿主 Activity（PixelCopy 要它的 `window`）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 揭示时长：400ms（`anim.setDuration(400)`，TG 原值）。
 *
 * 名字走 SCREAMING_SNAKE（本文件 [HOME_RETRY_INTERVAL_MILLIS] 同款）：`:ui` 里那十几条
 * CamelCase 的 `…Millis` 常量是**基线里的存量债**，新写的按规则来，不再往上加。
 */
private const val THEME_REVEAL_MILLIS = 400

/**
 * 把当前内容按"从 [reveal] 圆心长出来的圆"裁剪后画出来（揭示的绘制侧）。
 *
 * 顺序是关键：**先换主题、再裁** —— 旧主题那一侧铺 [ThemeSwitchReveal.fromBackground]
 * （旧主题的底）+ 旧主题那一帧的原样界面，另一侧是**新主题的界面本身**。反面做法
 * （先画一个色圆再换主题）圆里只有一块死色，不是"另一个模式"。
 *
 * **两个方向共用这一段代码**，差别只有两处（都在 [ThemeReveal] 里，都是纯函数）：
 * - 圆半径的占比：[ThemeReveal.circleFraction]（转深色 0→1 长大；转浅色 1→0 缩回）；
 * - 旧底片铺哪一侧：[ThemeReveal.oldFrameInside]（转浅色在圆内，转深色在圆外）。
 *
 * **每帧的代价**：一个全屏矩形 + 一次位图绘制（只画它该在的那一侧）+ 一次带圆裁剪的
 * 内容重绘。进度与半径都在 **draw 期**读，动画每帧只重绘、不重组整棵界面。
 */
private fun ContentDrawScope.drawThemeReveal(reveal: ThemeSwitchReveal, progress: Float) {
    val radius = ThemeReveal.circleFraction(progress, reveal.toDark) * ThemeReveal.maxRadius(
        originX = reveal.origin.x,
        originY = reveal.origin.y,
        width = size.width,
        height = size.height,
    )
    reveal.circle.rewind()
    reveal.circle.addOval(Rect(center = reveal.origin, radius = radius))
    // **旧主题那一帧铺在哪一侧由方向定**（[ThemeReveal.oldFrameInside]）：转浅色时它缩在
    // **圆内**，转深色时留在**圆外**；新主题永远铺另一侧。于是两个方向的起点都是"整屏旧
    // 主题"、终点都是"整屏新主题" —— 这一条由 `ThemeRevealTest` 钉着（写反了动画会从
    // 新主题那一屏开始往回长，而中段看过去一样是个圆）。
    val oldInside = ThemeReveal.oldFrameInside(reveal.toDark)
    val oldClip = if (oldInside) ClipOp.Intersect else ClipOp.Difference
    val newClip = if (oldInside) ClipOp.Difference else ClipOp.Intersect
    // 两层：先铺旧主题的底色（抓帧失败时看到的就是它），再叠上旧主题那一帧的原样界面。
    // 底片只画它该在的那一侧（**Difference/Intersect** 各裁掉另一半）：另一侧马上要被新主题
    // 整块盖住，画了也是白画（1080×2400 的一层满屏填充，省下来的是实打实的每帧填充率）。
    drawRect(reveal.fromBackground)
    reveal.fromFrame?.let { frame -> clipPath(reveal.circle, clipOp = oldClip) { drawImage(frame) } }
    clipPath(reveal.circle, clipOp = newClip) { this@drawThemeReveal.drawContent() }
}

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
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    // 悬浮底栏的临时隐藏开关：整屏面（脚本文本编辑器）进来时自己写 true、退出写回
    // false。开关建在这一层是因为**只有外壳能同时"供"和"读"它**（见 LocalTabBarHidden）。
    val tabBarHidden = remember { mutableStateOf(false) }
    // 收起进度 0（在）→ 1（收起）。在 layer 块里读，所以这段动画**一帧都不重组**；
    // 只有 `tabBarHidden` 翻面时本函数重组一次（那是必要的，得知道往哪边跑）。
    val tabBarShift = remember { Animatable(0f) }
    LaunchedEffect(tabBarHidden.value) {
        tabBarShift.animateTo(
            targetValue = if (tabBarHidden.value) 1f else 0f,
            animationSpec = tween(TAB_BAR_HIDE_MILLIS, easing = EaseInOutQuad),
        )
    }
    Column(modifier.fillMaxSize()) {
        // pager 占满整个屏高（**不留**底栏的那份）：底栏改成 TG 的悬浮胶囊后它不再
        // 是"占一行的一块版面"，而是浮在内容之上的一条 —— 与内容同层（Box），内容
        // 滚动时会从胶囊底下穿过（TG 同款：会话列表从底栏下面滚过去）。
        Box(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalTabBarHidden provides tabBarHidden) {
                // 浮层宿主包住内容（而不是并列摆一条）：`LocalToast` 要供到四屏里面去。
                // 位置让出悬浮胶囊与导航栏 —— 提示贴在胶囊**上方**，不压住页签。
                // 它也在 provider 里面：底栏收起时提示跟着往下走（读的是同一份留白）。
                ToastHost(
                    action = toast,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = TabBarBottomClearance()),
                ) {
                    content(Modifier.fillMaxSize())
                }
                // 胶囊盖在内容之上：后画的在上层。它自己的 8dp 外边距让四周露出内容。
                // **收起/展开是滑出去而不是瞬间消失**（编辑器进来时那一下最显眼）。
                // 用 `graphicsLayer` 的位移 + 透明度，不用 `AnimatedVisibility`：
                // ① 这两个量在 **layer 块里**读，动画每帧只更新这一层，不重组底栏；
                // ② `AnimatedVisibility` 在这个 `Column { Box { … } }` 里会被解析到
                //    `ColumnScope` 那个重载上，当场编译不过（实测，不是风格问题）；
                // ③ 位移把**触摸区一起挪走**，收起后不会留一条看不见却点得到的胶囊。
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer {
                            translationY = tabBarShift.value * size.height
                            alpha = 1f - tabBarShift.value
                        },
                ) {
                    TabBar(
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
    }
}

/**
 * pager 这一层的「露出来」口径：**永远不滚**。
 *
 * 病根（2026-10-07 云手机复现）：编辑器聚焦后 `BasicTextField` 会请求把光标
 * bring-into-view，请求沿祖先链一路冒泡到 `HorizontalPager`。pager 缺省的
 * `PagerBringIntoViewSpec` 会把它当成「把这一页滚进来」，`userScrollEnabled = false`
 * 也拦不住（那只关手势）—— 于是 pager 被推向下一页（任务页），而项目页因
 * `beyondViewportPageCount = 1` 仍在组合里，编辑器没退场，底栏一直被它收着。
 * 本仓切页只走页签点按（`animateScrollToPage`），pager 不该响应任何入视请求。
 */
@OptIn(ExperimentalFoundationApi::class)
private object NoPagerBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
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
 * 管理子页（控制台 / 日志管理）的系统返回，和各自顶栏共用同一个关闭动作。
 *
 * pager 会预组合邻页：只看子页是否打开会在其他页签吞返回。可见且停稳在管理页才启用，
 * 切页动画期间也退让。单开 Composable 读 pager，避免每次滚动把整个外壳重组。
 */
@Composable
private fun ManagementBackHandler(pagerState: PagerState, subPageOpen: Boolean, onBack: () -> Unit) {
    val managementPage = MainActivity.Tab.MANAGEMENT.ordinal
    BackHandler(
        enabled = subPageOpen && !pagerState.isScrollInProgress &&
            pagerState.currentPage == managementPage && pagerState.settledPage == managementPage,
        onBack = onBack,
    )
}

/**
 * 「切到本页签就现取一次」的驱动（回前台、停稳到某一页、或打开管理页里的控制台）。
 *
 * 单开一个小 Composable 不是洁癖：`pagerState.settledPage` 是在**组合里**读的，
 * 谁读谁就在它变化时重组。写在 `setContent` 顶层 = 每次停稳都把整个外壳（连同 pager）
 * 重组一遍；收在这里，变的只有这个空壳。重读本身仍是挂起的（`onSettled` 是 suspend）。
 */
@Composable
private fun TabReloadEffect(
    resumeTick: Int,
    pagerState: PagerState,
    subPage: MainActivity.ManagementPage?,
    onSettled: suspend (MainActivity.Tab) -> Unit,
) {
    // 仅管理页消费子页键：切到别页时改层级，不应取消那一页正在进行的读取。
    val tab = MainActivity.Tab.entries[pagerState.settledPage]
    // 子页键必须进 key：三个子屏都是"进了才现取"，不进 key 就会出现
    // "点进去看到的是上次的快照 / 首帧的未读取"，而刷新按钮成了唯一的出路。
    val visibleSubPage = subPage?.takeIf { tab == MainActivity.Tab.MANAGEMENT }
    LaunchedEffect(resumeTick, tab, visibleSubPage) {
        onSettled(tab)
    }
}
