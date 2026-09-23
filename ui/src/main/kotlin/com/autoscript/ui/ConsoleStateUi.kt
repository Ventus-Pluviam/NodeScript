package com.autoscript.ui

import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.host.ActiveRunRow
import com.autoscript.domain.host.ConsoleLineRow
import com.autoscript.domain.host.ConsoleSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 控制台呈现态（纯数据，Compose 之外可 JVM 测）。
 *
 * 与 [TaskCenterState]/[CapabilityCenterState] 同一条纪律，外加控制台特有的一条：
 * - [loaded] = false 且 [loadError] = null —— 还没读到过（首帧哨兵 [NOT_LOADED]）；
 *   [loadError] 非 null —— 最近一次读取失败，**但已读到的行与游标都保留**
 *   （一次瞬时失败不该把用户已经看到的日志抹掉，游标不清零才能续拉）；
 * - **行是累积的**（[of] 把本批接在既有行后面）：控制台是累计事实，刷新 = 增量拉取，
 *   不是重画；同一游标读两遍（并发刷新）按 seq 去重，不重复入列；
 * - 三样"现值"不累积、每次现取：[pageFull]、[droppedTotal]、[activeRuns] ——
 *   它们答的是"此刻"，留旧值会把过期事实当现状。
 *
 * 时间戳格式化在这一层（[ConsoleLineState] 的 `timeText`，`HH:mm:ss` —— 控制台行
 * 以秒为粒度，`MM-dd HH:mm` 分辨不出同分钟内的先后）；时刻由 [of] 的参数注入，
 * 类内不读 `System.currentTimeMillis()`（可测 + 同帧一致）。
 */
data class ConsoleState(
    val loaded: Boolean,
    val loadError: String?,
    val lines: List<ConsoleLineState>,
    /** 拉取游标（只进不退；失败不清零）。首读前为 0。 */
    val nextSeq: Long,
    val pageFull: Boolean,
    val droppedTotal: Long,
    val activeRuns: List<ActiveRunState>,
    val nowMillis: Long,
    val zone: ZoneId,
) {
    companion object {
        /**
         * 首帧哨兵：没读到过（**不是**"暂无日志"—— 那是读成功且真的没输出）。
         * `nowMillis` 取 0：没有行就没有时间戳可渲染。
         */
        val NOT_LOADED = ConsoleState(
            loaded = false,
            loadError = null,
            lines = emptyList(),
            nextSeq = 0L,
            pageFull = false,
            droppedTotal = 0L,
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
            return ConsoleState(
                loaded = true,
                loadError = null,
                lines = previous.lines + fresh.map { ConsoleLineState.of(it, zone) },
                nextSeq = added.nextSeq,
                pageFull = added.pageFull,
                droppedTotal = added.droppedTotal,
                activeRuns = added.activeRuns.map { ActiveRunState.of(it) },
                nowMillis = nowMillis,
                zone = zone,
            )
        }

        /**
         * 读取失败：保留 [previous] 的行与游标，只把失败亮出来（原异常文案，
         * message 为 null 时退到类名 —— 显示 null 会被渲染成"还没读取"）。
         */
        fun failed(t: Throwable, previous: ConsoleState): ConsoleState = previous.copy(
            loaded = false,
            loadError = t.message ?: t.javaClass.simpleName,
        )
    }
}

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
    companion object {
        fun of(line: ConsoleLineRow, zone: ZoneId): ConsoleLineState = ConsoleLineState(
            seq = line.seq,
            runId = line.runId,
            external = line.external,
            level = line.level,
            levelLabel = LevelText.describe(line.level),
            text = line.text,
            timeText = TIME.format(Instant.ofEpochMilli(line.atMillis).atZone(zone)),
        )

        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
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
) {
    companion object {
        fun of(run: ActiveRunRow): ActiveRunState = ActiveRunState(
            runId = run.runId,
            hostLabel = run.host?.let { EngineStatusText.describe(it) },
            poolLabel = EngineStatusText.describe(run.pool),
            drift = run.drift,
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
