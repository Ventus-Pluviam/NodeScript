package com.autoscript.domain.bridge

/**
 * 跨进程桥消息契约（docs/framework-design.md §7.4/§7.5）。
 * 控制面：小对象走 JSON；大二进制（Bitmap/像素）走 §7.4 ByteBuffer 直传通道，不入本 payload。
 */
data class BridgeRequest(
    val id: Long,
    val namespace: String,   // 如 "a11y"、"images"、"npm"
    val method: String,      // 如 "findOne"、"capture"
    val payload: String?,    // JSON 编码参数（可空 = 无参）
    val ttlMillis: Long,     // 请求侧 TTL，超时由 RequestRegistry 驱动 ERR_TIMEOUT
)

sealed interface BridgeResponse {
    val id: Long

    data class Ok(
        override val id: Long,
        val payload: String?,          // JSON 编码结果；大二进制走 side-channel
    ) : BridgeResponse

    data class Err(
        override val id: Long,
        val errorCode: String,         // ErrorCode.code（bride 侧仅透传，不解释）
        val detail: String?,
    ) : BridgeResponse
}

/**
 * 句柄引用（§7.4）：JS 侧代理对象持 [refId] + [generation]。
 * 操作携带 generation；控件/帧已离开窗口树或被 dispose → ERR_STALE_HANDLE。
 */
data class HandleRef(
    val refId: Long,
    val generation: Long,
)