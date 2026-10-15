package com.autoscript.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.Glyph
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.LogLineRow
import com.autoscript.ui.components.pressableLongPress
import com.autoscript.ui.state.ConsoleLineState
import com.autoscript.ui.theme.ThemeColors

/**
 * 执行日志悬浮窗的**内容**（2026-10-13 用户令；两个宿主共用同一份）。
 *
 * 版式（2026-10-16 用户令第三轮）：**窗 = 一张白卡片**（[WindowSurface] + 一圈淡灰描边
 * [ThemeColors.editorChipBorder]），里面**三块**同色白胶囊（[WindowBlockSurface]，
 * [RoundedCornerShape] + 内缩边距、**无描边**）：上面一块装标题行（标题 + 四颗图标钮）、
 * 中间一块装日志、最下面一块是**给脚本发输入的输入行**（输入框 + 蓝色箭头）。
 * 三轮口径的取舍写在 [WindowSurface] 那处。
 * 未绑定（执行还没进在途表 / **从开窗起一次都没绑上过**）→ 空态「本次执行未开始」。
 * 绑定那条跑完（结算）后窗口**保持**绑定、输出照常可见（用户令「脚本执行完不要
 * 自动清理输出」）—— 标题照旧只写路径，行区照常画；没出过一行且已结算的
 * 才画「本次执行没有输出」。
 *
 * **日志可拖选**（2026-10-15 用户令「日志不要让它每次只能复制一行，去除单行复制限制」）：
 * 日志那块包在 [SelectionContainer] 里 —— 长按拖出选择手柄，任意范围（跨行也行）
 * 都能选，再走系统的「复制」。**取代**了原来「点一行复制那一行」：那是"一次只能
 * 复制一行"的根源，用户要的是像看文本一样选。
 *
 * **为什么日志块用 `Column + verticalScroll` 而不是 `LazyColumn`**（性能上是退步，
 * 是有意换的）：foundation 1.7.3 的 `LazyList` **不认选择面**（`LazyList`/`LazyLayout`
 * 里一个 `SelectionRegistrar` 引用都没有，实测反编译确认），`SelectionContainer` 只能
 * 选到**已组合**的文本 —— 放在 lazy 里就是"只能选到屏幕上那几行、滚动出去就选不中"。
 * 窗里的行数本来就有界（`ConsoleCollector.DEFAULT_CAPACITY` = 2000，且清空/水位会削），
 * 而窗是"盯着看"的小面不是阅读器；用一次性组合换"选择真的能跨行跨屏"，这笔账划算。
 * 要回到 lazy 得等 foundation 把选择面接进 lazy（那时这段注释与实现一起改）。
 *
 * **全回调化、不读任何 CompositionLocal**：系统宿主是离屏 `ComposeView`
 * （没有 MainActivity 的 `Theme` 之外的提供链 —— `Theme` 由宿主自己包，
 * 见 `SystemLogWindowHost`；`LocalToast`/`LocalClipboardManager` 在窗外拿不到）。
 * 四个按钮全部经参数进来。
 */
@Composable
fun LogWindowContent(
    state: LogWindowState,
    lines: List<ConsoleLineState>,
    /**
     * 第一颗钮位的统一动作（2026-10-15 用户令「执行完变重跑」）：在途 = 暂停/继续
     * （[LogWindowController.togglePause]），已结算 = 重新执行（`LogWindowController.rerun`）。
     * 内容层只看 [state] 决定画什么图标与文案，动作本身由调用方按 [LogWindowState] 分派。
     */
    onFirstAction: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    onFullscreenToggle: () -> Unit,
    /** 头部拖拽：本次的位移增量（px，屏幕上的真实位移）。 */
    onDrag: (Offset) -> Unit,
    /**
     * 底部输入框的草稿变更（2026-10-16 用户令「在悬浮窗最下面放一个输入框」）。
     *
     * 草稿由调用方持有（`LogWindowState.inputDraft`）而不是内容层 `remember`：
     * 内容层是**无状态**的（两个宿主画同一个窗，见文件头 KDoc），`remember` 会在
     * 系统窗那次 `setContent` 重喂时把敲了一半的一行丢掉。
     */
    onInputDraft: (String) -> Unit,
    /** 底部那颗蓝色箭头（与输入框回车同一个动作）：把草稿那一行送进脚本进程的 stdin。 */
    onSendInput: () -> Unit,
    /**
     * 输入框拿到/失去焦点（2026-10-16）。**系统宿主必须接**：那颗窗全程带
     * `FLAG_NOT_FOCUSABLE`（"贴在旁边看"的窗不抢输入法），而不可聚焦的窗口打不出字 ——
     * 宿主要在拿到焦点那段时间把窗口变可聚焦（`SystemLogWindowHost.setImeFocus`）。
     * in-app 宿主是普通 Compose 树，输入法本来就能用，接了也只是转给控制器（它按
     * `usingSystemWindow` 决定要不要往下传）。
     */
    onImeFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 窗底 = **白 + 一圈淡灰描边**；里面两块胶囊是**灰底、无描边**
    // （2026-10-15 用户第二轮口径「背景换成白色（要有描边），胶囊框灰色，不要有描边试试」）。
    //
    // 第一轮是反过来的（灰窗底 + 白胶囊 + 阴影），用户看过说"效果不太好"——
    // 两块的色差在浅色档下太弱（白胶囊压浅灰底），加阴影也只是勉强分开。
    // 这一轮把层次反过来：**窗整体是"一张白卡片"，靠描边从页面里浮起来；
    // 内部两块用灰块分区**——灰压白的分辨率比白压灰高得多，且不用阴影。
    //
    // 描边用 [ThemeColors.editorChipBorder]（编辑器那颗白圆钮的"淡淡灰边"，
    // 浅色 `0xFFDCDCDC` / 深色 `0xFF4A5563`）—— 仓里现成的"白底 + 淡灰边"配方，
    // 不新造色键（深浅两档都是调好的）。
    Column(
        modifier
            // 顺序照 `ScriptEditorScreen` 那颗白圆钮的先例：`background(shape)` → `border(…, shape)`
            // —— 两者各带形状，不用再套一层 `clip`（多一层只是多一次绘制指令）。
            .background(WindowSurface, WindowShape)
            .border(WindowBorderWidth, ThemeColors.editorChipBorder, WindowShape)
            .padding(WindowBlockGap),
        verticalArrangement = Arrangement.spacedBy(WindowBlockGap),
    ) {
        // 头部（拖拽区）：标题 + 四钮。顺序用户令固定：暂停 / 清空 / 关闭 / 全屏。
        Row(
            Modifier
                .fillMaxWidth()
                .clip(WindowBlockShape)
                .background(WindowBlockSurface)
                .dragHandle(onDrag)
                .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.titleText,
                color = ThemeColors.text,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 第一颗钮位：暂停/继续/重跑三态（2026-10-15 用户令）。
            // - 在途且没暂停 → 暂停（点 = 暂停）；在途且已暂停 → 继续（点 = 恢复）；
            // - 已结算（跑完/被停/崩溃）→ **重跑**（用户令「执行完变重跑图标」）：
            //   点 = 重新执行同一文件，长按 = 也是重跑（同一语义，防"没长按对"）。
            WindowIconButton(
                kind = when {
                    state.paused -> GlyphKind.RESUME
                    state.running -> GlyphKind.PAUSE
                    else -> GlyphKind.RUN
                },
                description = when {
                    state.paused -> "继续执行"
                    state.running -> "暂停执行"
                    else -> "重新执行"
                },
                enabled = state.running || state.boundRunId != null,
                onClick = onFirstAction,
                onLongClick = onFirstAction,
            )
            WindowIconButton(kind = GlyphKind.CLEAR, description = "清空日志", onClick = onClear)
            WindowIconButton(kind = GlyphKind.CLOSE, description = "关掉悬浮窗", onClick = onClose)
            WindowIconButton(
                kind = GlyphKind.FULLSCREEN,
                description = if (state.fullscreen) "退出全屏" else "打开全屏",
                onClick = onFullscreenToggle,
            )
        }
        // 日志块：同一套胶囊（底 + 圆角），内容按状态三选一。
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(WindowBlockShape)
                .background(WindowBlockSurface),
        ) {
            LogBlock(state = state, lines = lines)
        }
        // 输入行（2026-10-16 用户令「在悬浮窗最下面放一个输入框，输入框右边放一个蓝色的
        // 箭头用于输入」）：**第三块**，与上面两块同一套胶囊几何。
        InputRow(
            draft = state.inputDraft,
            onDraftChange = onInputDraft,
            onSend = onSendInput,
            onImeFocusChange = onImeFocusChange,
        )
    }
}

/**
 * 底部输入行：灰底胶囊 + 裸 `BasicTextField` + 右侧蓝色箭头（[GlyphKind.SEND]）。
 *
 * **样式照首页搜索框**（用户口径）：`ThemeColors.fieldBackground` 那一档灰
 * （项目页/任务中心/控制台三处的输入框都是它）+ 两端全圆的药丸，高度 36dp
 * —— 比搜索栏矮一档，因为窗外框紧凑态只有 340×420dp，第三块再占 52dp 就把日志
 * 挤掉一行半（控制台的命令输入行也是 36dp，同一条取舍）。
 *
 * **回车即发送**（`ImeAction.Send`）：与右侧那颗箭头同一个动作 —— 敲完直接回车是
 * 输入框的肌肉记忆，不给它一条路等于逼用户每次把手移到箭头上（控制台的命令行
 * 同样是"回车即执行"）。
 *
 * **不自己管草稿**：`value`/`onValueChange` 全由调用方接（草稿住
 * `LogWindowState.inputDraft`，见那处的 KDoc —— 两个宿主画同一个窗，切宿主不该丢字）。
 */
@Composable
private fun InputRow(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onImeFocusChange: (Boolean) -> Unit,
) {
    val palette = ThemeColors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(WindowBlockShape)
            .background(WindowBlockSurface)
            .padding(horizontal = WindowBlockGap, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(36.dp)
                .background(palette.fieldBackground, RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (draft.isEmpty()) {
                Text(
                    text = "给脚本发一行输入",
                    color = palette.text.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.text),
                // 光标色是 `groupcreate_cursor`（[ThemeColors.cursor]），与强调色不是一个键
                // —— 与项目页/控制台两处输入框同一条口径。
                cursorBrush = SolidColor(palette.cursor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                // 焦点变化上报给宿主：系统窗要靠它把 `FLAG_NOT_FOCUSABLE` 摘掉，
                // 否则输入法根本不会起来（见 [onImeFocusChange] 的 KDoc）。
                modifier = Modifier.fillMaxWidth().onFocusChanged { onImeFocusChange(it.isFocused) },
            )
        }
        // 蓝色箭头（用户口径「蓝色的箭头」）：**用强调色**（`ThemeColors.accent`，
        // 浅色 #238AE3 / 深色 #5CC1FF —— 全仓的"可点的主色"就是它，不新造蓝）。
        // 触控格 36dp 与输入框同高（图标 20dp 居中），与标题行四颗钮同一套
        // [WindowIconButton] —— 按压缩放反馈、`contentDescription` 都跟着来。
        WindowIconButton(
            kind = GlyphKind.SEND,
            description = "把这一行送进脚本",
            onClick = onSend,
            tint = palette.accent,
        )
    }
}

/**
 * 日志块的内容（三态）：未开始 / 空 / 行（**可拖选**）。
 *
 * 抽出来是因为 `LogWindowContent` 的主体已贴着 detekt 的 `LongMethod` 阈值（120），
 * 而这里三态各自还带一段"为什么这么画"的理由 —— 摊在主函数里两边都读不清。
 *
 * **是 `BoxScope` 的扩展**（不是普通 composable）：空态那两句要 `Modifier.align(Center)`
 * 居中，而 `align` 只有 `BoxScope` 里有 —— 调用点就在那个 `Box { }` 里面，
 * 这个 receiver 是白拿的（CI 第一轮编译报 `Unresolved reference 'BlockHint'` 就是漏了它）。
 */
@Composable
private fun BoxScope.LogBlock(state: LogWindowState, lines: List<ConsoleLineState>) {
    if (state.boundRunId == null) {
        // **只有「从开窗起一次都没绑上过、兜底补绑也没补上」才到这里**：跑过的那条
        // 即使结算了也保持绑定（用户令「执行完不要自动清理输出」），起跑即结算的
        // 快速脚本由兜底补绑接住（见 `LogWindowState.bind` 的 fallbackRunId）——
        // 落这里的只剩：启动失败 / 门禁拒 / 起跑即被停的那一线，或跑得太快**一行都没出**
        // 就结束了（在途表没见到、行里也摘不出号）。
        BlockHint("本次执行未开始（启动失败、未通过门禁，或起跑即被停止）")
        return
    }
    if (lines.isEmpty()) {
        BlockHint(
            when {
                state.paused -> "已暂停 —— 恢复后输出继续"
                // 「已结算且一行都没有」：如实说没有输出，并指路持久全文 ——
                // 内存环（2000 行）理论上可能把这次的行挤掉，落盘那份不会
                // （`ScriptLogStore` 只追加），故不把"读不到"说成"没发生"。
                !state.running -> "本次执行没有输出（已结束）\n全文见 日志管理 → 脚本输出"
                else -> "等待输出…"
            },
        )
        return
    }
    // **可拖选**（见文件头「日志可拖选」）：`SelectionContainer` 包住整块，
    // 长按出选择手柄、任意范围（跨行）都能选。行本身不再是"点了复制一行"
    // 的按钮 —— 点击留给选择（拖选起点），复制走系统的选择菜单。
    val scroll = rememberScrollState()
    // 新行到底：行数变了就跟到尾（用户手动往上翻时也会被拉回 ——
    // P0 接受：悬浮窗是"盯着看"的面，不是"翻历史"的面，历史去详情页翻）。
    //
    // **`scroll.maxValue` 也进键**：`Column + verticalScroll` 的可滚区间是
    // **布局期**才算出来的，首帧组合时它还是 0 —— 只盯 `lines.size` 的话，
    // 第一屏内容到底部时那一次滚动会滚到 0（= 不动）。maxValue 一变就重跑，
    // 首屏因此也能到底（`LazyColumn` 时代不需要这一步：`animateScrollToItem`
    // 自己知道布局后的位置）。
    LaunchedEffect(lines.size, scroll.maxValue) {
        if (lines.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
    }
    SelectionContainer {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            lines.forEach { line ->
                LogLineRow(line = line, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 日志块的居中提示（未开始 / 空态共用）。 */
@Composable
private fun BoxScope.BlockHint(text: String) {
    Text(
        text = text,
        color = ThemeColors.textSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.align(Alignment.Center).padding(16.dp),
    )
}

/** 外窗圆角（16dp，与改版前一致）。 */
private val WindowShape = RoundedCornerShape(16.dp)

/** 窗内两块（头部/日志）的圆角。比外窗小一档 —— 块套在卡片里。 */
private val WindowBlockShape = RoundedCornerShape(12.dp)

/** 外窗内边距 = 两块之间、以及块离窗边的距离（同一份，四周看起来才匀）。 */
private val WindowBlockGap = 8.dp

/**
 * 窗底与块底（**三轮口径的落点**，2026-10-15 → 2026-10-16）。
 *
 * 三轮都是"哪一层是白的"这一个问题的不同答案：
 * 1. **第一轮**（用户令「背景改成任务栏背景一样的灰色，然后用胶囊框包裹起来控件」）：
 *    灰窗底（`surfaceMuted`）+ **白胶囊**（`surface`）+ 2dp 阴影 —— 用户看过说
 *    「效果不太好」：浅色档下白压浅灰只差一点点，阴影只是勉强补一点；
 * 2. **第二轮**（用户令「背景换成白色（要有描边），胶囊框灰色，不要有描边试试」）：
 *    白窗 + 1dp 淡灰描边 + **灰胶囊**（无描边、无阴影）—— 灰压白的分辨率高得多；
 * 3. **第三轮**（用户令「胶囊框换白色」；问询确认「窗底保持白+描边，胶囊也白」）：
 *    两块也回白 —— 于是整张卡片是**一个白面 + 一圈描边**，胶囊的形状不再可见。
 *    **这是用户的明确选择，不是漏改**：8dp 的块间距与 12dp 圆角仍在（结构没变），
 *    只是三块同色；要再把两块分出来，改 [WindowBlockSurface] 这一个键即可。
 *
 * **为什么两处都跟主题走、不写死白**：深色档的正文色 `ThemeColors.text` 是**白**的
 * —— 硬把底钉成白，标题与日志就白字白底，看不见。仓里那个"用户要求白色、深色也保持白"
 * 的先例（`editorChipBackground`）不适用：那颗圆钮里画的是**图标**（有独立 tint），
 * 不是正文。用户在浅色档下看到的正是白色（`surface` 浅色 = `0xFFFFFFFF`）。
 */
private val WindowSurface: Color
    @Composable @ReadOnlyComposable get() = ThemeColors.surface

/**
 * 三块（标题行 / 日志 / 输入行）的底。
 *
 * 当前值与 [WindowSurface] **同键**（第三轮口径：胶囊也白）—— 分成两个名字而不是
 * 直接复用，是因为它们是**两个可独立变的口径**（"窗多白、块多白"），三轮里已经
 * 各变过一次；合成一个常量下次再改就得先拆回来。
 */
private val WindowBlockSurface: Color
    @Composable @ReadOnlyComposable get() = ThemeColors.surface

/** 外窗描边宽（编辑器那颗白圆钮同款 1dp）。 */
private val WindowBorderWidth = 1.dp

/**
 * 悬浮窗标题行的一颗图标钮（28dp 触控格 + 按压缩放反馈走全仓 `pressable`）。
 *
 * [description] 进 `contentDescription`（读屏据此念「暂停执行」而不是「按钮」）——
 * 图标钮没有文字，无障碍名只能从这儿来。
 */
@Composable
private fun WindowIconButton(
    kind: GlyphKind,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: () -> Unit = {},
    /**
     * 图标色。缺省 = 次级灰（标题行那四颗的档）；底部那颗**发送箭头**用强调色
     * （用户口径「蓝色的箭头」）—— 它在这一行里是唯一的主动作，与四颗工具钮
     * 不是一个层级。
     */
    tint: Color? = null,
) {
    Box(
        modifier
            .size(36.dp)
            .semantics { contentDescription = description }
            .pressableLongPress(
                enabled = enabled,
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val fallback = if (enabled) ThemeColors.textSecondary else ThemeColors.textTertiary
        Glyph(kind = kind, tint = tint ?: fallback, size = 20.dp)
    }
}

/**
 * 头部拖拽手势：把 `positionChange()` 交给宿主
 * （系统窗 → `updateViewLayout` 改 x/y；in-app → 状态层 offset）。
 *
 * **不要"补偿"**（2026-10-14 用户实测"轻轻一动就移动出去很远"，起因就是补偿）：
 * Compose 的 `positionChange()` 已经把 `position` 与 `previousPosition` **用同一份
 * 当前坐标**换算过（事件派发时逐点 `screenToLocal`），所以差值恒等于屏幕上的真实位移
 * —— 窗自己挪了不会让它变小。再"补上一步落位量"就是**把位移加两遍**，而且逐帧累积：
 * 第 1 帧 10、第 2 帧 10+10=20、第 3 帧 10+20=30…… 窗越拖越快，正是"轻轻一动就飞出去"。
 *
 * **真正要防的是手势检测被重组打断**（2026-10-13 那次"不跟手"）：拖动每帧都改状态 →
 * 重喂内容 → 重组 → `pointerInput` 的 key 若是回调本身（每次新建实例），检测协程会被
 * 自己的重组取消重启，手指还没抬手势就断。故 key 恒为 [Unit]（检测器活过整场拖动），
 * 回调经 `rememberUpdatedState` 现取最新那份。
 *
 * **夹取**：手势层只管报位移，夹取在状态层做（它才知道窗多大、边界多宽）—— 窗贴边后
 * 位移继续报但落位被夹住，手指回退时坐标跟着回，天然不跳。
 */
@Composable
private fun Modifier.dragHandle(onDrag: (Offset) -> Unit): Modifier {
    val latest = rememberUpdatedState(onDrag)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                if (change.pressed) {
                    val delta = change.positionChange()
                    if (delta != Offset.Zero) latest.value(delta)
                }
                event.changes.forEach { it.consume() }
            }
        }
    }
}
