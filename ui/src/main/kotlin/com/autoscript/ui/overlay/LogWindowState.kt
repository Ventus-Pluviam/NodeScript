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
     * 输出 —— 于是「还在跑」与「跑完了」必须另有一处说得清：[titleText] 的
     * 「· 已结束」后缀、暂停钮的诚实回执都以本字段为判据。
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
) {
    /**
     * 窗标题（**单一归一化点**）：`/$projectId/$relPath` —— 用户口径的
     * `/data/test.js` 就是这个形状（relPath 是项目内路径，首段不是项目名，
     * 见 `ScriptLogRead` 的 KDoc；`/` 前缀是显示形态，落盘/索引一律不用它）。
     *
     * 结算不解绑后（[running] = false 且绑过），标题缀「· 已结束」：窗口还开着、
     * 日志还在，但不把「已经跑完了」说成「还在跑」。**还没绑上过**（boundRunId
     * null，窗刚开、执行还没起来）不缀 —— 那是"未开始"，不是"已结束"。
     */
    val titleText: String get() = if (running || boundRunId == null) title else "$title · 已结束"

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
     * - paused 随绑定 run 同步（run 结算消失后 paused 回 false，不留僵尸态）。
     */
    fun bind(activeRuns: List<ActiveRunState>): LogWindowState {
        val match = activeRuns
            .filter { it.projectId == projectId && it.scriptPath == relPath }
            .maxByOrNull { it.runId }
        // keep = 已绑过的那条；match 为 null（结算/未起跑）时保留它 —— **结算不解绑**。
        // paused 只看 match：keep 若还在表里它就必然被 match 选中（同一套归属过滤，
        // 取最大），故不存在"keep 在表里而 match 为 null"的第三态。
        val keep = boundRunId
        return copy(
            boundRunId = match?.runId ?: keep,
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
