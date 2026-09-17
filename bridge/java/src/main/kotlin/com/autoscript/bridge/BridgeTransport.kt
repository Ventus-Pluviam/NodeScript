package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse

/** 传输层契约（docs/framework-design.md §7.5 transports）：控制面小对象无损编解码。 */
interface BridgeTransport {
    fun encodeRequest(request: BridgeRequest): ByteArray
    fun decodeRequest(bytes: ByteArray): BridgeRequest
    fun encodeResponse(response: BridgeResponse): ByteArray
    fun decodeResponse(bytes: ByteArray): BridgeResponse
}