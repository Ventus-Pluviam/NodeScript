package com.autoscript.platform.system

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.SensorDelay
import com.autoscript.domain.system.SensorEvent
import com.autoscript.domain.system.SensorEventBatch
import com.autoscript.domain.system.SensorSource
import java.util.concurrent.atomic.AtomicLong

/**
 * `sensors` 的宿主侧实现（docs §12.2；SPI 见 `:domain` 的 [SensorSource]，
 * 语义层 handler 在 `:platform:capabilities` 的 `SensorsNamespaceHandler`）。
 * 分层照 README ops 表：Android 接触面只有 [Ops] 缝（真机 [SensorOps] 碰
 * `android.hardware.SensorManager`），本类留**本机 JVM 可测**的语义：
 *
 * 1. **名归一化在本层**（`trim().lowercase()`；别名 `accel`/`gyro`/`temperature`/
 *    `humidity` 照 v9 口语收 —— wire 形状稳定，判定点唯一）；
 * 2. **发号在本层**：[HandleRef.refId] 单调递增、generation 恒 1（订阅不复用，
 *    一个订阅活一次 —— 与 `AndroidFloatingWindowHost` 同形）；
 * 3. **有界环在本层**：每订阅一条 [RING_CAPACITY] 上限的采样环，超界丢最旧
 *    （seq 空洞可见，不静默断流 —— 与 `A11yEventRing` 同纪律）；`drain` 按
 *    `seq > sinceSeq` 取增量，空增量回 `first == last == sinceSeq` + 空表；
 * 4. **注销纪律**：已知已关幂等成功（脚本 `finally` 补刀是常态），未知/跨代
 *    抛 `ERR_STALE_HANDLE`；已关订阅的 `drain` 同样 `ERR_STALE_HANDLE`
 *    （"关掉的订阅"与"没见过的订阅"必须能分辨，§7.4）；
 * 5. **系统事实折叠在本层**：名不在表/设备缺席 → `ERR_NOT_SUPPORTED`；
 *    `registerListener` 回 false → `ERR_SERVICE_DISABLED`（传感器在但系统不给收）。
 *
 * 线程：`onSample` 可在任意线程进（SensorEventListener 回调线程），锁内追加；
 * `drain`/`unregister` 同锁读 —— 与 `A11yEventRing` 的 guard 同一套办法。
 */
class AndroidSensorSource(
    private val ops: Ops,
) : SensorSource {

    private val guard = Any()
    private val ids = AtomicLong(1)

    /** refId → 订阅态（活着的；unregister 后移除，tomb 入 [closedEver]）。 */
    private val live = LinkedHashMap<Long, Subscription>()

    /** 已发过号的 refId（含已关闭）：分辨"关过的订阅"与"没见过的订阅"。 */
    private val closedEver = HashSet<Long>()

    private inner class Subscription(
        val token: Any,
        val events: ArrayList<SensorEvent> = ArrayList(),
        var nextSeq: Long = 1L,
    )

    override fun isSupported(name: String): Boolean {
        val norm = normalizeOrNull(name) ?: return false
        return ops.hasSensor(norm)
    }

    override suspend fun register(name: String, delay: SensorDelay): HandleRef {
        val norm = normalizeOrNull(name)
            ?: throw IllegalArgumentException("sensors name 不得为空白")
        if (!ops.hasSensor(norm)) {
            throw AutojsException(ErrorCode.ERR_NOT_SUPPORTED, "设备不支持传感器: $name")
        }
        val refId = ids.getAndIncrement()
        val sub = Subscription(token = Any())
        val accepted = try {
            ops.start(norm, delay, sub.token) { values, accuracy, timestamp ->
                push(sub, values, accuracy, timestamp)
            }
        } catch (e: AutojsException) {
            throw e
        } catch (e: Exception) {
            throw AutojsException(
                ErrorCode.ERR_SERVICE_DISABLED,
                "传感器系统拒收: $name（${e.message}）",
                e,
            )
        }
        if (!accepted) {
            throw AutojsException(ErrorCode.ERR_SERVICE_DISABLED, "传感器系统拒收: $name")
        }
        synchronized(guard) { live[refId] = sub }
        return HandleRef(refId, GENERATION)
    }

    override suspend fun unregister(ref: HandleRef) {
        if (ref.generation != GENERATION) {
            throw AutojsException(
                ErrorCode.ERR_STALE_HANDLE,
                "传感器订阅句柄代次不匹配：期望 $GENERATION，实际 ${ref.generation}",
            )
        }
        val sub = synchronized(guard) {
            val found = live.remove(ref.refId)
            if (found == null && ref.refId !in closedEver) {
                throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "未知传感器订阅 refId=${ref.refId}")
            }
            found
        }
        // 已关闭（号发过但不在 live）→ 幂等返回，不再碰传感器系统。
        if (sub == null) return
        synchronized(guard) { closedEver += ref.refId }
        try {
            ops.stop(sub.token)
        } catch (_: Exception) {
            // 监听已不在（如系统侧先行释放）：**目标状态已达成**，不把它变成失败
            // （与 AndroidFloatingWindowHost.close 的处置同构）。
        }
    }

    override suspend fun unregisterAll() {
        val subs = synchronized(guard) {
            val all = live.values.toList()
            closedEver += live.keys
            live.clear()
            all
        }
        for (sub in subs) {
            try {
                ops.stop(sub.token)
            } catch (_: Exception) {
                // 同 unregister：目标状态已达成，不逐个翻成失败。
            }
        }
    }

    override suspend fun drain(ref: HandleRef, sinceSeq: Long, max: Int): SensorEventBatch {
        require(max > 0) { "drain 的 max 必须 > 0，实际 $max" }
        if (ref.generation != GENERATION) {
            throw AutojsException(
                ErrorCode.ERR_STALE_HANDLE,
                "传感器订阅句柄代次不匹配：期望 $GENERATION，实际 ${ref.generation}",
            )
        }
        val sub = synchronized(guard) {
            live[ref.refId]
                ?: throw AutojsException(
                    ErrorCode.ERR_STALE_HANDLE,
                    if (ref.refId in closedEver) "传感器订阅已关闭 refId=${ref.refId}"
                    else "未知传感器订阅 refId=${ref.refId}",
                )
        }
        val picked = synchronized(sub) {
            sub.events.filter { it.seq > sinceSeq }.take(max)
        }
        if (picked.isEmpty()) return SensorEventBatch(sinceSeq, sinceSeq, emptyList())
        return SensorEventBatch(picked.first().seq, picked.last().seq, picked)
    }

    /** 系统回调入口（ops 真实现经 SensorEventListener 转调；单测直调）。 */
    private fun push(sub: Subscription, values: List<Double>, accuracy: Int, timestamp: Long) {
        synchronized(sub) {
            sub.events.add(SensorEvent(seq = sub.nextSeq++, values = values, accuracy = accuracy, timestamp = timestamp))
            while (sub.events.size > RING_CAPACITY) sub.events.removeAt(0)
        }
    }

    /**
     * 传感器接触面（README ops 表的本行）：真机 [SensorOps]；单测注入内存替身 ——
     * `SensorManager` 是系统服务，stub 运行期抛异常。
     *
     * [start] 的 `onSample` 由实现方在每次采样时调用（任意线程）；回 false =
     * 系统拒收（宿主折 `ERR_SERVICE_DISABLED`，绝不返回假句柄）。
     */
    interface Ops {
        /** 设备是否有该传感器（名已归一化）。 */
        fun hasSensor(normalizedName: String): Boolean

        /** 开始监听；false = 系统拒收。 */
        fun start(
            normalizedName: String,
            delay: SensorDelay,
            token: Any,
            onSample: (values: List<Double>, accuracy: Int, timestamp: Long) -> Unit,
        ): Boolean

        /** 停止监听（token 是 start 时交出的同一份）。 */
        fun stop(token: Any)
    }

    companion object {
        /** 句柄代次：订阅不复用，故恒 1（§7.4 的 generation 语义）。 */
        const val GENERATION: Long = 1

        /** 单订阅采样环上限（与 A11yEventRing 同纪律：超界丢最旧，seq 空洞可见）。 */
        const val RING_CAPACITY: Int = 512

        /** P0 名单（motion/environment 系；心率等 BODY_SENSORS 系不在此表）。 */
        val SUPPORTED_NAMES: Set<String> = setOf(
            "accelerometer",
            "magnetic_field",
            "orientation",
            "gyroscope",
            "light",
            "pressure",
            "proximity",
            "gravity",
            "linear_acceleration",
            "rotation_vector",
            "ambient_temperature",
            "relative_humidity",
        )

        /** v9 口语别名（wire 形状稳定的归一化，不是第二套名字）。 */
        fun normalizeOrNull(name: String): String? {
            if (name.isBlank()) return null
            return when (val n = name.trim().lowercase()) {
                "accel" -> "accelerometer"
                "gyro" -> "gyroscope"
                "temperature" -> "ambient_temperature"
                "humidity" -> "relative_humidity"
                else -> n
            }
        }

        /** 名单内（归一化后在表里；设备在场由 ops 另判）。 */
        fun isKnownName(name: String): Boolean {
            val norm = normalizeOrNull(name) ?: return false
            return norm in SUPPORTED_NAMES
        }
    }
}
