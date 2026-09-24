package com.autoscript.platform.capabilities

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.Clipboard

/**
 * `clipboard` 命名空间的桥处理器（§12.2；JS 对偶 `bridge/js/src/clipboard.ts`）。
 *
 * 与 [DatastoreNamespaceHandler] / [ZipNamespaceHandler] / [SettingsNamespaceHandler] /
 * [NotificationNamespaceHandler] 同属系统面：**独立注入缝**（`AppShell.assemble` 的
 * `clipboardHandler`），不入 `SystemHandlers` 束 —— 那五个共担 OVERLAY/ROOT/ADB_INPUT
 * 门禁，剪贴板无门禁（读受限是系统的 null 答案、写不受限），判据在 SPI 自己身上
 * （§12.2 接线表）。单独成文件同前四者：混进 `SystemNamespaces.kt` 会搅浑
 * 「接就五个一起接」的束语义。
 *
 * **两方法**：`getText`/`setText`（照抄 [Clipboard]，不提供别名）。
 *
 * **读侧 null 是常态**（见 `:domain` [Clipboard] KDoc）：空剪贴板 / 后台受限时系统的
 * null 答案 → 裸 JSON `null`（与 settings 同形 —— 值面只有 `String?`，`null` 不与任何
 * 合法值撞，不需要 datastore 那种 `{found,value}` 信封；空串是真值一律不冒充缺失）。
 *
 * **写侧无门禁**：`setText` 不预检任何探针（`ClipboardManager.setPrimaryClip` 后台可调，
 * 无门可禁）。参数口径只认"在场且是字符串" —— 空串合法原样存；缺参/非串/`null`
 * → `ERR_INVALID_PARAM`，且**一次写都不发**。
 *
 * SPI 抛的 `AutojsException` 原码透传（不折叠成 `ERR_INVALID_PARAM`）。
 * 未知方法 → `ERR_NOT_IMPLEMENTED`（`get`/`set`/`clear`/`hasText` 这类没约定过的别名不猜）。
 */
class ClipboardNamespaceHandler(
    private val clipboard: Clipboard,
) {
    suspend fun handle(request: BridgeRequestLite): ResponseLite = when (request.method) {
        "getText" -> getText(request)
        "setText" -> setText(request)
        else -> ResponseLite.err(
            request.id,
            ErrorCode.ERR_NOT_IMPLEMENTED,
            "未知 clipboard 方法: ${request.method}",
        )
    }

    /** 读剪贴板文本；null = 无内容（裸 JSON null，与 settings 读缺失同形）。 */
    private fun getText(request: BridgeRequestLite): ResponseLite = try {
        ResponseLite.Ok(request.id, A11yBridgeJson.encode(clipboard.getText()))
    } catch (e: AutojsException) {
        ResponseLite.err(request.id, e.error, e.message)
    }

    private fun setText(request: BridgeRequestLite): ResponseLite {
        val fields = try {
            request.decodeObject()
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        // 空串是合法内容 —— 缺参/非串/null 才是参数错，不拿 isBlank 卡 text。
        val text = fields["text"] as? A11yBridgeJson.Value.S
            ?: return ResponseLite.err(
                request.id,
                ErrorCode.ERR_INVALID_PARAM,
                "setText 缺 text 字段或 text 不是字符串",
            )
        return try {
            clipboard.setText(text.v)
            ResponseLite.Ok(request.id, "true")
        } catch (e: AutojsException) {
            ResponseLite.err(request.id, e.error, e.message)
        }
    }
}
