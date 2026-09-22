package com.autoscript.platform.capabilities

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.storage.SystemSettings

/**
 * `settings` 命名空间的桥处理器（§9.6；JS 对偶 `bridge/js/src/settings.ts`）。
 *
 * 与 [DatastoreNamespaceHandler] / [ZipNamespaceHandler] 同属 §9.6 存储面：**独立注入缝**
 * （`AppShell.assemble` 的 `settingsHandler`），不入 `SystemHandlers` 束 —— 五个共担门禁的
 * 命名空间是「接就五个一起接」的一束，存储面不与它们同组（§12.2 接线表）。单独成文件的
 * 理由同 datastore：混进 `SystemNamespaces.kt` 会搅浑那束语义。
 *
 * **方法表照抄 SPI 的两型**：`canWrite`/`getString`/`getInt`/`putString`/`putInt`。
 * 不提供猜型的 `get`/`put` —— `Settings.System` 的字符串与整型是两套系统 API
 * （`putString` 存原文、`putInt` 存数字），按 `typeof value` 推断等于在桥面发明一条
 * SPI 没有的策略（`"128"` 该存成串还是数？），调用方显式选。未约定过的别名如实
 * `ERR_NOT_IMPLEMENTED`，与 zip 的 `unzip` 同一条「不猜」纪律。
 *
 * **读侧缺失回裸 JSON `null`**（不是 datastore 那种 `{found,value}` 信封）：本契约的值面
 * 只有 `String?`/`Int?`，JSON 的 `null` 与 `"…"`/`123` 天然不撞；需要信封的是 datastore ——
 * 它的值面含显式 JSON `null`，存的 null 与键缺失在裸 JSON 里分不开。JS 侧
 * `getString`/`getInt` 因此回 `null` 而非 `undefined`：缺键是常态答案，不是「查不到」。
 * 空串/0 同样是真值（`""` 是合法设置值、0 是合法亮度），一律不拿来冒充缺失。
 *
 * **写前不预检 `canWrite`**：未授 `WRITE_SETTINGS` 抛 `ERR_PERMISSION_DENIED` 的判据
 * 唯一出处是 [SystemSettings] 实现（`:platform:system` 的 `AndroidSystemSettings`）——
 * handler 再判一遍就是两处判据，必然漂移。这里只做参数口径 + `AutojsException` 原码透传。
 *
 * 参数口径：缺 payload/缺 key/空白 key/`putString` 的 value 非字符串/`putInt` 的 value
 * 非整型或超 Int 范围 → `ERR_INVALID_PARAM`，且**不碰 SPI**（垃圾进不了 ContentResolver）。
 */
class SettingsNamespaceHandler(
    private val settings: SystemSettings,
) {
    suspend fun handle(request: BridgeRequestLite): ResponseLite = when (request.method) {
        "canWrite" -> canWrite(request)
        "getString" -> getString(request)
        "getInt" -> getInt(request)
        "putString" -> putString(request)
        "putInt" -> putInt(request)
        else -> ResponseLite.err(
            request.id,
            ErrorCode.ERR_NOT_IMPLEMENTED,
            "未知 settings 方法: ${request.method}",
        )
    }

    /** 授权探针：读设置不需要授权，写前的诚实提问（JS 可先问再写）。 */
    private fun canWrite(request: BridgeRequestLite): ResponseLite =
        ResponseLite.Ok(request.id, settings.canWrite().toString())

    private fun getString(request: BridgeRequestLite): ResponseLite {
        val key = try {
            readKey(request)
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        // 缺失 = 裸 null（见类 KDoc：值面无显式 null，不需要信封）
        return try {
            ResponseLite.Ok(request.id, A11yBridgeJson.encode(settings.getString(key)))
        } catch (e: AutojsException) {
            ResponseLite.err(request.id, e.error, e.message)
        }
    }

    private fun getInt(request: BridgeRequestLite): ResponseLite {
        val key = try {
            readKey(request)
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        return try {
            ResponseLite.Ok(request.id, A11yBridgeJson.encode(settings.getInt(key)))
        } catch (e: AutojsException) {
            ResponseLite.err(request.id, e.error, e.message)
        }
    }

    private fun putString(request: BridgeRequestLite): ResponseLite {
        val fields = try {
            request.decodeObject()
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val key = try {
            keyOf(request, fields)
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        // 空串是合法设置值 —— 缺参/非串才是参数错，不拿 isBlank 卡 value。
        val value = fields["value"] as? A11yBridgeJson.Value.S
            ?: return ResponseLite.err(
                request.id,
                ErrorCode.ERR_INVALID_PARAM,
                "putString 缺 value 字段或 value 不是字符串",
            )
        return try {
            settings.putString(key, value.v)
            ResponseLite.Ok(request.id, "true")
        } catch (e: AutojsException) {
            ResponseLite.err(request.id, e.error, e.message)
        }
    }

    private fun putInt(request: BridgeRequestLite): ResponseLite {
        val fields = try {
            request.decodeObject()
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val key = try {
            keyOf(request, fields)
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val n = fields["value"] as? A11yBridgeJson.Value.N
            ?: return ResponseLite.err(
                request.id,
                ErrorCode.ERR_INVALID_PARAM,
                "putInt 缺 value 字段或 value 不是数字",
            )
        val raw = n.raw.toLongOrNull()
            ?: return ResponseLite.err(
                request.id,
                ErrorCode.ERR_INVALID_PARAM,
                "putInt value 必须是整数: ${n.raw}",
            )
        if (raw < Int.MIN_VALUE || raw > Int.MAX_VALUE) {
            return ResponseLite.err(
                request.id,
                ErrorCode.ERR_INVALID_PARAM,
                "putInt value 超出 Int 范围: ${n.raw}",
            )
        }
        return try {
            settings.putInt(key, raw.toInt())
            ResponseLite.Ok(request.id, "true")
        } catch (e: AutojsException) {
            ResponseLite.err(request.id, e.error, e.message)
        }
    }

    /** 解 payload 取 key；缺 payload/缺 key/空白 key 抛 [IllegalArgumentException]（调用方折叠）。 */
    private fun readKey(request: BridgeRequestLite): String = keyOf(request, request.decodeObject())

    /** 取 `key` 字段并拒空白；缺/非串/空白抛 [IllegalArgumentException]。 */
    private fun keyOf(
        request: BridgeRequestLite,
        fields: Map<String, A11yBridgeJson.Value>,
    ): String {
        val key = request.requiredStr(fields, "key")
        if (key.isBlank()) throw IllegalArgumentException("settings key 不得为空白")
        return key
    }
}
