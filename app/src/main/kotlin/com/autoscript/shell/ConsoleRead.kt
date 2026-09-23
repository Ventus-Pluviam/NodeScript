package com.autoscript.shell

import com.autoscript.appservice.runtime.RuntimeController
import com.autoscript.bridge.ConsoleCollector
import com.autoscript.domain.host.ActiveRunRow
import com.autoscript.domain.host.ConsoleLineRow
import com.autoscript.domain.host.ConsoleSnapshot

/**
 * 控制台快照的拼装与映射（§7.3 seq 游标拉取 + §8.3 在途执行两端对照）。
 *
 * **为什么单独一个文件**：与 [CapabilityCenterRead]/[TaskCenterRead] 同一条理由 ——
 * `Application` 在 JVM 单测里构造不出来，而"游标怎么推、拉满怎么标、丢包怎么带、
 * 两端状态怎么投影"这段判断必须可测。抽出来之后 `AppShellApplication` 只剩一句转接。
 *
 * **收集器与在途状态由调用方给，本对象不 new**：收集器是桥上注册的那一个（壳持有），
 * 另开一份就收不到桥上的行；在途状态出自 `RuntimeController`（它要遍历活跃表并逐个
 * 问宿主，挂起路径）。本对象只做**拉取 + 逐字段投影**，不改写任何一边的事实。
 *
 * 三条纪律：
 * - **游标只进不退**：`nextSeq` 取 `drain` 的返回（本批最大 seq；空批原样回传入参），
 *   不在这里"补算" —— 行在并发追加下 seq 与入队序可交错（见 `ConsoleCollectorTest`），
 *   自己推游标就会漏行；
 * - **拉满如实标 [ConsoleSnapshot.pageFull]**：`raw.size == maxLines` 只说明这批装不下，
 *   呈现层用「可能还有」措辞提示继续拉，不假装已经到底；
 * - **丢包数原样带上**（[ConsoleSnapshot.droppedTotal]）：有界队列丢最老是数据面的
 *   既定语义（§7.3 可丢包），但 UI 必须知道"显示的不是全部" —— 静默就是把"没显示"
 *   说成"没发生"。
 */
object ConsoleRead {

    /**
     * 拉一批控制台快照。
     *
     * @param collector 壳持有的收集器（`AppShell.console`）。
     * @param sinceSeq 游标：只回 `seq > sinceSeq` 的行（首读 0）。
     * @param maxLines 本批上限（> 0；`drain` 对 0/负数抛 `IllegalArgumentException`，这里不吞）。
     * @param runStatuses 在途执行两端对照（`RuntimeController.runStatuses`；挂起）。
     */
    suspend fun snapshot(
        collector: ConsoleCollector,
        sinceSeq: Long,
        maxLines: Int,
        runStatuses: suspend () -> List<RuntimeController.RunStatus>,
    ): ConsoleSnapshot {
        val (next, raw) = collector.drain(sinceSeq, maxLines)
        return ConsoleSnapshot(
            lines = raw.map { line ->
                ConsoleLineRow(
                    seq = line.seq,
                    runId = line.runId,
                    level = line.level,
                    text = line.text,
                    atMillis = line.atMillis,
                )
            },
            nextSeq = next,
            pageFull = raw.size >= maxLines,
            droppedTotal = collector.droppedCount(),
            activeRuns = runStatuses().map { status ->
                ActiveRunRow(
                    runId = status.runId,
                    host = status.host,
                    pool = status.pool,
                    drift = status.drift,
                )
            },
        )
    }
}
