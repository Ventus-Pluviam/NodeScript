package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.EmptyHint
import com.autoscript.ui.components.PillButton
import com.autoscript.ui.components.Separator
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.rememberRefreshAction
import com.autoscript.ui.state.ApprovalRowState
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.NpmRowState
import com.autoscript.ui.state.InstallProgressState
import com.autoscript.ui.state.NpmState
import com.autoscript.domain.npm.NpmMaintenanceAction
import com.autoscript.ui.state.Status
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors

/**
 * 依赖管理页（管理面板 → 依赖管理；§10.9.1 依赖面板 + §10.9.2 审批卡）。
 *
 * 两个分段：**已装依赖**（`npm ls` 直读 lockfile 的权威清单 + 尺寸/配额）与
 * **待审批**（全局队列）。
 *
 * 顶栏那颗「审计」进 [AuditScreen]（§10.5-2）：它记的就是**本页这些操作**发生过什么
 * （安装 / 卸载 / 镜像源变更 / 审批放行…），所以从本页进而不是从管理面板当第五项 ——
 * 从面板直进会让人以为它与依赖管理是并列的另一件事。
 *
 * 这一屏刻意**不做**的几件事（做了就是撒谎）：
 * - **不画百分比进度**：`InstallEvent.Progress.percent` 全仓从无赋值，npm 进程内的
 *   reify 是黑盒 —— 画一条会动的百分比条就是编一个拿不到的数。画的是六档**阶段条**
 *   （见 [InstallProgressState]）。§10.9 第 1 条原文写的「job 数」同理拿不到。
 * - 不画"依赖树"：`list(depth)` 的 depth 参数宿主侧目前只用 0（lockfile 是平铺的
 *   闭包，层级要真跑 `npm ls --all`）。画一棵假的树就是把平铺清单伪装成树。
 * - 不画 0% 配额条：尺寸没量到时显示「未量到」，不显示"这个项目不占地方"。
 * - **不画 registry 选择器**：镜像源是**全局**配置面（§10.9 第 8 条，管理面板 →
 *   镜像源管理），在这里再放一个就是第二份 registry 判据，还会让人以为"这次用这家、
 *   下次用那家"。
 * - **不画 `hasInstallScript` 前置告警**：那要 packument 解析面，今天没有 ——
 *   界面不假装自己知道装之前该警告什么（装完之后的 `SCRIPTS_SKIPPED` 是**事后**的）。
 *
 * **变更半边已落（2026-10-09 批 87）**：原文写的是「不画安装输入行/进度条」——
 * 那条口径的前提是"没有执行体"，而执行体（`runConsoleCommand`）批 84 已经落了。
 * 本批补上输入行 + 两颗旗标 + 阶段条 + 清单行「卸载」，走的是与控制台**同一条**宿主口
 * （门禁强度不取决于用户从哪个界面按下去）。
 *
 * 维护动作（§10.9 第 5 条的动作半边，2026-10-09 批 86）落在配额条下面一行：
 * `prune` / `dedupe` / `ci` 重装 / 缓存回收。在此之前配额满了那句提示把用户指去**控制台**
 * 敲 `npm prune` —— 那条路是通的，但「有路可走」不等于「有一键」，而这个页面的全部意义
 * 就是让用户在这一屏把地方腾出来。
 *
 * 读取与刷新由外壳驱动（进入本页/手动刷新）；本屏只画，状态原样来自 [NpmState]。
 */
@Composable
fun NpmScreen(
    state: NpmState,
    onRefresh: suspend () -> Unit,
    onDecide: (requestId: String, approve: Boolean) -> Unit,
    onSelectProject: (String) -> Unit,
    onMaintenance: (NpmMaintenanceAction) -> Unit,
    onReclaimCache: () -> Unit,
    onOpenAudit: () -> Unit,
    onInstallDraft: (String) -> Unit,
    onToggleDev: (Boolean) -> Unit,
    onToggleOffline: (Boolean) -> Unit,
    onSubmitInstall: () -> Unit,
    onRemoveInstalled: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = Status.count(
        load = state.load,
        notLoadedText = "尚未读取（点右上「刷新」现取）",
        total = state.installed.size,
        emptyText = "这个项目还没有依赖",
        unit = "个已装包",
    )
    // 「刷新」兼作进度续拉（`:ui` 没有常驻轮询循环，见 `pollInstallEvents` 的 KDoc）：
    // 宿主是入队即返回的，一次现取多半只拿到 `QUEUED`，再点一次就能看到下一步。
    // 续拉在 `MainActivity.reloadNpm()` 里与快照一起发生 —— 本屏不持有读口。
    val refresh = rememberRefreshAction { onRefresh() }
    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
        ActionBar(
            title = "依赖管理",
            onBack = onBack,
            subtitle = status.text,
            subtitleTone = status.tone,
            actions = {
                ActionBarAction("审计", onOpenAudit)
                ActionBarAction("刷新", refresh::trigger)
            },
        )
        // 读失败带原文（与日志管理页同一条：失败是**一句带原文的话**，不是一个空列表）。
        (state.load as? LoadState.Failed)?.let { failed ->
            ToneText(
                text = "读取失败：${failed.reason}",
                tone = StatusTone.PROBLEM,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        state.opError?.let {
            ToneText(it, StatusTone.PROBLEM, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        state.opNotice?.let {
            ToneText(it, StatusTone.MUTED, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = TabBarBottomClearance()),
        ) {
            // 项目选择只在**真的不止一个**项目时出现：一个项目时画一排只有一个格的
            // 分段控件，是在暗示"还有别的可以选"。
            val projects = state.snapshot?.projects.orEmpty()
            if (projects.size > 1) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        projects.forEach { p ->
                            PillButton(
                                text = p.projectId,
                                selected = p.projectId == state.selectedProjectId,
                                onClick = { onSelectProject(p.projectId) },
                            )
                        }
                    }
                }
            }
            item { QuotaCard(state) }
            item {
                InstallCard(
                    state = state,
                    onDraft = onInstallDraft,
                    onToggleDev = onToggleDev,
                    onToggleOffline = onToggleOffline,
                    onSubmit = onSubmitInstall,
                )
            }
            item { MaintenanceCard(state, onMaintenance, onReclaimCache) }
            item { SectionTitle("待审批（${state.pending.size}）") }
            if (state.load.isLoaded && state.pending.isEmpty()) {
                item { EmptyHint("没有等待人工决定的审批") }
            }
            items(state.pending, key = { it.requestId }) { row ->
                ApprovalCard(row, enabled = !state.deciding, onDecide = onDecide)
                Separator()
            }
            item { SectionTitle("已装依赖（${state.installed.size}）") }
            if (state.load.isLoaded && state.installed.isEmpty()) {
                item { EmptyHint("读到了，这个项目还没有依赖（先装点什么）") }
            }
            items(state.installed, key = { "${it.name}@${it.version}" }) { row ->
                InstalledRow(row, enabled = state.canInstall, onRemove = onRemoveInstalled)
                Separator()
            }
        }
    }
}

/**
 * 安装输入行 + 旗标 + 阶段进度条（§10.9 第 1 条的变更半边，2026-10-09 批 87）。
 *
 * **两颗旗标只有两颗**（`-D` 与「离线优先」）：§10.9 第 1 条还提到「registry 选择器」，
 * 但镜像源已经是一个**全局**配置面（§10.9 第 8 条，管理面板 → 镜像源管理），
 * 在这里再放一个选择器就是第二份 registry 判据 —— 而且它会让人以为"这次安装用这家、
 * 下次用那家"，与"全局缺省"那条口径直接冲突。
 *
 * **进度条是六档阶段条，不是百分比**（见 [InstallProgressState] 的 KDoc）：
 * job 数拿不到，画百分比就是编一个数。
 *
 * **不画 `hasInstallScript` 前置告警**：那要 packument 解析面，今天没有。
 * 装完之后的 `SCRIPTS_SKIPPED` 警告在事件流里（控制台可见），那是**事后**的，不是事前的 ——
 * 界面不假装自己知道装之前该警告什么。
 */
@Composable
private fun InstallCard(
    state: NpmState,
    onDraft: (String) -> Unit,
    onToggleDev: (Boolean) -> Unit,
    onToggleOffline: (Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        ToneText("安装（装进当前项目的 node_modules）", StatusTone.MUTED, style = MaterialTheme.typography.bodySmall)
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InstallField(
                value = state.installDraft,
                onChange = onDraft,
                enabled = state.canInstall,
                onSubmit = onSubmit,
                modifier = Modifier.weight(1f),
            )
            PillButton(
                text = if (state.installing) "提交中…" else "安装",
                selected = state.installing,
                enabled = state.canInstall && state.installDraft.isNotBlank(),
                onClick = onSubmit,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton(
                text = "-D（开发依赖）",
                selected = state.installDev,
                enabled = state.canInstall,
                onClick = { onToggleDev(!state.installDev) },
            )
            PillButton(
                text = "离线优先",
                selected = state.installOffline,
                enabled = state.canInstall,
                onClick = { onToggleOffline(!state.installOffline) },
            )
        }
        // 「离线优先」不是「仅离线」—— 这条旗标让 npm 先查缓存、缺了仍会联网。
        // 用一个词把两件事混成一件，用户会在断网时以为"勾了就能装上"。
        ToneText(
            "「离线优先」= 先查本地缓存，缺的仍会联网；真正断网要装，靠的是缓存里恰好有全部依赖。",
            StatusTone.MUTED,
            style = MaterialTheme.typography.bodySmall,
        )
        state.installProgress?.let { p -> InstallProgressRow(p) }
    }
}

/** 输入框（与 `ConsoleScreen.CommandField` 同形；`:ui` 里两个地方各画一份，不抽公共件 —— 两者提示语与禁用条件都不同）。 */
@Composable
private fun InstallField(
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier,
) {
    val palette = ThemeColors
    Row(
        modifier
            .height(36.dp)
            .background(palette.fieldBackground, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = "axios 或 axios@1.7.0",
                    color = palette.text.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) palette.text else palette.text.copy(alpha = 0.5f),
                ),
                cursorBrush = SolidColor(palette.featuredButton),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (enabled) onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 阶段条：六档排开，点亮到当前那一档。
 *
 * 收尾之后（[InstallProgressState.done]）**保留一行结论**而不是整条消失：
 * 「装完了 / 失败了 + 病因」是这次操作唯一需要留在屏幕上的东西，抹掉它等于让用户
 * 自己去清单里找变化。成功时不再画阶段格子（那六格已经没有信息量）。
 */
@Composable
private fun InstallProgressRow(p: InstallProgressState) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        if (p.done) {
            ToneText(
                text = if (p.ok) (p.detail ?: "安装完成") else "失败：${p.detail ?: "（无详情）"}",
                tone = if (p.ok) StatusTone.MUTED else StatusTone.PROBLEM,
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val reached = InstallProgressState.ORDER.indexOf(p.phase)
            InstallProgressState.ORDER.forEachIndexed { i, _ ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .background(
                            color = if (i <= reached) ThemeColors.featuredButton else ThemeColors.fieldBackground,
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
        ToneText(
            text = p.label + (p.pkg?.let { "  $it" } ?: ""),
            tone = StatusTone.MUTED,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = ThemeColors.textTertiary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * 尺寸/配额一行（§10.9.5 的配额条）。
 *
 * 三态各自说一句：没读到 → 不画；量到了 → 数字 + 条；配额为 0 → 只给数字不给比例
 * （除零会画出 NaN%，那比不画更糟）。黄/红两档与宿主侧发 `DISK_QUOTA` 警告、
 * 以及 100% 拦安装的判据**同源**（[com.autoscript.domain.npm.NpmProjectSnapshot]）。
 */
@Composable
private fun QuotaCard(state: NpmState) {
    val label = state.quotaLabel
    val snap = state.project
    // npm 缓存那一行（§10.9 第 5 条的 `npm-cache` 尺寸栏，2026-10-09 批 87）：
    // **与 node_modules 那行并列画在同一张卡里** —— 两者都是"尺寸读数"，且用户看的就是
    // 它们之间的关系（node_modules 占了多少 / 缓存里还压着多少）。但两行**各自独立**
    // 判空：量不到 node_modules 不该把缓存那一行也吞掉（反之亦然）——
    // 缓存是全局读数，项目目录出问题时它照样是有效的。
    val cache = state.cacheLabel
    if (label == null && cache == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (label != null && snap != null) {
            ToneText("node_modules：$label", StatusTone.MUTED, style = MaterialTheme.typography.bodySmall)
            when {
                snap.quotaFraction == null ->
                    ToneText("未量到", StatusTone.MUTED, style = MaterialTheme.typography.bodySmall)
                snap.overQuota -> ToneText(
                    "已达配额上限：新的安装会被拒（用下面的「清理多余包」腾地方）",
                    StatusTone.PROBLEM,
                    style = MaterialTheme.typography.bodySmall,
                )
                snap.quotaWarned -> ToneText(
                    "已用超过 80%：再装大包可能撞上限",
                    StatusTone.ATTENTION,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        // 文案里那句「全机一份」由 [NpmState.cacheLabel] 给（缓存按内容寻址、跨项目共享，
        // 写成「本项目缓存」会让人以为删掉这个项目就能腾出这些字节）。
        cache?.let { ToneText(it, StatusTone.MUTED, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * 维护动作一行（§10.9 第 5 条「包大小管理页」的动作半边）。
 *
 * 四颗按钮，**每颗都写清它改的是什么**（不靠用户猜 `dedupe` 是什么意思）：
 * `prune`/`dedupe`/`ci` 入队走安装会话（几十秒量级），缓存回收是瞬间的本地删除 ——
 * 后者的回执是**一次算出来的读数**而不是"入队了"，两者在界面上不共用一套措辞。
 *
 * **不做「cache clean 全清」**（用户 2026-10-09 裁定）：§10 整卷的离线能力全建在
 * npm 缓存上，全清等于把紧挨着的「按 lock 重装」变成必须联网。这颗按钮删的是
 * 「没有任何项目 lock 需要的那些」，按钮文案因此写「回收」而不是「清空」。
 *
 * 三颗维护按钮在任一动作进行中**整体禁用**（`globalSession` 全局互斥，同时只跑一个
 * 安装会话 —— 只禁被按下的那颗会让另外两颗看起来还能按，按下去是排队，用户以为卡了）。
 * 被按下的那颗显示「进行中」，所以 [NpmState.maintenance] 记的是**哪一个**。
 */
@Composable
private fun MaintenanceCard(
    state: NpmState,
    onMaintenance: (NpmMaintenanceAction) -> Unit,
    onReclaimCache: () -> Unit,
) {
    val busy = state.maintenance
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        ToneText(
            "维护（都在当前项目的 node_modules 上）",
            StatusTone.MUTED,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 三颗维护按钮在**任一动作**进行中整体禁用（`globalSession` 全局互斥，同时只跑一个
            // 安装会话）—— 包括安装输入行那次：只禁被按下的那颗会让另外几颗看起来还能按，
            // 按下去是排队，用户以为卡了。
            val enabled = state.canInstall || busy != null
            MaintenanceButton("清理多余包", NpmMaintenanceAction.PRUNE, busy, enabled, onMaintenance)
            MaintenanceButton("依赖去重", NpmMaintenanceAction.DEDUPE, busy, enabled, onMaintenance)
            MaintenanceButton("按 lock 重装", NpmMaintenanceAction.CI, busy, enabled, onMaintenance)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton(
                text = if (state.reclaimingCache) "回收中…" else "回收缓存",
                selected = false,
                enabled = !state.reclaimingCache && busy == null && !state.installing,
                onClick = onReclaimCache,
            )
        }
        // 这一句不是装饰：这颗按钮删的是别的项目离线重装要用的东西的**补集**，
        // 用户有权在按之前知道保留判据是什么。
        ToneText(
            "回收缓存只删没有任何项目 lock 需要的包（离线重装仍可用）；" +
                "「按 lock 重装」会先验 lock 签名，签名不对会被拒。",
            StatusTone.MUTED,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 一颗维护按钮：进行中的那颗显示「…中」，其余禁用（见 [MaintenanceCard] 的 KDoc）。 */
@Composable
private fun MaintenanceButton(
    label: String,
    action: NpmMaintenanceAction,
    busy: NpmMaintenanceAction?,
    enabled: Boolean,
    onClick: (NpmMaintenanceAction) -> Unit,
) {
    PillButton(
        text = if (busy == action) "$label…" else label,
        selected = busy == action,
        enabled = enabled,
        onClick = { onClick(action) },
    )
}

/**
 * 一张审批卡（§10.9.2）：包名 + 动作 + 风险说明 + 批准/拒绝。
 *
 * **不做「全局禁止脚本」那颗开关**：出厂默认已经是禁止（硬编码 `--ignore-scripts`
 * 是主控，§11.3 第 8 条），再放一颗"禁止"开关要么是重复、要么会被读成"现在允许"。
 * 真要做的是"白名单放行"那条反向通道，那是 T1 落地之后的事。
 */
@Composable
private fun ApprovalCard(row: ApprovalRowState, enabled: Boolean, onDecide: (String, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(row.pkg, style = MaterialTheme.typography.titleMedium, color = ThemeColors.text)
        ToneText("${row.projectId} · ${row.actionLabel} · ${row.versionHashShort}", StatusTone.MUTED)
        ToneText(row.riskNote, StatusTone.ATTENTION, style = MaterialTheme.typography.bodySmall)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton("批准", selected = false, enabled = enabled, onClick = { onDecide(row.requestId, true) })
            PillButton("拒绝", selected = false, enabled = enabled, onClick = { onDecide(row.requestId, false) })
        }
    }
}

/**
 * 已装包一行：名字 + 版本 + 「卸载」。
 *
 * **卸载那颗按钮走的是命令通道**（`npm uninstall <name>` → `runNpmPanelCommand`），
 * 不是另开一条 `facade.uninstall` 的宿主口 —— 门禁强度不该取决于用户从哪个界面按下去
 * （与安装输入行同一条纪律）。包名来自这份清单（宿主读 lockfile 的结果），不是用户敲的。
 */
@Composable
private fun InstalledRow(row: NpmRowState, enabled: Boolean, onRemove: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.name, color = ThemeColors.text, modifier = Modifier.weight(1f))
        ToneText(row.version, StatusTone.MUTED)
        PillButton(
            text = "卸载",
            selected = false,
            enabled = enabled,
            onClick = { onRemove(row.name) },
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
