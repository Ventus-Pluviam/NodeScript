package com.autoscript.ui.state

import com.autoscript.domain.npm.ApprovalAction
import com.autoscript.domain.npm.ApprovalRequest
import com.autoscript.domain.npm.NpmProjectSnapshot
import com.autoscript.domain.npm.NpmPanelSnapshot
import com.autoscript.domain.npm.NpmMaintenanceAction

/**
 * 依赖管理页呈现态（纯数据，Compose 之外可 JVM 测）。
 *
 * 与 [TaskLogState]/[ConsoleState] 同一条纪律，另加这一屏特有的两条：
 *
 * - **两件事共用一个读口**（依赖面板 + 审批卡）：它们同属一份宿主快照，拆成两个读口就会
 *   各自现取一次、两次结果可以互相矛盾（且审批票的现取会与依赖清单的现取错位）。一次现取、一份状态。
 *   注意待审队列挂在快照**顶层**（[NpmPanelSnapshot.pendingApprovals]）而非项目上 ——
 *   它是全局的，`resolveApproval` 也不带项目维度。
 * - **尺寸没量到 ≠ 尺寸是 0**：[NpmProjectSnapshot.quotaFraction] 为 null 时
 *   进度条画成"未量到"，不画 0% —— 0% 是在说"这个项目不占地方"（见 [quotaLabel]）。
 *
 * 操作面（批准/拒绝）与读取分开记账：审批失败**不清依赖清单**（清单没变），
 * 与项目页 `opError` 同一条纪律。
 */
data class NpmState(
    val load: LoadState,
    /** 面板上正在看的那个项目（null = 一个项目都没有 / 还没读到）。 */
    val selectedProjectId: String? = null,
    val snapshot: NpmPanelSnapshot? = null,
    /** 上一次审批操作的失败原文（≠ 读失败：两条账分开）。 */
    val opError: String? = null,
    /** 上一次审批操作的结论回执（现取即清，不缓存）。 */
    val opNotice: String? = null,
    /** 有审批决定在挂起中 —— 按钮禁用防连点（决定本身幂等，连点无害但回执会抖）。 */
    val deciding: Boolean = false,
    /**
     * 有维护动作在挂起中（null = 没有）—— 与 [deciding] 同一条防连点理由，
     * 但**要记住是哪一个**：三颗按钮同时禁用，其中被按下的那颗显示「进行中」，
     * 不记的话界面只能说"有件事在跑"，用户不知道是哪件。
     */
    val maintenance: NpmMaintenanceAction? = null,
    /** 缓存回收在挂起中（与 [maintenance] 分开：它不占安装会话，是另一条时长量级）。 */
    val reclaimingCache: Boolean = false,
) {
    /** 当前项目的读数（没选 / 读到的快照里没这个项目 → null，界面据此说"没有项目"）。 */
    val project: NpmProjectSnapshot?
        get() = snapshot?.projects?.firstOrNull { it.projectId == selectedProjectId }

    val installed: List<NpmRowState> get() = project?.installed.orEmpty().map { NpmRowState.of(it) }

    /** 待审队列是**全局**的（不属于某个项目），故直接取快照，不经过 [project]。 */
    val pending: List<ApprovalRowState>
        get() = snapshot?.pendingApprovals.orEmpty().map { ApprovalRowState.of(it) }

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
        )

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
 * 一张待审票的呈现行。
 *
 * [actionLabel] 与 [riskNote] 是本层的两句话：前者把 `ApprovalAction` 翻成人话，
 * 后者说明"批准 = 让这段代码在你设备上跑"（§10.5-2：卡片要能展开风险说明）。
 * **不写"已批准"之类的前缀** —— 这个列表里全是 PENDING，写前缀就是重复。
 */
data class ApprovalRowState(
    val requestId: String,
    val projectId: String,
    val pkg: String,
    val actionLabel: String,
    val versionHashShort: String,
) {
    /** 卡片上那行风险说明（§10.9.2「可展开『脚本=任意代码』风险说明」的正文）。 */
    val riskNote: String =
        "批准 = 允许这个包的脚本在你的设备上运行（与你的脚本同权）。" +
            "版本或脚本内容一变，这张批准就失效、需要重新批。"

    companion object {
        fun of(req: ApprovalRequest) = ApprovalRowState(
            requestId = req.id,
            projectId = req.projectId,
            pkg = req.pkg,
            actionLabel = actionLabel(req.action),
            // 哈希是 sha512 的长串：卡片上只给前 12 位（点开详情才需要全串），
            // 给全串会把包名挤掉 —— 而包名才是用户判断的对象。
            versionHashShort = req.versionHash.take(12).ifEmpty { "（无版本哈希）" },
        )

        fun actionLabel(a: ApprovalAction): String = when (a) {
            ApprovalAction.INSTALL_SCRIPT -> "安装脚本"
            ApprovalAction.RUN_SCRIPT -> "npm run"
            ApprovalAction.EXEC -> "npm exec"
        }
    }
}
