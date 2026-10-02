package com.autoscript.ui.state

/**
 * 一个事实该用哪一档颜色说（呈现层语义色的**唯一**出口）。
 *
 * **为什么是枚举而不是到处传四个 `Color` 参数**：旧 `CapabilityScreen` 里有个
 * `stateColor(state, primary, tertiary, error)` —— "三态各有各的色"这件事只活在那个函数
 * 的签名里，既没法 JVM 测，也没法在别的屏复用。现在每一处着色先落到一个 [StatusTone]，
 * 色值由主题统一给出，"哪一态用哪一档"这条判读留在可测的面。
 *
 * 档位按**用户该做什么**命名，不是按好看程度：
 * - [OK] 已就绪/成功 —— 什么都不用做；
 * - [ATTENTION] 能用但受限（降级投递、丢包、可能偏差）—— 值得知道，不是错；
 * - [PROBLEM] 被拒/失败/状态分歧 —— 要去改；
 * - [LINK] 强调色（可点、页签选中）；
 * - [NEUTRAL] 正常叙述、[MUTED] 三级弱化文字（时间、路径、次要说明）。
 */
enum class StatusTone { OK, ATTENTION, PROBLEM, LINK, NEUTRAL, MUTED }
