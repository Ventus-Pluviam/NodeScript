package com.autoscript.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * 版式（2026-10-15 用户令改）：**窗底 = 任务中心那种灰**（[ThemeColors.surfaceMuted]，
 * 与任务中心页底/顶栏同一个键），里面两块**胶囊框**（[RoundedCornerShape] + 内缩边距）：
 * 上面一块装标题行（标题 + 四颗图标钮），下面一块装日志。
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
    modifier: Modifier = Modifier,
) {
    // 窗底 = 任务中心的灰（[ThemeColors.surfaceMuted]：页底/顶栏同一个键），
    // 里面两块胶囊（头部、日志）各是一块浮起来的底 —— 用户口径「背景改成任务栏背景
    // 一样的灰色，然后用胶囊框包裹起来控件」。
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(ThemeColors.surfaceMuted)
            .padding(WindowBlockGap),
        verticalArrangement = Arrangement.spacedBy(WindowBlockGap),
    ) {
        // 头部（拖拽区）：标题 + 四钮。顺序用户令固定：暂停 / 清空 / 关闭 / 全屏。
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(elevation = WindowBlockElevation, shape = WindowBlockShape, clip = false)
                .clip(WindowBlockShape)
                .background(ThemeColors.surface)
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
                .fillMaxSize()
                .weight(1f)
                .shadow(elevation = WindowBlockElevation, shape = WindowBlockShape, clip = false)
                .clip(WindowBlockShape)
                .background(ThemeColors.surface),
        ) {
            LogBlock(state = state, lines = lines)
        }
    }
}

/**
 * 日志块的内容（三态）：未开始 / 空 / 行（**可拖选**）。
 *
 * 抽出来是因为 `LogWindowContent` 的主体已贴着 detekt 的 `LongMethod` 阈值（120），
 * 而这里三态各自还带一段"为什么这么画"的理由 —— 摊在主函数里两边都读不清。
 */
@Composable
private fun LogBlock(state: LogWindowState, lines: List<ConsoleLineState>) {
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

/** 窗内两块胶囊（头部/日志）的圆角。比外窗的 16dp 小一档 —— 胶囊套在胶囊里。 */
private val WindowBlockShape = RoundedCornerShape(12.dp)

/** 外窗内边距 = 两块胶囊之间、以及胶囊离窗边的距离（同一份，四周看起来才匀）。 */
private val WindowBlockGap = 8.dp

/**
 * 胶囊的抬起量（`TaskCenterScreen` 的搜索框同款 2dp）。
 *
 * 为什么要有它：胶囊与窗底是**同一套主题键的两个档**（`surface` 压在 `surfaceMuted` 上），
 * 浅色档下 `0xFFFFFFFF` 与 `0xFFF1F1F3` 只差一点点 —— 不给一点阴影，两块会糊成一片。
 * 深色档同理（`0xFF232326` vs `0xFF161E27`）。分层靠"底差一档 + 极轻阴影"，
 * 不靠第三档灰（与底页签胶囊同一条口径，见 `Chrome.kt` 的 `MainTabsShape` 那处）。
 */
private val WindowBlockElevation = 2.dp

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
        Glyph(kind = kind, tint = if (enabled) ThemeColors.textSecondary else ThemeColors.textTertiary, size = 20.dp)
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
