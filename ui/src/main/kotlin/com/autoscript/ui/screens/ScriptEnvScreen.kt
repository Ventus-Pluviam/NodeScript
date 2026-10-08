package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.autoscript.domain.scripts.ScriptEnvEntry
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.EmptyHint
import com.autoscript.ui.components.PillButton
import com.autoscript.ui.components.Separator
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.rememberRefreshAction
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.ScriptEnvState
import com.autoscript.ui.state.Status
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors

/**
 * 环境变量页（管理面板 → 环境变量；§8.1「脚本执行环境变量」）。
 *
 * 这一屏编的是**给脚本用的全局环境变量**：每一条在脚本执行时注入脚本进程的
 * `process.env`。页面顶部第一行就写明这条语义 —— "环境变量"这个词在别处也可以指
 * 宿主自己的配置，不说清楚就会被读成后者。
 *
 * 三件刻意不做的事：
 * - **不做"只对本项目生效"的开关**：契约就是全局一份（`ScriptEnvStore` 的 KDoc），
 *   画一个按下去没用的作用域选择器比不画更糟；
 * - **不自己判键名合法性**：那由 [ScriptEnvState.validate] → `:domain` 的
 *   `ScriptEnvKeys.reject` 裁决（含 `AUTOSCRIPT_` 保留前缀），界面只显示原文 ——
 *   界面另判一遍必与写入侧漂移；
 * - **不显示"已生效"**：改完下次执行才生效（引擎每次 spawn 现读），这一屏读不到
 *   任何执行态 —— 说"已生效"是编的。
 *
 * 读取/刷新由外壳驱动（进入本页/手动刷新）；本屏只画，状态原样来自 [ScriptEnvState]。
 */
@Composable
fun ScriptEnvScreen(
    state: ScriptEnvState,
    onRefresh: suspend () -> Unit,
    onDraftKey: (String) -> Unit,
    onDraftValue: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = Status.count(
        load = state.load,
        notLoadedText = "尚未读取（点右上「刷新」现取）",
        total = state.entries.size,
        emptyText = "还没有变量",
        unit = "个变量",
    )
    val refresh = rememberRefreshAction { onRefresh() }
    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
        ActionBar(
            title = "环境变量",
            onBack = onBack,
            subtitle = status.text,
            subtitleTone = status.tone,
            actions = { ActionBarAction("刷新", refresh::trigger) },
        )
        ToneText(
            "对所有脚本生效：每次执行时注入脚本进程的 process.env（下次执行起生效）",
            StatusTone.MUTED,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        // 读失败**保留**了上一次读到的表（见 ScriptEnvState.failed），故这里说的是
        // "这是上次读到的"，不是"没有变量" —— 两句话不能混。
        (state.load as? LoadState.Failed)?.let { failed ->
            ToneText(
                text = "读取失败：${failed.reason}（下表是上次读到的，可能已过期）",
                tone = StatusTone.PROBLEM,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
            item { AddRow(state, onDraftKey, onDraftValue, onAdd) }
            item { Separator() }
            if (state.load.isLoaded && state.entries.isEmpty()) {
                item { EmptyHint("读到了，还没有设过任何变量") }
            }
            items(state.entries, key = { it.key }) { row ->
                EnvRow(row, onRemove)
                Separator()
            }
        }
    }
}

/**
 * 新增行：两个格子 + 「添加」。
 *
 * 用裸 `BasicTextField`（同 `ProjectScreen` 的新建行）：这行的版式是"输入框贴边、
 * 右侧一颗按钮"，`OutlinedTextField` 那套装饰在这里是多余的一圈描边。
 */
@Composable
private fun AddRow(
    state: ScriptEnvState,
    onDraftKey: (String) -> Unit,
    onDraftValue: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EnvField(state.draftKey, "变量名", Modifier.weight(1f), onDraftKey)
        EnvField(state.draftValue, "值（可空）", Modifier.weight(1f), onDraftValue)
        PillButton("添加", selected = false, onClick = onAdd)
    }
}

@Composable
private fun EnvField(value: String, hint: String, modifier: Modifier, onChange: (String) -> Unit) {
    val palette = ThemeColors
    // 灰底圆角格（`SearchField` 同款 `fieldBackground`），高度压到 36dp —— 这一行
    // 右侧还要站一颗「添加」药丸，40dp 的搜索栏档会把行撑得比列表行高一截。
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
                    text = hint,
                    color = palette.text.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.text),
                // 光标色是 `groupcreate_cursor`（[ThemeColors.cursor]），与强调色**不是一个键**。
                cursorBrush = SolidColor(palette.cursor),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 一行已设变量。
 *
 * **值画成 `KEY=VALUE` 而不是两列**：`VALUE` 为空串时两列版式会把空值画成一个空洞
 * （读起来像"这个键没设"），而 `KEY=` 明确说的是"设了，值是空串" —— 与
 * `ScriptEnvEntry.value` 的契约（空串 ≠ 未设）一致。
 */
@Composable
private fun EnvRow(row: ScriptEnvEntry, onRemove: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${row.key}=${row.value}",
            color = ThemeColors.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        PillButton("删除", selected = false, onClick = { onRemove(row.key) })
    }
}
