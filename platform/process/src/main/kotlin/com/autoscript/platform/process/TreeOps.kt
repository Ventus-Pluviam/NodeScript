package com.autoscript.platform.process

import java.nio.file.Files

/**
 * 进程树收尸的**操作面**（缝：单测注入替身，生产 = [ProcTreeOps]）。
 *
 * 为什么是缝（两侧同一个理由，批 105 合并自两份逐字同款）：收尸的判据要能跑成确定性用例
 * —— 「孙进程确实被点名了」「TERM 后补 KILL」「二次收割捡 reparent 的漏网」都是
 * **顺序与集合**的断言，替身比真进程便宜且不抖；而真 `/proc` + 真 `kill` 那条由各调用方
 * 侧的「真进程树」用例单独钉（起的是自己生的树，判据是自己的 pid，不借宿主 /proc）。
 * 两类判据分开钉是本仓的纪律（替身钉顺序，真进程钉 `/proc` 面本身）。
 *
 * **为什么是 public 而不是 internal**：两个调用方（[com.autoscript.appservice.npm] 的
 * `T1Child`、`:platform:system` 的 `AndroidShellExecutor`）都是 public 类，构造参数的
 * 类型必须同级可见（Kotlin 不许 public 签名暴露 internal 类型）。
 */
interface TreeOps {
    /** [process] 的全部后代 pid（不含它自己）。 */
    fun descendantsOf(process: Process): Set<Long>

    /** 从仍活着的 pid 集合再扫一轮后代（二次收割：宽限期内 TERM 被忽略的成员新起的）。 */
    fun descendantsOf(alivePids: Set<Long>): Set<Long>

    /** 按 pid 杀一批（TERM 或 KILL）。 */
    fun kill(pids: Collection<Long>, force: Boolean)

    /** [pid] 还活着吗（`/proc/<pid>` 在不在）。 */
    fun alive(pid: Long): Boolean
}

/**
 * 生产实现：`/proc` 扫描 + `kill` 二进制（[ProcOps]）。
 *
 * 构造参数 [ops] 可注入 —— 合成 /proc 树的单测用（见 [ProcOps] 的 [ProcOps.procDir]）。
 */
class ProcTreeOps(
    private val ops: ProcOps = ProcOps(),
) : TreeOps {
    override fun descendantsOf(process: Process): Set<Long> = ops.descendantsOf(process)
    override fun descendantsOf(alivePids: Set<Long>): Set<Long> =
        alivePids.flatMap { ops.descendantsOf(it) }.toSet()
    override fun kill(pids: Collection<Long>, force: Boolean) = ops.kill(pids, force)
    // 用 [ops] 自己的 procDir 而不是写死 DEFAULT_PROC：合成树单测要能注入这条判据
    // （否则「alive 在合成树里判活」永远去查宿主 /proc —— 那正是本仓吃过的假绿）。
    override fun alive(pid: Long): Boolean = Files.exists(ops.procDir.resolve(pid.toString()))
}
