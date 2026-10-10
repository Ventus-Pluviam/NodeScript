package com.autoscript.ui.state

import com.autoscript.domain.npm.NpmProjectSnapshot
import com.autoscript.domain.npm.NpmPanelSnapshot
import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.NpmMaintenanceAction
import com.autoscript.domain.npm.NodeModulesStats

/**
 * 依赖管理页呈现态（纯数据，Compose 之外可 JVM 测）。
 *
 * 与 [TaskLogState]/[ConsoleState] 同一条纪律，另加这一屏特有的两条：
 *
 * - **尺寸没量到 ≠ 尺寸是 0**：[NpmProjectSnapshot.quotaFraction] 为 null 时
 *   进度条画成"未量到"，不画 0% —— 0% 是在说"这个项目不占地方"（见 [quotaLabel]）。
 *
 * 操作面（安装/维护/缓存回收）与读取分开记账：操作失败**不清依赖清单**（清单没变），
 * 与项目页 `opError` 同一条纪律。
 */
data class NpmState(
    val load: LoadState,
    /** 面板上正在看的那个项目（null = 一个项目都没有 / 还没读到）。 */
    val selectedProjectId: String? = null,
    val snapshot: NpmPanelSnapshot? = null,
    /** 上一次操作的失败原文（≠ 读失败：两条账分开）。 */
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
) {
    /** 当前项目的读数（没选 / 读到的快照里没这个项目 → null，界面据此说"没有项目"）。 */
    val project: NpmProjectSnapshot?
        get() = snapshot?.projects?.firstOrNull { it.projectId == selectedProjectId }

    val installed: List<NpmRowState> get() = project?.installed.orEmpty().map { NpmRowState.of(it) }

    /**
     * 配额一行（"已用 / 配额"）。
     *
     * 三态各自说一句：没读到 → null（不画）；量到了 → 真实数字；
     * 配额为 0（不该发生，但实现方可以传）→ 不显示比例，避免除零画出 NaN%。
     */
    val quotaLabel: String?
        get() {
            val p = project ?: return null
            val s = p.storage ?: return null
            return "${NpmRowState.bytes(s.totalBytes)} / ${NpmRowState.bytes(p.quotaBytes)}"
        }

    /**
     * npm 缓存一行（§10.9 第 5 条的 `npm-cache` 尺寸栏）。
     *
     * **文案里点明「全机一份」**：缓存按内容寻址、跨项目共享（§10.2），写成
     * 「本项目缓存 12MB」是撒谎 —— 用户会以为删掉这个项目就能腾出那 12MB。
     * 量不到（老宿主没实现这个口）→ null（不画），与 [quotaLabel] 同一条纪律：
     * 没量到 ≠ 0 字节。
     */
    val cacheLabel: String?
        get() {
            val c: NodeModulesStats = project?.cache ?: return null
            return "npm 缓存（全机一份）：${NpmRowState.bytes(c.totalBytes)}"
        }

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
                )
            }

        /**
         * 读失败：**保留 [previous] 已读到的那份**（一次瞬时失败不该把依赖清单抹成空 ——
         * 空清单在界面上就是"这个项目没有依赖"，那是另一句话）。
         */
        fun failed(t: Throwable, previous: NpmState = NOT_LOADED) =
            previous.copy(load = LoadState.of(t))
    }
}

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
