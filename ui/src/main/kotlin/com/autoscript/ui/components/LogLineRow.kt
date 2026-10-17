package com.autoscript.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoscript.ui.state.ConsoleLineState
import com.autoscript.ui.state.StatusTone

/**
 * 一行日志（2026-10-13：悬浮窗窗体与日志管理「脚本输出」详情共用）。
 *
 * 文案 = `time level text`（悬浮窗与详情都知道自己在看谁的输出，故**不带**
 * `[#runId]` —— 那个归属后缀只在"多执行混排"的旧脚本输出列表里有意义）。
 *
 * **本行不再自带点击**（2026-10-15 用户令「日志不要让它每次只能复制一行，去除单行
 * 复制限制」）：原来这里是"点一行复制那一行"的按钮，而那正是"一次只能复制一行"的
 * 根源。现在行是**纯文本**，选择与复制交给包住它的 `SelectionContainer`
 * （悬浮窗那处已包；见 `LogWindowContent` 的「日志可拖选」）—— 长按拖选任意范围，
 * 跨行也行。要加行为请由调用方包一层，别在这里重新长出 `pressable`。
 */
@Composable
fun LogLineRow(
    line: ConsoleLineState,
    modifier: Modifier = Modifier,
) {
    val text = "${line.timeText}  ${line.levelLabel}  ${line.text}"
    ToneText(
        text = text,
        tone = if (line.level == "error") StatusTone.PROBLEM else StatusTone.NEUTRAL,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 1.dp),
    )
}
