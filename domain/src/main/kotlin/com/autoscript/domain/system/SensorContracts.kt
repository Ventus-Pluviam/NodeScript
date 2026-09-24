package com.autoscript.domain.system

import com.autoscript.domain.bridge.HandleRef

/**
 * `sensors` 命名空间契约（docs/framework-design.md §12.2；JS 对偶 `auto.sensors`，
 * 对标 AutoJsPro v9 `sensors.register/unregister/unregisterAll` + `SensorDelay` 四档）。
 *
 * 为什么住 `:domain`：与 [Clipboard] / [NotificationPoster] 同一套理由 —— 真实现要碰
 * `android.hardware.SensorManager`，§6 要求 `:platform:*` 只依赖 `:domain`；
 * 「采什么、怎么拿」与「怎么问系统」切开，桥面 handler 才是纯 JVM 可测的。
 *
 * **拉取式游标，不做跨进程 push 回调**（与 `a11y.events` / `engines.channelDrain` 同构）：
 * 传感器是系统侧的 push 源（`SensorEventListener`），但跨进程调用永远异步且有 TTL
 * （铁律 2/3）—— 宿主向脚本 push 意味着背压与订阅生命周期归属不清（脚本进程死了，
 * 宿主侧的回调发给谁？）。于是采样在宿主侧进**有界环**（超界丢最旧，seq 空洞可见），
 * 脚本侧持游标 `drain` 拉增量；JS 的 `on('change')` 只是 facade 的节流轮询
 * （与 `EngineChannel.on` 同一条纪律），不是第二套订阅语义。
 *
 * **P0 范围钉死**（刻意不预支的面，逐条给理由）：
 * - **只做 motion/environment 名单**（`accelerometer`/`gyroscope`/`light`/`proximity`/
 *   `magnetic_field`/`pressure`/`gravity`/`linear_acceleration`/`rotation_vector`/
 *   `orientation`/`ambient_temperature`/`relative_humidity` —— 名单是文档，判定在实现）：
 *   心率等 `BODY_SENSORS` 系要运行时权限，等有真实消费方再开，不预支；
 * - **delay 四档照抄 Android**（[SensorDelay] 与 `SensorManager.SENSOR_DELAY_*` 同值，
 *   亦与 v9 `SensorDelay.FASTEST/GAME/UI/NORMAL = 0/1/2/3` 同序）：wire 传**名字面量**
 *   （与 `ShellMode`/`DialogMode` 的字符串枚举同纪律），缺省 `NORMAL`；
 * - **不支持是分类错误**：未知名/设备缺席 → `ERR_NOT_SUPPORTED`（不是回 null ——
 *   v9 的 `ignoresUnsupportedSensor=true` 回 null 是**facade 层的折叠**，
 *   本 SPI 只抛，flag 不进契约）；
 * - **系统拒收是现场事实**：`registerListener` 回 false → `ERR_SERVICE_DISABLED`
 *   （传感器在，但系统不给收 —— 与 a11y 服务未连同码）。
 *
 * **句柄纪律**（与 `FloatingWindowHost` 同形）：[register] 发号（[HandleRef.refId] 单调递增，
 * [HandleRef.generation] 恒 1 —— 订阅不复用，一个订阅活一次）；[unregister] 对**已知已关**
 * 幂等成功（脚本 `finally` 里补一刀是常态），**未知/跨代**抛 `ERR_STALE_HANDLE`；
 * [drain] 凭句柄读环，已关/未知/跨代同样 `ERR_STALE_HANDLE`（"关掉的订阅"与
 * "没见过的订阅"必须能分辨，§7.4）。
 *
 * **P0 名单无运行时门禁**：motion/environment 系不需要申请权限 —— 门禁判据仍在 SPI
 * 自己身上（未知名→`ERR_NOT_SUPPORTED`，系统拒收→`ERR_SERVICE_DISABLED`），
 * 与五个命名空间的 OVERLAY/ROOT/ADB_INPUT 不共担，故走**独立注入缝**。
 */
enum class SensorDelay { FASTEST, GAME, UI, NORMAL }

/** 一次采样（`values` 语义随传感器类型，见 v9 文档；`timestamp` 是系统 boot-time 纳秒，不透明）。 */
data class SensorEvent(
    val seq: Long,
    val values: List<Double>,
    val accuracy: Int,
    val timestamp: Long,
)

/** 增量批次（空增量回 `first == last == sinceSeq` + 空表 —— 调用方以前进游标为准）。 */
data class SensorEventBatch(
    val firstSeq: Long,
    val lastSeq: Long,
    val events: List<SensorEvent>,
)

/** 传感器采样 SPI。实现住 `:platform:system`（`AndroidSensorSource` + `SensorOps`）。 */
interface SensorSource {

    /** 设备是否支持该传感器（名归一化后查表 + 设备在场双判；空白名回 false 不抛）。 */
    fun isSupported(name: String): Boolean

    /**
     * 注册监听并返回订阅句柄。
     * @throws IllegalArgumentException 空白名（handler 折 `ERR_INVALID_PARAM`）。
     * @throws com.autoscript.domain.core.AutojsException `ERR_NOT_SUPPORTED` 不支持；
     * `ERR_SERVICE_DISABLED` 系统拒收。
     */
    suspend fun register(name: String, delay: SensorDelay = SensorDelay.NORMAL): HandleRef

    /**
     * 注销订阅（已知已关幂等成功；未知/跨代抛 `ERR_STALE_HANDLE`）。
     * @throws com.autoscript.domain.core.AutojsException `ERR_STALE_HANDLE`。
     */
    suspend fun unregister(ref: HandleRef)

    /**
     * 注销**全部**订阅。
     *
     * 越界提醒：宿主侧没有脚本归属（桥 handler 不认"谁注册的"），清的就是全清 ——
     * 多脚本并发时误调会掐掉别人的订阅。要精准请 `unregister(ref)`。
     */
    suspend fun unregisterAll()

    /**
     * 拉取增量（`seq > sinceSeq`，至多 `max` 条）。
     * @throws IllegalArgumentException `max <= 0`（handler 折 `ERR_INVALID_PARAM`）。
     * @throws com.autoscript.domain.core.AutojsException `ERR_STALE_HANDLE` 句柄已死。
     */
    suspend fun drain(ref: HandleRef, sinceSeq: Long, max: Int): SensorEventBatch
}
