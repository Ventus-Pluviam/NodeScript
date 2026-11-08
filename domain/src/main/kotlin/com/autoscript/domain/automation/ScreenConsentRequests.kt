package com.autoscript.domain.automation

/**
 * 进程内**单请求**投屏同意状态机（§9.2）。
 *
 * ## 为什么需要它
 * `MediaProjectionManager.createScreenCaptureIntent()` 的结果只能经 `onActivityResult` 回来，
 * 而那条回调**不带任何请求标识** —— 一次 `launch` 对应一次回调，回调本身说不出
 * "我这是哪一次请求的结果"。于是三类真实故障都出在这里：
 *
 * - **并发覆盖**：脚本那次征询还在等，能力中心又拉起一次 —— 后一次把前一次的等待者
 *   冲掉，前一次永远等不到结果（表现是"脚本卡死在 startCapturer"）；
 * - **迟到结果错配**：前一次征询的等待者已被取消，对话框还在系统里；此时来了新请求，
 *   旧结果回来被当成**新请求**的结果交付 —— 把一次旧同意发给下一次执行；
 * - **销毁后悬挂**：Activity 销毁，等待者没人叫醒，一直挂到脚本超时。
 *
 * ## 判据（四条，逐条对应上面一个故障）
 * - **单请求**：同时只允许一次在征询 —— [begin] 在已有一次在进行时如实回 `null`，
 *   调用方据此拒绝本次（`ERR_CAPTURE_DENIED`），而不是排队或覆盖；
 * - **在途与等待者分开记**：`outstanding` 表示"对话框已经交给系统、结果还没回来"，
 *   它**不随等待者取消而清**（对话框还在用户眼前）。所以被取消的那次之后，新请求
 *   仍会被挡到旧结果回来为止 —— 旧结果因此永远不会被错配给新请求；
 * - **结果按轮次交付**：[deliver] 只交给**当前这一轮**的等待者，等待者已被撤销就丢弃
 *   （不是"留着下次用"）；
 * - **销毁即收口**：[abandon] 叫醒等待者并清在途（界面没了，没人能再收结果）。
 *
 * **不做超时**：超时是调用方的事（脚本那次征询由连接/执行的取消兜住，见
 * [ScreenConsentBroker] 的调用点）—— 本类只保证"结果不会交错、不会悬挂在没人管的状态"。
 *
 * 线程安全：`begin`/`deliver`/`revoke`/`abandon` 可能来自任意线程（脚本协程、主线程、
 * 连接撤销线程）。
 *
 * **谁改状态谁负责叫醒**：等待者可能被从任意线程登记/撤销/叫醒，所以每次状态转换
 * 都（a）在锁内摘出"该叫的人"，（b）**在锁外**调它 —— 回调里再回头碰本类（例如
 * `revoke`）也不会自锁死。
 */
class ScreenConsentRequests {

    /** 一次征询的凭据（[begin] 发放；用于把后续动作对到"是不是我这一轮"）。 */
    class Ticket internal constructor(internal val id: Long)

    /** 一轮征询的落点。 */
    sealed interface Outcome {
        /** 系统回了结果（含用户取消 —— `granted` 为 false，见 [ScreenConsentOutcome]）。 */
        data class Result(val outcome: ScreenConsentOutcome) : Outcome

        /**
         * 没能走完这一轮（对话框拉不起来、等待者被撤销、界面销毁）。
         *
         * [reason] 是**给现场看的原因**（诊断用，进日志/异常文案）；对调用方而言三种落点
         * 是同一件事：这次没能把用户送到授权页。
         */
        data class Unavailable(val reason: String) : Outcome
    }

    private val lock = Any()

    /** 在途轮次：对话框已交给系统、结果还没回来。**不随等待者撤销而清**（见类 KDoc）。 */
    private var outstanding: Ticket? = null

    private var waiterTicket: Ticket? = null
    private var waiter: ((Outcome) -> Unit)? = null

    private var nextId = 1L

    /** 现在有没有一次征询在跑（对话框在系统里，或有人正等着）。 */
    val busy: Boolean get() = synchronized(lock) { outstanding != null }

    /**
     * 占一轮。
     *
     * @param onResult 结果落点（**至多被调一次**）；`null` = 这次没有等待者
     *   （能力中心「去授权」那条 fire-and-forget）—— 结果改由
     *   [ScreenConsentInbox] 一次性待领，见 `requestScreenConsentDetached` 的 KDoc。
     * @return 本轮凭据；`null` = 已有一轮在跑，**本次不排队也不覆盖**（调用方如实拒绝）。
     */
    fun begin(onResult: ((Outcome) -> Unit)?): Ticket? = synchronized(lock) {
        if (outstanding != null) return null
        val ticket = Ticket(nextId++)
        outstanding = ticket
        waiterTicket = ticket
        waiter = onResult
        ticket
    }

    /**
     * 对话框没拉起来（系统里没有这个界面 / 抛异常 / 没有投屏服务）：结束这一轮并把
     * `Unavailable(reason)` 交给等待者。
     *
     * 必须在**拉不起来时**调 —— 否则这一轮会一直占着 [busy]，此后所有征询都被挡掉。
     */
    fun fail(ticket: Ticket, reason: String) = finish(ticket, Outcome.Unavailable(reason))

    /**
     * 等待者自己撤销（脚本那次征询被取消）。
     *
     * **只撤等待者，不清在途**：对话框还在用户眼前，它的结果迟早要回来；在结果回来之前
     * 不放新的请求进来 —— 这正是"旧结果不会错配给下一次执行"的机制。
     */
    fun revoke(ticket: Ticket) {
        synchronized(lock) {
            if (waiterTicket == ticket) {
                waiterTicket = null
                waiter = null
            }
        }
    }

    /**
     * 系统回了结果（`onActivityResult`）。**无论有没有等待者，这一轮都算走完** ——
     * 没有等待者（能力中心那条）时结果由 [ScreenConsentInbox] 负责，本类不扣着在途不放。
     *
     * @return true = 结果**交付给了等待者**；false = 没有等待者（fire-and-forget 那条，
     *   或等待者已被撤销，或根本没有在途轮次）—— **不抛**，调用方不必分支。
     */
    fun deliver(outcome: ScreenConsentOutcome): Boolean {
        val w = synchronized(lock) {
            val current = outstanding ?: return false
            outstanding = null
            val target = if (waiterTicket?.id == current.id) waiter else null
            waiterTicket = null
            waiter = null
            target
        }
        if (w == null) return false
        w(Outcome.Result(outcome))
        return true
    }

    /**
     * 界面销毁（Activity 销毁）：叫醒等待者、清在途 —— 界面没了，没人能再收结果。
     *
     * 等待者拿到的 `Unavailable` 让调用方**如实回"没能问用户"**（`ERR_CAPTURE_DENIED`），
     * 而不是一直挂着。**不把任何东西留进待领口**：销毁不是授权。
     *
     * 与 [revoke] 的差别就在"清不清在途"：撤销是"我不要这个结果了"（对话框还在，结果
     * 还要回来），销毁是"收结果的界面没了"（没有下一次回调，在途必须清掉，否则
     * [busy] 永久为真）。
     */
    fun abandon() {
        val w = synchronized(lock) {
            outstanding = null
            val current = waiter
            waiterTicket = null
            waiter = null
            current
        }
        w?.invoke(Outcome.Unavailable("投屏授权界面已销毁（应用不在前台）"))
    }

    /**
     * 收口一轮（拉不起来那条路：只有**本轮**才收得掉）。
     *
     * 在途那一轮走 [deliver] —— 系统回调不知道轮次号，但在途恒只有一轮（见类 KDoc）。
     */
    private fun finish(ticket: Ticket, outcome: Outcome) {
        val w = synchronized(lock) {
            val current = outstanding ?: return
            if (ticket.id != current.id) return
            outstanding = null
            val target = if (waiterTicket?.id == current.id) waiter else null
            waiterTicket = null
            waiter = null
            target
        }
        w?.invoke(outcome)
    }

    companion object {
        /** 进程内那一份（呈现层写、装配层读，见 [ScreenConsentHolder] 同一形态）。 */
        val shared = ScreenConsentRequests()
    }
}

/**
 * 能力中心「去授权」那条路的**一次性待领凭据**（§9.2）。
 *
 * ## 为什么必须有它
 * 投屏**没有持久 grant**：一次系统同意只换一条会话（API 34 起系统不再复用同意凭据），
 * 而且这条凭据只活在这个进程里。所以能力中心那次「去授权」如果只是"把对话框弹起来、
 * 结果丢掉"，用户看到的就是：**弹了、点了同意、界面还是"未生效"，下次脚本再弹一次** ——
 * 这正是"假装已授权"的反面：什么都没发生，却要用户重做一遍。
 *
 * 于是结果落进这里，由**下一次** `screen.startCapturer` 一次性领走（[claim] 恰好一次）：
 * 用户同意过的那一次不会白费，也不会被用第二次。
 *
 * ## 三条判据
 * - **只收「真的同意了」的**：拒绝/取消的结果**没有可换会话的凭据**
 *   （`getMediaProjection` 需要那次结果的 `Intent`，用户取消时它是 null）——
 *   收进来也开不了会话，只会让下一次请求拿到一个必然失败的凭据。所以 [offer] 丢弃非
 *   `granted` 的结果，下一次请求**如实重新问用户**；
 * - **恰好一次**：[claim] 取走即清（第二个人拿不到）—— 一次同意换一条会话，与设备层的
 *   `AndroidScreenConsentToken.consume` 是同一条纪律的两道闸；
 * - **不跨进程、不持久化**：进程重启即失效（凭据本来就活不过进程），
 *   Activity 重建**不**自动补一份 —— 见 [ScreenConsentRequests.abandon]。
 *
 * **归属**：领走发生在 `screen.startCapturer` 里，那里已经取过
 * [mediaProjectionOwnerOrThrow] —— 于是**只有一条已认证的执行**领得走它（没有身份的
 * 调用在更早的一步就 `ERR_PERMISSION_DENIED` 了）。凭据本身是系统凭据、不绑执行，
 * 所以"谁能领"这件事由调用点把关，而不是靠凭据自己。
 */
class ScreenConsentInbox {

    /**
     * 待领凭据。**恰好一次的原子性由 [AtomicReference.getAndSet] 保证**（见 [claim]）——
     * 「`@Volatile` + 读出来再清」是两步，并发下两个执行会各领走**同一份**同意。
     */
    private val pending = java.util.concurrent.atomic.AtomicReference<ScreenConsentOutcome?>()

    /** 放进一份同意。**非 `granted` 的结果直接丢弃**（见类 KDoc）。 */
    fun offer(outcome: ScreenConsentOutcome) {
        if (!outcome.granted) return
        pending.set(outcome)
    }

    /**
     * 领走（恰好一次）：第二次、以及没放过东西时都回 `null`。
     *
     * **原子性是这条承诺的本体**：调用点（`screen.startCapturer`）跑在桥面读线程上，
     * 两个脚本可以同时来领。「读 → 清」两步写法下，同一份系统凭据会被两个执行
     * 各领走一份 —— 后到者在 `getMediaProjection` 处被系统拒（API 34 起凭据一次性），
     * 用户明明同意过却有一个脚本看到 `ERR_CAPTURE_DENIED`、下一次还得再弹一次
     * 对话框，正是本类要堵掉的那条。`getAndSet(null)` 把「读+清」收成一次原子操作，
     * 赢家恒只有一个。
     */
    fun claim(): ScreenConsentOutcome? = pending.getAndSet(null)

    /** 丢弃待领凭据（界面销毁/用户取消时如实清掉，不留"下次自动授权"）。 */
    fun clear() {
        pending.set(null)
    }

    companion object {
        /** 进程内那一份。 */
        val shared = ScreenConsentInbox()
    }
}
