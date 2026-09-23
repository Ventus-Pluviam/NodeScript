package com.autoscript.appservice.packager.axml

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/** manifest 属性读出值的分型（`package`/`versionName` 是字面串，`label` 可能是资源引用）。 */
sealed interface ManifestAttrValue {

    /** 字面串值（`rawValue` 与 `data` 同指字符串池下标）。 */
    data class Text(val value: String) : ManifestAttrValue

    /** 资源引用（`android:label="@string/app_name"` 这类；data 即资源 id）。 */
    data class ResourceRef(val resourceId: Int) : ManifestAttrValue

    /** 整型值（`versionCode` 等；data 即数值本身）。 */
    data class Number(val value: Int) : ManifestAttrValue

    /** 其余类型（bool/float/未识别）：原样带出，调用方自行判断。 */
    data class Other(val dataType: Int, val data: Int) : ManifestAttrValue
}

/**
 * 二进制 `AndroidManifest.xml`（AXML）的外科手术式改写。
 *
 * 改写只碰**目标属性那一小段**（+ 池尾追加新串），其余字节原样搬运：
 * 1. 解析出字符串池对象 + 各节点原始块（节点按副本持有，可原地改）；
 * 2. [setStringAttr]/[setIntAttr] 定位 START_ELEMENT → 属性 → 改 `rawValue`/`dataType`/`data`；
 * 3. [toBytes] 重排外层 size + 池 + 资源映射 + 节点。
 *
 * 为什么不用 aapt2：模板已编译好，重跑 aapt2 link 需要完整资源树与 framework，
 * 而我们要改的只是包名/版本/显示名与组件类名这类小字段 —— 字节级改写既免依赖也免重编译，
 * 且改完仍能被 aapt2/apksigner 当合法 AXML 读（见测试的往返断言）。
 */
class AxmlPatcher private constructor(
    private val pool: StringPool,
    private val resourceMap: ByteArray?,
    private val nodes: MutableList<ByteArray>,
) {

    /** 重排为完整的二进制 XML。未追加过新串时，池字节与输入逐字节一致。 */
    fun toByteArray(): ByteArray {
        val poolBytes = pool.toBytes()
        val total = 8 + poolBytes.size + (resourceMap?.size ?: 0) + nodes.sumOf { it.size }
        val out = ByteArray(total)
        putU16(out, 0, ResChunk.XML)
        putU16(out, 2, 8)
        putU32(out, 4, total)
        var pos = 8
        System.arraycopy(poolBytes, 0, out, pos, poolBytes.size); pos += poolBytes.size
        if (resourceMap != null) {
            System.arraycopy(resourceMap, 0, out, pos, resourceMap.size); pos += resourceMap.size
        }
        for (n in nodes) {
            System.arraycopy(n, 0, out, pos, n.size); pos += n.size
        }
        return out
    }

    /** 设字面串属性（必要时把 REF 型就地降级为字面串 —— ARSC 缺资源时的兜底路径）。 */
    fun setStringAttr(element: String, attr: String, value: String) {
        val (node, attrOff) = locate(element, attr)
        writeStringAttr(node, attrOff, value)
    }

    /** 设整型属性（`versionCode`）：rawValue 置无下标，data 存数值。 */
    fun setIntAttr(element: String, attr: String, value: Int) {
        val (node, attrOff) = locate(element, attr)
        putU32(node, attrOff + 8, ResChunk.NO_INDEX)
        node[attrOff + 15] = ResChunk.TYPE_INT_DEC.toByte()
        putU32(node, attrOff + 16, value)
    }

    /** 读属性当前值（找不到返回 null —— 调用方据此决定是改写还是报缺字段）。 */
    fun readAttr(element: String, attr: String): ManifestAttrValue? {
        val node = findStartElement(element) ?: return null
        val attrOff = findAttr(node, attr) ?: return null
        val dataType = u8(node, attrOff + 15)
        val data = u32(node, attrOff + 16)
        val rawIndex = u32(node, attrOff + 8)
        return when (dataType) {
            ResChunk.TYPE_STRING -> {
                // 字面串的 rawValue 与 data 通常同指；以 data 为准，data 缺位时回落 rawValue。
                val index = if (data >= 0 && data < pool.size) data else rawIndex
                if (index < 0 || index >= pool.size) ManifestAttrValue.Other(dataType, data)
                else ManifestAttrValue.Text(pool[index])
            }
            ResChunk.TYPE_REFERENCE -> ManifestAttrValue.ResourceRef(data)
            ResChunk.TYPE_INT_DEC, ResChunk.TYPE_INT_HEX -> ManifestAttrValue.Number(data)
            else -> ManifestAttrValue.Other(dataType, data)
        }
    }

    /**
     * 读同名元素上 [attr] 的**全部**字面串值（按文档序）。[readAttr] 只取首个节点 ——
     * `<activity>` 有多个时不够用，组件名改写后的回读/诊断走这里。
     */
    fun readStringAttrs(element: String, attr: String): List<String> {
        val out = mutableListOf<String>()
        forEachStartElement(setOf(element)) { node ->
            val attrOff = findAttr(node, attr) ?: return@forEachStartElement
            stringAttrAt(node, attrOff)?.let { out += it }
        }
        return out
    }

    /**
     * 遍历 [elements] 每类 START_ELEMENT，把其上 [attr] 的字面串值经 [transform] 就地改写。
     * 组件类名按旧包绝对化走这条（[com.autoscript.appservice.packager.IdentityTemplatePatch]）
     * —— 同类型有多个节点，[setStringAttr] 的"首配"语义不够用。缺 [attr] 或非字面串的
     * 节点跳过（`targetActivity` 只在 activity-alias 上有；`name` 是 aapt2 必填，缺 = 模板
     * 非 aapt2 产物，组件找不到由运行时如实报，不在字节层替它猜）。[transform] 返回原串时
     * 不动节点字节、不追加池串。
     */
    fun transformStringAttrs(elements: Set<String>, attr: String, transform: (String) -> String) {
        forEachStartElement(elements) { node ->
            val attrOff = findAttr(node, attr) ?: return@forEachStartElement
            val current = stringAttrAt(node, attrOff) ?: return@forEachStartElement
            val next = transform(current)
            if (next != current) writeStringAttr(node, attrOff, next)
        }
    }

    /**
     * **测试专用**：把 [element] 上名为 [from] 的属性改名为 [to]。
     *
     * 生产路径没有"改属性名"这个需求（该改的是值），但"模板缺 label"这一支需要一个
     * 可复现的构造手段 —— 直接删属性会让 [findAttr] 的定位逻辑没得测，改名则让
     * `readAttr(label)` 如实返回 null。故以 `ForTest` 后缀显式标出非生产面。
     */
    fun renameAttrForTest(element: String, from: String, to: String) {
        val (node, attrOff) = locate(element, from)
        val index = pool.add(to)
        putU32(node, attrOff + 4, index)
    }

    /** 属性不存在时抛出明确错误（用于"模板缺 versionCode 就没法打"这类早失败）。 */
    fun requireAttr(element: String, attr: String): ManifestAttrValue =
        readAttr(element, attr)
            ?: throw AutojsException(
                ErrorCode.ERR_INVALID_PARAM,
                "模板 manifest 缺少 $element 的 $attr 属性，无法按身份改写",
            )

    private fun locate(element: String, attr: String): Pair<ByteArray, Int> {
        val node = findStartElement(element)
            ?: throw AutojsException(ErrorCode.ERR_INVALID_PARAM, "模板 manifest 缺少 <$element> 元素")
        val attrOff = findAttr(node, attr)
            ?: throw AutojsException(ErrorCode.ERR_INVALID_PARAM, "<$element> 缺少 $attr 属性")
        return node to attrOff
    }

    private fun findStartElement(name: String): ByteArray? {
        for (n in nodes) {
            if (needU16(n, 0, "节点 type") != ResChunk.XML_START_ELEMENT) continue
            val nameIndex = u32(n, 20)
            if (nameIndex >= 0 && nameIndex < pool.size && pool[nameIndex] == name) return n
        }
        return null
    }

    private fun findAttr(node: ByteArray, name: String): Int? {
        val attrStart = needU16(node, 24, "attributeStart")
        val attrSize = needU16(node, 26, "attributeSize")
        val attrCount = needU16(node, 28, "attributeCount")
        if (attrSize < 20) malformed("属性项过小：$attrSize")
        for (i in 0 until attrCount) {
            val off = 16 + attrStart + i * attrSize
            needRange(node, off, attrSize, "属性[$i]")
            val nameIndex = u32(node, off + 4)
            if (nameIndex >= 0 && nameIndex < pool.size && pool[nameIndex] == name) return off
        }
        return null
    }

    /** 按元素类型名遍历 START_ELEMENT（节点保持文档序；[block] 内可 return@ 跳过本节点）。 */
    private fun forEachStartElement(names: Set<String>, block: (ByteArray) -> Unit) {
        for (n in nodes) {
            if (needU16(n, 0, "节点 type") != ResChunk.XML_START_ELEMENT) continue
            val nameIndex = u32(n, 20)
            if (nameIndex < 0 || nameIndex >= pool.size) continue
            if (pool[nameIndex] !in names) continue
            block(n)
        }
    }

    /** 属性的字面串值（下标口径与 [readAttr] 字符串分支一致：data 优先、回落 rawValue）。 */
    private fun stringAttrAt(node: ByteArray, attrOff: Int): String? {
        if (u8(node, attrOff + 15) != ResChunk.TYPE_STRING) return null
        val data = u32(node, attrOff + 16)
        val rawIndex = u32(node, attrOff + 8)
        val index = if (data >= 0 && data < pool.size) data else rawIndex
        if (index < 0 || index >= pool.size) return null
        return pool[index]
    }

    /** 就地写字面串属性（rawValue/data 同指新串；池按需追加、已存在则复用下标）。 */
    private fun writeStringAttr(node: ByteArray, attrOff: Int, value: String) {
        val index = pool.add(value)
        putU32(node, attrOff + 8, index)
        node[attrOff + 15] = ResChunk.TYPE_STRING.toByte()
        putU32(node, attrOff + 16, index)
    }

    companion object {

        fun parse(bytes: ByteArray): AxmlPatcher {
            val type = needU16(bytes, 0, "AXML type")
            if (type != ResChunk.XML) {
                throw AutojsException(ErrorCode.ERR_IO, "AndroidManifest.xml 不是二进制 XML（type=0x${type.toString(16)}）")
            }
            val headerSize = needU16(bytes, 2, "AXML headerSize")
            val total = needU32(bytes, 4, "AXML size")
            if (total != bytes.size) malformed("AXML size=$total 与实际字节数 ${bytes.size} 不符")

            var pool: StringPool? = null
            var resourceMap: ByteArray? = null
            val nodes = mutableListOf<ByteArray>()
            var pos = headerSize
            while (pos < bytes.size) {
                val chunkType = needU16(bytes, pos, "AXML 子块 type")
                val chunkSize = needU32(bytes, pos + 4, "AXML 子块 size")
                if (chunkSize < 8) malformed("AXML 子块 size 过小：$chunkSize")
                needRange(bytes, pos, chunkSize, "AXML 子块")
                when (chunkType) {
                    ResChunk.STRING_POOL -> {
                        if (pool != null) malformed("AXML 出现多个字符串池")
                        pool = StringPool.parse(bytes, pos)
                    }
                    ResChunk.XML_RESOURCE_MAP -> resourceMap = bytes.copyOfRange(pos, pos + chunkSize)
                    else -> nodes.add(bytes.copyOfRange(pos, pos + chunkSize))
                }
                pos += chunkSize
            }
            val p = pool ?: malformed("AXML 缺少字符串池")
            return AxmlPatcher(p, resourceMap, nodes)
        }
    }
}
