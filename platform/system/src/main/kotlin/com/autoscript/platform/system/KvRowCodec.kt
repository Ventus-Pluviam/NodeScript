package com.autoscript.platform.system

import com.autoscript.domain.storage.StoredEntry

/**
 * kv 行 ↔ [StoredEntry] 编解码（§9.6 serializer 适配的落地形状）。
 *
 * **为什么要有显式 `kind` 列**（`J`/`B`）而不靠 SQLite `typeof()` 猜：
 * 行是持久状态，「这一列当时存的是文本还是字节」必须是**写下的事实**，
 * 不是对存储引擎类型亲和行为的再解释 —— 引擎换亲和规则、行被外部工具改过，
 * `typeof` 的答案会变，kind 列不会。
 *
 * [decode] 的访问器是**按 kind 惰性**的：`J` 行绝不碰 blob 访问器、`B` 行绝不碰
 * 文本访问器（游标对错误类型的列取值会抛/给脏数据）。纯函数，JVM 可测；
 * 游标取值只在 [SqliteKvOps] 出现一次。
 */
object KvRowCodec {

    const val KIND_JSON = "J"
    const val KIND_BYTES = "B"

    fun kindOf(value: StoredEntry): String = when (value) {
        is StoredEntry.Json -> KIND_JSON
        is StoredEntry.Bytes -> KIND_BYTES
    }

    /**
     * 行 → 值。[text]/[blob] 只会被各自 kind 分支调用一次；
     * kind 与可空性对不上（表损坏/半截写）→ [IllegalStateException]，**不静默降级**。
     */
    fun decode(kind: String, text: () -> String?, blob: () -> ByteArray?): StoredEntry = when (kind) {
        KIND_JSON -> StoredEntry.Json(
            text() ?: throw IllegalStateException("kv 行 kind=$KIND_JSON 但 value 为 NULL（表损坏？）"),
        )
        KIND_BYTES -> StoredEntry.Bytes(
            blob() ?: throw IllegalStateException("kv 行 kind=$KIND_BYTES 但 value 为 NULL（表损坏？）"),
        )
        else -> throw IllegalStateException("kv 行 kind 非法: \"$kind\"（表损坏？）")
    }
}
