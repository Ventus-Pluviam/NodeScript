package com.autoscript.platform.system

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.FloatingWindowHost
import com.autoscript.domain.system.FloatingWindowSpec
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
 *   "已经关掉的句柄"与"从来没见过的句柄"必须能分辨（§7.4）。
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

    private val ids = AtomicLong(1)

    /** [HandleRef.refId] → 窗口令牌（活着的）。`close` 后移除。 */
    private val live = LinkedHashMap<Long, Any>()

    /** 已发过号的 refId（含已关闭）：用来把"关过的句柄"与"没见过的句柄"分开。 */
    private val everIssued = HashSet<Long>()
    private val guard = Mutex()

    override suspend fun create(spec: FloatingWindowSpec): HandleRef {
        val token = try {
            ops.add(spec, overlay = overlayTypeAvailable())
        } catch (e: AutojsException) {
            throw e
        } catch (e: Exception) {
            // 现场拒绝（未授予 SYSTEM_ALERT_WINDOW / 窗口类型不可用）→ 分类错误，绝不返回假句柄。
            throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "悬浮窗添加被系统拒绝（overlay 权限或窗口类型不可用）：${e.message}",
                e,
            )
        }
        val refId = ids.getAndIncrement()
        guard.withLock {
            live[refId] = token
            everIssued += refId
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
            if (found == null && ref.refId !in everIssued) {
                throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "未知悬浮窗句柄 refId=${ref.refId}")
            }
            found
        }
        // 已关闭（号发过但不在 live）→ 幂等返回，不再碰窗口系统。
        if (token == null) return
        try {
            ops.remove(token)
        } catch (e: AutojsException) {
            throw e
        } catch (e: Exception) {
            // 窗口已被系统收走（如 a11y 服务被关）：**目标状态已达成**，不把它变成失败。
            // （这条与 create 的处置相反是有意的：create 没做成就是没做成，close 已经关掉了就是关掉了。）
        }
    }

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

    private companion object {
        /** 句柄代次：窗口不复用，故恒 1（§7.4 的 generation 语义）。 */
        const val GENERATION: Long = 1
    }
}
