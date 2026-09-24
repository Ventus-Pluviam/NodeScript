package com.autoscript.platform.capabilities

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.AppLauncher
import com.autoscript.domain.system.DeviceInfoProvider
import com.autoscript.domain.system.DialogChooseRequest
import com.autoscript.domain.system.DialogHost
import com.autoscript.domain.system.DialogMode
import com.autoscript.domain.system.DialogOutcome
import com.autoscript.domain.system.DialogPromptRequest
import com.autoscript.domain.system.FloatingWindowHost
import com.autoscript.domain.system.FloatingWindowSpec
import com.autoscript.domain.system.ShellExecutor
import com.autoscript.domain.system.ShellMode

/**
 * `dialogs` / `shell` / `device` / `app` / `floatingWindow` 五个命名空间的 JVM 可测实现
 * （docs §9.4 / §9.6 / §12.2；JS 对偶 `bridge/js/src/extras.ts`）。
 *
 * 为什么住 `:platform:capabilities` 而不是 `:platform:system`：§12.2 的注入缝是
 * `NamespaceHandler`（住 `:domain`），`BridgeRouter` 的 `RequestHandler` 只是它的
 * typealias。`:app` 禁止直连 `:platform`，所以「自有形状 → 桥信封」的转接必须发生在
 * 平台侧（本模块已有 [CapabilityNamespaces] 的先例），否则装配层要同时直连
 * `:platform:capabilities` 和 `:platform:system` 两个模块。
 *
 * **能力门禁不在这里**：`:app-service:permission-center` 的 `PermissionFacade` 住
 * `:app-service:*`，而本模块的 archUnit 黑名单含 `com.autoscript.appservice..`（§6）。
 * 所以门禁由装配层在调用本文件工厂前完成（`ensure(Capability.OVERLAY)` 等），
 * 本文件只负责**能力已保证之后的语义**：参数校验、句柄记账、分类错误。
 * 未接线的命名空间由 Router 如实回 ERR_NOT_IMPLEMENTED（§7.5），不伪造可用。
 *
 * 超时（铁律 3）：桥侧 TTL 由 `BridgeRequest.ttlMillis` 驱动，handler 必须在
 * TTL 内返回。`shell.exec` 的实现方超时（[DEFAULT_SHELL_TIMEOUT_MILLIS]）是宿主侧
 * 兜底：即使请求侧忘了给 TTL，也不会有无限等待的 `Runtime.waitFor`。
 */

// ── shell（§9.6）────────────────────────────────────────────────────

/** 默认 shell 超时：`child_process` 缺失的副作用由宿主侧兑现（§10 零 spawn），实现不得无限等。 */
const val DEFAULT_SHELL_TIMEOUT_MILLIS: Long = 30_000

class ShellNamespaceHandler(
    private val executor: ShellExecutor,
    private val defaultTimeoutMillis: Long = DEFAULT_SHELL_TIMEOUT_MILLIS,
) {
    suspend fun handle(request: BridgeRequestLite): ResponseLite = when (request.method) {
        "exec", "shell" -> exec(request)
        else -> ResponseLite.err(request.id, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 shell 方法: ${request.method}")
    }

    private suspend fun exec(request: BridgeRequestLite): ResponseLite {
        val fields = try {
            request.decodeObject()
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val cmd = try {
            request.requiredStr(fields, "cmd")
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val mode = try {
            request.enumOrNull(fields, "mode", ShellMode.DEFAULT) { ShellMode.valueOf(it.uppercase()) }
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val timeout = try {
            request.optLong(fields, "timeout") ?: defaultTimeoutMillis
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        if (timeout <= 0) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, "timeout 必须 > 0，实际 $timeout")
        }
        return try {
            val r = executor.exec(cmd, mode, timeout)
            ResponseLite.Ok(
                request.id,
                A11yBridgeJson.encode(
                    mapOf("code" to r.code.toLong(), "stdout" to r.stdout, "stderr" to r.stderr),
                ),
            )
        } catch (e: AutojsException) {
            ResponseLite.err(request.id, e.error, e.message)
        }
    }
}

// ── device（§9.6）───────────────────────────────────────────────────

class DeviceNamespaceHandler(private val info: DeviceInfoProvider) {
    suspend fun handle(request: BridgeRequestLite): ResponseLite = when (request.method) {
        "model" -> {
            val p = info.profile()   // 构造期已校验（空型号/SDK<1 即拒）
            ResponseLite.Ok(request.id, A11yBridgeJson.encode(p.model))
        }
        "sdkInt" -> ResponseLite.Ok(request.id, A11yBridgeJson.encode(info.profile().sdkInt.toLong()))
        else -> ResponseLite.err(request.id, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 device 方法: ${request.method}")
    }
}

// ── app（§9.3/§12.2）────────────────────────────────────────────────

class AppNamespaceHandler(private val launcher: AppLauncher) {
    suspend fun handle(request: BridgeRequestLite): ResponseLite = when (request.method) {
        "launch" -> {
            val fields = try {
                request.decodeObject()
            } catch (e: IllegalArgumentException) {
                return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
            }
            val pkg = try {
                request.requiredStr(fields, "packageName")
            } catch (e: IllegalArgumentException) {
                return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
            }
            // 起不来回 false（JS facade `=== true` 判成败），不抛错
            ResponseLite.Ok(request.id, A11yBridgeJson.encode(launcher.launch(pkg)))
        }
        "currentPackage" -> {
            val pkg = launcher.currentPackage()
            ResponseLite.Ok(request.id, A11yBridgeJson.encode(pkg))
        }
        else -> ResponseLite.err(request.id, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 app 方法: ${request.method}")
    }
}

// ── dialogs（§9.4）──────────────────────────────────────────────────

class DialogsNamespaceHandler(private val host: DialogHost) {
    suspend fun handle(request: BridgeRequestLite): ResponseLite = when (request.method) {
        "prompt" -> {
            val fields = try {
                request.decodeObject()
            } catch (e: IllegalArgumentException) {
                return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
            }
            val req = try {
                DialogPromptRequest(
                    title = request.requiredStr(fields, "title"),
                    placeholder = request.optStr(fields, "placeholder"),
                    mode = request.enumOrNull(fields, "mode", DialogMode.AUTO) { DialogMode.valueOf(it.uppercase()) },
                )
            } catch (e: IllegalArgumentException) {
                return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
            }
            val out = try {
                host.prompt(req)
            } catch (e: AutojsException) {
                return ResponseLite.err(request.id, e.error, e.message)
            }
            ResponseLite.Ok(
                request.id,
                A11yBridgeJson.encode(mapOf("value" to out.value, "confirmed" to out.confirmed)),
            )
        }
        "choose" -> {
            val fields = try {
                request.decodeObject()
            } catch (e: IllegalArgumentException) {
                return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
            }
            val req = try {
                DialogChooseRequest(
                    title = request.requiredStr(fields, "title"),
                    options = request.requiredStrList(fields, "options"),
                    mode = request.enumOrNull(fields, "mode", DialogMode.AUTO) { DialogMode.valueOf(it.uppercase()) },
                )
            } catch (e: IllegalArgumentException) {
                return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
            }
            val choice = try {
                host.choose(req)
            } catch (e: AutojsException) {
                return ResponseLite.err(request.id, e.error, e.message)
            }
            // 下标直出（JS facade `?? -1`）；取消即 -1，不套 null
            ResponseLite.Ok(request.id, A11yBridgeJson.encode(choice.index.toLong()))
        }
        else -> ResponseLite.err(request.id, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 dialogs 方法: ${request.method}")
    }
}

// ── floatingWindow（§9.4）───────────────────────────────────────────

class FloatingWindowNamespaceHandler(
    private val host: FloatingWindowHost,
) {
    suspend fun handle(request: BridgeRequestLite): ResponseLite {
        return when (request.method) {
            "create" -> {
                val fields = try {
                    request.decodeObject()
                } catch (e: IllegalArgumentException) {
                    return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
                }
                val spec = try {
                    FloatingWindowSpec(
                        title = request.optStr(fields, "title"),
                        width = request.optLong(fields, "width")?.toInt(),
                        height = request.optLong(fields, "height")?.toInt(),
                    )
                } catch (e: IllegalArgumentException) {
                    return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
                }
                val ref = try {
                    host.create(spec)
                } catch (e: AutojsException) {
                    return ResponseLite.err(request.id, e.error, e.message)
                }
                ResponseLite.Ok(
                    request.id,
                    A11yBridgeJson.encode(mapOf("refId" to ref.refId, "generation" to ref.generation)),
                )
            }
            "close" -> {
                val fields = try {
                    request.decodeObject()
                } catch (e: IllegalArgumentException) {
                    return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
                }
                val ref = try {
                    request.requiredRef(fields)
                } catch (e: IllegalArgumentException) {
                    return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
                }
                try {
                    host.close(ref)
                } catch (e: AutojsException) {
                    return ResponseLite.err(request.id, e.error, e.message)
                }
                ResponseLite.Ok(request.id, "true")
            }
            else -> ResponseLite.err(
                request.id,
                ErrorCode.ERR_NOT_IMPLEMENTED,
                "未知 floatingWindow 方法: ${request.method}",
            )
        }
    }
}

/**
 * 桥信封的最小视图（§7.4 BridgeRequest 的同构子集）。
 *
 * 为什么不让 handler 直接吃 `com.autoscript.domain.bridge.BridgeRequest`：本模块
 * archUnit 门禁把 `com.autoscript.bridge..` 整体列进黑名单（§6，严于设计表），
 * 而 `:domain` 与 `:bridge:java` 是两个不同包。用最小视图接住 id/method/payload
 * 三字段，转接层（[SystemNamespaces]）做字段级映射，逻辑零改动、门禁仍成立。
 * 与 [CapabilityNamespaces] 用 `NamespaceHandler` 缝是同一套办法的两面。
 */
data class BridgeRequestLite(
    val id: Long,
    val method: String,
    val payload: String?,
)

/** handler 自有响应形状（[ResponseLite.Err] 的 code 用 `ErrorCode.code` 原样透传）。 */
sealed interface ResponseLite {
    data class Ok(val id: Long, val payload: String?) : ResponseLite
    data class Err(val id: Long, val code: String, val detail: String?) : ResponseLite

    companion object {
        fun err(id: Long, code: ErrorCode, detail: String?): Err = Err(id, code.code, detail)
    }
}

/**
 * 载荷 helpers（[BridgeRequestLite] 的扩展，只在本文件用）。
 * 每个方法在非法输入上抛 IllegalArgumentException —— handler 折叠为
 * ERR_INVALID_PARAM（§7 诚实上报，不伪造成功）。
 */
internal fun BridgeRequestLite.decodeObject(): Map<String, A11yBridgeJson.Value> {
    if (payload == null) throw IllegalArgumentException("$method 缺 payload")
    return A11yBridgeJson.decodeObject(payload)
}

internal fun BridgeRequestLite.requiredStr(
    o: Map<String, A11yBridgeJson.Value>,
    key: String,
): String = (o[key] as? A11yBridgeJson.Value.S)?.v ?: throw IllegalArgumentException("缺字符串字段 $key")

internal fun BridgeRequestLite.optStr(
    o: Map<String, A11yBridgeJson.Value>,
    key: String,
): String? = when (val v = o[key]) {
    null, is A11yBridgeJson.Value.Null -> null
    is A11yBridgeJson.Value.S -> v.v
    else -> throw IllegalArgumentException("字段 $key 必须是字符串")
}

internal fun BridgeRequestLite.optLong(
    o: Map<String, A11yBridgeJson.Value>,
    key: String,
): Long? = when (val v = o[key]) {
    null, is A11yBridgeJson.Value.Null -> null
    is A11yBridgeJson.Value.N -> v.raw.toLongOrNull() ?: throw IllegalArgumentException("字段 $key 数字越界")
    else -> throw IllegalArgumentException("字段 $key 必须是数字")
}

internal fun BridgeRequestLite.requiredStrList(
    o: Map<String, A11yBridgeJson.Value>,
    key: String,
): List<String> {
    val v = o[key] ?: throw IllegalArgumentException("缺 $key 字段")
    if (v !is A11yBridgeJson.Value.Arr) throw IllegalArgumentException("$key 必须是数组")
    return v.items.map { (it as? A11yBridgeJson.Value.S)?.v ?: throw IllegalArgumentException("$key 必须是字符串数组") }
}

/** 必填数字（JSON 数字原文 → Double；缺键/非数字 → IllegalArgumentException）。
 * 置信度/阈值这类非整数量走它（optLong 只认整数，会悄悄把 `0.9` 挡成参数错）。 */
internal fun BridgeRequestLite.requiredDouble(
    o: Map<String, A11yBridgeJson.Value>,
    key: String,
): Double {
    val v = o[key] ?: throw IllegalArgumentException("缺数字字段 $key")
    return rawDouble(v, key)
}

/** 数字原文 → Double（拒绝 NaN/Infinity：wire 上送不着，实现侧也不该拿到）。 */
private fun rawDouble(v: A11yBridgeJson.Value, key: String): Double {
    if (v !is A11yBridgeJson.Value.N) throw IllegalArgumentException("字段 $key 必须是数字")
    val d = v.raw.toDoubleOrNull() ?: throw IllegalArgumentException("字段 $key 不是数字: ${v.raw}")
    if (!d.isFinite()) throw IllegalArgumentException("字段 $key 必须是有限数字")
    return d
}

internal fun BridgeRequestLite.requiredRef(o: Map<String, A11yBridgeJson.Value>): HandleRef =
    requiredRef(o, "ref")

/** 句柄字段：键可配（一处请求带两个句柄时 —— `images.matchTemplate` 的 haystack/needle）。 */
internal fun BridgeRequestLite.requiredRef(
    o: Map<String, A11yBridgeJson.Value>,
    key: String,
): HandleRef {
    val v = o[key] ?: throw IllegalArgumentException("缺 $key 字段")
    if (v !is A11yBridgeJson.Value.Obj) throw IllegalArgumentException("$key 必须是对象")
    val refId = (v.fields["refId"] as? A11yBridgeJson.Value.N)?.raw?.toLongOrNull()
        ?: throw IllegalArgumentException("缺数字 $key.refId")
    val gen = (v.fields["generation"] as? A11yBridgeJson.Value.N)?.raw?.toLongOrNull()
        ?: throw IllegalArgumentException("缺数字 $key.generation")
    return HandleRef(refId, gen)
}

/** 枚举字段：缺省/`null` 走 [fallback]；未知字面量拒绝（拼错即报错，不静默套默认）。 */
internal fun <T> BridgeRequestLite.enumOrNull(
    o: Map<String, A11yBridgeJson.Value>,
    key: String,
    fallback: T,
    parse: (String) -> T,
): T = when (val v = o[key]) {
    null, is A11yBridgeJson.Value.Null -> fallback
    is A11yBridgeJson.Value.S -> try {
        parse(v.v)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("未知 $key 值: ${v.v}")
    }
    else -> throw IllegalArgumentException("字段 $key 必须是字符串")
}
