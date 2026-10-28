package com.autoscript.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.ui.theme.ThemeColors

/**
 * 可折叠分组头（TG `CollapseTextCell` 的几何）：点一下在展开/收起之间翻。
 *
 * - **46dp 条**（`CollapseTextCell` 的 `onMeasure` 恒 46dp）、文字缩进缺省 21dp、
 *   14sp（`textView.setTextSize(dp(14))`）、正文色（`windowBackgroundWhiteBlackText`）；
 * - **右端三角**（[GlyphKind.CHEVRON]，14dp 同 `arrow_more` 的格）：收起 = 尖朝下
 *   （rotation 0）、展开 = 尖朝上（180°，340ms [EaseOutQuint] —— 与 TG 的
 *   `collapsedArrow.animate().rotation(…)` 同一条时间线）；
 * - **自己不画底**：这一条要么顶在卡片最上面（读起来是"卡的标题行"），要么就是整张折叠卡
 *   的脸 —— 两种都该露出**卡片自己的底色**。原先这里写死 `ThemeColors.background`（任务
 *   中心那张卡的底色）提出来之后就不成立了：依赖页的卡取的是 [ThemeColors.surface]
 *   （深色下比 `background` 亮一档），写死就会在深色下露出一条比卡更暗的头。
 *   按压遮罩照旧（`pressable` 的 overlay 画在底之上，不需要头自己有不透明底）。
 *
 * **2026-10-17 从 `TaskCenterScreen` 的私有 `GroupHeader` 提出来**：依赖管理页的
 * 「已装依赖」要的是同一条（用户口径「参考任务中心的列表，点下可以伸展开」），
 * 抄第二份就会长出第二种几何。原处那条关于**空组**的语义（"组头是开关不是内容指示器，
 * 空组照样有三角、照样能收放"）留在调用点，不属于这条几何。
 *
 * @param expanded 当前展开态（三角朝向由它推）。
 * @param onClick 翻状态。**组件自己不持有状态**：同一屏里两个头各翻各的，
 *   状态归调用方（任务中心两份、依赖页一份）。
 * @param textIndent 文字左缩进（缺省 21dp = TG 原值，任务中心照用）；依赖页的卡头要
 *   与卡内行文字对齐，传自己的值过去。
 */
@Composable
fun CollapseHeader(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textIndent: Dp = HeaderTextIndent,
) {
    val palette = ThemeColors
    // 展开因子驱动旋转：0 = 收起（尖朝下）、1 = 展开（尖朝上）。TG 的插值器
    // EASE_OUT_QUINT = cubic-bezier(.23, 1, .32, 1)，340ms。
    val expandFraction by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = HeaderChevronTurnsMillis, easing = EaseOutQuint),
        label = "collapseChevron",
    )
    Row(
        modifier
            .fillMaxWidth()
            .height(HeaderHeight)
            .pressable(
                role = Role.Button,
                overlay = palette.pressedOverlay,
                onClick = onClick,
            )
            .padding(start = textIndent, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = palette.text,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(Modifier.weight(1f))
        Glyph(
            kind = GlyphKind.CHEVRON,
            tint = palette.text,
            size = 14.dp,
            modifier = Modifier.graphicsLayer {
                rotationZ = 180f * expandFraction
            },
        )
    }
}

/** 分组头高（`CollapseTextCell.onMeasure` 恒 46dp）。 */
private val HeaderHeight = 46.dp

/** 分组头文字缩进（TG 那条 cell 的 `setPadding(dp(21), …)`）。 */
private val HeaderTextIndent = 21.dp

/** 三角翻转时长（`collapsedArrow.animate().rotation(…)` 的 340ms）。 */
private const val HeaderChevronTurnsMillis = 340
