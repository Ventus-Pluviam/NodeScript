package com.autoscript.domain.bridge

import com.autoscript.domain.json.DomainJson

/**
 * 桥载荷解码 helpers（从 `:platform:capabilities` 上移）：
 * 每个方法在非法输入上抛 [IllegalArgumentException] —— [RpcNamespaceHandler] 统一折叠为
 * `ERR_INVALID_PARAM`（§7 诚实上报，不伪造成功），处理器里不再逐段 try/catch。
 *
 * 与 `DomainJson` 同居 `:domain`：返回的 [DomainJson.Value] 必须对全部模块可见。
 */
fun BridgeRequest.decodeObject(): Map<String, DomainJson.Value> {
    if (payload == null) throw IllegalArgumentException("$method 缺 payload")
    return DomainJson.decodeObject(payload)
}

fun BridgeRequest.requiredStr(
    o: Map<String, DomainJson.Value>,
    key: String,
): String = (o[key] as? DomainJson.Value.S)?.v ?: throw IllegalArgumentException("缺字符串字段 $key")

fun BridgeRequest.optStr(
    o: Map<String, DomainJson.Value>,
    key: String,
): String? = when (val v = o[key]) {
    null, is DomainJson.Value.Null -> null
    is DomainJson.Value.S -> v.v
    else -> throw IllegalArgumentException("字段 $key 必须是字符串")
}

fun BridgeRequest.requiredLong(
    o: Map<String, DomainJson.Value>,
    key: String,
): Long = when (val v = o[key]) {
    null -> throw IllegalArgumentException("缺数字字段 $key")
    is DomainJson.Value.Null -> throw IllegalArgumentException("字段 $key 必须是数字")
    is DomainJson.Value.N -> v.raw.toLongOrNull() ?: throw IllegalArgumentException("字段 $key 数字越界")
    else -> throw IllegalArgumentException("字段 $key 必须是数字")
}

fun BridgeRequest.optLong(
    o: Map<String, DomainJson.Value>,
    key: String,
): Long? = when (val v = o[key]) {
    null, is DomainJson.Value.Null -> null
    is DomainJson.Value.N -> v.raw.toLongOrNull() ?: throw IllegalArgumentException("字段 $key 数字越界")
    else -> throw IllegalArgumentException("字段 $key 必须是数字")
}

/**
 * 必填整数（`Int` 域）：**越界即拒，不截断**。
 *
 * 为什么要有这个 helper 而不是 `requiredLong(...).toInt()`：后者在 Kotlin 里是**静默
 * 低 32 位截断**，`4294967297` 变成 `1`、`4294967296` 变成 `0`。一条 `batch=4294967297`
 * 的请求于是被当成 `batch=1` 执行 —— 调用方拿到的回执与它给的参数毫无关系，而且这条
 * 错**永远不会报出来**（截断后的值多半还是合法的正数）。
 *
 * 判据本身在 [DomainJson.reqInt]（同一份实现，不在这里抄第二遍）：`SettingsNamespaceHandler.putInt`
 * 与 `NotificationNamespaceHandler.idOf` 那两处手写先例的口径（`raw < Int.MIN_VALUE ||
 * raw > Int.MAX_VALUE` → `ERR_INVALID_PARAM`）现在有了可复用的落点。非整数（`1.5`）
 * 与 [requiredLong] 同一条折叠路（`ERR_INVALID_PARAM`）。
 */
fun BridgeRequest.requiredInt(
    o: Map<String, DomainJson.Value>,
    key: String,
): Int = DomainJson.reqInt(o, key)

/** 可选整数（`Int` 域），口径同 [requiredInt]；缺键/`null` → null。 */
fun BridgeRequest.optInt(
    o: Map<String, DomainJson.Value>,
    key: String,
): Int? = DomainJson.optInt(o, key)

fun BridgeRequest.requiredStrList(
    o: Map<String, DomainJson.Value>,
    key: String,
): List<String> {
    val v = o[key] ?: throw IllegalArgumentException("缺 $key 字段")
    if (v !is DomainJson.Value.Arr) throw IllegalArgumentException("$key 必须是数组")
    return v.items.map { (it as? DomainJson.Value.S)?.v ?: throw IllegalArgumentException("$key 必须是字符串数组") }
}

/** 必填数字（JSON 数字原文 → Double；缺键/非数字 → IllegalArgumentException）。
 * 置信度/阈值这类非整数量走它（optLong 只认整数，会悄悄把 `0.9` 挡成参数错）。 */
fun BridgeRequest.requiredDouble(
    o: Map<String, DomainJson.Value>,
    key: String,
): Double {
    val v = o[key] ?: throw IllegalArgumentException("缺数字字段 $key")
    return rawDouble(v, key)
}

/** 数字原文 → Double（拒绝 NaN/Infinity：wire 上送不着，实现侧也不该拿到）。 */
private fun rawDouble(v: DomainJson.Value, key: String): Double {
    if (v !is DomainJson.Value.N) throw IllegalArgumentException("字段 $key 必须是数字")
    val d = v.raw.toDoubleOrNull() ?: throw IllegalArgumentException("字段 $key 不是数字: ${v.raw}")
    if (!d.isFinite()) throw IllegalArgumentException("字段 $key 必须是有限数字")
    return d
}

fun BridgeRequest.requiredRef(o: Map<String, DomainJson.Value>): HandleRef =
    requiredRef(o, "ref")

/** 句柄字段：键可配（一处请求带两个句柄时 —— `images.matchTemplate` 的 haystack/needle）。 */
fun BridgeRequest.requiredRef(
    o: Map<String, DomainJson.Value>,
    key: String,
): HandleRef {
    val v = o[key] ?: throw IllegalArgumentException("缺 $key 字段")
    if (v !is DomainJson.Value.Obj) throw IllegalArgumentException("$key 必须是对象")
    val refId = (v.fields["refId"] as? DomainJson.Value.N)?.raw?.toLongOrNull()
        ?: throw IllegalArgumentException("缺数字 $key.refId")
    val gen = (v.fields["generation"] as? DomainJson.Value.N)?.raw?.toLongOrNull()
        ?: throw IllegalArgumentException("缺数字 $key.generation")
    return HandleRef(refId, gen)
}

/** 必填整数数组（JSON 数字数组 → List<Int>；缺键/非数组/非整数元素即抛）。
 * `images findColor` 的 color/region 走它：分量是原生侧的域（0..255），非整数
 * 由本层折 `ERR_INVALID_PARAM`，不把 `1.5` 这种值悄悄截给 native。 */
fun BridgeRequest.requiredIntList(
    o: Map<String, DomainJson.Value>,
    key: String,
): List<Int> {
    val v = o[key] ?: throw IllegalArgumentException("缺 $key 字段")
    if (v !is DomainJson.Value.Arr) throw IllegalArgumentException("$key 必须是数组")
    return v.items.map { item ->
        val n = item as? DomainJson.Value.N
            ?: throw IllegalArgumentException("$key 必须是数字数组")
        val i = n.raw.toIntOrNull() ?: throw IllegalArgumentException("$key 元素不是整数: ${n.raw}")
        i
    }
}

/** 可选整数数组：缺键/JSON `null` → null；在场即按 [requiredIntList] 同一口径解析。 */
fun BridgeRequest.optIntList(
    o: Map<String, DomainJson.Value>,
    key: String,
): List<Int>? = when (val v = o[key]) {
    null, is DomainJson.Value.Null -> null
    else -> requiredIntList(o, key)
}

/** 枚举字段：缺省/`null` 走 [fallback]；未知字面量拒绝（拼错即报错，不静默套默认）。 */
fun <T> BridgeRequest.enumOrNull(
    o: Map<String, DomainJson.Value>,
    key: String,
    fallback: T,
    parse: (String) -> T,
): T = when (val v = o[key]) {
    null, is DomainJson.Value.Null -> fallback
    is DomainJson.Value.S -> try {
        parse(v.v)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("未知 $key 值: ${v.v}")
    }
    else -> throw IllegalArgumentException("字段 $key 必须是字符串")
}
