package com.autoscript.ui.state

import com.autoscript.domain.npm.NpmProjectSnapshot
import com.autoscript.domain.npm.NpmPanelSnapshot
import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.NpmMaintenanceAction
import com.autoscript.domain.npm.NodeModulesStats
import com.autoscript.domain.npm.NpmConsoleSnapshot
import java.time.ZoneId

/**
 * 依赖管理页呈现态（纯数据，Compose 之外可 JVM 测）。
 *
 * 与 [TaskLogState]/[ConsoleState] 同一条纪律，另加这一屏特有的两条：
 *
 * - **尺寸没量到 ≠ 尺寸是 0**：量不到时那一行**不画**，不画成 "0 B" ——
 *   0 B 是在说"这个项目不占地方"（见 [sizeLabel]）。
 *
 * 操作面（安装/维护/缓存回收）与读取分开记账：操作失败**不清依赖清单**（清单没变），
 * 与项目页 `opError` 同一条纪律。
 */
data class NpmState(
    val load: LoadState,
    /** 面板上正在看的那个项目（null = 一个项目都没有 / 还没读到）。 */
    val selectedProjectId: String? = null,
    val snapshot: NpmPanelSnapshot? = null,
    /**
     * 上一次操作的失败原文（≠ 读失败：两条账分开）。
     *
     * **它画在最下面那个日志大框里**（2026-10-17 用户令「删除顶上的报错。不需要它」+
     * 「报错全部收进底部日志大框」）—— 见 [logLines]。页面上半部分不再出现红字。
     */
    val opError: String? = null,
    /** 上一次操作的结论回执（现取即清，不缓存）。 */
    val opNotice: String? = null,
    /**
     * 有维护动作在挂起中（null = 没有）—— 防连点，
     * 但**要记住是哪一个**：三颗按钮同时禁用，其中被按下的那颗显示「进行中」，
     * 不记的话界面只能说"有件事在跑"，用户不知道是哪件。
     */
    val maintenance: NpmMaintenanceAction? = null,
    /** 缓存回收在挂起中（与 [maintenance] 分开：它不占安装会话，是另一条时长量级）。 */
    val reclaimingCache: Boolean = false,
    /**
     * 安装输入行的草稿（§10.9 第 1 条，2026-10-09 批 87）。
     *
     * **住在这里而不是 `rememberSaveable`**：`:ui` 的每个状态都是 `data class` + 顶层
     * ops 函数（见 `ConsoleCmdState.draft` 的先例），草稿放 Compose 局部状态就没法
     * 在 JVM 单测里断言"失败时不清草稿"这条纪律。
     */
    val installDraft: String = "",
    /** 勾了 `-D`（写进 `dependencies.devDependencies`）。 */
    val installDev: Boolean = false,
    /**
     * 勾了「离线优先」（`--prefer-offline`）。
     *
     * 措辞是**「优先」不是「仅离线」**：这条旗标让 npm 先查缓存、缺了仍会联网。
     * 真正的"断网也得装上"是另一回事（那要缓存里恰好有全部闭包），界面不该
     * 用一个词把两件事混成一件。
     */
    val installOffline: Boolean = false,
    /** 有安装/卸载命令在途（防连点；与 [maintenance] 同一条理由）。 */
    val installing: Boolean = false,
    /**
     * 本次安装的阶段进度（§10.9 第 1 条；`null` = 这次没在跑安装会话）。
     *
     * **为什么是"事件流 + 当前阶段"而不是"百分比"**：npm 进程内的 reify 是黑盒，
     * 宿主只能给到**阶段**（`InstallEvent.Phase` 六档），给不出 job 数 —— 见
     * [InstallProgressState] 的 KDoc。画一条会动的百分比条就是在编一个拿不到的数。
     */
    val installProgress: InstallProgressState? = null,
    /**
     * 安装事件的拉取游标（下次该传给 `npmInstallEvents` 的值）。
     *
     * **只进不退**：读失败也不清零 —— 清零会把已经显示过的阶段重放一遍，
     * 看起来像"又跑了一遍"（与 `ConsoleCmdState.nextSeq` 同一条纪律）。
     */
    val installSeq: Long = 0L,
    /**
     * 日志大框里的 npm 输出行（这个项目的；**累积**，按 seq 递增）。
     *
     * 行状态直接复用控制台那份 [ConsoleCmdLineState]：**着色判读只有一份**
     * （读宿主给的 kind 与 ok，不按文本猜），日志框与控制台页因此不会"同一行两种颜色"。
     *
     * 它是**环的投影**，不是档案：留 [LOG_KEEP] 行，更早的裁掉并如实标注
     * （见 [consoleTruncated]）—— 全量在「控制台」页与系统日志里，这里要的是
     * 「刚才那次操作说了什么」。
     */
    val consoleLines: List<ConsoleCmdLineState> = emptyList(),
    /** 日志大框的拉取游标（与 [installSeq] 同一条纪律：只进不退、读失败不清零、换项目归 0）。 */
    val consoleSeq: Long = 0L,
    /** 留的行超过上限、更早的已经裁掉（日志框据此说一句"上面这段不是全部"）。 */
    val consoleTruncated: Boolean = false,
) {
    /** 当前项目的读数（没选 / 读到的快照里没这个项目 → null，界面据此说"没有项目"）。 */
    val project: NpmProjectSnapshot?
        get() = snapshot?.projects?.firstOrNull { it.projectId == selectedProjectId }

    val installed: List<NpmRowState> get() = project?.installed.orEmpty().map { NpmRowState.of(it) }

    /**
     * node_modules 尺寸一行 —— **2026-10-17 起只有尺寸，没有「/ 512MB」**。
     *
     * 用户口径「node_modules 取消 512MB 限制」「取消上限设置吧」把项目配额整个摘了
     * （源头是 `InstallConfig` 的 512MB 硬编码，见 `NpmProjectSnapshot` 的 KDoc）：
     * 既然没有上限，"已用 / 上限"这个比例就没有对象了，硬画一个只会在屏幕上留一条假的线。
     *
     * 两态各自说一句：没读到 / 没量到 → null（**不画**，那个位置写 "0 B" 是在说
     * "这个项目不占地方"）；量到了 → 真实数字（量到 0 就如实说 0 B）。
     */
    val sizeLabel: String?
        get() = project?.storage?.let { NpmRowState.bytes(it.totalBytes) }

    /**
     * npm 缓存尺寸（§10.9 第 5 条的 `npm-cache` 尺寸栏）—— **只有数字**。
     *
     * 「全机一份」那句搬到了行标题上（缓存按内容寻址、跨项目共享，§10.2 —— 写成
     * 「本项目缓存 12MB」是撒谎，用户会以为删掉这个项目就能腾出那 12MB）。
     * 量不到（老宿主没实现这个口）→ null（不画），与 [sizeLabel] 同一条纪律：没量到 ≠ 0 字节。
     */
    val cacheSizeLabel: String?
        get() = project?.cache?.let { NpmRowState.bytes(it.totalBytes) }

    /**
     * 日志大框里要画的行（**只读的投影**，页面上半部分不再有红字）。
     *
     * 次序是**用户 2026-10-17 裁定的「两者都显示」**：本页这次操作的账在前
     * （读失败 / [opError] / [opNotice]），npm 输出行在后 —— 前者是"谁把这事干的"，
     * 后者是"它说了什么"。
     *
     * 三条刻意：
     * - **读失败也进这个框**（口径是"页面上半部分不再出现红字"），但措辞保留
     *   「（下面显示的是上次读到的，可能已过期）」那半句 —— 失败态下屏上还有旧数据，
     *   不说这句就等于把过期的读数当现在的。
     * - 裁过就说（[consoleTruncated] 那一行在最前）：让用户以为看到了全部，
     *   比少显示几行糟得多（与「控制台」页 `gap` 那条同一条纪律）。
     * - 一条都没有时给一句**怎么做才有**的话，而不是一个空框。
     */
    val logLines: List<NpmLogLine>
        get() = buildList {
            if (consoleTruncated) add(NpmLogLine("更早的输出已裁掉，这里只留最近 $LOG_KEEP 行", StatusTone.MUTED))
            (load as? LoadState.Failed)?.let {
                add(NpmLogLine("读取失败：${it.reason}（下面显示的是上次读到的，可能已过期）", StatusTone.PROBLEM))
            }
            opError?.let { add(NpmLogLine(it, StatusTone.PROBLEM)) }
            opNotice?.let { add(NpmLogLine(it, StatusTone.MUTED)) }
            consoleLines.forEach { add(NpmLogLine(it.timeText + "  " + it.text, it.tone)) }
        }

    /**
     * 「清理」（回收缓存）那颗按钮能不能按。
     *
     * 判据与三颗维护按钮**同源**（`globalSession` 全局互斥，同时只跑一个安装会话）：
     * 在途的安装 / 维护 / 回收三者互斥。2026-10-17 这颗按钮从「维护」卡搬到缓存那一行，
     * 判据顺势从界面里的行内表达式提上来 —— 它是能 JVM 测的一条纯逻辑，留在 Composable
     * 里就永远只有"看一眼觉得对"。
     */
    val canReclaimCache: Boolean
        get() = !reclaimingCache && maintenance == null && !installing

    /** 输入行与旗标是否可提交（没有项目 / 已有动作在跑 / 没读到 → 不可）。 */
    val canInstall: Boolean
        get() = load.isLoaded && project != null && !installing && maintenance == null &&
            !reclaimingCache

    companion object {
        val NOT_LOADED = NpmState(LoadState.NotLoaded)

        /**
         * 读到了：**默认选中第一个项目**（面板总要有个主体）；已有选择且它还在就保持
         * —— 刷新不该把用户正在看的项目换掉（那是"手一滑跳走了"的经典形态）。
         */
        fun of(snapshot: NpmPanelSnapshot, previous: NpmState = NOT_LOADED) = NpmState(
            load = LoadState.Loaded,
            selectedProjectId = previous.selectedProjectId
                ?.takeIf { id -> snapshot.projects.any { it.projectId == id } }
                ?: snapshot.projects.firstOrNull()?.projectId,
            snapshot = snapshot,
            // 现取**不许**把操作面抹掉（2026-10-09 批 87）：输入行草稿、旗标勾选、
            // 「在途」与阶段条都是**用户的**状态，不是宿主的读数 —— 从 `snapshot` 重建
            // 一份就等于每次刷新把用户打到一半的包名和正在跑的进度条一起清空。
            // `installed` 那一侧不需要这样：它本来就来自 `snapshot`（宿主才是权威）。
            installDraft = previous.installDraft,
            installDev = previous.installDev,
            installOffline = previous.installOffline,
            installing = previous.installing,
            installProgress = previous.installProgress,
            installSeq = previous.installSeq,
            // 日志那一段同理：它是"这次会话已经发生了什么"的**累积**，每次刷新重建一份
            // 就等于把用户刚看到的 npm 输出抹成空的。
            consoleLines = previous.consoleLines,
            consoleSeq = previous.consoleSeq,
            consoleTruncated = previous.consoleTruncated,
        )

        /**
         * 换一个项目看（[previous] 就是当前那份）。
         *
         * **换项目要把阶段条与游标一起归零**（与 `ConsoleCmdState.withProject` 同一条纪律，
         * 2026-10-09 批 87）：seq 是**环内全局单调**的、`drain` 才按 projectId 过滤，所以
         * 沿用上一个项目的游标会**漏掉**新项目 seq 更小的那些事件（它们对新项目是新的、
         * 对那个游标却不是）—— 包括 `Finished`，于是阶段条会永远停在「进行中」。
         * 归 0 不是"重头开始"，而是"把这个项目还留在环里的那些事件全取回来"。
         *
         * 归零的**只有项目域的那两样**：草稿与旗标是用户的输入（`axios` 换个项目照样是
         * 要装的东西），`installing` 记的是全局在途（安装会话是全局互斥的，换个项目看
         * 不会让它停下来）。
         */
        fun withProject(previous: NpmState, projectId: String): NpmState =
            if (previous.selectedProjectId == projectId) {
                previous
            } else {
                previous.copy(
                    selectedProjectId = projectId,
                    installProgress = null,
                    installSeq = 0L,
                    // 日志那一段与安装事件**同一条理由**（seq 是环内全局单调、drain 才按
                    // projectId 过滤）：沿用上一个项目的游标会漏掉新项目 seq 更小的行。
                    // 归 0 = 把这个项目还留在环里的行全取回来。
                    consoleLines = emptyList(),
                    consoleSeq = 0L,
                    consoleTruncated = false,
                )
            }

        /**
         * 读失败：**保留 [previous] 已读到的那份**（一次瞬时失败不该把依赖清单抹成空 ——
         * 空清单在界面上就是"这个项目没有依赖"，那是另一句话）。
         */
        fun failed(t: Throwable, previous: NpmState = NOT_LOADED) =
            previous.copy(load = LoadState.of(t))

        /**
         * 把一批 npm 输出折进日志大框（**纯函数**，可单测）。
         *
         * 累积规则与 [ConsoleCmdState.of] 逐字同款（按 seq 去重、只进不退），差在**有上限**：
         * 日志框是面板上的一格，不是日志查看器 —— 留 [LOG_KEEP] 行、更早的裁掉并把
         * [consoleTruncated] 置上（裁过就要说，见 [logLines]）。
         * [ConsoleCmdState] 那边没有上限，因为那一屏**就是**给人看输出的地方。
         */
        fun withConsole(
            previous: NpmState,
            snapshot: NpmConsoleSnapshot,
            zone: ZoneId = ZoneId.systemDefault(),
        ): NpmState {
            val seen = previous.consoleLines.mapTo(HashSet()) { it.seq }
            val fresh = snapshot.lines.filter { it.seq !in seen }
            if (fresh.isEmpty() && snapshot.lastSeq <= previous.consoleSeq) return previous
            val merged = (previous.consoleLines + fresh.map { ConsoleCmdLineState.of(it, zone) })
                .sortedBy { it.seq }
            return previous.copy(
                consoleLines = merged.takeLast(LOG_KEEP),
                consoleSeq = maxOf(previous.consoleSeq, snapshot.lastSeq),
                consoleTruncated = previous.consoleTruncated || merged.size > LOG_KEEP,
            )
        }
    }
}

/** 日志大框最多留多少行（更早的裁掉，并在框里说明 —— 见 [NpmState.consoleTruncated]）。 */
internal const val LOG_KEEP = 200

/** 日志大框一次拉多少行（与控制台那个缺省页大小同量级：一次现取就够把框填满）。 */
internal const val LOG_PAGE = 256

/**
 * 日志大框里的一行。
 *
 * 与控制台那份 [ConsoleCmdLineState] 分开：这里只要「一句话 + 一种语气」——
 * 行来源有两类（本页的操作账、npm 的输出行），合成同一种画法才谈得上"一个框"。
 */
data class NpmLogLine(val text: String, val tone: StatusTone)

/** 已装包一行（名字 + 版本；`sizeBytes` 不在这一层 —— `list()` 直读 lockfile，量不到尺寸）。 */
data class NpmRowState(
    val name: String,
    val version: String,
) {
    companion object {
        fun of(node: com.autoscript.domain.npm.PkgNode) = NpmRowState(node.name, node.version)

        /**
         * 字节数的可读形态（二进制档，与系统口径一致）。`< 1KB` 不写成 `0KB`；
         * 整 MB 不带小数（配额是 512 MB，写成 "512.0 MB" 是把常量伪装成测量值）。
         */
        fun bytes(n: Long): String = when {
            n < 1024 -> "$n B"
            n < 1024 * 1024 -> "${n / 1024} KB"
            else -> {
                val mb = "%.1f".format(java.util.Locale.ROOT, n / 1024.0 / 1024.0)
                "${mb.removeSuffix(".0")} MB"
            }
        }
    }
}

/**
 * 一次安装会话的阶段进度（§10.9 第 1 条的进度条，2026-10-09 批 87）。
 *
 * **六档阶段，不是百分比。** §10.9 第 1 条原文写的是「阶段进度条（packument→下载→解包→链接，
 * job 数）」，但 **job 数拿不到**：`HostNodeExecutor` 起的是 vendored npm CLI 进程，
 * npm 进程内的 reify 是黑盒，宿主只在进程前后发得出三枚粗标记
 * （`DOWNLOAD` → `REIFY` → `DONE`，见 `HostNodeExecutor.execute`），
 * `InstallEvent.Progress.percent` 全仓**从无赋值**。
 *
 * 所以这里画的是**阶段条**：把 [InstallEvent.Phase] 六档按序排开、点亮到当前那一档。
 * 画一条会动的百分比条就是在编一个拿不到的数 —— 用户看着它走到 90% 停住，
 * 比看着它诚实地停在「写入 node_modules」更糟。
 *
 * **`pkg` 只在 npm 自己报的时候有**（今天的执行体一个都不报），故它只用于在阶段文案后面
 * 补一句「正在处理哪个包」，没有就不补 —— 不编。
 *
 * @property phase 当前阶段（[InstallEvent.Phase] 六档里走到的那一档）。
 * @property pkg npm 报出来的包名（`null` = 这次没有；**不是**"不知道"）。
 * @property done 是否已经收到终态（`Finished`）。收尾后阶段条要能自己收起来 ——
 *   否则一次失败之后屏幕上永远停着一个"进行中"的条。
 * @property ok 终态的成败（[done] 为 false 时无意义）。
 * @property detail 终态的详情原文（失败时是病因）。
 */
data class InstallProgressState(
    val phase: InstallEvent.Phase,
    val pkg: String? = null,
    val done: Boolean = false,
    val ok: Boolean = true,
    val detail: String? = null,
) {
    /** 阶段条上的一句话（阶段名的中文说法与宿主侧 `phaseText` **同源**：那一份在 `:app-service`，够不到，故此处不重复翻译，直接用契约里的枚举名）。 */
    val label: String get() = when (phase) {
        InstallEvent.Phase.QUEUED -> "排队中"
        InstallEvent.Phase.RESOLVE -> "解析依赖"
        InstallEvent.Phase.DOWNLOAD -> "下载"
        InstallEvent.Phase.REIFY -> "写入 node_modules"
        InstallEvent.Phase.POST_CHECK -> "收尾校验"
        InstallEvent.Phase.DONE -> "完成"
    }

    companion object {
        /**
         * 六档的展示序（与 [InstallEvent.Phase] 的声明序一致 —— 写成一份显式列表是为了
         * 让「阶段条有几格」在呈现层可见，而不是靠 `entries` 隐式决定）。
         */
        val ORDER: List<InstallEvent.Phase> = listOf(
            InstallEvent.Phase.QUEUED,
            InstallEvent.Phase.RESOLVE,
            InstallEvent.Phase.DOWNLOAD,
            InstallEvent.Phase.REIFY,
            InstallEvent.Phase.POST_CHECK,
            InstallEvent.Phase.DONE,
        )
    }
}
