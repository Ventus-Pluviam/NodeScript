package com.autoscript.platform.system.floatingWindow

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * `floatingWindow` 命名空间的 Android 实现（docs §9.4；SPI 见 `:domain` 的 [FloatingWindowHost]）。
 *
 * §12.2 明说**有状态的判断归实现层**，本类就是那一层：
 * - **句柄记账与 generation**：`create` 发号（[HandleRef.refId] 单调递增，[HandleRef.generation] 恒 1 ——
 *   窗口不复用句柄，一个句柄活一次）；
 * - **`close` 幂等**：同一句柄重复 `close` 返回成功而非报错（脚本的 `finally` 里补一刀是常态，
 *   为它抛错会把"清理"变成新的失败源）；但**跨代/未知句柄**抛 [ErrorCode.ERR_STALE_HANDLE] ——
 *   "已经关掉的句柄"与"从来没见过的句柄"必须能分辨（§7.4）。分辨靠**号段水位** [lastIssued]
 *   （不是"发过的号"那张集合 —— 那是一条随进程寿命无界增长的账，见其 KDoc）。
 * - **窗口类型选择**：按 [overlayTypeAvailable] 缝在 `TYPE_ACCESSIBILITY_OVERLAY` 与
 *   `TYPE_APPLICATION_OVERLAY` 之间选。
 *
 * **能力门禁不在这里**（§9.5）：本类只处理"系统在加窗时拒绝"这一现场事实，折成
 * [ErrorCode.ERR_PERMISSION_DENIED]；"该不该允许脚本开悬浮窗"由装配层的 `PermissionFacade` 先判。
 *
 * [ops] 是唯一的 Android 接触面（[FloatingWindowOps]）：真机走 [WindowManagerOps]（真
 * `WindowManager.addView`，窗口令牌是 `View`）；单测注入内存替身，于是发号/幂等/错误分类
 * 这些**语义**不需要真 `WindowManager` 也能测 —— 与 `ScreenshotSource` 的 `FrameProducer` 同一套办法。
 */
class AndroidFloatingWindowHost(
    private val ops: FloatingWindowOps,
    private val overlayTypeAvailable: () -> Boolean = { false },
) : FloatingWindowHost {

    private val ids = AtomicLong(FIRST_REF_ID)

    /** [HandleRef.refId] → 窗口令牌（活着的）。`close` 后移除。 */
    private val live = LinkedHashMap<Long, Any>()

    /**
     * **已发出去的最后一个号**（水位；只在 [guard] 内读写，发号也在同一把锁内）。
     *
     * 它就把"关过的句柄"与"没见过的句柄"分开了（[issuedLocked]）：号单调递增且**绝不复用**
     * （§7.4），于是 `号 ≤ 水位 && 不在 live` ⟺ 发过且已关（`close` 幂等成功），
     * `号 > 水位` ⟺ 从来没见过（`ERR_STALE_HANDLE`）。
     *
     * **为什么不是"发过的号"那张集合**（原 `everIssued: HashSet<Long>`）：本类是
     * `SystemSpis.of` 装配期建的**进程级单例**，一张只进不退的号集合就是一条无界增长 ——
     * 一个 create/close 循环跑一百万次，集合里就有一百万个装箱 Long，而它的用途只是回答
     * 一个**区间**问题。仓里同类三处都已经裁过（`ProjectionForegroundHost.ABANDONED_KEEP`、
     * `MediaProjectionRecorder.FINISHED_KEEP`、`ScreenNamespaceHandler.RECORDINGS_KEEP`
     * 各留 64 条），这里连"留多少条"都不用定：水位是 O(1) 且**精确**（裁剪过的集合会把
     * "很久以前关的"误报成"没见过"，那正是本类 KDoc 里那条要分辨的事实）。
     *
     * **水位成立的前提**：号只在**窗口真加上之后**才前进（见 [create]）—— 否则被系统拒绝
     * 那次消耗的号会落在水位以下、却从没进过 `live`，于是一个从没发出去的号被报成"已关闭"
     * （wire 上的谎：脚本会以为它曾经拿到过这个窗口）。
     */
    private var lastIssued: Long = FIRST_REF_ID - 1
    private val guard = Mutex()

    override suspend fun create(spec: FloatingWindowSpec): HandleRef {
        val token = try {
            ops.add(spec, overlay = overlayTypeAvailable())
        } catch (e: AutojsException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 现场拒绝（未授予 SYSTEM_ALERT_WINDOW / 窗口类型不可用）→ 分类错误，绝不返回假句柄。
            throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "悬浮窗添加被系统拒绝（overlay 权限或窗口类型不可用）：${e.message}",
                e,
            )
        }
        // **成了才占号**（水位判据的前提，见 [lastIssued]）：发号与入表在同一把锁内，
        // 于是水位与 live 之间不存在"号发了但表还没落"的窗口 —— 并发 create 也不会
        // 让水位回退（若发号在锁外，两条线程会按拿号顺序之外的次序写水位）。
        val refId = guard.withLock {
            val id = ids.getAndIncrement()
            live[id] = token
            lastIssued = id
            id
        }
        return HandleRef(refId, GENERATION)
    }

    override suspend fun close(ref: HandleRef) {
        if (ref.generation != GENERATION) {
            throw AutojsException(
                ErrorCode.ERR_STALE_HANDLE,
                "悬浮窗句柄代次不匹配：期望 $GENERATION，实际 ${ref.generation}",
            )
        }
        val token = guard.withLock {
            val found = live.remove(ref.refId)
            if (found == null && !issuedLocked(ref.refId)) {
                throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "未知悬浮窗句柄 refId=${ref.refId}")
            }
            found
        }
        // 已关闭（号在水位以下但不在 live）→ 幂等返回，不再碰窗口系统。
        if (token == null) return
        try {
            ops.remove(token)
        } catch (e: AutojsException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 窗口已被系统收走（如 a11y 服务被关）：**目标状态已达成**，不把它变成失败。
            // （这条与 create 的处置相反是有意的：create 没做成就是没做成，close 已经关掉了就是关掉了。）
        }
    }

    /**
     * 这个号**发出去过**吗（调用方须持 [guard]）：`[FIRST_REF_ID, lastIssued]` 区间内即发过。
     *
     * 下界那一半不是多余的：负数与 0 都在水位以下，却从没发过 —— 少了它就是拿"已关闭"
     * 回答一个垃圾号，而本类 KDoc 要的正是"两种句柄给出不同结论"。
     */
    private fun issuedLocked(refId: Long): Boolean = refId in FIRST_REF_ID..lastIssued

    /**
     * 窗口操作缝：真机实现见 [WindowManagerOps]，单测注入内存替身。
     * [add] 返回的令牌对本类不透明（只用于 [remove] 时原样交回）。
     */
    interface FloatingWindowOps {
        /** 建窗。被系统拒绝时抛异常（本类折成 `ERR_PERMISSION_DENIED`）。 */
        suspend fun add(spec: FloatingWindowSpec, overlay: Boolean): Any

        /** 撤窗。窗口已不在时幂等返回。 */
        suspend fun remove(token: Any)
    }

    companion object {
        /** 句柄代次：窗口不复用，故恒 1（§7.4 的 generation 语义）。 */
        const val GENERATION: Long = 1

        /** 号段起点（[lastIssued] 的下界判据；单源，别在两处各写一个 1）。 */
        const val FIRST_REF_ID: Long = 1L
    }
}
