package com.autoscript.platform.system

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * [AndroidClipboard.Ops] 的真机实现（docs §12.2）：唯一碰
 * `android.content.ClipboardManager` 的地方。这里只有「读/写 + 把系统事实折成契约形状」：
 *
 * - [getText]：`primaryClip` 为空 / 条目数为 0 → null（空剪贴板是常态答案）；
 *   首条目 `coerceToText` → String（与 `AutoScriptAccessibilityService.clipboardRead`
 *   同口径 —— 两条路读的是同一份系统剪贴板，形状必须一致）；
 * - [setText]：`setPrimaryClip(newPlainText)`，空串原样存（合法内容，不拒）；
 * - 后台受限（API 10+ 默认输入法外读 null）不抛 —— 系统的 null 就是答案，
 *   由 handler 编成裸 JSON `null` 回桥。
 *
 * 本机 JVM **只编译不执行**（系统服务 + Context，stub 运行期抛异常）。
 */
class ClipboardOps(private val context: Context) : AndroidClipboard.Ops {

    private val manager: ClipboardManager?
        get() = context.getSystemService(ClipboardManager::class.java)

    override fun getText(): String? {
        val clip = manager?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    override fun setText(text: String) {
        manager?.setPrimaryClip(ClipData.newPlainText("autoscript", text))
    }
}
