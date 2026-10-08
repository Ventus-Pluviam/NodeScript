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
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.rememberRefreshAction
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.RegistryState
import com.autoscript.ui.state.Status
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors

/**
 * 镜像源管理页（管理面板 → 镜像源管理；§10.9 第 8 条）。
 *
 * 编的是 **npm registry**（所有项目共用的缺省），不是脚本环境变量 —— 两者同住管理面板
 * 但一个喂 npm CLI、一个喂脚本进程，页首第一句就把这件事说清。
 *
 * 这一屏刻意**不做**的三件事（做了就是撒谎）：
 * - **不画镜像列表/一键切换**：那要 §10.9 第 6 条的首启引导（ping 探测 + 候选表），
 *   本批没做。画一个按下去只是填输入框的"快捷按钮"是在假装探测过；
 * - **不自己判地址合法性**：判据的唯一一份在 `:domain` 的 `NpmRegistryKeys.reject`
 *   （与宿主写入侧同一个），界面只显示原文；
 * - **不显示"已生效于所有项目"**：项目 `.npmrc` 可以覆盖全局这一层，界面读不到
 *   每个项目各自设了什么 —— 说"所有项目都已切过去"是编的，故写明"项目可单独覆盖"。
 *
 * 读取/刷新由外壳驱动（进入本页/手动刷新）；本屏只画，状态原样来自 [RegistryState]。
 */
@Composable
fun RegistryScreen(
    state: RegistryState,
    onRefresh: suspend () -> Unit,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = Status.count(
        load = state.load,
        notLoadedText = "尚未读取（点右上「刷新」现取）",
        total = if (state.snapshot == null) 0 else 1,
        emptyText = "尚未读取",
        unit = "项配置",
    )
    val refresh = rememberRefreshAction { onRefresh() }
    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
        ActionBar(
            title = "镜像源管理",
            onBack = onBack,
            subtitle = status.text,
            subtitleTone = status.tone,
            actions = { ActionBarAction("刷新", refresh::trigger) },
        )
        ToneText(
            "所有项目共用的 npm 下载源；某个项目自己的 .npmrc 可以单独覆盖它",
            StatusTone.MUTED,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        (state.load as? LoadState.Failed)?.let { failed ->
            ToneText(
                text = "读取失败：${failed.reason}（下面显示的是上次读到的，可能已过期）",
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
            item { CurrentCard(state) }
            item { Separator() }
            item { EditRow(state, onDraft, onSave, onReset) }
            item { Separator() }
            item { SecondaryNote(state) }
        }
    }
}

/** 当前生效值 + 出厂缺省。两行都是**读数**，不是写死的话。 */
@Composable
private fun CurrentCard(state: RegistryState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        val snap = state.snapshot
        Text(
            text = when {
                snap == null -> "当前生效：尚未读取"
                snap.customized -> "当前生效：${snap.effective}"
                else -> "当前生效：${snap.effective}（出厂缺省）"
            },
            color = ThemeColors.text,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (snap != null) {
            Text(
                text = "出厂缺省：${snap.defaultRegistry}",
                color = ThemeColors.text.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 输入行 + 「保存」「恢复出厂」。
 *
 * 裸 `BasicTextField`（同 `ScriptEnvScreen` 的新增行）：这行的版式是"输入框贴边、
 * 右侧两颗按钮"，`OutlinedTextField` 那套装饰在这里是多余的一圈描边。
 */
@Composable
private fun EditRow(
    state: RegistryState,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RegistryField(state.draft, onDraft, Modifier.weight(1f))
            PillButton("保存", selected = false, onClick = onSave)
            // 「恢复出厂」只在**确实改过**时可用：没改过时它是空操作，
            // 画成可点会让用户以为"点一下能把什么恢复回来"。
            PillButton("恢复出厂", selected = false, enabled = state.customized, onClick = onReset)
        }
        Text(
            text = "留空保存即恢复出厂缺省。只接受 https:// 地址",
            color = ThemeColors.text.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RegistryField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
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
                    text = "https://registry.example.com",
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
                cursorBrush = SolidColor(palette.cursor),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 第二意见那一句：它解释的是**为什么改了源仍会被拦**，不解释就会像 bug。 */
@Composable
private fun SecondaryNote(state: RegistryState) {
    val secondary = state.snapshot?.secondaryRegistry ?: return
    ToneText(
        text = "安装前会用另一家注册表交叉核对声明（当前第二意见：$secondary）。" +
            "两家说法不一致时会拒绝安装，这不是镜像源配错了。",
        tone = StatusTone.MUTED,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
