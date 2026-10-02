package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.LabeledRow
import com.autoscript.ui.components.SectionHeader
import com.autoscript.ui.components.TgDivider
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.TgScaffoldScreen
import com.autoscript.ui.state.HomeState
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.TgTheme

/**
 * 首屏（UI 轨第一片竖切：壳状态 + 漏投账本 + 手动刷新）。
 *
 * 版式照 Telegram 的「个人资料页」：标题进 [TgScaffoldScreen] 的顶栏，主体是一列
 * 两栏键值行（`项 / 值`），项一栏固定 88dp —— 这正是 TG 设置类页面的读法，
 * 比旧版「一行一个 24dp 标题 + 一行一个句子」省一半高度。
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
    TgScaffoldScreen(
        title = "AutoScript",
        modifier = modifier,
        subtitle = shellSubtitle(state),
        subtitleTone = state.shellTone,
        action = { ActionBarAction("刷新", onRefresh) },
    ) { contentModifier ->
        LazyColumn(
            modifier = contentModifier.background(TgTheme.colors.background),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                SectionHeader("运行状态")
                LabeledRow("壳状态", state.shellText, tone = state.shellTone)
                LabeledRow(
                    label = "宿主摘要",
                    value = if (state.summaryWired) "已接线" else "未接线",
                    tone = if (state.summaryWired) StatusTone.OK else StatusTone.PROBLEM,
                )
                TgDivider()
            }
            item {
                SectionHeader("保活与调度")
                // 保活（§8.7）：false 时熄屏的 SCREEN_ON 任务会被如实拒绝 —— 这条对用户是
                // "任务为什么没跑"的直接答案，所以摆在首屏第一屏，不藏在二级页里。
                LabeledRow(
                    label = "保活",
                    value = if (state.keepAliveActive) {
                        "已生效（前台服务 + 唤醒锁）"
                    } else {
                        "未生效：熄屏的亮屏任务会被拒绝（点亮屏幕可正常运行）"
                    },
                    tone = if (state.keepAliveActive) StatusTone.OK else StatusTone.PROBLEM,
                )
                LabeledRow(
                    label = "漏投闹钟",
                    // 有漏投不藏：那是「闹钟响了但调度未就绪」的账（§4.1），用户该知道。
                    value = "${state.missedAlarms} 条",
                    tone = if (state.missedAlarms > 0) StatusTone.ATTENTION else StatusTone.MUTED,
                )
            }
        }
    }
}

/** 顶栏副标题：一句现状（TG 的 ActionBar 副标题位）。没接线时说清是接线问题。 */
private fun shellSubtitle(state: HomeState): String = when {
    !state.summaryWired -> "宿主摘要未接线"
    state.shellReady && state.keepAliveActive -> "运行中"
    state.shellReady -> "运行中 · 保活未生效"
    else -> "装配中"
}
