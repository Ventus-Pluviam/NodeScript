package com.autoscript.ui.state

/**
 * 「这一屏读到了没有」的三态。
 *
 * **为什么把这条纪律做成一个类型，而不是每个 State 各带 `loaded`/`loadError` 两个字段**：
 * 四个屏（首屏/任务中心/控制台/设置）都踩过同一个坑 —— 把 `loaded=false, loadError=null`
 * 与 `loaded=false, loadError="xxx"` 画成同一句"尚未读取"，等于把「读失败」说成「没读」，
 * 现场（ROM 查询崩了 vs 装配没接线）就此丢失。两个字段自由组合还会长出第三种
 * 「loaded=true 且 loadError≠null」这种自相矛盾的态，没有类型约束就迟早有人写出来。
 *
 * 三态各自**只能说自己那一句**：[NotLoaded] 说"还没读"，[Failed] 说"读失败 + 原文"，
 * [Loaded] 才轮到"读到了" —— 哪怕内容为空。呈现层用 `isLoaded` 以外的分支去说话就是撒谎。
 *
 * @property reason 失败时的**原异常文案**（`message` 为 null 时由 [of] 退到类名）。
 *   原文是现场唯一的区分线索，吞成一句"读取失败"就再也分不出是哪一种失败。
 */
sealed interface LoadState {

    /** 还没读到过（首帧；也包括读口压根没接线）。**不是**「读到了但为空」。 */
    data object NotLoaded : LoadState

    /** 读到失败。[reason] 保留原始文案。 */
    data class Failed(val reason: String) : LoadState

    /** 读到了（哪怕内容为空 —— "真的没有"是这时候才敢说的话）。 */
    data object Loaded : LoadState

    val isLoaded: Boolean get() = this is Loaded

    /**
     * 失败原文（非失败态一律 null）。
     *
     * **只在测试与日志摘要里用** —— 界面不要拿它渲染：那会把「没读到」与
     * 「读失败」画成同一句，正是本类型要防的合并。界面走 [Status] 的 `when`，
     * 三态各自说一句。
     */
    fun failedReason(): String? = (this as? Failed)?.reason

    companion object {
        /** 由异常造失败态：原文透传，`message` 为 null 时退到类名（渲染成 `null` 会被说成没读）。 */
        fun of(t: Throwable): Failed = Failed(t.message ?: t.javaClass.simpleName)
    }
}
