package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.RecoveryRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 开机恢复（§8.5 崩溃恢复的装配层接线点）：壳就绪之后把未 COMMIT 的意图重新入队。
 *
 * 为什么单独一个类而不是写在 `Application.install` 里：`install` 是**同步**的，
 * 而恢复是挂起函数（要读意图日志、要 dispatch）。把这段挪出来的真正理由是
 * 它带着三条必须在真机之外也能验的判断：
 *
 * 1. **一个壳只恢复一次**。`install` 可能被重复调用（装配重试、Activity 重建后重新装壳），
 *    同一个壳上跑两次恢复 = 同一批未完成意向被 reopen 两次。`Scheduler.recoverUncommitted`
 *    自己靠 runNonce 幂等，但那是"投递不双跑"的幂等，不是"账本不出现两行 reopen"的幂等
 *    —— 任务中心会看到重复的恢复记录，用户以为任务跑了两轮。
 * 2. **恢复失败不外抛**。`install` 还要接闹钟路线，一个恢复异常把 `install` 打断 =
 *    壳装了一半（路线没接、闹钟全漏投）。异常记账后可查，不静默、也不扩散。
 * 3. **过期意向照样呈现**。`RecoveryRecord.expired` 的那几条是"封口不投"（§8.6），
 *    任务中心要能读到"为何没跑"；这里原样转发，不因为它们"没跑"就过滤掉。
 *
 * 缺省恢复走 [AppShell.bootRecover]（先 `restoreTasks` 续排、后 `recoverUncommitted`
 * 重投，顺序写死在那一处）而不是直调 `scheduler.recoverUncommitted` —— 顺序反了
 * 不丢数据，但恢复重投的 Once 任务会被续排又注册一次。[recover] 是缝：单测注入
 * 替身即可，不必构造真调度器。
 */
class BootRecovery(
    private val recover: suspend (AppShell) -> List<RecoveryRecord> = { it.bootRecover() },
) {
    /**
     * 协程锁（不是 `synchronized`）：整个恢复体是挂起的（读意图日志 + dispatch），
     * `synchronized` 会把挂起点关在临界区里，Kotlin 直接编译不过；而 `Mutex`
     * 恰好给出这里真正想要的语义 —— 并发第二次调用**等**第一次跑完，再拿同一份结果。
     */
    private val lock = Mutex()

    /** 最近一次恢复过的壳（有界：只记一个引用，重装壳即替换）。 */
    private var recoveredShell: Any? = null

    /** 恢复结果（含过期封口的那些）。按时间序，UI/任务中心读。 */
    private val recovered = mutableListOf<RecoveryRecord>()

    /** 最近一次恢复失败（null = 没失败过或还没跑）。 */
    private var failure: Throwable? = null

    /**
     * 为 [shell] 跑一次恢复（幂等，同壳不重跑）。
     *
     * 锁罩住整个挂起体：并发第二次调用会**等**第一次跑完再返回同一份结果，
     * 而不是看到半份结果就返回（那会让 UI 显示"恢复了 0 条"然后又莫名多出几条）。
     */
    suspend fun recoverOnce(shell: AppShell): RecoverySnapshot = lock.withLock {
        if (recoveredShell !== shell) {
            recoveredShell = shell
            failure = null
            recovered.clear()
            recovered += try {
                recover(shell)
            } catch (t: Throwable) {
                failure = t
                emptyList()
            }
        }
        // 锁已持有：就地读，**不能走 [snapshot] 的 tryLock** —— Mutex 不可重入，
        // 那会拿到一把永远等不到的锁（这里侥幸只是 tryLock 才暴露成"空账"）。
        RecoverySnapshot(records = recovered.toList(), failure = failure)
    }

    /** 恢复快照（UI/任务中心/日志用；拷贝，不被外部改动）。 */
    fun snapshot(): RecoverySnapshot = RecoverySnapshot(records = recovered.toList(), failure = failure)
}

/**
 * 一次恢复的账（§8.5）：重投了几条、其中几条过期未投、有没有失败。
 *
 * [expired] 单列而不是混在 [records] 里让人自己数：任务中心的文案是
 * "恢复 N 条（其中 M 条已过约定时刻，未重投）"，两件事对用户是不同信息。
 */
data class RecoverySnapshot(
    val records: List<RecoveryRecord> = emptyList(),
    val failure: Throwable? = null,
) {
    /** 重投（含过期封口）的总条数。 */
    val total: Int get() = records.size

    /** 因 deadline 到期而未重投、只封口记账的条数（§8.6）。 */
    val expired: Int get() = records.count { it.expired }

    /** 是否跑过且成功（失败也是"跑过了"，故这条只看 failure）。 */
    val ok: Boolean get() = failure == null

    /** 一行日志/UI 文案：宁可少说话，也不把失败说成成功。 */
    fun describe(): String = when {
        failure != null -> "恢复失败：${failure!!::class.simpleName}: ${failure!!.message}"
        total == 0 -> "无可恢复的未完成意向"
        expired == total -> "恢复 $total 条，全部已过约定时刻（封口不重投）"
        expired == 0 -> "恢复 $total 条并重投"
        else -> "恢复 $total 条并重投，其中 $expired 条已过约定时刻（封口不重投）"
    }
}
