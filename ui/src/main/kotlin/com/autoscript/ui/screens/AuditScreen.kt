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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.EmptyHint
import com.autoscript.ui.components.PillButton
import com.autoscript.ui.components.Separator
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.pressable
import com.autoscript.ui.components.rememberCopyAction
import com.autoscript.ui.components.rememberRefreshAction
import com.autoscript.ui.state.AuditRowState
import com.autoscript.ui.state.AuditState
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.Status
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors

/**
 * 审计页（管理面板 → 依赖管理 → 审计；§10.5-2）。
 *
 * 它消费 `HostSummary.npmHistory()` —— `install-history.jsonl` 的只读投影。此前那条读口
 * 在生产里**零消费方**（approve / registry 变更 / lock 重签都写进去了，没人读），
 * 于是「审计日志落 App 且可导出」这句话只有前半截成立。本页补上后半截。
 *
 * 这一屏刻意**不做**的三件事（做了就是撒谎）：
 * - **不做导出按钮**：§10.5-2 的原话是「落 App 且可导出」，而可导出的那条通道
 *   （SAF 选目录 + 写文件）本批没接。画一颗按下去什么都不发生的按钮比不画更糟。
 * - **不做筛选的服务端下推**：宿主那条读口**无参**（全量），筛在这里做 —— 按项目问会让
 *   `registry` 那条（`projectId` 空串的全局变更）掉出去。见 `PackageManagerFacade.history`。
 * - **不按 op 猜成败**：成败来自记录本身的 `success` 位（落盘侧写死的），不按 `op` 名字推断
 *   —— 「安装」这条既可能是成功也可能是失败。
 *
 * 读取/刷新由外壳驱动（进入本页/手动刷新）；本屏只画，状态原样来自 [AuditState]。
 */
@Composable
fun AuditScreen(
    state: AuditState,
    onRefresh: suspend () -> Unit,
    onFilter: (String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = state.visible
    val status = Status.count(
        load = state.load,
        notLoadedText = "尚未读取（点右上「刷新」现取）",
        total = visible.size,
        emptyText = "读到了，暂无审计记录",
        unit = "条记录",
    )
    val refresh = rememberRefreshAction { onRefresh() }
    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
        ActionBar(
            title = "审计",
            onBack = onBack,
            subtitle = status.text,
            subtitleTone = status.tone,
            actions = { ActionBarAction("刷新", refresh::trigger) },
        )
        ToneText(
            "依赖相关操作的记账（安装 / 卸载 / 镜像源变更 / 脚本执行 …）。" +
                "**失败也在这里** —— 审计要能回答「当时到底成没成」。点一行复制。",
            StatusTone.MUTED,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        // 筛选条只在**真的不止一档**时出现：一档时画一排只有一个格的控件，
        // 是在暗示"还有别的可以选"（与 NpmScreen 的项目选择器同一条理由）。
        if (state.projectOptions.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.projectOptions.forEach { opt ->
                    PillButton(
                        text = opt.label,
                        selected = opt.value == state.projectFilter,
                        onClick = { onFilter(opt.value) },
                    )
                }
            }
        }
        // 筛掉了失败行时必须说出来：否则「全部」那档看着干净，而用户以为审计是干净的。
        if (state.projectFilter != null && state.totalFailureCount > 0) {
            ToneText(
                text = "本筛选下 ${state.failureCount} 条失败；全部记录里共 ${state.totalFailureCount} 条失败",
                tone = if (state.failureCount > 0) StatusTone.PROBLEM else StatusTone.MUTED,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        (state.load as? LoadState.Failed)?.let { failed ->
            ToneText(
                text = "读取失败：${failed.reason}（下面显示的是上次读到的，可能已过期）",
                tone = StatusTone.PROBLEM,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = TabBarBottomClearance()),
        ) {
            if (state.load.isLoaded && visible.isEmpty()) {
                item { EmptyHint("读到了，这个筛选下没有记录") }
            }
            items(visible, key = { "${it.atMillis}-${it.op}-${it.projectId}" }) { row ->
                AuditRow(row)
                Separator()
            }
        }
    }
}

/**
 * 一条记录：时间 + 项目 + 操作 + 成败（+ 明细）。
 *
 * 成败**用颜色和字同时表达**（`ToneText` 的 tone + 行尾那个词）：只靠颜色的话，
 * 色觉障碍用户读不出这条到底成没成，而这是审计页上最该看清的一位。
 */
@Composable
private fun AuditRow(row: AuditRowState) {
    val copy = rememberCopyAction()
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(role = Role.Button, onClick = { copy.copy("已复制该条记录", row.copyText()) })
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.opLabel, color = ThemeColors.text, style = MaterialTheme.typography.bodyMedium)
            ToneText(if (row.success) "成功" else "失败", if (row.success) StatusTone.MUTED else StatusTone.PROBLEM)
        }
        ToneText("${row.timeText} · ${row.projectLabel}", StatusTone.MUTED, style = MaterialTheme.typography.bodySmall)
        row.detail?.takeIf { it.isNotBlank() }?.let {
            ToneText(
                text = it,
                tone = if (row.success) StatusTone.MUTED else StatusTone.PROBLEM,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
