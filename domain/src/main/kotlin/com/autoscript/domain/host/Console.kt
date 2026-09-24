package com.autoscript.domain.host

import com.autoscript.domain.engine.EngineStatus

/**
 * 控制台读口（§7.3 节流拉取的数据面 + 在途执行的两端状态对照）的呈现面 DTO。
 *
 * **为什么住 `:domain`**：与任务中心/能力中心同一条理由 —— 行的来源是
 * `:bridge:java` 的 `ConsoleCollector` 与 `:app-service:runtime` 的 `RuntimeController`，
 * 两者都只有 `:app` 见得到；呈现层在 `:ui`（只许依赖 `:domain`）。`:domain` 出事实
 * DTO，`:app` 装配时逐字段映射，`:ui` 只画。
 *
 * **游标语义（[ConsoleSnapshot.nextSeq]）**：数据面是 seq 游标拉取（§7.3 不做回调推送），
 * 快照只回 `seq > sinceSeq` 的行并把游标推到本批最大 seq；**呈现层累积行、游标只进不退**
 * —— 刷新一次就把已读的行删掉，等于每次刷新都"丢日志"。读失败时游标不清零
 * （下一次从上次成功处续拉）。
 */
data class ConsoleSnapshot(
    /** 本批新行（seq 升序，最多 `maxLines` 条；空批 = 没有新输出）。 */
    val lines: List<ConsoleLineRow>,
    /** 下次拉取的游标（= 本批最大 seq；空批原样回传 `sinceSeq`）。 */
    val nextSeq: Long,
    /**
     * 本批是否拉满上限 —— true = 可能还有更新的行没拉到，呈现层提示「点刷新继续」。
     * 用「可能」是因为拉满只说明这一批装不下，不排除恰好到此为止（再探一次才知）。
     */
    val pageFull: Boolean,
    /**
     * 收集器**累计**丢包数（队列有界、容量满丢最老）。
     * 非零 = 用户看到的控制台有缺口 —— 必须说出来，静默就是把"没显示"说成"没发生"。
     */
    val droppedTotal: Long,
    /** 在途执行的两端状态对照（`RuntimeController.runStatuses`）；空 = 此刻没有在途执行。 */
    val activeRuns: List<ActiveRunRow>,
)

/**
 * 一行控制台输出（`ConsoleLine` 的呈现侧投影）。
 *
 * @property runId 执行归属；0 = **引擎外日志**（宿主/装配期/无归属），不是"第 0 次执行"。
 * @property level 原样保留的级别字符串（数据面允许 `log|info|warn|error|debug`，
 *   直写口 `append` 不设限）—— 呈现层翻译成中文标签，但原值也在（未知级别不丢现场）。
 */
data class ConsoleLineRow(
    val seq: Long,
    val runId: Long,
    val level: String,
    val text: String,
    val atMillis: Long,
) {
    /** 引擎外日志（[runId] = 0）：不属于任何执行的宿主侧输出。 */
    val external: Boolean get() = runId == 0L
}

/**
 * 一条在途执行的状态对照（`RuntimeController.RunStatus` 的呈现侧投影，§8.3 校准）。
 *
 * @property host 宿主自报；**null = 读不到**（引擎已死/实现未接线）—— 它不是
 *   `STOPPED`，把"读不到"渲染成一个状态就是编的（呈现层对 null 如实说"读不到"）。
 * @property drift 两端状态分歧；host 为 null 时恒 false（读不到不判分歧，
 *   判据在 `RuntimeController.statusOf`，本层不另写一套）。
 */
data class ActiveRunRow(
    val runId: Long,
    val host: EngineStatus?,
    val pool: EngineStatus,
    val drift: Boolean,
)
