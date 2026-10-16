package com.autoscript.ui.overlay

import com.autoscript.ui.state.ActiveRunState
import com.autoscript.ui.state.ConsoleLineState

/**
 * 执行日志悬浮窗的**纯状态**（2026-10-13 用户令；JVM 可测，不碰 Android）。
 *
 * 悬浮窗回答四个问题："看谁的输出"（[boundRunId]，由 [bind] 绑定 —— **绑定不随
 * 结算解除**：用户令「执行完不要自动清理输出」）、"看到哪"（[watermarkSeq]，
 * 清空按钮只推水位）、"多大"（[fullscreen] 紧凑/全屏切换）、"摆在哪"（[x]/[y]，
 * 拖动改它，见 [movedTo]）。
 *
 * **为什么绑定走"归属匹配"而不是调用方传 runId**：`runScriptFile`/`runTaskNow`
 * 那两条链**不回 runId**（挂起到结算只回 Unit）—— 调用方在开窗那一刻根本不知道
 * 号是多少。开窗只带 `(projectId, relPath)`，随后每次拉取 `activeRuns`（现值现取）
 * 按归属找到"这次触发起来的那条 run"（同归属取最大 runId = 最近一次）。
 * 定时任务在后台自己跑起来时**不调 open**（用户令：后台不弹窗），故误绑窗口不存在 ——
 * 窗开着的时候同归属的新 run 只可能是用户刚点的那一次（池容量默认 1，串行）。
 *
 * **快速脚本的兜底补绑**（2026-10-15 用户令「有时点执行日志不显示在悬浮窗上」）：
 * 起跑即结算的脚本整个生命周期可以落在两轮轮询（400ms）之间 —— 在途表一次都没
 * 见过它，从开窗起一次都没绑上过。此时 [bind] 收一个从行里摘出的 [fallbackRunId]
 * 补绑（详见 [bind] 的 KDoc），窗口仍能画刚跑完的输出，而不是永远停在「未开始」。
 */
data class LogWindowState(
    val projectId: String,
    val relPath: String,
    /** 清空水位：≤ 它的一律不画（**不删文件** —— 详情页重开全文仍在；只进不退）。 */
    val watermarkSeq: Long = 0L,
    /** 紧凑/全屏（系统宿主 updateViewLayout，in-app 宿主改 Box 尺寸）。 */
    val fullscreen: Boolean = false,
    /** 绑定的 run（null = 这次执行还没进在途表 / 还没跑起来过；**结算不在此列**）。 */
    val boundRunId: Long? = null,
    /** 绑定那条 run 此刻冻没冻住（暂停按钮的图标与可用态就看它）。 */
    val paused: Boolean = false,
    /**
     * 绑定那条 run 此刻还在不在途（false = 已结算：跑完/被停/崩溃）。
     *
     * 结算**不解绑**（用户令「脚本执行完不要自动清理输出」），窗口继续画刚跑完的
     * 输出 —— 于是「还在跑」与「跑完了」必须另有一处说得清：**第一颗钮**（在途画暂停、
     * 已结算画重跑）与它的可用态以本字段为判据（2026-10-15 用户令后标题不再缀后缀，
     * 见 [titleText]）。
     */
    val running: Boolean = false,
    /**
     * 紧凑态窗左上角（**dp**，相对宿主坐标系：系统窗 = 屏幕、in-app 窗 = 应用内容区）。
     *
     * 为什么位置住状态里而不是各宿主自己记：两个宿主画的是同一个窗，切换宿主
     * （授权前后）不该把它挪回原点；拖动也只有一份事实源 —— 宿主按它落位，
     * 手势按它算目标（见 [movedTo]）。
     */
    val x: Float = 0f,
    val y: Float = 0f,
    /**
     * 底部输入框里正在敲的那一行（2026-10-16 用户令：悬浮窗最下面放输入框 + 蓝色箭头）。
     *
     * **为什么草稿住状态里而不是内容层 `remember`**：两个宿主画的是**同一个窗**
     * （系统离屏 `ComposeView` 与 in-app 兜底窗），切宿主不该把敲了一半的一行弄丢；
     * 而内容层是**无状态**的（全由参数进，见 `LogWindowContent` 的 KDoc）——
     * 草稿放进去就等于"系统窗那次 `setContent` 重喂会清空输入框"。
     *
     * 送出去之后由调用方清空（[LogWindowController.sendInput] 成功才清）：失败还留着，
     * 用户不必重敲 —— 与"失败不假装送到"同一条纪律。
     */
    val inputDraft: String = "",
) {
    /**
     * 窗标题（**单一归一化点**）：`/$projectId/$relPath` —— 用户口径的
     * `/data/test.js` 就是这个形状（relPath 是项目内路径，首段不是项目名，
     * 见 `ScriptLogRead` 的 KDoc；`/` 前缀是显示形态，落盘/索引一律不用它）。
     *
     * **不缀「· 已结束」**（2026-10-15 用户令「不需要在悬浮窗路径栏那里加已结束三个字」）。
     * 曾缀过（结算不解绑那批加的，理由是把「跑完了」与「还在跑」在标题上分开）——
     * 用户不要它：路径栏就是**路径**，状态由第一颗钮自己说（跑完 = 重跑图标、
     * 在途 = 暂停图标，那颗钮本来就是三态的事实源），标题再缀一遍是重复且占宽。
     * 「已结算」这个事实仍留在状态里（[running]，暂停钮的可用态与
     * 「本次执行没有输出」那句仍读它），只是不再上标题。
     */
    val titleText: String get() = "/$projectId/$relPath"

    /**
     * 这次执行的扩展名（文件类型图标的判据；取最后一段最后一个点之后，小写）。
     *
     * **`substringAfterLast('/')` 不带第二参数**（缺省 = 整串）：传 `""` 会让没有斜杠的
     * 路径整个变成空串（`main.js` 于是"没有扩展名"）—— `ScriptRunnability` 踩过同一个坑。
     */
    val ext: String get() = relPath
        .substringAfterLast('/')
        .substringAfterLast('.', "")
        .lowercase()

    /**
     * 在途表现值 → 绑定/更新（每次 `reloadConsole` 后调用）。
     *
     * - 同归属（projectId + scriptPath 都对上）取最大 runId = 最近一次触发；
     * - 窗开着但表里没有同归属 → **不落 null 覆盖当前绑定**（2026-10-15 用户令
     *   「脚本执行完不要自动清理输出」）：在途表是"正在跑的"这一句，run 结算离开
     *   在途表是**执行完成**不是「没有这回事」—— 一结算就把窗口切回「未开始」空态，
     *   等于把刚跑完的日志在用户眼前自动清掉。窗口保持绑在最后一条同归属 run 上，
     *   输出照常可见；只有一种情况落空态：**从开窗起一次都没绑上过**（启动失败/
     *   门禁拒 —— 失败 toast 已经说了原因，窗再自关等于抢话，故留空态手动关）；
     * - [fallbackRunId]：快速脚本的兜底（用户令「有时点执行日志不显示在悬浮窗上」）。
     *   起跑即结算的脚本整个生命周期可以落在两轮轮询（400ms）之间 —— 在途表从开窗起
     *   一次都没见过它，上面那层"结算不解绑"救不到（它**从没绑上过**）。此时从行里摘
     *   出的归属（调用方现算，见 `MainActivity` 轮询处）**补绑**：仍为 null（没绑上也没
     *   补位）才落空态。行摘的 risk 受「池容量默认 1 串行 + 窗只开给用户刚点的那一次」
     *   约束 —— 归属不会错，最多是"跑了 A 又紧跟跑 B、A 的行先到"把 B 的窗先指向 A，
     *   下一轮同归属在途 B 现身后立刻换回（误绑窗口只存在一帧，且输出仍如实按行归属画）。
     *   **它不被当作"跑起来过"**：[running]/[paused] 都只看在途表（match），补绑号
     *   running 恒 false —— 第一颗钮画重跑、暂停钮置灰，画的是"刚跑完"不是"正在跑"；
     *   **归属过滤是硬要求**（2026-10-16 修）：调用方给的这个号必须已经按
     *   `projectId` + `relPath` 过滤过（见 `fallbackRunIdFor`）—— 此前调用方取的是
     *   全库最新非引擎外行，窗刚开时会被补到**上一次别的脚本**的输出上，
     *   用户读到的是「它跑了上一次那个脚本」。本函数**不替调用方补这道过滤**：
     *   它只看得到 runId，看不到归属（行里没有归属字段）；
     * - paused 随绑定 run 同步（run 结算消失后 paused 回 false，不留僵尸态）。
     */
    fun bind(activeRuns: List<ActiveRunState>, fallbackRunId: Long? = null): LogWindowState {
        val match = activeRuns
            .filter { it.projectId == projectId && it.scriptPath == relPath }
            .maxByOrNull { it.runId }
        // keep = 已绑过的那条；match 为 null（结算/未起跑）时保留它 —— **结算不解绑**。
        // paused 只看 match：keep 若还在表里它就必然被 match 选中（同一套归属过滤，
        // 取最大），故不存在"keep 在表里而 match 为 null"的第三态。
        val keep = boundRunId
        return copy(
            // 补绑只在「从开窗起一次都没绑上过」时生效：keep 非空（绑过）就不碰 ——
            // 已绑的那条再快也不能被行里摘出的号顶掉（同一归属重复触发时行按实际归属画）。
            // **0 不作补位**（引擎外哨兵，防御性兜底：调用方已滤、这里再滤一道，
            // 0 落进来会把窗绑到"引擎外行"上，标题会说谎）。
            boundRunId = match?.runId ?: keep ?: fallbackRunId?.takeIf { it != 0L },
            running = match != null,
            paused = match?.paused == true,
        )
    }

    /**
     * 按位移挪一格（[dx]/[dy] = 这一次的位移增量，dp；**屏幕上的真实位移**）。
     *
     * 为什么收"位移"而不是"目标坐标"：手势层逐帧拿到的就是增量
     * （`PointerInputChange.positionChange()`），交给状态层相加即目标 —— 计算收在
     * 这一处，两个宿主的手势层只报增量、不各自维护"按下点在哪"。
     *
     * **夹取**（[boundsWidth]/[boundsHeight] 非空时）：窗不许被拖出可视区。夹取后
     * 手指继续外推时窗贴边不动，手指回退即跟回 —— 这是"拖丢了找不回来"的唯一防线
     * （窗外没有第二次机会把标题栏拖回来）。边界未知（null）则不夹：不假装知道屏幕多大。
     */
    fun movedTo(dx: Float, dy: Float, boundsWidth: Float? = null, boundsHeight: Float? = null): LogWindowState {
        val maxX = boundsWidth?.let { maxOf(0f, it - COMPACT_WIDTH_DP) }
        val maxY = boundsHeight?.let { maxOf(0f, it - COMPACT_HEIGHT_DP) }
        return copy(
            x = if (maxX == null) x + dx else (x + dx).coerceIn(0f, maxX),
            y = if (maxY == null) y + dy else (y + dy).coerceIn(0f, maxY),
        )
    }

    /**
     * 可见行：绑定 run 的行 + 水位之上（清空按钮推水位后，旧行不画、新行照进）。
     *
     * 未绑定（boundRunId null）→ 空表（空态由内容层画，不在这里编一行假的）。
     */
    fun visible(lines: List<ConsoleLineState>): List<ConsoleLineState> {
        val bound = boundRunId ?: return emptyList()
        return lines.filter { it.runId == bound && it.seq > watermarkSeq }
    }

    companion object {
        /**
         * 紧凑尺寸（**dp 数**，日志窗是"盯着看"的面，不是阅读器 —— 默认不大）。
         *
         * 为什么是裸 `Float` 而不是 `Dp`：本文件是纯状态层（JVM 可测，同包 `LogWindowStateTest`
         * 跑在 `ui` 的单测门上），**不引 Compose 类型**；`Dp` 由两个宿主各自 `340.dp` 包一层
         * —— 数字只有这一份，`Dp` 的包装是呈现层的事。
         *
         * 住这里而不是宿主里：尺寸既是宿主的窗口参数，也是**夹取边界**的一半
         * （[movedTo] 要知道窗多宽才知道能拖多远），两个宿主共用同一份。
         */
        const val COMPACT_WIDTH_DP: Float = 340f
        const val COMPACT_HEIGHT_DP: Float = 420f
    }
}
