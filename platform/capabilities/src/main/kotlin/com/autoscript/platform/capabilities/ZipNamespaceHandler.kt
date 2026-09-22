package com.autoscript.platform.capabilities

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.storage.ZipArchiver
import java.nio.file.Path

/**
 * `zip` 命名空间的桥处理器（§9.6；JS 对偶 `bridge/js/src/zip.ts`）。
 *
 * 与 [DatastoreNamespaceHandler] 同属存储面：**独立注入缝**（`AppShell.assemble`
 * 的 `zipHandler`），不入 `SystemHandlers` 束 —— 归档无需能力门禁，且与五个
 * 共担门禁的命名空间不是一组（§12.2 接线表）。单独成文件的理由同 datastore：
 * 混进 `SystemNamespaces.kt` 会搅浑「接就五个一起接」的束语义。
 *
 * 本层只做参数口径 + 错误透传，**归档语义（zip-slip 防线、原子落位）全在
 * [ZipArchiver] 实现里**（`:platform:system` 的 `JdkZipArchiver`）—— handler
 * 不碰归档字节，也就不可能绕开那条契约级安全底线。
 *
 * 错误口径：SPI 抛的 `AutojsException` 原码透传（`ERR_FILE_NOT_FOUND` / `ERR_IO` /
 * `ERR_INVALID_PARAM` 不折叠）；缺参/空白路径 → `ERR_INVALID_PARAM`；未知方法 →
 * `ERR_NOT_IMPLEMENTED`（含 `unzip` 这种没约定过的别名 —— 不猜，facade 只发 `extract`）。
 */
class ZipNamespaceHandler(
    private val archiver: ZipArchiver,
) {
    suspend fun handle(request: BridgeRequestLite): ResponseLite = when (request.method) {
        "compress" -> compress(request)
        "extract" -> extract(request)
        else -> ResponseLite.err(
            request.id,
            ErrorCode.ERR_NOT_IMPLEMENTED,
            "未知 zip 方法: ${request.method}",
        )
    }

    private suspend fun compress(request: BridgeRequestLite): ResponseLite {
        val fields = try {
            request.decodeObject()
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val (source, archive) = try {
            pathOf(request, fields, "source") to pathOf(request, fields, "archive")
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        return try {
            archiver.compress(source, archive)
            ResponseLite.Ok(request.id, "true")
        } catch (e: AutojsException) {
            ResponseLite.err(request.id, e.error, e.message)
        }
    }

    private suspend fun extract(request: BridgeRequestLite): ResponseLite {
        val fields = try {
            request.decodeObject()
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val (archive, targetDir) = try {
            pathOf(request, fields, "archive") to pathOf(request, fields, "targetDir")
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        return try {
            archiver.extract(archive, targetDir)
            ResponseLite.Ok(request.id, "true")
        } catch (e: AutojsException) {
            ResponseLite.err(request.id, e.error, e.message)
        }
    }

    /** 取非空白路径字段；非法（缺/非字符串/空白/NUL 字符）抛 IllegalArgumentException。 */
    private fun pathOf(
        request: BridgeRequestLite,
        fields: Map<String, A11yBridgeJson.Value>,
        key: String,
    ): Path {
        val s = request.requiredStr(fields, key)
        if (s.isBlank()) throw IllegalArgumentException("$key 不得为空白")
        // Path.of 对 NUL 等非法字符抛 InvalidPathException（IllegalArgumentException 子类）→ 调用方折叠
        return Path.of(s)
    }
}
