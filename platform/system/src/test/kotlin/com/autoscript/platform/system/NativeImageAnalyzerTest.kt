package com.autoscript.platform.system

import com.autoscript.domain.automation.ColorHit
import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.automation.ImageMatch
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `images` 宿主侧真实现的语义测试（§9.2）。native 替身住内存（[FakeOps]）——
 * 真 so 只在设备上（P1），这里钉的是**装配侧语义**：句柄纪律、错误码对表、
 * 未匹配/分类失败的区分、"so 缺席 → 不注入"的凑数防线。
 *
 * 判据与 `:domain` `ImageAnalyzer` KDoc 逐条对齐：
 * 1. decode 发号（refId 单调、generation 恒 1），宽高是 native 报的真值；
 * 2. native 帧号与 refId 一一对照，release 后**匹配同帧即 STALE**；
 * 3. 状态码对表原码透传：FILE_NOT_FOUND / IO / STALE 不折叠成 INVALID_PARAM；
 * 4. 未命中回 null（答案不是异常）—— 与 conf=0 假命中可分辨；
 * 5. 未知/跨代句柄 → STALE（"放过的帧"与"没见过的帧"同码，但都不到 native）；
 * 6. `of(ops = null)` → null（装配层据此不喂分析器，桥回 NOT_IMPLEMENTED）。
 */
class NativeImageAnalyzerTest {

    /** 内存 native 替身：可指定每次调用的结论（含帧号账本，验对照表真被用上）。 */
    private class FakeOps : NativeImageAnalyzer.Ops {
        var nextNativeRef = 100L
        var decodeResult: Triple<Long, Int, Int>? = null
        var decodeStatus = 0

        /** true = 模拟 JNI 自身失败（回 null 而 status 仍 0：OOME/编码失败）。 */
        var decodeJniFail = false
        var matchResult: ImageMatch? = null
        var matchStatus = 0
        val released = mutableListOf<Long>()
        val matchCalls = mutableListOf<Triple<Long, Long, Double>>()
        var releaseStatus = 0

        override fun decode(path: String, status: IntArray): Triple<Long, Int, Int>? {
            status[0] = decodeStatus
            if (decodeStatus != 0 || decodeJniFail) return null
            decodeResult?.let { return it }
            return Triple(nextNativeRef++, 640, 480)
        }

        override fun match(
            haystack: Long,
            needle: Long,
            threshold: Double,
            status: IntArray,
        ): ImageMatch? {
            matchCalls += Triple(haystack, needle, threshold)
            status[0] = matchStatus
            return if (matchStatus == 0) matchResult else null
        }

        var colorResult: ColorHit? = null
        var colorStatus = 0
        data class ColorCall(
            val frame: Long,
            val color: IntArray,
            val tolerance: Int,
            val region: IntArray?,
        )

        val colorCalls = mutableListOf<ColorCall>()

        override fun color(
            nativeFrame: Long,
            color: IntArray,
            tolerance: Int,
            region: IntArray?,
            status: IntArray,
        ): ColorHit? {
            colorCalls += ColorCall(nativeFrame, color, tolerance, region)
            status[0] = colorStatus
            return if (colorStatus == 0) colorResult else null
        }

        override fun release(nativeRef: Long): Int {
            released += nativeRef
            return releaseStatus
        }
    }

    private val ops = FakeOps()
    private val analyzer = NativeImageAnalyzer(ops)

    private fun codeOf(t: Throwable): String = (t as AutojsException).error.code

    @Test
    fun `decode 发号 + native 帧号进对照表（宽高是 native 真值）`() = runBlocking {
        ops.decodeResult = Triple(100L, 1080, 2400)
        val f = analyzer.decode("/sdcard/screen.png")
        assertEquals(1L, f.handle.refId, "refId 从 1 起（handler 侧同一起点，但两边账本独立）")
        assertEquals(1L, f.handle.generation, "一个文件一个帧，generation 恒 1")
        assertEquals(1080, f.width)
        assertEquals(2400, f.height)

        val second = analyzer.decode("/sdcard/icon.png")
        assertEquals(2L, second.handle.refId, "refId 单调递增，不复用不缓存")
        Unit
    }

    @Test
    fun `release 先删对照表再放 native——放掉的帧匹配即 STALE`() = runBlocking {
        val haystack = analyzer.decode("/a.png")      // refId 1
        val needle = analyzer.decode("/b.png")        // refId 2，仍在场
        analyzer.release(haystack.handle)
        // needle 仍在场而 haystack 已死：STALE 该怪 haystack（不是 needle），
        // 且**一次 native 匹配都不发**（native 帧号经由对照表才拿得到）。
        val t = runCatching { analyzer.findImage(haystack.handle, needle.handle, 0.8) }
            .exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, t)
        assertEquals("ERR_STALE_HANDLE", codeOf(t!!))
        assertTrue(ops.matchCalls.isEmpty(), "帧已死 → 一次 native 匹配都不发")
        assertEquals(1, ops.released.size, "release 真落 native（帧号经对照表翻译）")
        Unit
    }

    @Test
    fun `匹配把两个 refId 译成本机帧号（native 看到的是它发的号）`() = runBlocking {
        ops.decodeResult = Triple(11L, 640, 480)
        val h = analyzer.decode("/h.png")
        ops.decodeResult = Triple(22L, 40, 20)
        val n = analyzer.decode("/n.png")
        ops.matchResult = ImageMatch(3, 4, 40, 20, 0.91)

        val hit = analyzer.matchTemplate(h.handle, n.handle, 0.8)
        assertEquals(ImageMatch(3, 4, 40, 20, 0.91), hit)
        assertEquals(1, ops.matchCalls.size)
        assertEquals(Triple(11L, 22L, 0.8), ops.matchCalls.single(), "native 收到它自己发的帧号")
        Unit
    }

    @Test
    fun `未命中回 null 且 status 为 0（答案不是异常，也不是假命中）`() = runBlocking {
        ops.matchResult = null
        ops.matchStatus = 0
        val h = analyzer.decode("/h.png")
        val n = analyzer.decode("/n.png")
        assertNull(analyzer.findImage(h.handle, n.handle, 0.9), "native 未命中 → null")
        Unit
    }

    @Test
    fun `decode 状态码原码透传：文件缺失与 IO 不折叠`() = runBlocking {
        ops.decodeStatus = 2
        val t1 = runCatching { analyzer.decode("/nope.png") }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, t1)
        assertEquals("ERR_FILE_NOT_FOUND", codeOf(t1!!), "路径不在 —— 脚本要能分辨它和解码失败")

        ops.decodeStatus = 3
        val t2 = runCatching { analyzer.decode("/x.png") }.exceptionOrNull()
        assertEquals("ERR_IO", codeOf(t2 as AutojsException), "在但不是合法图片")
        Unit
    }

    @Test
    fun `decode 的 JNI 自身失败（null + status 0）归 IO 不猜码`() = runBlocking {
        ops.decodeJniFail = true
        val t = runCatching { analyzer.decode("/x.png") }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, t)
        assertEquals("ERR_IO", codeOf(t!!), "OOME/字符串编解码失败不冒充某个语义码")
        Unit
    }

    @Test
    fun `release 未知帧 STALE 且不碰 native`() = runBlocking {
        val t = runCatching { analyzer.release(HandleRef(4242, 1)) }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, t)
        assertEquals("ERR_STALE_HANDLE", codeOf(t!!))
        assertTrue(ops.released.isEmpty(), "未知句柄一次 native 释放都不发")
        Unit
    }

    @Test
    fun `release 跨代 STALE（generation 不匹配先拒，不进对照表）`() = runBlocking {
        val f = analyzer.decode("/x.png")
        val t = runCatching { analyzer.release(HandleRef(f.handle.refId, 7)) }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, t)
        assertEquals("ERR_STALE_HANDLE", codeOf(t!!))
        assertTrue(ops.released.isEmpty(), "跨代连帧号都不给 native")
        Unit
    }

    @Test
    fun `native 拒绝 release（帧号不在场）→ STALE 原码透传`() = runBlocking {
        val f = analyzer.decode("/x.png")
        ops.releaseStatus = 1
        val t = runCatching { analyzer.release(f.handle) }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, t)
        assertEquals("ERR_STALE_HANDLE", codeOf(t!!))
        Unit
    }

    @Test
    fun `空白路径先拒（contract 的 IllegalArgumentException）`() {
        val t = runCatching { runBlocking { analyzer.decode("   ") } }.exceptionOrNull()
        assertInstanceOf(IllegalArgumentException::class.java, t)
        Unit
    }

    // ── findColor（§9.2 P1 第一个算子）────────────────────────────────────
    // 三条口径：域 require 折叠 INVALID_PARAM、x=-1 视为未命中、status=4 原码对表。

    @Test
    fun `findColor 域越界在这层 require（不放进 native）`() = runBlocking {
        val f = analyzer.decode("/x.png")
        val bad = listOf<Pair<String, suspend () -> Unit>>(
            "color 三分量" to { analyzer.findColor(f.handle, listOf(1, 2, 3), 0, null) },
            "分量 256" to { analyzer.findColor(f.handle, listOf(256, 0, 0, 255), 0, null) },
            "分量 -1" to { analyzer.findColor(f.handle, listOf(-1, 0, 0, 255), 0, null) },
            "tolerance 256" to { analyzer.findColor(f.handle, listOf(0, 0, 0, 255), 256, null) },
            "tolerance -1" to { analyzer.findColor(f.handle, listOf(0, 0, 0, 255), -1, null) },
            "region 三元组" to { analyzer.findColor(f.handle, listOf(0, 0, 0, 255), 0, listOf(0, 0, 0)) },
        )
        for ((why, call) in bad) {
            val t = runCatching { call() }.exceptionOrNull()
            assertInstanceOf(IllegalArgumentException::class.java, t, why)
        }
        assertTrue(ops.colorCalls.isEmpty(), "域错一次 native 调用都不发")
        Unit
    }

    @Test
    fun `findColor 把 refId 译成本机帧号，色与区域原样到 native`() = runBlocking {
        val f = analyzer.decode("/x.png")
        ops.colorResult = ColorHit(7, 8, 9, 10, 11, 255)
        val hit = analyzer.findColor(f.handle, listOf(1, 2, 3, 255), 12, listOf(10, 20, 30, 40))
        assertEquals(ColorHit(7, 8, 9, 10, 11, 255), hit)
        assertEquals(1, ops.colorCalls.size)
        val call = ops.colorCalls.single()
        assertEquals(100L, call.frame, "native 收到它自己发的帧号")
        assertEquals(listOf(1, 2, 3, 255), call.color.toList(), "四分量原序 r,g,b,a")
        assertEquals(12, call.tolerance, "tolerance 原样")
        assertEquals(listOf(10, 20, 30, 40), call.region?.toList(), "region 原样")
        Unit
    }

    @Test
    fun `findColor 的 x=-1 视为未命中回 null（不是 (0,0) 假命中）`() = runBlocking {
        val f = analyzer.decode("/x.png")
        ops.colorResult = null      // JniOps 见到 x<0 就折成 null（哨兵在那一层解释）
        ops.colorStatus = 0
        assertNull(analyzer.findColor(f.handle, listOf(0, 0, 0, 255), 0, null), "扫过了、没有")
        Unit
    }

    @Test
    fun `findColor 的 status=4 原码对表 INVALID_PARAM`() = runBlocking {
        val f = analyzer.decode("/x.png")
        ops.colorStatus = 4
        ops.colorResult = null
        val t = runCatching { analyzer.findColor(f.handle, listOf(0, 0, 0, 255), 0, null) }
            .exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, t)
        assertEquals("ERR_INVALID_PARAM", codeOf(t!!), "区域不在帧内扫过 0 像素 → 参数错，不折叠成 IO")
        Unit
    }

    @Test
    fun `findColor 用已释放的帧 STALE 且不碰 native`() = runBlocking {
        val f = analyzer.decode("/x.png")
        analyzer.release(f.handle)
        val t = runCatching { analyzer.findColor(f.handle, listOf(0, 0, 0, 255), 0, null) }
            .exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, t)
        assertEquals("ERR_STALE_HANDLE", codeOf(t!!))
        assertTrue(ops.colorCalls.isEmpty(), "帧已死，一次找色都不发")
        Unit
    }

    @Test
    fun `so 缺席 → of(null) 回 null（装配层不喂分析器，桥回 NOT_IMPLEMENTED）`() {
        assertNull(NativeImageAnalyzer.of(null), "没有 so 就没有分析器——不塞内存替身冒充")
        assertInstanceOf(ImageAnalyzer::class.java, NativeImageAnalyzer.of(FakeOps())!!)
        Unit
    }
}
