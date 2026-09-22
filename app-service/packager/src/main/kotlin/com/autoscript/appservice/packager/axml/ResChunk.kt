package com.autoscript.appservice.packager.axml

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.io.ByteArrayOutputStream

/**
 * 二进制资源块（AXML / ARSC）共用的 chunk 类型与标志（AOSP `ResourceTypes.h` 的子集）。
 *
 * 本包**零 Android 依赖**：只读写字节，不做资源编译 —— 所以能在 packager（纯 JVM）
 * 里闭环单测，也能在设备端跑同一份代码。
 */
internal object ResChunk {

    const val STRING_POOL = 0x0001
    const val TABLE = 0x0002
    const val XML = 0x0003
    const val XML_START_ELEMENT = 0x0102
    const val XML_RESOURCE_MAP = 0x0180
    const val TABLE_PACKAGE = 0x0200
    const val TABLE_TYPE = 0x0201
    const val TABLE_TYPE_SPEC = 0x0202

    const val UTF8_FLAG = 0x100
    const val SORTED_FLAG = 0x1

    /** XML 属性 / 节点里的"没有下标"（原字节 0xFFFFFFFF，按 Int 即 -1）。 */
    const val NO_INDEX = -1

    const val TYPE_REFERENCE = 0x01
    const val TYPE_STRING = 0x03
    const val TYPE_INT_DEC = 0x10
    const val TYPE_INT_HEX = 0x11
    const val TYPE_INT_BOOL = 0x12

    /** `ResTable_entry.flags`：置位表示后随的是 ResTable_map 而非单个 Res_value。 */
    const val ENTRY_FLAG_COMPLEX = 0x0001
}

/** 结构损坏 / 截断的统一出口：**不**静默跳过坏块（那会产出装不上的包）。 */
internal fun malformed(detail: String): Nothing =
    throw AutojsException(ErrorCode.ERR_IO, detail)

internal fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xff

internal fun u16(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

internal fun u32(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xff) or
        ((b[off + 1].toInt() and 0xff) shl 8) or
        ((b[off + 2].toInt() and 0xff) shl 16) or
        ((b[off + 3].toInt() and 0xff) shl 24)

/** 需要越界保护的读取：偏移落在块外即报损坏，而不是抛下标异常冒充"格式问题"。 */
internal fun needRange(b: ByteArray, off: Int, len: Int, what: String) {
    if (off < 0 || len < 0 || off > b.size - len) {
        malformed("$what 越界：offset=$off len=$len size=${b.size}")
    }
}

internal fun needU16(b: ByteArray, off: Int, what: String): Int {
    needRange(b, off, 2, what); return u16(b, off)
}

internal fun needU32(b: ByteArray, off: Int, what: String): Int {
    needRange(b, off, 4, what); return u32(b, off)
}

internal fun putU32(b: ByteArray, off: Int, value: Int) {
    b[off] = (value and 0xff).toByte()
    b[off + 1] = ((value ushr 8) and 0xff).toByte()
    b[off + 2] = ((value ushr 16) and 0xff).toByte()
    b[off + 3] = ((value ushr 24) and 0xff).toByte()
}

internal fun putU16(b: ByteArray, off: Int, value: Int) {
    b[off] = (value and 0xff).toByte()
    b[off + 1] = ((value ushr 8) and 0xff).toByte()
}

/**
 * 二进制字符串池（`ResStringPool`）：解析 → 追加 → 重序列化。
 *
 * **关键不变量：新串只追加在池尾，已有串的下标一律不动。** 于是引用池的 AXML 属性
 * 与 ARSC 条目**一个都不必改下标**，每处只改自己那一小段 —— 这是本实现能做"外科手术式"
 * 改写而不必重建整棵树的根因。附带好处：追加不破坏已有串的字节，diff 面最小。
 *
 * 支持 UTF-8 / UTF-16 两种池编码（aapt2 两种都会产：ARSC 全局池常见 UTF-8，
 * AXML 属性池常见 UTF-16）。**带样式（styleCount > 0）的池拒绝解析** —— 样式偏移
 * 相对池起点，追加会整体位移，硬改会产出静默错位的坏块；manifest 与 ARSC 全局池
 * 实测均无样式，遇到即响亮报错好过悄悄写坏。
 */
internal class StringPool private constructor(
    private val strings: MutableList<String>,
    private val flags: Int,
    private val original: ByteArray,
    /** 该池在源字节里占的 chunk 长度（解析后跳到下一块用）。 */
    val bytesLength: Int,
) {
    private var dirty = false

    val size: Int get() = strings.size

    operator fun get(index: Int): String {
        if (index < 0 || index >= strings.size) malformed("字符串下标越界：$index（size=${strings.size}）")
        return strings[index]
    }

    fun indexOf(value: String): Int = strings.indexOf(value)

    /** 追加一个串并返回下标；已存在则复用原下标（不产重复串）。 */
    fun add(value: String): Int {
        val existing = strings.indexOf(value)
        if (existing >= 0) return existing
        strings.add(value)
        dirty = true
        return strings.size - 1
    }

    /** 未改动则原样回吐源字节：避免与 aapt2 产物出现无谓差异（也就不必赌我的重排与它逐字节一致）。 */
    fun toBytes(): ByteArray = if (!dirty) original else serialize()

    private fun serialize(): ByteArray {
        val utf8 = flags and ResChunk.UTF8_FLAG != 0
        val encoded = strings.map { if (utf8) encodeUtf8(it) else encodeUtf16(it) }
        val headerSize = 28
        val offsetsSize = 4 * strings.size
        val stringsStart = headerSize + offsetsSize
        val stringBytes = encoded.sumOf { it.size }
        val total = (stringsStart + stringBytes + 3) and -4

        val out = ByteArray(total)
        putU16(out, 0, ResChunk.STRING_POOL)
        putU16(out, 2, headerSize)
        putU32(out, 4, total)
        putU32(out, 8, strings.size)
        putU32(out, 12, 0) // styleCount：本实现只在 styleCount==0 时走到这里
        // 追加过的池已不再保证有序：清掉 SORTED 位，读取方退化为线性扫描（正确性不受影响）。
        putU32(out, 16, flags and ResChunk.SORTED_FLAG.inv())
        putU32(out, 20, stringsStart)
        putU32(out, 24, 0) // stylesStart：无样式
        var pos = headerSize
        var dataPos = stringsStart
        for (e in encoded) {
            putU32(out, pos, dataPos - stringsStart)
            pos += 4
            System.arraycopy(e, 0, out, dataPos, e.size)
            dataPos += e.size
        }
        return out
    }

    companion object {

        fun parse(bytes: ByteArray, offset: Int): StringPool {
            val type = needU16(bytes, offset, "字符串池 type")
            if (type != ResChunk.STRING_POOL) malformed("期望字符串池块(0x0001)，实际 0x${type.toString(16)}")
            val headerSize = needU16(bytes, offset + 2, "字符串池 headerSize")
            val chunkSize = needU32(bytes, offset + 4, "字符串池 size")
            if (headerSize < 28) malformed("字符串池 headerSize 过小：$headerSize")
            if (chunkSize < headerSize) malformed("字符串池 size($chunkSize) 小于 headerSize($headerSize)")
            needRange(bytes, offset, chunkSize, "字符串池块")

            val stringCount = needU32(bytes, offset + 8, "字符串池 stringCount")
            val styleCount = needU32(bytes, offset + 12, "字符串池 styleCount")
            val flags = needU32(bytes, offset + 16, "字符串池 flags")
            val stringsStart = needU32(bytes, offset + 20, "字符串池 stringsStart")
            val stylesStart = needU32(bytes, offset + 24, "字符串池 stylesStart")
            if (styleCount != 0) {
                malformed("字符串池带样式（styleCount=$styleCount），追加新串会位移样式偏移，拒绝改写")
            }
            if (stringsStart < headerSize + 4L * stringCount) {
                malformed("字符串池 stringsStart=$stringsStart 与 stringCount=$stringCount 不自洽")
            }
            val utf8 = flags and ResChunk.UTF8_FLAG != 0

            val strings = ArrayList<String>(stringCount)
            for (i in 0 until stringCount) {
                val rel = needU32(bytes, offset + headerSize + 4 * i, "字符串偏移[$i]")
                val start = offset + stringsStart + rel
                if (start >= offset + chunkSize) malformed("字符串[$i] 起点越界：$start")
                strings.add(if (utf8) readUtf8(bytes, start, offset + chunkSize) else readUtf16(bytes, start, offset + chunkSize))
            }
            // styleCount==0 时 aapt2 约定 stylesStart=0；非 0 却没有样式属结构不自洽。
            if (stylesStart != 0) malformed("styleCount=0 但 stylesStart=$stylesStart，字符串池结构不自洽")
            return StringPool(strings, flags, bytes.copyOfRange(offset, offset + chunkSize), chunkSize)
        }

        private fun readLen8(b: ByteArray, pos: Int, end: Int): Pair<Int, Int> {
            if (pos >= end) malformed("UTF-8 串长度字段越界")
            val first = b[pos].toInt() and 0xff
            return if (first and 0x80 != 0) {
                if (pos + 1 >= end) malformed("UTF-8 串长度字段越界（双字节）")
                (((first and 0x7f) shl 8) or (b[pos + 1].toInt() and 0xff)) to pos + 2
            } else {
                first to pos + 1
            }
        }

        private fun readUtf8(b: ByteArray, start: Int, end: Int): String {
            // 第一个长度 = UTF-16 码元数（诊断用），第二个 = UTF-8 字节数（真正界定串的）。
            val (_, afterChars) = readLen8(b, start, end)
            val (byteLen, dataPos) = readLen8(b, afterChars, end)
            if (byteLen < 0 || dataPos > end - byteLen) malformed("UTF-8 串数据越界：len=$byteLen")
            return String(b, dataPos, byteLen, Charsets.UTF_8)
        }

        private fun readLen16(b: ByteArray, pos: Int, end: Int): Pair<Int, Int> {
            if (pos + 2 > end) malformed("UTF-16 串长度字段越界")
            val first = u16(b, pos)
            return if (first and 0x8000 != 0) {
                if (pos + 4 > end) malformed("UTF-16 串长度字段越界（双字节）")
                val second = u16(b, pos + 2)
                (((first and 0x7fff) shl 16) or second) to pos + 4
            } else {
                first to pos + 2
            }
        }

        private fun readUtf16(b: ByteArray, start: Int, end: Int): String {
            val (charCount, dataPos) = readLen16(b, start, end)
            val byteLen = charCount * 2
            if (dataPos > end - byteLen - 2) malformed("UTF-16 串数据越界：chars=$charCount")
            return String(b, dataPos, byteLen, Charsets.UTF_16LE)
        }

        private fun encodeUtf8(s: String): ByteArray {
            val data = s.toByteArray(Charsets.UTF_8)
            val out = ByteArrayOutputStream(data.size + 4)
            writeLen8(out, s.length)
            writeLen8(out, data.size)
            out.write(data)
            out.write(0)
            return out.toByteArray()
        }

        private fun writeLen8(out: ByteArrayOutputStream, len: Int) {
            if (len > 0x7fff) malformed("串过长，UTF-8 池长度字段装不下：$len")
            if (len > 0x7f) {
                out.write(((len shr 8) or 0x80) and 0xff)
                out.write(len and 0xff)
            } else {
                out.write(len and 0xff)
            }
        }

        private fun encodeUtf16(s: String): ByteArray {
            val n = s.length
            if (n > 0x7fff) malformed("串过长，UTF-16 池长度字段装不下：$n")
            val out = ByteArrayOutputStream(n * 2 + 4)
            writeLen16(out, n) // 单字长形式（n<=0x7fff）；双字长形式留给超长串，此处先拒
            for (ch in s) writeLen16(out, ch.code) // Kotlin 按 Char 迭代 = 逐 UTF-16 码元（代理对逐半写）
            writeLen16(out, 0)
            return out.toByteArray()
        }

        private fun writeLen16(out: ByteArrayOutputStream, v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
        }
    }
}
