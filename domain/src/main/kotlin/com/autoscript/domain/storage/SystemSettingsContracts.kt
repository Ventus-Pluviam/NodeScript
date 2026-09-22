package com.autoscript.domain.storage

/**
 * `settings` 系统设置契约（docs/framework-design.md §9.6，JS 对偶待建 `auto.settings`）。
 *
 * 为什么住 `:domain`：与 [DataStore] / [ZipArchiver] 同一套理由 —— 真实现要碰
 * `android.provider.Settings`，§6 要求 `:platform:*` 只依赖 `:domain`；
 * 「问什么」与「怎么问系统」切开，桥面 handler 才是纯 JVM 可测的。
 *
 * **P0 范围钉在 `Settings.System` 命名空间**（可写面：亮度/铃声/屏幕超时这类
 * 用户可改项；WRITE_SETTINGS 门控的正是它）。`Settings.Global`/`Secure`
 * 不在本契约 —— 那是系统/设备策略面，脚本可写项与门控模型都不同，
 * 等有真实需求再开第三组方法，不预支。
 *
 * **写入失败的口径（§9.5）**：未授 `WRITE_SETTINGS` → 抛 `ERR_PERMISSION_DENIED`
 * （不是回 false —— 授权问题就该是分类错误，调用方才能引导；「回 false 交给你猜」
 * 把门禁语义冲掉了）。系统在已授权情况下仍拒绝（键被保护等）→ `ERR_IO`。
 * 读侧缺键回 null 是**常态不是错误**（与 [DataStore.get] 的缺失口径同族）。
 */
interface SystemSettings {

    /** `WRITE_SETTINGS` 是否已授（读设置不需要；写前的诚实探针，JS 可先问再写）。 */
    fun canWrite(): Boolean

    /** 读字符串设置；键不存在/系统不给 → null（绝不拿空串冒充缺失）。 */
    fun getString(key: String): String?

    /** 读整型设置；键不存在 → null（不拿 0 冒充缺失 —— 0 是合法亮度值）。 */
    fun getInt(key: String): Int?

    /**
     * 写字符串设置。
     * @throws IllegalArgumentException 空白键（handler 折叠 ERR_INVALID_PARAM）
     * @throws com.autoscript.domain.core.AutojsException `ERR_PERMISSION_DENIED` 未授
     *   WRITE_SETTINGS；`ERR_IO` 已授权但系统仍拒。
     */
    fun putString(key: String, value: String)

    /** 写整型设置；失败口径同 [putString]。 */
    fun putInt(key: String, value: Int)
}
