package com.autoscript.platform.system

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.autoscript.domain.system.SensorDelay

/**
 * [AndroidSensorSource.Ops] 的真机实现（docs §12.2）：唯一碰
 * `android.hardware.SensorManager` 的地方。这里只有「查表/启停 + 把系统事实折成契约形状」：
 *
 * - [hasSensor]：名（已归一化）→ `TYPE_*` 查表 + `getDefaultSensor != null`
 *   （名不在表 → false；设备缺席 → false —— 两者都是"不支持"，细分归宿主）；
 * - [start]：`registerListener(listener, sensor, rate)` 回 false = 系统拒收
 *   （宿主折 `ERR_SERVICE_DISABLED`，绝不返回假句柄）；抛异常同样由宿主折现场；
 * - [stop]：`unregisterListener(listener)`（token 即 listener 注册表键）。
 *
 * delay 四档 → `SENSOR_DELAY_*` 直映（与 `:domain` [SensorDelay] 同序同值，
 * 亦与 v9 `SensorDelay.FASTEST/GAME/UI/NORMAL = 0/1/2/3` 同序）。
 *
 * 本机 JVM **只编译不执行**（系统服务 + Context，stub 运行期抛异常）。
 */
class SensorOps(private val context: Context) : AndroidSensorSource.Ops {

    private val manager: SensorManager?
        get() = context.getSystemService(SensorManager::class.java)

    private val listeners = java.util.concurrent.ConcurrentHashMap<Any, SensorEventListener>()

    override fun hasSensor(normalizedName: String): Boolean {
        val type = TYPE_OF[normalizedName] ?: return false
        return manager?.getDefaultSensor(type) != null
    }

    override fun start(
        normalizedName: String,
        delay: SensorDelay,
        token: Any,
        onSample: (values: List<Double>, accuracy: Int, timestamp: Long) -> Unit,
    ): Boolean {
        val mgr = manager ?: return false
        val type = TYPE_OF[normalizedName] ?: return false
        val sensor = mgr.getDefaultSensor(type) ?: return false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                onSample(event.values.map { it.toDouble() }, event.accuracy, event.timestamp)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listeners[token] = listener
        val ok = try {
            mgr.registerListener(listener, sensor, RATE_OF[delay] ?: SensorManager.SENSOR_DELAY_NORMAL)
        } catch (_: Exception) {
            false
        }
        if (!ok) listeners.remove(token)
        return ok
    }

    override fun stop(token: Any) {
        val listener = listeners.remove(token) ?: return
        try {
            manager?.unregisterListener(listener)
        } catch (_: Exception) {
            // 监听已不在：目标状态已达成，宿主同样吞掉（见 AndroidSensorSource）。
        }
    }

    companion object {
        /** 归一化名 → `Sensor.TYPE_*`（P0 名单；BODY_SENSORS 系不在此表）。 */
        val TYPE_OF: Map<String, Int> = mapOf(
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "magnetic_field" to Sensor.TYPE_MAGNETIC_FIELD,
            "orientation" to Sensor.TYPE_ORIENTATION,
            "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "light" to Sensor.TYPE_LIGHT,
            "pressure" to Sensor.TYPE_PRESSURE,
            "proximity" to Sensor.TYPE_PROXIMITY,
            "gravity" to Sensor.TYPE_GRAVITY,
            "linear_acceleration" to Sensor.TYPE_LINEAR_ACCELERATION,
            "rotation_vector" to Sensor.TYPE_ROTATION_VECTOR,
            "ambient_temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
            "relative_humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
        )

        /** [SensorDelay] → `SENSOR_DELAY_*` 直映。 */
        val RATE_OF: Map<SensorDelay, Int> = mapOf(
            SensorDelay.FASTEST to SensorManager.SENSOR_DELAY_FASTEST,
            SensorDelay.GAME to SensorManager.SENSOR_DELAY_GAME,
            SensorDelay.UI to SensorManager.SENSOR_DELAY_UI,
            SensorDelay.NORMAL to SensorManager.SENSOR_DELAY_NORMAL,
        )
    }
}
