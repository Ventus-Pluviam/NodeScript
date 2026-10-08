package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import com.autoscript.ui.state.NpmState
import com.autoscript.ui.state.Status
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors

/**
 * 依赖管理页（管理面板 → 依赖管理；§10.9.1 依赖面板 + §10.9.2 审批卡）。
 *
 * 两个分段：**已装依赖**（`npm ls` 直读 lockfile 的权威清单 + 尺寸/配额）与
 * **待审批**（全局队列）。
 *
 * 这一屏刻意**不做**的三件事（做了就是撒谎）：
 * - 不画安装输入行/进度条：那要 `install` 会话（§10.9.1 的完整形态），而本屏读口
 *   只到"看"这一层。没有执行体时画一个按下去必失败的输入框，比不画更糟。
 * - 不画"依赖树"：`list(depth)` 的 depth 参数宿主侧目前只用 0（lockfile 是平铺的
 *   闭包，层级要真跑 `npm ls --all`）。画一棵假的树就是把平铺清单伪装成树。
 * - 不画 0% 配额条：尺寸没量到时显示「未量到」，不显示"这个项目不占地方"。
 *
 * 读取与刷新由外壳驱动（进入本页/手动刷新）；本屏只画，状态原样来自 [NpmState]。
 */
@Composable
fun NpmScreen(
    state: NpmState,
    onRefresh: suspend () -> Unit,
    onDecide: (requestId: String, approve: Boolean) -> Unit,
    onSelectProject: (String) -> Unit,
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
    val refresh = rememberRefreshAction { onRefresh() }
    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
        ActionBar(
            title = "依赖管理",
            onBack = onBack,
            subtitle = status.text,
            subtitleTone = status.tone,
            actions = { ActionBarAction("刷新", refresh::trigger) },
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
                InstalledRow(row)
                Separator()
            }
        }
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
    val label = state.quotaLabel ?: return
    val snap = state.project ?: return
    val fraction = snap.quotaFraction
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        ToneText("node_modules：$label", StatusTone.MUTED, style = MaterialTheme.typography.bodySmall)
        when {
            fraction == null -> ToneText("未量到", StatusTone.MUTED, style = MaterialTheme.typography.bodySmall)
            snap.overQuota -> ToneText(
                "已达配额上限：新的安装会被拒（先 prune 或删掉不用的包）",
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

@Composable
private fun InstalledRow(row: NpmRowState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(row.name, color = ThemeColors.text, modifier = Modifier.weight(1f))
        ToneText(row.version, StatusTone.MUTED)
    }
}
