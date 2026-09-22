package com.autoscript.appservice.packager.axml

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/**
 * `resources.arsc` 的外科手术式改写：按资源 id 改某个**字符串资源**的值。
 *
 * 为什么需要它：模板的 `android:label` 常写成 `@string/app_name`（AXML 里是
 * REF 型属性、data=资源 id）。只改 AXML 里的字面串改不到它 —— 真正的显示名躺在
 * ARSC 的全局字符串池里，得按 id 走 `package → type → entry → Res_value` 找到那一条，
 * 再把值改到**池尾追加的新串**上（下标不变量见 [StringPool]）。
 *
 * 结构（aapt2 产出，已按真机包核对）：
 * ```
 * ResTable{ header(12) | 全局字符串池 | ResTable_package... | 其余顶层块... }
 * ResTable_package{ header(288) | type 池 | key 池 | TYPE_SPEC... | TYPE... }
 * ResTable_type{ header | config | u32 entryOffsets[] | entries[] }
 * entry = ResTable_entry{ size, flags, key } + Res_value{ size, res0, dataType, data }
 * ```
 *
 * **未知顶层块原样保留**：aapt2 会按 targetSdk 写 overlayable / staged-alias 等块，
 * 丢掉它们等于丢资源，所以本类只改自己认识的那一条，其余按原序搬运。
 *
 * **多配置全改**：同一 typeId 可能有多个 config（多语言）。打包换的是**应用身份**，
 * 留着模板的多语言显示名只会让新包在某些 locale 下露出旧名字，故该 id 的所有 config
 * 一并改写（代价是丢掉模板原有的本地化文案 —— 对被替换身份的应用而言这正是想要的）。
 */
class ArscPatcher private constructor(
    /** 顶层块（含全局池自身）按原序；改写只碰 package 里的 TYPE 条目与全局池追加。 */
    private val top: MutableList<TopPart>,
    private val globalPool: StringPool,
) {

    /**
     * 把资源 [resourceId] 的字符串值改为 [value]，返回改写到的 config 条目数。
     * 返回 0 = 资源不存在（调用方据此决定兜底策略）。
     */
    fun replaceStringResource(resourceId: Int, value: String): Int {
        val packageId = (resourceId ushr 24) and 0xff
        val typeId = (resourceId ushr 16) and 0xff
        val entryId = resourceId and 0xffff
        val newGlobalIndex = globalPool.add(value)

        var patched = 0
        for (part in top) {
            if (part !is TopPart.Package) continue
            val pkg = part.chunk
            if (pkg.id != packageId) continue
            for (inner in pkg.parts) {
                if (inner !is PackagePart.Raw) continue
                val chunk = inner.bytes
                if (chunk.size < 20 || u16(chunk, 0) != ResChunk.TABLE_TYPE) continue
                if (u8(chunk, 8) != typeId) continue
                if (patchTypeEntry(chunk, entryId, newGlobalIndex)) patched++
            }
        }
        return patched
    }

    /**
     * 读资源 [resourceId] 的字符串值（不存在 / 非字符串返回 null）。
     * 写后回读的对称面 —— 测试靠它证明"改写真的落到了全局池的那一条"。
     */
    fun readStringResource(resourceId: Int): String? {
        val packageId = (resourceId ushr 24) and 0xff
        val typeId = (resourceId ushr 16) and 0xff
        val entryId = resourceId and 0xffff
        for (part in top) {
            if (part !is TopPart.Package) continue
            val pkg = part.chunk
            if (pkg.id != packageId) continue
            for (inner in pkg.parts) {
                if (inner !is PackagePart.Raw) continue
                val chunk = inner.bytes
                if (chunk.size < 20 || u16(chunk, 0) != ResChunk.TABLE_TYPE) continue
                if (u8(chunk, 8) != typeId) continue
                val dataAt = stringValueDataOffset(chunk, entryId) ?: continue
                val index = u32(chunk, dataAt)
                if (index < 0 || index >= globalPool.size) return null
                return globalPool[index]
            }
        }
        return null
    }

    /** 重排为完整的资源表：外层 header + 顶层块按原序。 */
    fun toByteArray(): ByteArray {
        val encoded = top.map {
            when (it) {
                is TopPart.Pool -> it.pool.toBytes()
                is TopPart.Package -> it.chunk.serialize()
                is TopPart.Raw -> it.bytes
            }
        }
        val total = 12 + encoded.sumOf { it.size }
        val out = ByteArray(total)
        putU16(out, 0, ResChunk.TABLE)
        putU16(out, 2, 12)
        putU32(out, 4, total)
        putU32(out, 8, top.count { it is TopPart.Package })
        var pos = 12
        for (b in encoded) {
            System.arraycopy(b, 0, out, pos, b.size); pos += b.size
        }
        return out
    }

    /**
     * 定位 [entryId] 在该 TYPE 块里的 `Res_value.data` **字节偏移**；不存在/非字符串返回 null。
     * 读与写共用这一条定位路径 —— 两份逻辑各写一遍必然漂移，漂移了就是"改了别处"。
     */
    private fun stringValueDataOffset(chunk: ByteArray, entryId: Int): Int? {
        val headerSize = needU16(chunk, 2, "type headerSize")
        val entryCount = needU32(chunk, 12, "entryCount")
        val entriesStart = needU32(chunk, 16, "entriesStart")
        if (entryId >= entryCount) return null
        val entryOffset = needU32(chunk, headerSize + 4 * entryId, "entryOffset[$entryId]")
        if (entryOffset == ResChunk.NO_INDEX) return null // 该 config 下未定义此条目
        val entryAt = entriesStart + entryOffset
        needRange(chunk, entryAt, 8, "ResTable_entry")
        val flags = u16(chunk, entryAt + 2)
        if (flags and ResChunk.ENTRY_FLAG_COMPLEX != 0) {
            malformed("资源条目是 complex(map) 型，字符串读写不适用")
        }
        val valueAt = entryAt + 8
        needRange(chunk, valueAt, 8, "Res_value")
        if (u8(chunk, valueAt + 3) != ResChunk.TYPE_STRING) return null // 非字符串值：不碰
        return valueAt + 4
    }

    private fun patchTypeEntry(chunk: ByteArray, entryId: Int, newGlobalIndex: Int): Boolean {
        val dataAt = stringValueDataOffset(chunk, entryId) ?: return false
        putU32(chunk, dataAt, newGlobalIndex)
        return true
    }

    private sealed interface TopPart {
        class Pool(val pool: StringPool) : TopPart
        class Package(val chunk: PackageChunk) : TopPart
        class Raw(val bytes: ByteArray) : TopPart
    }

    private sealed interface PackagePart {
        class Pool(val pool: StringPool) : PackagePart
        class Raw(val bytes: ByteArray) : PackagePart
    }

    /** 一个 `ResTable_package`：原始 header + 按原序的池/裸块。 */
    private class PackageChunk(
        val id: Int,
        private val header: ByteArray,
        val parts: List<PackagePart>,
    ) {
        fun serialize(): ByteArray {
            val body = parts.map {
                when (it) {
                    is PackagePart.Pool -> it.pool.toBytes()
                    is PackagePart.Raw -> it.bytes
                }
            }
            val size = header.size + body.sumOf { it.size }
            val out = ByteArray(size)
            System.arraycopy(header, 0, out, 0, header.size)
            putU32(out, 4, size) // package 自身 size 跟着重排（header 里只有 size 可能变）
            var pos = header.size
            for (b in body) {
                System.arraycopy(b, 0, out, pos, b.size); pos += b.size
            }
            return out
        }
    }

    companion object {

        fun parse(bytes: ByteArray): ArscPatcher {
            val type = needU16(bytes, 0, "ARSC type")
            if (type != ResChunk.TABLE) {
                throw AutojsException(ErrorCode.ERR_IO, "resources.arsc 不是资源表（type=0x${type.toString(16)}）")
            }
            val headerSize = needU16(bytes, 2, "ARSC headerSize")
            val total = needU32(bytes, 4, "ARSC size")
            if (total != bytes.size) malformed("ARSC size=$total 与实际字节数 ${bytes.size} 不符")
            val packageCount = needU32(bytes, 8, "ARSC packageCount")

            val top = mutableListOf<TopPart>()
            var globalPool: StringPool? = null
            var pos = headerSize
            while (pos < bytes.size) {
                val chunkType = needU16(bytes, pos, "ARSC 子块 type")
                val chunkSize = needU32(bytes, pos + 4, "ARSC 子块 size")
                if (chunkSize < 8) malformed("ARSC 子块 size 过小：$chunkSize")
                needRange(bytes, pos, chunkSize, "ARSC 子块")
                when {
                    chunkType == ResChunk.STRING_POOL && globalPool == null -> {
                        val pool = StringPool.parse(bytes, pos)
                        globalPool = pool
                        top.add(TopPart.Pool(pool))
                    }
                    chunkType == ResChunk.TABLE_PACKAGE ->
                        top.add(TopPart.Package(parsePackage(bytes, pos, chunkSize)))
                    // 其余顶层块（overlayable / staged-alias / 第二个池…）：原样保留，不参与改写。
                    else -> top.add(TopPart.Raw(bytes.copyOfRange(pos, pos + chunkSize)))
                }
                pos += chunkSize
            }
            val pool = globalPool ?: malformed("ARSC 缺少全局字符串池")
            val actualPackages = top.count { it is TopPart.Package }
            if (actualPackages != packageCount) {
                malformed("ARSC packageCount=$packageCount 与实际解析 $actualPackages 不符")
            }
            return ArscPatcher(top, pool)
        }

        private fun parsePackage(bytes: ByteArray, start: Int, chunkSize: Int): PackageChunk {
            val headerSize = needU16(bytes, start + 2, "package headerSize")
            if (headerSize < 284) malformed("package headerSize 过小：$headerSize")
            needRange(bytes, start, headerSize, "package header")
            val id = needU32(bytes, start + 8, "package id")
            val header = bytes.copyOfRange(start, start + headerSize)

            val parts = mutableListOf<PackagePart>()
            var pos = start + headerSize
            val end = start + chunkSize
            while (pos < end) {
                val size = needU32(bytes, pos + 4, "package 子块 size")
                if (size < 8 || pos + size > end) malformed("package 子块越界：offset=$pos size=$size")
                val t = needU16(bytes, pos, "package 子块 type")
                if (t == ResChunk.STRING_POOL) {
                    parts.add(PackagePart.Pool(StringPool.parse(bytes, pos)))
                } else {
                    parts.add(PackagePart.Raw(bytes.copyOfRange(pos, pos + size)))
                }
                pos += size
            }
            return PackageChunk(id, header, parts)
        }
    }
}
