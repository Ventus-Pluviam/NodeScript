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

/**
 * 挂载缝（§4.1/§6 依赖规则的必然产物）：namespace → 请求处理器。
 *
 * 为什么这个函数类型住 `:domain`：`BridgeRouter` 按 namespace 路由，而实现方
 * （`:platform:capabilities` 的 a11y/screen handler）**不允许**依赖 `:bridge:java`
 * （§6：capabilities 只依赖 `:domain` + 系统 API，其 archUnit 门禁把
 * `com.autoscript.bridge..` 列进黑名单）。把接缝类型放在两端都看得见的 `:domain`，
 * `:bridge:java` 用 typealias 保留 `RequestHandler` 名字，
 * `:app` 装配层（AppShell.assemble 注入）只负责把它挂上 Router。
 *
 * 语义仍完全是 [BridgeRequest]/[BridgeResponse]（§7），此处不新增任何契约字段。
 */
fun interface NamespaceHandler {
    suspend fun handle(request: BridgeRequest): BridgeResponse
}
