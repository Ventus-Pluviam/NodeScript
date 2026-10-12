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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.PillButton
import com.autoscript.ui.components.Separator
import com.autoscript.ui.components.SettingsCard
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.rememberRefreshAction
import com.autoscript.ui.state.CONCURRENCY_PRESETS
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.ScriptLimitsState
import com.autoscript.ui.state.Status
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.state.defaultsOf
import com.autoscript.ui.theme.ThemeColors

/**
 * 脚本设置页（设置 → 脚本设置；§8.1「脚本资源限额」）。
 *
 * 这一屏编的是**两类不同资源的上限**，所以顶部第一段就分栏说明 —— 「内存上限」这四个字
 * 在别处也可以指宿主内存，不说清楚就会被读成后者：
 *
 * - **单脚本**（前两行）：限的是**这一个脚本进程**，堆（MB）与它的 IO 后台线程数；
 * - **桥**（后三行）：限的是**所有脚本合计**、按连接切开的在途请求与处理并行度。
 *   每行标题都带「每连接」三个字 —— 没有它，用户会把这三行当成「单脚本的内存/线程」，
 *   调小之后得到的是「别人的请求也被拒」。
 *
 * **「每连接并发处理数」给预设档而不是纯数字框**（与其余四行不同）：线程数不是「越大越好」
 * 的连续量，而是「够用/过剩」的档位 —— 2 适合只做点按的脚本，16 在手机上通常反而更慢
 * （线程互抢 CPU）。给 2/4/8/16 四档比给一个输入框少踩坑；旁边保留一个数字框兜住
 * 「都不是我想要的」，故**不是二选一**，只是把常见值摆到台面上。
 *
 * 三件刻意不做的事（与 [ScriptEnvScreen] 同一份理由）：
 * - **不显示「已生效」**：单脚本那半只在下次 spawn 生效，桥那半立即生效，两者的生效时机
 *   不同且本页读不到任何执行态 —— 一律写「立即生效，下一次执行/请求起」并分开说；
 * - **不自己判上限**：[ScriptLimitsState.draftToLimits] 委托 `:domain` 的唯一一份，
 *   界面另判一遍必与写入侧漂（「界面放行、写入拒绝」或更糟的反面）；
 * - **不做「按项目分级」**：契约是全局一份（`ScriptRuntimeLimits` 的 KDoc），画一个按下去
 *   没用的选择器比不画更糟。
 */
@Composable
fun ScriptLimitsScreen(
    state: ScriptLimitsState,
    onRefresh: suspend () -> Unit,
    onDraft: (field: ScriptLimitsDraft, value: String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = Status.count(
        load = state.load,
        notLoadedText = "尚未读取（点右上「刷新」现取）",
        total = 5,
        emptyText = "未读取",
        unit = "项限额",
    )
    val refresh = rememberRefreshAction { onRefresh() }
    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
        ActionBar(
            title = "脚本设置",
            onBack = onBack,
            subtitle = status.text,
            subtitleTone = status.tone,
            actions = { ActionBarAction("刷新", refresh::trigger) },
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 8.dp, bottom = TabBarBottomClearance()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 三行状态**在列表里**而不是 ActionBar 之下：读失败那句「下面是上次读到的」
            // 得跟着下面那几行一起看，否则用户会把它当成当前值。
            (state.load as? LoadState.Failed)?.let { failed ->
                item {
                    SettingsCard {
                        ToneText("读取失败：${failed.reason}（下面是上次读到的，可能已过期）", StatusTone.PROBLEM)
                    }
                }
            }
            state.opError?.let {
                item { SettingsCard { ToneText(it, StatusTone.PROBLEM) } }
            }
            state.opNotice?.let {
                item { SettingsCard { ToneText(it, StatusTone.MUTED) } }
            }
            item {
                SettingsCard {
                    GroupTitle("单脚本进程", "限的是这一个脚本进程自己")
                    LimitRow("堆上限", "MB", state.draftHeapMb, { onDraft(ScriptLimitsDraft.HEAP, it) }, "192")
                    Separator()
                    LimitRow("IO 后台线程数", "", state.draftUvThreads, { onDraft(ScriptLimitsDraft.UV, it) }, "4")
                    GroupNote(
                        "堆上限落 Node 的 --max-old-space-size；IO 线程数落 UV_THREADPOOL_SIZE" +
                            "（Node 缺省就是 4，不改则不注入）",
                    )
                }
            }
            item {
                SettingsCard {
                    GroupTitle("桥（所有脚本合计，按连接切）", "下面三项每一项都是「每一条连接」的上限")
                    LimitRow("每连接在途请求数", "条", state.draftConnInflight, { onDraft(ScriptLimitsDraft.INFLIGHT, it) }, "64")
                    Separator()
                    LimitRow("每连接在途字节", "MB", state.draftConnBytesMb, { onDraft(ScriptLimitsDraft.BYTES, it) }, "32")
                    Separator()
                    LimitRow(
                        "每连接并发处理数", "", state.draftConnThreads, { onDraft(ScriptLimitsDraft.THREADS, it) },
                        defaultsOf().bridgeReadThreadsPerConnection.toString(),
                        presets = CONCURRENCY_PRESETS,
                    )
                    GroupNote(
                        "在途 = 已被受理但还没回的请求；被拒时脚本收到 ERR_PERMISSION_DENIED" +
                            "，话术点名是哪一道闸（不是参数错）。改完下一次请求即生效。",
                    )
                }
            }
            item {
                SettingsCard {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PillButton("保存", selected = false, onClick = onSave)
                        PillButton(
                            "恢复出厂",
                            selected = false,
                            enabled = state.limits != null,
                            onClick = onReset,
                        )
                        ToneText(
                            "立即生效：单脚本下次执行起，桥侧下一次请求起",
                            StatusTone.MUTED,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp)) {
        ToneText(title, StatusTone.NEUTRAL, style = MaterialTheme.typography.bodyMedium)
        ToneText(subtitle, StatusTone.MUTED, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GroupNote(text: String) {
    ToneText(
        text,
        StatusTone.MUTED,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    )
}

/**
 * 一行限额：标题 + 单位 + 输入框（+ 可选预设档）。
 *
 * 输入框的形制照 [RegistryScreen] 的 `RegistryField`（裸 [BasicTextField] + 36dp 底色块）——
 * **不换控件**：那条 KDoc 解释了为什么不用 `OutlinedTextField`（版式与设置页其余行不一致，
 * 且带默认下划线的形态在这个深色底上要另配一套颜色）。
 */
@Composable
private fun LimitRow(
    title: String,
    unit: String,
    value: String,
    onChange: (String) -> Unit,
    defaultHint: String,
    presets: List<Int> = emptyList(),
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToneText(
                if (unit.isEmpty()) title else "$title（$unit）",
                StatusTone.NEUTRAL,
                modifier = Modifier.weight(1f),
            )
            NumberField(value, onChange, Modifier.width(88.dp))
        }
        if (presets.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                presets.forEach { n ->
                    PillButton(
                        n.toString(),
                        selected = value.trim() == n.toString(),
                        onClick = { onChange(n.toString()) },
                    )
                }
                ToneText(
                    "预设档（出厂 ${defaultHint}）",
                    StatusTone.MUTED,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 裸 [BasicTextField] 的数字输入（形制理由见 [LimitRow]）。 */
@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val palette = ThemeColors
    Row(
        modifier
            .height(36.dp)
            .background(palette.fieldBackground, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.text),
                cursorBrush = SolidColor(palette.cursor),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
