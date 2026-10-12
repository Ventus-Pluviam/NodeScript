package com.autoscript.ui.theme

import androidx.compose.ui.graphics.Color
import com.autoscript.domain.editor.SyntaxKind

/**
 * 语法着色的取色口（**只此一处**）。
 *
 * 键是 `:domain` 的 [SyntaxKind]，`when` 穷尽枚举 —— 桥面新增一种 kind 时这里编译期就红，
 * 不会安静地少染一类（少染是高亮面里最难发现的一种错：没有报错、没有崩溃，只是某一类词
 * 一直是正文色）。
 *
 * 取色是**观感参考**而非 TG 抄本（TG 的编辑器本就没有语法着色）。**日间/夜间一套颜色**
 * （用户 2026-10-13 口径：两模式高亮一致）：取值就是深色那一档 —— IntelliJ / VSCode
 * 暗色主题的中位观感，对 `#FFFFFF` 正文（`Colors.text`）与 `#161E27` 屏底（深色档）都成立。
 * 之前分深浅两套的写法删掉了：那套浅色档的深蓝/深绿在 `#161E27` 上发暗、对比不够。
 * 正文色 [Colors.text] 一律不动 —— 未命中任何 kind 的文本仍按正文色画，着色只是叠在
 * 上面的一层。
 */
fun syntaxColor(kind: SyntaxKind, dark: Boolean): Color = when (kind) {
    SyntaxKind.KEYWORD -> Color(0xFFCF8E6D)
    SyntaxKind.STRING -> Color(0xFF6AAB73)
    SyntaxKind.COMMENT -> Color(0xFF7F848E)
    SyntaxKind.NUMBER -> Color(0xFF2AACB8)
    SyntaxKind.FUNCTION_NAME -> Color(0xFF56A8F5)
    SyntaxKind.OPERATOR -> Color(0xFFD0D4DA)
    SyntaxKind.TYPE -> Color(0xFF4EC9B0)
}
