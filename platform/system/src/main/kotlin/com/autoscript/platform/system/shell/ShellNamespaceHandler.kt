package com.autoscript.platform.system.shell

import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.decodeObject
import com.autoscript.domain.bridge.enumOrNull
import com.autoscript.domain.bridge.optLong
import com.autoscript.domain.bridge.requiredStr
import com.autoscript.domain.bridge.generated.WireMethods
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.json.DomainJson

/**
 * `shell` 命名空间的桥处理器（`exec`/`shell` 两方法；JS `extras.ts` 的 `shell()` 只是 `exec()` 的别名）
 *
 * 沿革与「门禁不在这里」的边界见 `SystemNamespaces` 的类注释（同一条纪律，此处不重复）。
 */

// ── shell（§9.6）────────────────────────────────────────────────────

/** 默认 shell 超时：`child_process` 缺失的副作用由宿主侧兑现（§10 零 spawn），实现不得无限等。 */
const val DEFAULT_SHELL_TIMEOUT_MILLIS: Long = 30_000

class ShellNamespaceHandler(
    private val executor: ShellExecutor,
    private val defaultTimeoutMillis: Long = DEFAULT_SHELL_TIMEOUT_MILLIS,
) : RpcNamespaceHandler() {

    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("shell")

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "exec", "shell" -> exec(request)
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 shell 方法: ${request.method}")
    }

    private suspend fun exec(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val cmd = request.requiredStr(fields, "cmd")
        val mode = request.enumOrNull(fields, "mode", ShellMode.DEFAULT) { ShellMode.valueOf(it.uppercase()) }
        val timeout = request.optLong(fields, "timeout") ?: defaultTimeoutMillis
        if (timeout <= 0) {
            return err(request, ErrorCode.ERR_INVALID_PARAM, "timeout 必须 > 0，实际 $timeout")
        }
        return run {
            val r = executor.exec(cmd, mode, timeout)
            // 四字段与 `extras.ts` 的 `ShellResult` 逐字对齐；`truncated`
            // 纯增量字段（实现侧超 [ShellCaptureLimit.MAX_CAPTURE_BYTES] 才为 true，
            // 见 `docs/design-decisions.md` 第 21 项）—— JS 侧读不到它时按缺省 false 处理。
            ok(request, DomainJson.encode(
                    mapOf(
                        "code" to r.code.toLong(),
                        "stdout" to r.stdout,
                        "stderr" to r.stderr,
                        "truncated" to r.truncated,
                    ),
                ),
            )
        }
    }
}
