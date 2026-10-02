package com.autoscript.ui.state

/**
 * 一屏顶部的状态横幅（**一段**话，不是四屏各写一遍的 `when`）。
 *
 * 旧版四个 `Header()` 各有一份 `when { !loaded && err==null -> …; !loaded -> …; 空 -> …;
 * else -> … }`，抄了四遍、也漂了四遍（Telegram 风格的副标题统一走这里之后，
 * "读到了但为空"与"没读到"的说法终于只有一处措辞）。
 *
 * 三条纪律都在这里，不在 `@Composable` 里：
 * - 没读到与读失败是**两句**（后者带原文）；
 * - "真的空"只有 [LoadState.Loaded] 才敢说；
 * - 计数类结论（如「共 N 条任务」）只在 loaded 时出现 —— 失败时 N 无意义。
 *
 * @property tone 横幅的着色档（[StatusTone]；色值由主题给）。
 */
data class ScreenStatus(
    val text: String,
    val tone: StatusTone,
)

/** 状态横幅的构造器。`loadedHint` 是"没读到"那一句（各屏措辞不同），其余共用。 */
object Status {

    /**
     * 首屏横幅。
     *
     * @param notLoadedText 没读到时那句（首屏是同步读的，所以"点刷新"在这屏语义更弱，
     *   各屏自己写）。
     */
    fun of(
        load: LoadState,
        notLoadedText: String,
        loadedText: String,
    ): ScreenStatus = when (load) {
        LoadState.NotLoaded -> ScreenStatus(notLoadedText, StatusTone.MUTED)
        is LoadState.Failed -> ScreenStatus(load.reason, StatusTone.PROBLEM)
        LoadState.Loaded -> ScreenStatus(loadedText, StatusTone.MUTED)
    }

    /** 没读到 + 读成功但计数为 0 的两态合并：这两句**必须**不同（见类 KDoc）。 */
    fun count(
        load: LoadState,
        notLoadedText: String,
        total: Int,
        emptyText: String,
        unit: String,
    ): ScreenStatus = when (load) {
        LoadState.NotLoaded -> ScreenStatus(notLoadedText, StatusTone.MUTED)
        is LoadState.Failed -> ScreenStatus(load.reason, StatusTone.PROBLEM)
        LoadState.Loaded -> ScreenStatus(
            if (total == 0) emptyText else "共 $total $unit",
            StatusTone.MUTED,
        )
    }
}
