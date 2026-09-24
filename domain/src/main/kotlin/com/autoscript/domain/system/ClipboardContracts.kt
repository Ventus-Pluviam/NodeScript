package com.autoscript.domain.system

/**
 * `clipboard` 命名空间契约（docs/framework-design.md §12.2；JS 对偶 `auto.clipboard`）。
 *
 * 为什么住 `:domain`：与 [NotificationPoster] /
 * [com.autoscript.domain.storage.SystemSettings] 同一套理由 —— 真实现要碰
 * `android.content.ClipboardManager`，§6 要求 `:platform:*` 只依赖 `:domain`；
 * 「读写什么」与「怎么问系统」切开，桥面 handler 才是纯 JVM 可测的。
 *
 * **P0 范围钉在两方法**（`getText`/`setText`，纯文本）。刻意不预支的面，逐条给理由：
 * - **不带 `hasText`**：`getText` 回 null 即"无内容"，再开一个布尔读口就是两处判据，
 *   必然漂移（同 settings「不猜型」的纪律）；
 * - **不带 `clear`**：`clearPrimaryClip` 要 API 28+（minSdk 26 之下两档行为），等有
 *   真实消费方再开，不预支；
 * - **只做纯文本**：HTML/Uri 流是富剪贴面，等有真实消费方再开。
 *
 * **读侧 null 是常态不是错误**（对偶 `SystemSettings` 读缺失回 null）：
 * 空剪贴板 → null；Android 10+ 后台读受限时系统直接给 null —— 那是系统的诚实答案，
 * 本契约原样透传，不编一个 `ERR_PERMISSION_DENIED`（系统根本没抛异常，编错误码
 * 才是伪造；前台/默认输入法照常读到真值）。空串是合法内容（a11y `copy` 空节点记
 * 空串，见 [com.autoscript.domain.automation.UiActionExecutor.copy]），不与 null 混淆。
 *
 * **写侧无门禁**：`setPrimaryClip` 后台也能调（读受限、写不受限），故无 `canWrite`
 * 探针 —— 与 settings/notification 的"门禁判据在 SPI"同构，这里是"无门可禁"。
 */
interface Clipboard {

    /** 读剪贴板文本；null = 无内容（空剪贴板 / 后台受限时系统的 null 答案）。 */
    fun getText(): String?

    /** 写剪贴板文本（空串合法，原样存，不拿空白当缺参）。 */
    fun setText(text: String)
}
