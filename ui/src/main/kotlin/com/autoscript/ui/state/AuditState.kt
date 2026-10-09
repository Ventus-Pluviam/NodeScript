package com.autoscript.ui.state

import com.autoscript.domain.npm.InstallHistoryEntry
import com.autoscript.domain.npm.InstallHistoryOp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 审计页呈现态（纯数据，Compose 之外可 JVM 测；§10.5-2）。
 *
 * 它消费的是 `HostSummary.npmHistory()` —— 一条**历史**读口（`install-history.jsonl`
 * 的只读投影），与依赖面板那条**当前事实**读口（`npmSnapshot()`）是两件事：
 * 那个答「此刻装了什么」，这个答「过去发生过什么」。见 `PackageManagerFacade.history` 的 KDoc。
 *
 * 三条纪律：
 * - **筛选只影响显示，不影响读**（[projectFilter]）：宿主那条读口无参（全量），
 *   筛在呈现层做。理由在宿主侧 KDoc 里 —— 按项目问会让 `registry` 那条
 *   （`projectId` 是空串的全局变更）从任何一次筛选里掉出去，而它恰恰是审计最该看见的。
 * - **未知 op 原样显示，不编人话**（[AuditRowState.opLabel]）：`op` 的取值域是开放的
 *   （`opName(args)` 直取 argv 首词），呈现层认不出就照原样写 —— 编一句「未知操作」
 *   会把「这个版本还不认识它」说成「这条记录有问题」。
 * - **失败行不藏**（[failureCount]）：审计要能回答「用户当时看到成功了吗」，
 *   藏掉失败行等于把那份记录改成只记成功。
 *
 * 时间格式化在这一层（[AuditRowState.timeText]）；时刻由读口给，类内不读时钟。
 */
data class AuditState(
    val load: LoadState,
    /**
     * 筛选的项目号：`null` = 全部（**含**全局变更那条空串行）。
     *
     * 为什么空串单独成一档而不是并进「全部」：`registry` 改动不属于任何项目，
     * 而它改的是全局状态 —— 用户要能只挑出这一类看。见 [projectOptions]。
     */
    val projectFilter: String? = null,
    /** 全量条目（宿主读数的原样，**最新在最后** —— 那是文件里真实的顺序）。 */
    val entries: List<AuditRowState> = emptyList(),
) {
    /**
     * 筛选条上的选项：`null`（全部） + 出现过的项目号。
     *
     * 空串（全局变更）**排在最前**：它不是某个项目的记录，放中间会读成"某个叫空名字的项目"。
     * 排序稳定（项目号字典序），刷新不会让筛选条跳位。
     */
    val projectOptions: List<AuditFilterOption>
        get() {
            val ids = entries.map { it.projectId }.distinct()
            val global = ids.any { it.isEmpty() }
            return buildList {
                add(AuditFilterOption(null, "全部"))
                if (global) add(AuditFilterOption("", "全局"))
                ids.filter { it.isNotEmpty() }.sorted().forEach { add(AuditFilterOption(it, it)) }
            }
        }

    /** 当前筛选下要画的那些行（**最新在最上** —— 审计页的第一眼该是最近发生的事）。 */
    val visible: List<AuditRowState>
        get() = entries
            .filter { projectFilter == null || it.projectId == projectFilter }
            .asReversed()

    /** 当前筛选下的失败条数（状态栏那句话说它）。 */
    val failureCount: Int get() = visible.count { !it.success }

    /** 未筛选时总共几条失败 —— 筛选成别的项目时状态栏不该假装"全都成功"。 */
    val totalFailureCount: Int get() = entries.count { !it.success }

    companion object {
        val NOT_LOADED = AuditState(LoadState.NotLoaded)

        /**
         * 读到了：**保留仍在的筛选**（刷新不该把用户正在看的那个项目换掉 —— 与
         * `NpmState.of` 的 `previous` 参数同一条理由），筛选没了就回到「全部」。
         */
        fun of(entries: List<InstallHistoryEntry>, previous: AuditState = NOT_LOADED): AuditState {
            val rows = entries.map { AuditRowState.of(it) }
            val ids = rows.map { it.projectId }.distinct()
            return AuditState(
                load = LoadState.Loaded,
                projectFilter = previous.projectFilter?.takeIf { it in ids },
                entries = rows,
            )
        }

        /** 读失败：**保留 [previous] 已读到的那份**（一次瞬时失败不该把审计抹成空）。 */
        fun failed(t: Throwable, previous: AuditState = NOT_LOADED) =
            previous.copy(load = LoadState.of(t))
    }
}

/** 筛选条上的一格（[value] 为 null = 「全部」）。 */
data class AuditFilterOption(val value: String?, val label: String)

/**
 * 一条审计记录的呈现行。
 *
 * [detailText] 是**原文**（失败原因是异常消息、成功是摘要），不重编：那句「磁盘可用
 * 12MB < 预检下限 500MB」比任何概括都更能说明发生了什么。
 */
data class AuditRowState(
    val op: String,
    val projectId: String,
    val success: Boolean,
    val detail: String?,
    val atMillis: Long,
    val timeText: String,
) {
    /** 项目那一栏：空串 = 全局变更（不是「项目号丢了」）。 */
    val projectLabel: String get() = projectId.ifEmpty { "全局" }

    /**
     * 操作名的人话（[op] 未知时**原样显示**）。
     *
     * 为什么允许这里存在一张映射表（而 `NpmConsoleKeys` 那种判据必须唯一一份）：
     * 它**不是判据**，是标签 —— 猜错了顶多显示得不好看，不会放行一个不该放行的动作。
     * 而 `op` 本身原样保留在 [AuditRowState.op] 上，界面上任何地方都能看到真名。
     */
    val opLabel: String
        get() = when (op) {
            InstallHistoryOp.INSTALL -> "安装依赖"
            InstallHistoryOp.CI -> "按 lock 重建（ci）"
            InstallHistoryOp.UNINSTALL -> "卸载依赖"
            InstallHistoryOp.PRUNE -> "清理多余包（prune）"
            InstallHistoryOp.DEDUPE -> "依赖去重（dedupe）"
            InstallHistoryOp.REGISTRY -> "镜像源变更"
            InstallHistoryOp.IMPORT -> "离线包导入"
            InstallHistoryOp.EXPORT -> "快照导出"
            InstallHistoryOp.LS -> "列依赖（ls）"
            InstallHistoryOp.AUDIT -> "漏洞审计（audit）"
            InstallHistoryOp.UPDATE -> "更新依赖（update）"
            InstallHistoryOp.RUN_SCRIPT -> "跑脚本（npm run）"
            InstallHistoryOp.EXEC -> "跑依赖命令（npx）"
            InstallHistoryOp.INSTALL_SCRIPT -> "安装脚本"
            else -> op   // 认不出就照原样写：编一句「未知操作」是把"还不认识"说成"记录有问题"
        }

    /** 复制出去的那串字（与屏幕上看到的一致）。 */
    fun copyText(): String = buildString {
        append(timeText).append("  ").append(projectLabel).append("  ").append(opLabel)
        append(if (success) "  成功" else "  失败")
        detail?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
    }

    companion object {
        fun of(e: InstallHistoryEntry, zone: ZoneId = ZoneId.systemDefault()) = AuditRowState(
            op = e.op,
            projectId = e.projectId,
            success = e.success,
            detail = e.detail,
            atMillis = e.atMillis,
            timeText = TIME.format(Instant.ofEpochMilli(e.atMillis).atZone(zone)),
        )

        /**
         * `MM-dd HH:mm:ss`：审计要看的是「什么时候」，跨天是常态，光给时分秒会读不出是哪天
         * （与项目页文件日期同一条理由：`yy-MM-dd HH:mm` 那种粒度在这里太粗，一次安装会话
         * 里能落好几条）。
         */
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    }
}
