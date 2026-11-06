package com.autoscript.platform.system.sensors
import com.autoscript.domain.bridge.generated.WireMethods

import com.autoscript.domain.bridge.decodeObject
import com.autoscript.domain.bridge.optInt
import com.autoscript.domain.bridge.optLong
import com.autoscript.domain.bridge.requiredRef
import com.autoscript.domain.bridge.enumOrNull
import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/**
 * `sensors` 命名空间的桥处理器（§12.2；JS 对偶 `bridge/js/src/sensors.ts`；
 * SPI 见 `:domain` 的 [SensorSource]，真身 `:platform:system` 的 `AndroidSensorSource`）。
 *
 * 与 [DatastoreNamespaceHandler] / [ZipNamespaceHandler] / [SettingsNamespaceHandler] /
 * [NotificationNamespaceHandler] / [ClipboardNamespaceHandler] 同属系统面：**独立注入缝**
 * （`AppShell.assemble` 的 `sensorsHandler`），不入 `SystemHandlers` 束 —— 五个共担
 * OVERLAY/ROOT/ADB_INPUT，传感器 P0 名单无运行时门禁（判据在 SPI：未知名→`ERR_NOT_SUPPORTED`、
 * 系统拒收→`ERR_SERVICE_DISABLED`，§12.2 接线表）。单独成文件同前五者：混进
 * `SystemNamespaces.kt` 会搅浑「接就五个一起接」的束语义。
 *
 * **五方法**：`isSupported`/`register`/`unregister`/`unregisterAll`/`drain`
 * （照抄 [SensorSource]；v9 的 `ignoresUnsupported` 标志不进契约 —— facades 侧折叠，
 * 见 `sensors.ts`）。
 *
 * **拉取式游标，不做 push 回调**（见 `:domain` [SensorSource] KDoc）：`drain` 的
 * `{ref,sinceSeq?,max?}` → `{first,last,events:[{seq,values,accuracy,timestamp}]}`，
 * 与 `a11y.events` 同形（空增量回 `first == last == sinceSeq` + 空表 ——
 * 调用方以前进游标为准，不以空数组为终结）。
 *
 * 参数口径只认在场形态：空白名/非法 delay/非正 max/缺 ref → `ERR_INVALID_PARAM`，
 * 且（除读口外）一次 SPI 写都不发。`delay` 缺省 `NORMAL`、wire 传**名字面量**
 * （与 `ShellMode`/`DialogMode` 的字符串枚举同纪律，拼错即拒不静默套默认）。
 * SPI 抛的 `AutojsException` 原码透传（`ERR_NOT_SUPPORTED`/`ERR_SERVICE_DISABLED`/
 * `ERR_STALE_HANDLE` 不折叠成 `ERR_INVALID_PARAM` —— 调用方要能识别并引导）。
 * 未知方法 → `ERR_NOT_IMPLEMENTED`（`on/subscribe/watch` 这类没约定过的别名不猜）。
 */
class SensorsNamespaceHandler(
    private val sensors: SensorSource,
) : RpcNamespaceHandler() {

    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("sensors")

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "isSupported" -> isSupported(request)
        "register" -> register(request)
        "unregister" -> unregister(request)
        "unregisterAll" -> unregisterAll(request)
        "drain" -> drain(request)
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED,
            "未知 sensors 方法: ${request.method}",
        )
    }

    /** 设备是否支持该传感器；空白名 → `ERR_INVALID_PARAM`（不是 false —— 垃圾名不是答案）。 */
    private fun isSupported(request: BridgeRequest): BridgeResponse {
        val name = sensorNameOf(request)
        return ok(request, sensors.isSupported(name).toString())
    }

    private suspend fun register(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val name: String
        val delay: SensorDelay
        run {
            name = sensorNameOf(fields)
            delay = request.enumOrNull(fields, "delay", SensorDelay.NORMAL) {
                SensorDelay.valueOf(it.uppercase())
            }
        }
        return run {
            val ref = sensors.register(name, delay)
            ok(request, DomainJson.encode(mapOf("refId" to ref.refId, "generation" to ref.generation)),
            )
        }
    }

    private suspend fun unregister(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val ref = request.requiredRef(fields)
        return run {
            sensors.unregister(ref)
            ok(request, "true")
        }
    }

    private suspend fun unregisterAll(request: BridgeRequest): BridgeResponse = run {
        sensors.unregisterAll()
        ok(request, "true")
    }

    private suspend fun drain(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val ref = request.requiredRef(fields)
        val sinceSeq: Long
        val max: Int
        run {
            sinceSeq = request.optLong(fields, "sinceSeq") ?: 0L
            max = request.optInt(fields, "max") ?: 128
            require(max > 0) { "drain 的 max 必须 > 0，实际 $max" }
        }
        return run {
            val got = sensors.drain(ref, sinceSeq, max)
            ok(request, DomainJson.encode(
                    mapOf(
                        "first" to got.firstSeq,
                        "last" to got.lastSeq,
                        "events" to got.events.map {
                            mapOf(
                                "seq" to it.seq,
                                "values" to it.values,
                                "accuracy" to it.accuracy.toLong(),
                                "timestamp" to it.timestamp,
                            )
                        },
                    ),
                ),
            )
        }
    }

    /** 取 `name` 字段：必须是**非空白**字符串（空白名是参数错，不是"不支持"）。 */
    private fun sensorNameOf(request: BridgeRequest): String =
        sensorNameOf(request.decodeObject())

    private fun sensorNameOf(fields: Map<String, DomainJson.Value>): String {
        val name = fields["name"] as? DomainJson.Value.S
            ?: throw IllegalArgumentException("sensors 缺 name 字段或 name 不是字符串")
        if (name.v.isBlank()) throw IllegalArgumentException("sensors name 不得为空白")
        return name.v
    }
}
