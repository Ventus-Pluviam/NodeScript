package com.autoscript.platform.system.floatingWindow

import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.decodeObject
import com.autoscript.domain.bridge.optInt
import com.autoscript.domain.bridge.optStr
import com.autoscript.domain.bridge.requiredRef
import com.autoscript.domain.bridge.generated.WireMethods
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.json.DomainJson

/**
 * `floatingWindow` 命名空间的桥处理器（`create`/`close`，§9.4）
 *
 * 沿革与「门禁不在这里」的边界见 `SystemNamespaces` 的类注释（同一条纪律，此处不重复）。
 */

// ── floatingWindow（§9.4）───────────────────────────────────────────

class FloatingWindowNamespaceHandler(
    private val host: FloatingWindowHost,
) : RpcNamespaceHandler() {

    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("floatingWindow")

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse {
        return when (request.method) {
            "create" -> {
                val fields = request.decodeObject()
                val spec = run {
                    FloatingWindowSpec(
                        title = request.optStr(fields, "title"),
                        width = request.optInt(fields, "width"),
                        height = request.optInt(fields, "height"),
                    )
                }
                val ref = host.create(spec)
                ok(request, DomainJson.encode(mapOf("refId" to ref.refId, "generation" to ref.generation)),
                )
            }
            "close" -> {
                val fields = request.decodeObject()
                val ref = request.requiredRef(fields)
                run {
                    host.close(ref)
                }
                ok(request, "true")
            }
            else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED,
                "未知 floatingWindow 方法: ${request.method}",
            )
        }
    }
}
