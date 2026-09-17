package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import java.nio.charset.StandardCharsets

/**
 * 桥信封 JSON 编码（控制面小对象）。主结构：
 *
 * 请求 {"t":"req","id":1,"ns":"a11y","m":"findOne","ttl":5000,"payload":<json|null>,"side":<long|null>}
 * 成功 {"t":"ok","id":1,"payload":<json|null>,"side":<long|null>}
 * 错误 {"t":"err","id":1,"code":"ERR_*","detail":<string|null>}
 *
 * "side" 为传输层扩展：大二进制（Bitmap/像素）走 §7.4 side-channel 时携带句柄引用；
 * 领域类型 BridgeRequest/BridgeResponse 不感知（payload 内自持，由实现方约定）。
 */
class JsonTransport : BridgeTransport {

    private val allowedReq = setOf("t", "id", "ns", "m", "ttl", "payload", "side")

    override fun encodeRequest(request: BridgeRequest): ByteArray = TinyJson.encode(
        listOf(
            "t" to TinyJson.Field.S("req"),
            "id" to TinyJson.Field.N(request.id.toString()),
            "ns" to TinyJson.Field.S(request.namespace),
            "m" to TinyJson.Field.S(request.method),
            "ttl" to TinyJson.Field.N(request.ttlMillis.toString()),
            "payload" to (request.payload?.let { TinyJson.Field.S(it) } ?: TinyJson.Field.Null),
            "side" to TinyJson.Field.Null,
        ),
    ).toByteArray(StandardCharsets.UTF_8)

    override fun decodeRequest(bytes: ByteArray): BridgeRequest {
        val m = TinyJson.decode(String(bytes, StandardCharsets.UTF_8), allowedReq)
        require(m["t"] == TinyJson.Field.S("req")) { "非请求信封" }
        return BridgeRequest(
            id = num(m, "id").toLong(),
            namespace = str(m, "ns"),
            method = str(m, "m"),
            payload = m["payload"]?.let { (it as? TinyJson.Field.S)?.v },
            ttlMillis = num(m, "ttl").toLong(),
        )
    }

    override fun encodeResponse(response: BridgeResponse): ByteArray = when (response) {
        is BridgeResponse.Ok -> TinyJson.encode(
            listOf(
                "t" to TinyJson.Field.S("ok"),
                "id" to TinyJson.Field.N(response.id.toString()),
                "payload" to (response.payload?.let { TinyJson.Field.S(it) } ?: TinyJson.Field.Null),
                "side" to TinyJson.Field.Null,
            ),
        )
        is BridgeResponse.Err -> TinyJson.encode(
            listOf(
                "t" to TinyJson.Field.S("err"),
                "id" to TinyJson.Field.N(response.id.toString()),
                "code" to TinyJson.Field.S(response.errorCode),
                "detail" to (response.detail?.let { TinyJson.Field.S(it) } ?: TinyJson.Field.Null),
            ),
        )
    }.toByteArray(StandardCharsets.UTF_8)

    override fun decodeResponse(bytes: ByteArray): BridgeResponse {
        val m = TinyJson.decode(String(bytes, StandardCharsets.UTF_8), setOf("t", "id", "payload", "side", "code", "detail"))
        val id = num(m, "id").toLong()
        return when ((m["t"] as? TinyJson.Field.S)?.v) {
            "ok" -> BridgeResponse.Ok(id, m["payload"]?.let { (it as? TinyJson.Field.S)?.v })
            "err" -> BridgeResponse.Err(id, str(m, "code"), m["detail"]?.let { (it as? TinyJson.Field.S)?.v })
            else -> throw IllegalArgumentException("未知响应类型")
        }
    }

    private fun str(m: Map<String, TinyJson.Field>, k: String): String =
        (m[k] as? TinyJson.Field.S)?.v ?: throw IllegalArgumentException("缺少字符串字段 $k")

    private fun num(m: Map<String, TinyJson.Field>, k: String): String =
        (m[k] as? TinyJson.Field.N)?.v ?: throw IllegalArgumentException("缺少数字字段 $k")
}