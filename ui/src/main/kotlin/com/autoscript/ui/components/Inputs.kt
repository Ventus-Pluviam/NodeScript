package com.autoscript.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.autoscript.ui.theme.ThemeColors

/**
 * 胶囊输入框 —— **全屏公用的一档输入版式**（2026-10-17 从 `ProjectScreen` 搬到 `components/`）。
 *
 * 灰底（[ThemeColors.fieldBackground]，白底压 5% 黑 —— 与任务中心那颗白药丸不是一个键）、
 * 圆角 20dp（40dp 高上 20dp 圆角 = 两端全圆）、高 40dp、槽内水平 12dp、文字与提示词 15sp
 * （[MaterialTheme.typography.bodyLarge]）、光标 [ThemeColors.cursor]（`groupcreate_cursor`，
 * 与强调色不是一个键）、**无投影**。
 *
 * **为什么搬到公共件**（2026-10-17 第二批）：第三个使用点出现了 —— 依赖管理页的「安装依赖」
 * 输入框，用户口径是「样式抄一下首页的搜索框」。本文件原先住在 `ProjectScreen` 里、
 * 由搜索框与新建对话框两处共用，那条 KDoc 自己写着「用户要的是"一样"，两份拷贝只能靠
 * "记得同步改两处"维持一样 —— 共用之后"一样"是结构上的事实」。第三个点出现在另一个
 * **屏**里，那份理由一点没变：`screens` 之间互相 import `private` 件做不到，于是它只能住
 * 到 `components`。搬的是位置，不是版式 —— 上面那几个数一个没动。
 *
 * 不用 M3 `TextField`：它自带的 56dp 最小高与大内边距塞不进 40dp 的行高里
 * （批 25 实机：hint 文字被上下裁掉一截），而这套输入用不上它的 label/indicator 装饰 ——
 * 直接 `BasicTextField` + 自己摆 `Row`，内边距就是版式要的那几个 dp。
 *
 * @param modifier 调用方给的外框（槽位内边距、宽度都在这里 —— 本件只定框本身的版式）。
 * @param leadingGlyph 左端那颗图标（搜索框给放大镜；「安装依赖」不给 —— 它左边那三个字
 *   就是它的话题，再放一颗图标是重复）。
 * @param focusDismiss 非 null 时这个框上报自己的 bounds/聚焦态（搜索框那一份，见
 *   [FocusDismiss]）。模态对话框与依赖管理页**不报**：前者点外面就是关掉它，后者不在
 *   「点外面收键盘」那套判据里。
 * @param enabled false 时不可输入、文字压到半透明（**光标与键盘动作也一起关**：只把字变灰
 *   而框还吞键，用户会当成"打不进去"的 bug）。
 * @param onSubmit 键盘上的完成键（[ImeAction.Done]）；null = 不接（搜索框与新建对话框都是
 *   边打边生效，没有"提交"这个动作）。**只在 [enabled] 时触发。**
 */
@Composable
fun CapsuleInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingGlyph: GlyphKind? = null,
    focusDismiss: FocusDismissState? = null,
    enabled: Boolean = true,
    onSubmit: (() -> Unit)? = null,
) {
    val palette = ThemeColors
    Row(
        modifier
            .height(40.dp)
            .background(palette.fieldBackground, RoundedCornerShape(20.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        if (leadingGlyph != null) {
            Glyph(kind = leadingGlyph, tint = palette.text.copy(alpha = 0.6f))
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = palette.text.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (enabled) palette.text else palette.text.copy(alpha = 0.5f),
                ),
                cursorBrush = SolidColor(palette.cursor),
                keyboardOptions = KeyboardOptions(
                    imeAction = if (onSubmit != null) ImeAction.Done else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (enabled) onSubmit?.invoke() },
                ),
                modifier = Modifier.fillMaxWidth().then(
                    if (focusDismiss == null) Modifier else Modifier.reportFocusBounds(focusDismiss),
                ),
            )
        }
        Spacer(Modifier.width(12.dp))
    }
}
