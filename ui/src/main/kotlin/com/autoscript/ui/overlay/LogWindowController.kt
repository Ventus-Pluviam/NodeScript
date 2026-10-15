package com.autoscript.ui.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.autoscript.domain.host.HostSummary
import com.autoscript.ui.state.ActiveRunState
import com.autoscript.ui.state.ConsoleLineState
import java.util.HashSet

/**
 * 执行日志悬浮窗的**控制器**（2026-10-13 用户令；Activity `remember` 一份）。
 *
 * 只管状态机，不管画：开（先开窗再跑 —— 跑起来之前窗里是空态）、关、
 * 清空（推水位，不删文件）、暂停/继续（问宿主，false 如实 toast）、绑定刷新。
 * 内容组合（`LogWindowContent`）由宿主层（`LogWindowHosts.kt`）构建 —— 回调里要
 * `scope.launch`（togglePause 是挂起的）与 clipboard，控制器自己没有协程域，
 * 不替调用方拿（故本文件零 Compose UI 依赖，只有运行时状态）。
 *
 * **开窗只带归属不带 runId**（见 `LogWindowState.bind` 的 KDoc：调用链不回 runId）。
 * 定时/后台触发**不调 [open]** —— 那条路由没有"用户在看"这件事，弹窗是打扰；
 * 日志照存（`onRunStarted` 不分触发源）。
 */
class LogWindowController(
    private val hostSummary: () -> HostSummary?,
    private val toast: (String) -> Unit,
    val systemHost: SystemLogWindowHost?,
) {
    /** null = 窗没开（系统窗与 in-app 窗都不画）。 */
    var state: LogWindowState? by mutableStateOf(null)
        private set

    /** 此刻走的是系统窗还是 in-app 回退（open 那一刻按权限定，开关期间不变）。 */
    var usingSystemWindow: Boolean by mutableStateOf(false)
        private set

    /**
     * 窗位置（dp）：紧凑态的左上角，**跨窗存活** —— 关掉再开不跳回原点
     * （用户刚把它挪到顺手的位置，下一次执行又弹回左上角是纯粹的骚扰）。
     *
     * 为什么住控制器而不是 [LogWindowState]：state 每次 `open` 都新建，而位置是
     * **用户的偏好**不是"这一次执行的事实"。开窗时把它灌进新 state（单一事实源
     * 仍在 state 里，宿主与手势只读那一份）。
     */
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    /**
     * 开窗（文件 ▶ / 立即执行触发；先开再跑 —— 跑起来之前窗里是「未开始」空态）。
     *
     * 已开着（上一条还没关）→ 替换绑定（旧窗直接切到新归属，不叠两层窗）。
     * 系统窗的首次 `show` 由 `SystemWindowSync` 效应接（state 非空即弹），
     * in-app 窗由 `LogWindowInAppHost` 画 state —— 本函数只落状态。
     */
    fun open(projectId: String, relPath: String) {
        // 沿用上一次的位置（跨窗存活，见 [lastX]）—— 换了执行不换地方。
        state = LogWindowState(projectId = projectId, relPath = relPath, x = lastX, y = lastY)
        usingSystemWindow = (systemHost != null) && (hostSummary()?.overlayAllowed() == true)
        if (!usingSystemWindow && systemHost != null) {
            // 有系统宿主但没权限：每次开窗说一次，不记"已说过" ——
            // 权限随时可能在设置里打开，"只说一次"会让用户错过已授权的事实。
            toast("未授予悬浮窗权限，日志窗显示在应用内；可在能力中心授权")
        }
    }

    /**
     * 拖动（**系统窗**的头部手势；位移 dp，相对按下那一刻）。
     *
     * 边界从系统宿主现取（屏幕可用区），夹取与坐标计算在状态层 —— 宿主只落位。
     * 拖动改的是 state，`SystemWindowSync` 会把新坐标重喂给窗口（绝对坐标）。
     *
     * [dx]/[dy] 是**屏幕坐标系**里的真实位移（手势层换算好的，见
     * `LogWindowContent.onDrag` 的 KDoc）—— 这里只管相加与夹取，不猜参照系。
     */
    fun drag(dx: Float, dy: Float) {
        val current = state ?: return
        val bounds = systemHost?.boundsDp() ?: (0f to 0f)
        val next = current.movedTo(
            dx,
            dy,
            bounds.first.takeIf { it > 0f },
            bounds.second.takeIf { it > 0f },
        )
        if (next.x == current.x && next.y == current.y) return
        state = next
        lastX = next.x
        lastY = next.y
        if (usingSystemWindow) systemHost?.moveTo(next.x, next.y)
    }

    /**
     * 拖动（**in-app 兜底窗**；位移 dp + 宿主量到的内容区边界 dp）。
     *
     * 与 [drag] 同一条分工（状态层算、宿主落位），差别只在边界来源：兜底窗的坐标系是
     * 应用内容区，只有它自己量得到。
     */
    fun dragInApp(dx: Float, dy: Float, boundsW: Float, boundsH: Float) {
        val current = state ?: return
        val next = current.movedTo(dx, dy, boundsW.takeIf { it > 0f }, boundsH.takeIf { it > 0f })
        if (next.x == current.x && next.y == current.y) return
        state = next
        lastX = next.x
        lastY = next.y
    }

    /** 关窗（系统窗撤 view，in-app 窗靠 state null 不画）。 */
    fun close() {
        state = null
        systemHost?.hide()
    }

    /**
     * 窗内日志行的**事实源**（`consoleState.lines` 的现值；调用方每次拉取后 [updateLines]）。
     *
     * 为什么行数据住控制器而不是"由调用方当参数喂进系统窗内容"（2026-10-14「点执行就闪退」）：
     * 参数喂进去的代价是**每喂一次就要重跑一次 `setContent`**（`SystemWindowSync` 的效应
     * 以它为键），而轮询 400ms 一轮、每轮 `ConsoleState.of` 都产出一个新的 `List` 实例 ——
     * 于是窗一开就每 400ms 重建一次离屏组合。住这里之后，窗内容**现读**这份状态，
     * 刷新走 Compose 重组，`setContent` 的执行次数 = 开窗次数。
     */
    var lines: List<ConsoleLineState> by mutableStateOf(emptyList())
        private set

    /** 行事实源刷新（调用方在 `reloadConsole` 之后推；见 [lines] 的 KDoc）。 */
    fun updateLines(next: List<ConsoleLineState>) {
        lines = next
    }

    /** 系统窗弹失败（运行时被拒）→ 落回 in-app（由 `SystemWindowSync` 调）。 */
    fun fallbackToInApp() {
        usingSystemWindow = false
        systemHost?.hide()
    }

    /**
     * 在途表现值 → 绑定（每次 `reloadConsole` 后调用；现值现取）。
     *
     * 结算**不解绑**（用户令「执行完不要自动清理输出」）：绑定 run 跑完离开在途表后
     * 窗口仍绑在它上面、输出照常可见（详见 `LogWindowState.bind` 的 KDoc）。
     *
     * [fallbackRunId] 是快速脚本的兜底补绑（用户令「有时点执行日志不显示在悬浮窗上」）：
     * 起跑即结算的脚本在途表一次都没见过 → 从行里摘出归属补绑。兜底补位号（结算为
     * false 的 runId）**不进 [settledRunIds]** —— 那只是"还没在途过"的补位，不是被
     * 摘走的事实。
     */
    fun bind(activeRuns: List<ActiveRunState>, fallbackRunId: Long? = null) {
        val current = state ?: return
        val before = current.boundRunId
        val next = current.bind(activeRuns, fallbackRunId)
        state = next
        // 被在途表摘走的号（结算/被停/崩溃）记进账：之后任何宿主读口按它查都是"不在途"
        // （走 `RuntimeController` 的都在途表里）—— 暂停按钮据此**静默早退**，不再把
        // 「已结算」翻译成「该执行不支持暂停」那句给用户看（2026-10-15 用户令）。
        //
        // 判据：**仍绑着（boundRunId 没变）但不再 running** —— 结算不解绑，正是这个
        // 形状：run 离开在途表时绑定保持、running 回 false。反例都不记：
        // - 换绑（boundRunId 变了）：暂停按钮只作用于**当前**绑定，旧号已不可达，
        //   记不记都无差别（且串行池下新 run 起跑前旧的必然先让位，见 `LogWindowState.bind`）；
        // - 兜底补位号（from null → fallback）：那只是"还没在途过"的补位，不是被摘走
        //   （且它 running 恒 false，暂停钮从画出来起就是置灰的，点不到）。
        if (before != null && next.boundRunId == before && !next.running) settledRunIds += before
    }

    /** 已结算（离开在途表）的 runId 集；[bind] 维护，只进不退（runId 不重用）。 */
    private val settledRunIds = HashSet<Long>()

    /**
     * 清空日志（**只推水位，不删文件**）：`watermarkSeq` 推到当前可见最大 seq ——
     * 窗里旧行消失、新行照进；详情页重开全文仍在（用户令"清空日志"是清窗，不是删档）。
     */
    fun clearLogs(visibleMaxSeq: Long) {
        val current = state ?: return
        if (visibleMaxSeq > current.watermarkSeq) {
            state = current.copy(watermarkSeq = visibleMaxSeq)
        }
    }

    /** 全屏切换（系统宿主 updateViewLayout，in-app 宿主读同一份 state 改尺寸）。 */
    fun toggleFullscreen() {
        val current = state ?: return
        val next = !current.fullscreen
        state = current.copy(fullscreen = next)
        if (usingSystemWindow) systemHost?.setFullscreen(next)
    }

    /**
     * 暂停/继续（悬浮窗第一颗钮；boundRunId null 时不问宿主 —— 没绑上就没有可停的东西）。
     *
     * **已结算（[settledRunIds] 里有它）→ 静默早退**：暂停钮的可用态按 `state.running`
     * 画，但轮询 400ms 一轮，用户点击可以落在"最后一轮还见它在途、这一下已结算"的窗口
     * 里 —— 按钮此刻仍可点，宿主一查就 false。把那个 false 翻译成「该执行不支持暂停」
     * 是撒谎（2026-10-15 用户令）：它已经**结束**了，不是"不支持"，结算本来就是暂停的
     * 终态。静默早退让按钮在这几帧里点了等于没点；下一轮 `running` 回 false、钮置灰，
     * 没有误导性的话术。
     *
     * false（冻不住/已结算，但**还没被在途表摘走**）→ toast 如实说，不假装成功。
     */
    suspend fun togglePause() {
        val current = state ?: return
        val runId = current.boundRunId ?: return
        if (runId in settledRunIds) return
        val host = hostSummary() ?: run {
            toast("宿主摘要未接线（Application 未实现 HostSummary）")
            return
        }
        try {
            val ok = if (current.paused) host.resumeRun(runId) else host.pauseRun(runId)
            if (!ok) {
                toast(if (current.paused) "恢复失败：该执行已结算或不支持" else "该执行不支持暂停（本设备冻不住该进程）")
            }
            // 成败都由下一次 bind 刷新 paused（现值现取，不在这里猜）。
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Exception) {
            toast("暂停失败：${t.message ?: t.javaClass.simpleName}")
        }
    }
}
