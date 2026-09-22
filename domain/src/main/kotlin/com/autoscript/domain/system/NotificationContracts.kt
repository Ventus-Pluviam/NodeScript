package com.autoscript.domain.system

/**
 * `notification` 命名空间契约（docs/framework-design.md §12.2；JS 对偶 `auto.notification`）。
 *
 * 为什么住 `:domain`：与 [ShellExecutor] / [com.autoscript.domain.storage.SystemSettings]
 * 同一套理由 —— 真实现要碰 `android.app.NotificationManager`，§6 要求 `:platform:*`
 * 只依赖 `:domain`；「发什么」与「怎么发」切开，桥面 handler 才是纯 JVM 可测的。
 *
 * **P1 范围钉在三方法**（`canPost`/`post`/`cancel`）+ 三字段（[NotificationSpec] 的
 * `id`/`text`/`title`）。刻意不预支的面，逐条给理由：
 * - **不带 `channelId`**：minSdk 26 起通知必须有 channel，但脚本侧选 channel 是
 *   产品决策不是能力 —— 实现侧维护一条默认 channel，要多 channel 的需求出现再开第三参；
 * - **不带 actions/点击意图**：那要 `PendingIntent` + `:main` 侧的接收组件，与
 *   `dialogs` 的通知回调同属 §14 P2（`DialogHost`），现在给就是给了个发不出去的字段；
 * - **不带 `ongoing`/样式/优先级**：同上，等有真实消费方（任务中心常驻态）再加，
 *   现在加只会让 wire 形状先膨胀一轮再改。
 *
 * **`id` 必填不是苛刻**：§8.5 把「只发一次」的通知 id 列为 `runNonce` 的幂等目标 ——
 * 生成式 id 恰恰毁掉这个用途（同一意向重投会发出两条）。要自动发号的调用方自己发号，
 * 契约不替它藏起这件事。
 *
 * **读侧/失败口径（§9.5）**：`canPost()` 是写前的诚实探针（对偶 [SystemSettings.canWrite]）；
 * [post] 在 `POST_NOTIFICATIONS`/应用通知未开时抛 `ERR_PERMISSION_DENIED` —— **不是回 false**
 * （授权问题是分类错误，脚本要能识别并引导；静默成功才是最坏的谎 —— 系统在拒绝时
 * 恰恰是不抛异常地丢弃通知）。[cancel] 是**无回执的幂等动作** —— `NotificationManager.cancel`
 * 既不抛也不回码，系统没有任何"撤到了没有"的读口，契约因此回 `Unit` 而不是编一个
 * Boolean（拿"没报错"冒充"真撤了"正是本仓反复在打的那种谎）。
 */
data class NotificationSpec(
    /** 通知 id：同 id 重发即覆盖，也是 §8.5 幂等键的目标。 */
    val id: Int,
    /** 通知正文（必填；空白正文由 handler 折叠 `ERR_INVALID_PARAM`）。 */
    val text: String,
    /** 标题；null = 不设标题（省略与显式 null 同义）。 */
    val title: String? = null,
)

/** 通知投递 SPI。实现住 `:platform:system`（`AndroidNotificationPoster`）。 */
interface NotificationPoster {

    /** 通知是否可发（应用通知开关 + `POST_NOTIFICATIONS`，读侧不需要授权）。 */
    fun canPost(): Boolean

    /**
     * 发/覆盖一条通知。
     * @throws com.autoscript.domain.core.AutojsException `ERR_PERMISSION_DENIED` 通知未授权。
     */
    fun post(spec: NotificationSpec)

    /** 按 id 撤销；幂等不抛（撤一个没发过的 id 什么也不会发生）。 */
    fun cancel(id: Int)
}
