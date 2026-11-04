package com.autoscript.appservice.scheduler.core

/**
 * 排队/到期上限的默认分级表（§8.6 分级口径的**唯一正本**）。
 *
 * （backlog D7）自 `Scheduler.kt` 原样外迁：它是**一个纯函数常量**
 * （零 `Scheduler` 状态、零 Android），留在类文件里既占篇幅又让人以为它与
 * 调度器生命周期有关。语义逐字未改。
 *
 * 分级依据 = **谁在等、等久了会不会连带出事**：
 * - ENGINE_INTERNAL 最紧（15s）：一个已占槽的引擎在等另一个引擎，满池时这是
 *   「持有者等后来者」的嵌套形态，等久了就是跨引擎死锁，必须先爆；
 * - USER_CLICK 次之（10s）：人盯着 UI，给不出结果就该如实回 Cancelled，让任务中心
 *   呈现「引擎忙，未执行」，而不是让按钮原地转圈；
 * - INTENT_BROADCAST / EVENT 宽一些（60s）：外部涌入的批量触发本就该容忍排队；
 * - TIMED 最宽（120s）：守时任务已承诺「亮屏+解锁保底 + 可能偏差」，2 分钟兜底
 *   只为满足铁律 3（满池排队必须有 TTL，绝不无限等），不追求抢跑。
 *
 * 两处消费同一引用（不是两份相同的数字）：scheduler 的 `deadlineMillis`（恢复判过期）
 * 与 `:app` dispatcher 的排队上限（在途等多久）。`:app` 侧以
 * `ControllerRunDispatcher.DEFAULT_QUEUE_TIMEOUTS` 别名引用本表（arch 门禁禁止
 * scheduler→:app 方向，故正本只能住 scheduler 侧）；生产装配（`AppShell.assemble`）
 * 把那张表显式喂给 `Scheduler(deadlineFor=…)` —— 缺省恰好相同是巧合，写出来才是契约。
 */
val DefaultDeadlines: (TriggerSource) -> Long = { trigger ->
    when (trigger) {
        TriggerSource.ENGINE_INTERNAL -> 15_000L
        TriggerSource.USER_CLICK -> 10_000L
        TriggerSource.INTENT_BROADCAST -> 60_000L
        TriggerSource.EVENT -> 60_000L
        TriggerSource.TIMED -> 120_000L
    }
}
