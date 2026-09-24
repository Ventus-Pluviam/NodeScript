package com.autoscript.platform.system

import com.autoscript.domain.system.Clipboard

/**
 * `clipboard` 的 Android 实现（docs §12.2；SPI 见 `:domain` 的 [Clipboard]，
 * 语义层 handler 在 `:platform:capabilities`）。分层照 settings/notification：
 * Android 接触面只有 [Ops] 缝（真机 [ClipboardOps] 碰 `android.content.ClipboardManager`），
 * 本类留**本机 JVM 可测**的语义：
 *
 * 1. **读侧原样透传**：ops 的 null 即系统的 null 答案（空剪贴板 / 后台受限），
 *    不编错误码、不拿空串冒充缺失（空串是合法内容）；
 * 2. **写侧不设门**：无 canWrite 探针（写不受限），只原样下发；
 * 3. 读写的罢工口径（ops 抛 `AutojsException`）原码透传，不折叠。
 *
 * **与 `UiActionExecutor.copy/paste` 的关系（不是重复造剪贴板）**：a11y 的 copy/paste
 * 是"节点文本 ⇄ 剪贴板"的中转动作（经 `A11yBridge.clipboardRead/Write`，服务未连 =
 * `ERR_SERVICE_DISABLED`）；本类是**系统剪贴板的直读写**（`ClipboardManager` 直连，
 * 无障碍服务不在场也能用）。两条路各走各的系统事实，不互相复用 —— 与 settings
 * 「handler 不预检、判据归 SPI」同一条纪律。
 */
class AndroidClipboard(
    private val ops: Ops,
) : Clipboard {

    override fun getText(): String? = ops.getText()

    override fun setText(text: String) {
        ops.setText(text)
    }

    /**
     * 剪贴板接触面：真机 [ClipboardOps]；单测注入内存替身 ——
     * `ClipboardManager` 是系统服务，stub 运行期抛异常。
     */
    interface Ops {
        fun getText(): String?
        fun setText(text: String)
    }
}
