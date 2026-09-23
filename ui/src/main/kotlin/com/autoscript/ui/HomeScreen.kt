package com.autoscript.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 首屏（UI 轨第一片竖切：壳状态 + 漏投账本 + 手动刷新）。
 *
 * 诚实边界：只画 [HomeState] 给的事实 —— 未接线/未就绪/漏投数各自明说，
 * 不把「装配中」渲染成「就绪」，也不把漏投吞成 0。
 * 自动刷新（ON_RESUME 已由 Activity 承担）之外提供手动刷新按钮：
 * 装配完成时刻不可预知，按钮是「现在再看一眼」的兜底。
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("AutoScript", style = MaterialTheme.typography.headlineMedium)
                StatusBlock(state)
                Button(onClick = onRefresh) {
                    Text("刷新状态")
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(state: HomeState) {
    val colors = MaterialTheme.colorScheme
    when {
        !state.summaryWired -> Text(
            "宿主摘要未接线（Application 未实现 HostSummary）",
            color = colors.error,
        )
        state.shellReady -> Text(
            "壳已就绪",
            color = colors.primary,
        )
        else -> Text(
            "壳未就绪（装配中或失败，原因见 logcat「壳自装配失败」）",
            color = colors.error,
        )
    }
    val missed = "漏投闹钟：${state.missedAlarms} 条"
    if (state.missedAlarms > 0) {
        // 有漏投不藏：那是「闹钟响了但调度未就绪」的账（§4.1），用户该知道。
        Text(missed, color = colors.tertiary)
    } else {
        Text(missed, color = colors.onSurfaceVariant)
    }
    // 保活（§8.7）：false 时熄屏的 SCREEN_ON 任务会被如实拒绝 —— 这条对用户是"任务为什么没跑"
    // 的直接答案，所以不藏在二级页里。
    if (state.keepAliveActive) {
        Text("保活已生效（前台服务 + 唤醒锁）", color = colors.primary)
    } else {
        Text(
            "保活未生效：熄屏的亮屏任务会被拒绝（点亮屏幕可正常运行）",
            color = colors.error,
        )
    }
}
