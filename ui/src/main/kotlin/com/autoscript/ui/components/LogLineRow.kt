package com.autoscript.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.autoscript.ui.state.ConsoleLineState
import com.autoscript.ui.state.StatusTone

/**
 * 一行日志（2026-10-13：悬浮窗窗体与日志管理「脚本输出」详情共用）。
 *
 * 文案 = `time level text`（悬浮窗与详情都知道自己在看谁的输出，故**不带**
 * `[#runId]` —— 那个归属后缀只在"多执行混排"的旧脚本输出列表里有意义）。
 * 点一行复制（`onCopy` 由调用方给：in-app 用 `rememberCopyAction`，系统窗
 * （ComposeView 离屏）拿不到 `LocalToast`，由控制器传直连回调 ——
 * 见 LogWindowContent 的 KDoc）。
 */
@Composable
fun LogLineRow(
    line: ConsoleLineState,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = "${line.timeText}  ${line.levelLabel}  ${line.text}"
    ToneText(
        text = text,
        tone = if (line.level == "error") StatusTone.PROBLEM else StatusTone.NEUTRAL,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier
            .pressable(role = Role.Button, onClick = { onCopy(text) })
            .padding(horizontal = 16.dp, vertical = 1.dp),
    )
}
