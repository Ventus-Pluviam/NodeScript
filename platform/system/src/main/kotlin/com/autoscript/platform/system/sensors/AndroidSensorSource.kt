package com.autoscript.platform.system.sensors

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
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
 *    （"关掉的订阅"与"没见过的订阅"必须能分辨，§7.4）—— 分辨靠**号段水位**
 *    [lastIssued]，不是"发过的号"那张集合（见其 KDoc：那是一条无界增长）；
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
    private val ids = AtomicLong(FIRST_REF_ID)

    /** refId → 订阅态（活着的；`unregister`/`unregisterAll` 后移除）。 */
    private val live = LinkedHashMap<Long, Subscription>()

    /**
     * **已发出去的最后一个号**（水位；只在 [guard] 内读写，发号也在同一把锁内）。
     *
     * 它是"关过的订阅"与"没见过的订阅"的**全部**判据（[issuedLocked]）：号单调递增且
     * **绝不复用**（§7.4），于是 `号 ≤ 水位 && 不在 live` ⟺ 发过且已关，`号 > 水位`
     * ⟺ 从来没见过。
     *
     * **为什么不是"发过的号"那张集合**（原 `closedEver: HashSet<Long>`）：本类是
     * `SystemSpis.of` 装配期建的**进程级单例**，一张只进不退的号集合就是一条无界增长
     * —— 一个 register/unregister 循环跑一百万次，集合里就有一百万个装箱 Long（约几十 MB），
     * 而它的用途只是回答一个**区间**问题。仓里同类三处都已经裁过
     * （`ProjectionForegroundHost.ABANDONED_KEEP`、`MediaProjectionRecorder.FINISHED_KEEP`、
     * `ScreenNamespaceHandler.RECORDINGS_KEEP` 各留 64 条），这里连"留多少条"都不用定：
     * 水位是 O(1) 且**精确**（裁剪过的集合会把"很久以前关的"误报成"没见过"）。
     *
     * **水位成立的前提**：号只在**订阅真建成之后**才前进（见 [register]）—— 否则失败那次
     * 消耗的号会落在水位以下、却从没进过 `live`，于是一个从没发出去的号被报成"已关闭"
     * （那是 wire 上的谎：脚本会以为它曾经拿到过这个订阅）。
     */
    private var lastIssued: Long = FIRST_REF_ID - 1

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
        // **成了才占号**（水位判据的前提，见 [lastIssued]）：发号与入表在同一把锁内，
        // 于是水位与 live 之间不存在"号发了但表还没落"的窗口。
        val refId = synchronized(guard) {
            val id = ids.getAndIncrement()
            live[id] = sub
            lastIssued = id
            id
        }
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
            if (found == null && !issuedLocked(ref.refId)) {
                throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "未知传感器订阅 refId=${ref.refId}")
            }
            found
        }
        // 已关闭（号在水位以下但不在 live）→ 幂等返回，不再碰传感器系统。
        if (sub == null) return
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
            // 不用另记 tomb：清空的号本来就都在水位以下（[issuedLocked] 即答"已关闭"）。
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
                    if (issuedLocked(ref.refId)) "传感器订阅已关闭 refId=${ref.refId}"
                    else "未知传感器订阅 refId=${ref.refId}",
                )
        }
        val picked = synchronized(sub) {
            sub.events.filter { it.seq > sinceSeq }.take(max)
        }
        if (picked.isEmpty()) return SensorEventBatch(sinceSeq, sinceSeq, emptyList())
        return SensorEventBatch(picked.first().seq, picked.last().seq, picked)
    }

    /**
     * 这个号**发出去过**吗（调用方须持 [guard]）：`[FIRST_REF_ID, lastIssued]` 区间内即发过。
     *
     * 下界那一半不是多余的：负数与 0 都在水位以下，却从没发过 —— 少了它就是拿"已关闭"
     * 回答一个垃圾号（同 [lastIssued] 那条"不报假事实"的理由）。
     */
    private fun issuedLocked(refId: Long): Boolean = refId in FIRST_REF_ID..lastIssued

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

        /** 号段起点（[lastIssued] 的下界判据；单源，别在两处各写一个 1）。 */
        const val FIRST_REF_ID: Long = 1L

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
