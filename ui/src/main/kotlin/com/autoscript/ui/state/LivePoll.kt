package com.autoscript.ui.state

import kotlinx.coroutines.delay

/**
 * 「有东西在跑就自己拉」的那条循环（2026-10-10 批 90）。
 *
 * **它修的是什么**：`:ui` 的每个读口原先都是"进页面 / 手动刷新时现取一次"
 * （`ConsoleCmdState`/`NpmState` 的 KDoc 都写着这条纪律），而宿主侧的重操作是
 * **入队即返回**的。两者合起来的具体症状有两个，都不是"没做全"而是**做错了**：
 *
 * 1. 控制台敲 `npm install axios` 之后，npm 边跑边说的那些话**一个字都不显示** ——
 *    要用户手动点「刷新」才看得到，而那时候命令多半已经跑完了（那不叫流式，
 *    那叫事后倒带）；
 * 2. 依赖面板的阶段条**通常只走到 `QUEUED`** 那一格：入队那一刻取到的就是 `QUEUED`，
 *    而 `DOWNLOAD`/`REIFY` 发生在之后，没人再取。
 *
 * **为什么是循环而不是让宿主推**：宿主确实有推送面（`InstallCoordinator.progress`
 * 那条 `SharedFlow`），但它**没有重放**（`extraBufferCapacity` 只是缓冲，不是历史），
 * 订阅者晚一步就永远丢那一批 —— 而"用户切进控制台时命令已经跑了一半"正是常态。
 * 拉取面（`consoleOutput`/`npmInstallEvents`，都是 seq 游标）本来就是为这件事设计的：
 * 它答的是"从 seq N 起有什么"，晚到也能取全。故这里拉，不推。
 *
 * **三条自我约束**（缺一条就会把"活着"变成"一直在问"）：
 * - **有界**：[maxTicks] 次之后一定停 —— 宿主若永远报 `running=true`（句柄泄漏、
 *   命令真卡住），界面不能跟着无限轮询下去；
 * - **只在有东西在跑时转**：[shouldContinue] 由调用方按**宿主给的**事实判
 *   （`ConsoleCmdState.running` / `NpmState.installing`），不是界面自己猜的；
 * - **每次拉都经同一个读口**：[tick] 就是那个现取函数本身，不另开一条取数路径 ——
 *   两条路径迟早会在"谁先看到新行"上分家。
 *
 * 停的时候**不报错也不清状态**：轮询是显示面，它停下来只说明"不追了"，
 * 不是"命令失败了"。下一批新行仍会由用户刷新或下次进页面取到。
 *
 * @param tick 取一轮（挂起）。调用方把新状态写回它自己那份 state，并返回它。
 * @param shouldContinue 拿**刚取到的那份状态**判还要不要继续。
 * @param intervalMillis 两次拉取之间的间隔。**可注入**：单测要验"有界"就得让
 *   [maxTicks] 跑满，用真实间隔（400ms）会让那条用例跑成四分钟 —— 一条拖慢整条
 *   CI 的用例迟早会被人用 `@Disabled` 关掉，那等于没有这条守卫。
 */
internal suspend fun <T> pollWhile(
    initial: T,
    intervalMillis: Long = LIVE_POLL_INTERVAL_MILLIS,
    maxTicks: Int = LIVE_POLL_MAX_TICKS,
    tick: suspend (T) -> T,
    shouldContinue: (T) -> Boolean,
): T {
    var current = initial
    repeat(maxTicks) {
        if (!shouldContinue(current)) return current
        delay(intervalMillis)
        current = tick(current)
    }
    return current
}

/**
 * 两次拉取之间的间隔。
 *
 * 400ms 是**手感**与**成本**的折中，不是随便取的：控制台一条 npm 输出到眼睛里的
 * 延迟上限就是它（用户感知不到 400ms 的"卡"），而每次拉取只是一次内存里的
 * `SeqRing.drain`（宿主侧没有 IO、没有进程）。再密一点（100ms）只是把同样几行
 * 多读几遍；再疏一点（1s+）就会让「边跑边看」退化成"一跳一跳地出"。
 */
internal const val LIVE_POLL_INTERVAL_MILLIS = 400L

/**
 * 一次轮询最多拉几轮（[LIVE_POLL_INTERVAL_MILLIS] × 这个数 ≈ 4 分钟）。
 *
 * 与宿主侧的 TTL 是**两条独立的线**，不试图对齐：安装会话 120s、shell 命令 30s、
 * T1 脚本 60s，各自不同；而这里的上限要盖住最长的那条**再留余量**（用户可能
 * 在命令跑的中途才切进控制台）。它防的是"宿主永远说在跑"那一种坏掉——
 * 真到那一步，界面停下来比继续问更有用（用户会看到一条不再更新的输出，
 * 而不是一个永远转的圈）。
 */
internal const val LIVE_POLL_MAX_TICKS = 600
