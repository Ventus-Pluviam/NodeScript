package com.autoscript.ui.state

import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.host.ActiveRunRow
import com.autoscript.domain.host.ConsoleLineRow
import com.autoscript.domain.host.ConsoleSnapshot
import java.time.Instant
import java.time.ZoneId

/**
 * 控制台呈现态（纯数据，Compose 之外可 JVM 测）。
 *
 * 与 [TaskCenterState]/[CapabilityCenterState] 同一条纪律，外加控制台特有的一条：
 * - [load] = [LoadState.NotLoaded] 首帧哨兵；[LoadState.Failed] 表示最近一次读取失败，
 *   **但已读到的行与游标都保留**（一次瞬时失败不该把用户已经看到的日志抹掉，
 *   游标不清零才能续拉）；
 * - **行是累积的，但累积有上限**（[of] 把本批接在既有行后面，超过 [CONSOLE_LINES_KEEP]
 *   裁最老、裁掉的行数记进 [trimmedTotal]）：控制台是累计事实，刷新 = 增量拉取，
 *   不是重画；同一游标读两遍（并发刷新）按 seq 去重，不重复入列；
 * - 三样"现值"不累积、每次现取：[pageFull]、[droppedTotal]、[activeRuns] ——
 *   它们答的是"此刻"，留旧值会把过期事实当现状。
 *
 * **为什么必须有上限**（修）：数据面是**环**（`ConsoleCollector.DEFAULT_CAPACITY = 2000`，
 * 溢出丢最老并把 [droppedTotal] 带上），而这里的累积原先是**只增不减**的 —— 于是"有界环"
 * 这道设计在 UI 侧被抵消了：宿主丢掉的行 UI 还留着，进程活多久 `lines` 就长多久。
 * 具体代价不只是内存（每行都带时间戳与正文），还有**每轮拉取的成本随行数涨**：
 * [of] 每次都把既有行整份 `mapTo(HashSet())` 去重、再对合并结果整份 `sortedBy`，
 * 而悬浮窗在跑时是 400ms 一轮（`LogWindowPoll`）—— 长跑脚本刷到几万行之后，
 * 每一轮都在为一个只增不减的表做 O(n log n)。上限取**与环同值**：UI 的账不该比数据面更长，
 * 而"要看全文"本来就有落盘那条路（`ScriptLogStore` 只追加，日志管理 → 脚本输出）。
 *
 * 裁了就**必须说**（[trimmedTotal]，与 [droppedTotal] 同一条纪律）：静默裁剪等于把
 * "界面没留"说成"没发生"。两笔账分开记：[droppedTotal] 是宿主环丢的（数据面），
 * [trimmedTotal] 是这一层裁的（呈现面），加起来才是"你看到的不是全部"的完整缺口。
 *
 * 时间戳格式化在这一层（[ConsoleLineState.timeText]，`HH:mm:ss` —— 控制台行
 * 以秒为粒度，`MM-dd HH:mm` 分辨不出同分钟内的先后）；时刻由 [of] 的参数注入，
 * 类内不读 `System.currentTimeMillis()`（可测 + 同帧一致）。
 */
data class ConsoleState(
    val load: LoadState,
    val lines: List<ConsoleLineState>,
    /** 拉取游标（只进不退；失败不清零）。首读前为 0。 */
    val nextSeq: Long,
    val pageFull: Boolean,
    val droppedTotal: Long,
    /**
     * **呈现层自己裁掉的行数**（累积计数；超过 [CONSOLE_LINES_KEEP] 时裁最老）。
     *
     * 与 [droppedTotal] 是两笔账（那边是宿主环丢的）—— 见类 KDoc「为什么必须有上限」。
     */
    val trimmedTotal: Long = 0L,
    val activeRuns: List<ActiveRunState>,
    val nowMillis: Long,
    val zone: ZoneId,
    /** 上一次**停止操作**失败原文（≠ 读失败：两条账分开，见 [TaskCenterState] 同纪律）。 */
    val stopError: String? = null,
    /** 上一次**停止操作**成功回执（刷新现取即清，不缓存）。 */
    val stopNotice: String? = null,
    /** 有停止在挂起中 —— 在途行按钮禁用防连点（停止本身幂等，连点无害但回执会抖）。 */
    val stopInFlight: Boolean = false,
) {
    companion object {
        /**
         * 首帧哨兵：没读到过（**不是**"暂无日志"—— 那是读成功且真的没输出）。
         * `nowMillis` 取 0：没有行就没有时间戳可渲染。
         */
        val NOT_LOADED = ConsoleState(
            load = LoadState.NotLoaded,
            lines = emptyList(),
            nextSeq = 0L,
            pageFull = false,
            droppedTotal = 0L,
            trimmedTotal = 0L,
            activeRuns = emptyList(),
            nowMillis = 0L,
            zone = ZoneId.systemDefault(),
        )

        /**
         * 读取成功：**累积**本批行、推进游标，三样现值覆盖为最新快照的。
         *
         * [nowMillis]/[zone] 由调用方注入（同 [TaskCenterState.of]）。
         * 去重按 seq 对照既有行：并发刷新会让两批从同一游标拉到同样的行，
         * 重复入列在控制台上表现为同一句日志出现两遍。
         */
        fun of(
            previous: ConsoleState,
            added: ConsoleSnapshot,
            nowMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): ConsoleState {
            val seen = previous.lines.mapTo(HashSet()) { it.seq }
            val fresh = added.lines.filter { it.seq !in seen }
            val merged = (previous.lines + fresh.map { ConsoleLineState.of(it, zone) }).sortedBy { it.seq }
            // 上限裁剪（见类 KDoc）。`takeLast` 保最新 —— 控制台读的是"刚才发生了什么"。
            val kept = if (merged.size > CONSOLE_LINES_KEEP) merged.takeLast(CONSOLE_LINES_KEEP) else merged
            return ConsoleState(
                load = LoadState.Loaded,
                lines = kept,
                nextSeq = maxOf(previous.nextSeq, added.nextSeq),
                pageFull = added.pageFull,
                droppedTotal = added.droppedTotal,
                trimmedTotal = previous.trimmedTotal + (merged.size - kept.size),
                activeRuns = added.activeRuns.map { ActiveRunState.of(it) },
                nowMillis = nowMillis,
                zone = zone,
            )
        }

        /**
         * 读取失败：保留 [previous] 的行与游标，只把失败亮出来（原异常文案，
         * `message` 为 null 时退到类名 —— 显示 null 会被渲染成"还没读取"）。
         */
        fun failed(t: Throwable, previous: ConsoleState): ConsoleState = previous.copy(
            load = LoadState.of(t),
        )

        /**
         * 呈现层最多留多少行（更早的裁掉并把 [ConsoleState.trimmedTotal] 记上）。
         *
         * 取**与宿主环同值**（`ConsoleCollector.DEFAULT_CAPACITY`）而不是另编一个数：
         * 环的容量就是"这一次进程里控制台总共能有多少行"的既有答案，UI 留得比它多
         * 不会让用户多看到任何宿主还记得的东西，只是把内存与每轮拉取的成本抬上去。
         * 不直接引用那个常量是因为 `:ui` 不依赖 `:bridge:java`（§6 依赖方向）。
         */
        const val CONSOLE_LINES_KEEP = 2_000
    }
}

/**
 * 日志管理页三段各自的数据源过滤（**唯一一份**；屏幕与用例读同一个函数）。
 *
 * 为什么提出来：`runId == 0` 是「引擎外」（宿主自己的行），非 0 是「脚本进程的输出」——
 * 这条判据原先散在 `ConsoleScreen` 与 `LogManagementScreen` 两处的 `filter` 里，
 *  把日志整体搬去日志管理时收成一处：两处各写一份的话，
 * 同一行日志可以在两个页签下**同时**出现或**同时**消失，而没人会立刻发现。
 *
 * 第三段（任务日志）不过这里 —— 它是另一本账（[TaskLogState]），不是 console 行的子集。
 */
fun ConsoleState.systemLogLines(): List<ConsoleLineState> = lines.filter { it.external }

/** 脚本进程的 console 输出（见 [systemLogLines] 的 KDoc）。 */
fun ConsoleState.scriptOutputLines(): List<ConsoleLineState> = lines.filter { !it.external }

/**
 * 一行控制台输出的呈现态。
 *
 * @property levelLabel 级别的中文说法（[LevelText.describe]）；[level] 原值也在 ——
 *   未知级别不翻译也不丢（翻译会把现场信息抹掉）。
 * @property runId 0 = 引擎外日志（[external]），呈现为「引擎外」而不是「#0」。
 */
data class ConsoleLineState(
    val seq: Long,
    val runId: Long,
    val external: Boolean,
    val level: String,
    val levelLabel: String,
    val text: String,
    val timeText: String,
) {
    /** 这一行的着色档：只有 error 通栏标红，其余走正文色 —— 标红多了就等于没标。 */
    val tone: StatusTone
        get() = if (level == "error") StatusTone.PROBLEM else StatusTone.NEUTRAL

    companion object {
        fun of(line: ConsoleLineRow, zone: ZoneId): ConsoleLineState = ConsoleLineState(
            seq = line.seq,
            runId = line.runId,
            external = line.external,
            level = line.level,
            levelLabel = LevelText.describe(line.level),
            text = line.text,
            timeText = HH_MM_SS.format(Instant.ofEpochMilli(line.atMillis).atZone(zone)),
        )

    }
}

/**
 * 一条在途执行的呈现态。
 *
 * @property hostLabel 宿主状态的人话；**null = 读不到**（引擎已死/未接线）——
 *   呈现层对 null 如实说"读不到"，绝不拿它当 `STOPPED` 之类的某个状态渲染。
 * @property poolLabel 池侧状态机的人话（权威的一侧，host 读不到时也还在）。
 * @property drift 两端状态分歧（§8.3 校准的事实；判据在 `RuntimeController`，这里只画）。
 */
data class ActiveRunState(
    val runId: Long,
    val hostLabel: String?,
    val poolLabel: String,
    val drift: Boolean,
    /** 本次执行的归属（悬浮窗标题与日志列表标题的事实源）。 */
    val projectId: String,
    /** 项目内相对路径（如 `data/test.js`）。 */
    val scriptPath: String,
    /** 是否被暂停（暂停按钮与「已暂停」后缀的事实源）。 */
    val paused: Boolean,
) {
    /**
     * 复制用的执行摘要 —— 与行里画的那句**同源**（改一处两边都改）。
     *
     * **唯一一份**：此前控制台屏与任务中心屏各有一份私有副本，
     * 日志搬家时顺手收成这一处 —— 留第三份的代价不是多几行，而是两屏复制出去的话
     * 可以不一样（用户贴给别人看的时候没人知道哪份是准的）。
     */
    fun summaryText(): String =
        "#$runId 池侧：$poolLabel · ${hostLabel ?: "宿主状态读不到（引擎已死或未接线）"}"

    /** 「池侧 / 宿主」那一行的着色档：读不到宿主或两端分歧都值得注意。 */
    val tone: StatusTone
        get() = when {
            drift -> StatusTone.PROBLEM
            hostLabel == null -> StatusTone.ATTENTION
            else -> StatusTone.MUTED
        }

    companion object {
        fun of(run: ActiveRunRow): ActiveRunState = ActiveRunState(
            runId = run.runId,
            hostLabel = run.host?.let { EngineStatusText.describe(it) },
            poolLabel = EngineStatusText.describe(run.pool),
            drift = run.drift,
            projectId = run.projectId,
            scriptPath = run.scriptPath,
            paused = run.paused,
        )
    }
}

/**
 * 控制台级别的用户说法。
 *
 * 未知级别**原样返回**（直写口 `append` 不设限，现场值比翻译重要）——
 * 编一个「日志」标签会把"这条是 error 级"的现场信息抹掉。
 */
object LevelText {
    fun describe(level: String): String = when (level) {
        "error" -> "错误"
        "warn" -> "警告"
        "info" -> "信息"
        "log", "debug" -> "日志"
        else -> level
    }
}

/**
 * 引擎状态的用户说法（[EngineStatus] 六态逐态一句）。
 *
 * `when` 穷尽：将来枚举加值，这里编译期就红 —— 不会静默落到某个兜底词上。
 */
object EngineStatusText {
    fun describe(status: EngineStatus): String = when (status) {
        EngineStatus.IDLE -> "空闲"
        EngineStatus.BOOTING -> "启动中"
        EngineStatus.RUNNING -> "运行中"
        EngineStatus.QUIESCING -> "排空中"
        EngineStatus.STOPPED -> "已停止"
        EngineStatus.CRASHED -> "已崩溃"
    }
}
