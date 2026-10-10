package com.autoscript.ui.state

import com.autoscript.domain.npm.NpmConsoleLine
import com.autoscript.domain.npm.NpmConsoleLineKind
import com.autoscript.domain.npm.NpmConsoleSnapshot
import com.autoscript.domain.npm.ShellConsoleMode
import com.autoscript.domain.npm.SequencedConsoleLine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 控制台（命令面）呈现态（纯数据，Compose 之外可 JVM 测；§10.9 第 3 条）。
 *
 * **控制台不是日志查看器**（2026-10-09 用户口径）：它是用来**执行命令**的面 ——
 * npm 子命令、以及装好的依赖提供的命令。日志（宿主事件与脚本输出）整体搬去
 * `LogManagementScreen`，本页从此与「系统日志」彻底分家。
 *
 * 三条纪律：
 * - **行是累积的**（[of] 把本批接在既有行后面）：与 [ConsoleState] 同款 —— 控制台是
 *   累计事实，刷新 = 增量拉取，不是重画；同一游标读两遍按 seq 去重，不重复入列。
 * - **读失败保留已读到的行与游标**（[failed]）：一次瞬时失败把用户刚看到的 npm 输出
 *   抹掉，比报错更糟；游标清了下次还会从环里最旧那行重放一遍。
 * - **换项目清行 + 游标归 0**（[withProject]）：seq 是**环内全局单调**的、`drain` 才按
 *   projectId 过滤，所以归 0 不是「重头开始」，而是「把这个项目还留在环里的那些行全取
 *   回来」—— 这正是想要的（换过去就看到它的历史）。沿用上一个项目的游标会**漏掉**
 *   seq 更小的那些行（它们对这个项目是新的，对游标却不是）。
 *
 * [running] 来自宿主（它持有句柄账），呈现层**不猜** —— 猜出来的「在跑」会让输入行在
 * 命令早就结束时仍然禁用。
 *
 * 时间戳格式化在这一层（[ConsoleCmdLineState.timeText]，`HH:mm:ss` —— 控制台行以秒为
 * 粒度）；时刻由 [of] 的参数注入，类内不读 `System.currentTimeMillis()`（可测 + 同帧一致）。
 */
data class ConsoleCmdState(
    val load: LoadState,
    /** 面板上正在看的那个项目（null = 一个项目都没有 / 还没读到）。 */
    val selectedProjectId: String? = null,
    /**
     * 可跑命令的项目清单（依赖面板那份全量快照的 `projects` 段）。
     *
     * 存下来而不是让屏幕自己去读：控制台的项目选择与输出**必须来自同一次现取** ——
     * 两次现取可以互相矛盾（选择器里还有的项目，输出那一次已经没有它了）。
     * 与 [NpmState] "两件事共用一个读口"同一条理由。
     */
    val projects: List<String> = emptyList(),
    val lines: List<ConsoleCmdLineState> = emptyList(),
    /** 拉取游标（只进不退；失败不清零；换项目归 0）。首读前为 0。 */
    val nextSeq: Long = 0L,
    /** 宿主说这个项目此刻有命令在跑（判据在宿主侧，见类 KDoc）。 */
    val running: Boolean = false,
    /** 环丢过包（`firstSeq > 发请求前那个游标 + 1`）—— 用户看到的不是全部，得知道。 */
    val gap: Boolean = false,
    /** 本批拉满，可能还有更新的行（措辞是「可能」，拉满不等于确实还有）。 */
    val pageFull: Boolean = false,
    /** 输入框里正在敲的那行（**不规整化**：回显要与他敲的一致）。 */
    val draft: String = "",
    /**
     * 命令历史（最近的在最前、已去重），来自宿主读口 `HostSummary.consoleHistory`。
     *
     * 与 [lines] 的差别是**环 vs 盘**：输出行活在宿主的环里（进程没了就没了），
     * 历史活在盘上（关掉控制台再进来还翻得到）。故它**不参与累积**——每次都是
     * 宿主那份的完整投影，不是"把新的接在旧的后面"。
     *
     * 换项目**清空**（[withProject] 不搬运它）：控制台的命令跑在某个项目上，
     * 在 A 项目敲的 `npm install axios` 翻到 B 项目去点，落的是 B 的 `node_modules`，
     * 而按钮上那行字一模一样。判据在宿主侧（历史按项目分开存），这里只是不保留旧值。
     */
    val history: List<String> = emptyList(),
    /**
     * 控制台**此刻**的特权模式（2026-10-09）。
     *
     * 它只改一件事：**裸首词是什么意思** —— [ShellConsoleMode.DEFAULT] 下裸首词 = npm bin
     * （装好的依赖提供的命令），[ShellConsoleMode.ROOT]/[ShellConsoleMode.ADB] 下裸首词 =
     * shell 命令。五个入口词（`npm`/`npx`/`su`/`shizuku`/`exit`）在任何模式下都优先，
     * 否则进了 root 模式就再也退不出来。
     *
     * **为什么是呈现层状态而不是宿主状态**：`su`/`shizuku` 改的是「用户接下来敲的这行
     * 怎么解析」，解析在界面侧先跑一遍（判据同一份，见 [NpmConsoleKeys.parse]）；
     * 宿主每次只收到一条**已定形**的命令（`Shell(command, mode)`），它不需要知道
     * 用户是不是还在特权模式里。这也让「换个项目」不必重置模式 —— 模式是人的姿势，
     * 不是项目的属性。
     */
    val mode: ShellConsoleMode = ShellConsoleMode.DEFAULT,
    /** 上一次读失败的原文（[load] 已是 [LoadState.Failed]；供「保留旧行」时仍能显示原因）。 */
    val loadError: String? = null,
    /** 上一次**执行**操作的失败原文（≠ 读失败：两条账分开，与 [RegistryState] 同纪律）。 */
    val opError: String? = null,
    /** 上一次**执行**操作的结论回执（现取即清，不缓存）。 */
    val opNotice: String? = null,
    /** 有执行在挂起中 —— 按钮禁用防连点（同一行敲两遍 = 排队两次）。 */
    val opInFlight: Boolean = false,
    val nowMillis: Long = 0L,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** 可执行的那个项目号（没读到 / 一个项目都没有 → null，界面据此禁用输入行）。 */
    val project: String? get() = selectedProjectId

    /**
     * 现在能不能敲命令：读到了、有项目、且没有在跑的命令。
     *
     * **跑动中禁用**是刻意的：`runConsoleCommand` 的重操作那条路会挂起在安装会话上
     * （`enqueueHeavy` 的 `globalSession`），按钮若可点就会长时间"按下去没反应"。
     * 宁可灰着 —— 灰按钮说明「现在不能敲」，按下去没反应说明不了任何事。
     */
    val canRun: Boolean get() = load.isLoaded && project != null && !running && !opInFlight

    companion object {
        /** 首帧哨兵：没读到过（**不是**「还没有输出」—— 那是读成功且真的没输出）。 */
        val NOT_LOADED = ConsoleCmdState(load = LoadState.NotLoaded)

        /**
         * 读到了：**累积**本批行、推进游标，现值（[running]/[gap]/[pageFull]）覆盖为最新快照的。
         *
         * [gap] 要拿**发请求前那个游标**（[sinceSeq]）判 —— 快照自己不知道调用方用的是什么，
         * 与 `ConsoleSnapshot` 把 `droppedTotal` 原样带上、由呈现层下结论同一条分工。
         * 首读（`sinceSeq == 0`）时 `firstSeq > 1` 也判为缺口：环里最旧那行之前的东西
         * 已经不在环里了，那不是「从头开始」。
         *
         * [pageFull] 由「本批条数 == 本次要的条数」判（[maxLines] 是发请求时用的那个数）——
         * 快照自己不带这个数，猜一个常量会与调用方实际要的对不上。
         */
        fun of(
            previous: ConsoleCmdState,
            projectId: String,
            snapshot: NpmConsoleSnapshot,
            sinceSeq: Long,
            maxLines: Int,
            nowMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): ConsoleCmdState {
            val seen = previous.lines.mapTo(HashSet()) { it.seq }
            val fresh = snapshot.lines.filter { it.seq !in seen }
            return previous.copy(
                load = LoadState.Loaded,
                selectedProjectId = projectId,
                lines = (previous.lines + fresh.map { ConsoleCmdLineState.of(it, zone) }).sortedBy { it.seq },
                nextSeq = maxOf(previous.nextSeq, snapshot.lastSeq),
                running = snapshot.running,
                gap = previous.gap || (fresh.isNotEmpty() && snapshot.firstSeq > sinceSeq + 1),
                pageFull = fresh.size >= maxLines,
                loadError = null,
                nowMillis = nowMillis,
                zone = zone,
            )
        }

        /**
         * 读失败：保留 [previous] 的行与游标，只把失败亮出来（原异常文案，
         * `message` 为 null 时退到类名 —— 显示 null 会被渲染成"还没读取"）。
         */
        fun failed(t: Throwable, previous: ConsoleCmdState): ConsoleCmdState = previous.copy(
            load = LoadState.of(t),
            loadError = t.message ?: t.javaClass.simpleName,
        )

        /**
         * 换项目：**清行 + 游标归 0**（见类 KDoc：归 0 = 把这个项目还留在环里的行全取回来）。
         * 草稿与两条操作账一并清掉 —— 它们属于上一个项目的那次敲击。
         */
        fun withProject(previous: ConsoleCmdState, projectId: String): ConsoleCmdState =
            if (previous.selectedProjectId == projectId) {
                previous
            } else {
                ConsoleCmdState(
                    load = previous.load,
                    selectedProjectId = projectId,
                    projects = previous.projects,
                    nowMillis = previous.nowMillis,
                    zone = previous.zone,
                )
            }
    }
}

/**
 * 一行控制台输出的呈现态。
 *
 * @property tone 由 [kind] 与 [ok] 决定（判读在宿主侧给的类别与成败上，呈现层**不按文本猜**）：
 *   ECHO/PHASE 弱化（过程），OUTPUT 正文，WARNING 注意，RESULT 按成败 PROBLEM/NEUTRAL。
 */
data class ConsoleCmdLineState(
    val seq: Long,
    val kind: NpmConsoleLineKind,
    val text: String,
    val ok: Boolean,
    val timeText: String,
) {
    val tone: StatusTone
        get() = when (kind) {
            NpmConsoleLineKind.ECHO, NpmConsoleLineKind.PHASE -> StatusTone.MUTED
            NpmConsoleLineKind.OUTPUT -> StatusTone.NEUTRAL
            NpmConsoleLineKind.WARNING -> StatusTone.ATTENTION
            NpmConsoleLineKind.RESULT -> if (ok) StatusTone.NEUTRAL else StatusTone.PROBLEM
        }

    companion object {
        fun of(line: SequencedConsoleLine, zone: ZoneId): ConsoleCmdLineState =
            of(seq = line.seq, line = line.line, zone = zone)

        fun of(seq: Long, line: NpmConsoleLine, zone: ZoneId): ConsoleCmdLineState = ConsoleCmdLineState(
            seq = seq,
            kind = line.kind,
            text = line.text,
            ok = line.ok,
            timeText = TIME.format(Instant.ofEpochMilli(line.atMillis).atZone(zone)),
        )

        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
