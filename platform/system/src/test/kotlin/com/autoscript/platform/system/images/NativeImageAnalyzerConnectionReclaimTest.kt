package com.autoscript.platform.system.images

import com.autoscript.domain.automation.ColorHit
import com.autoscript.domain.automation.FeatureHit
import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.ImageMatch
import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.ConnectionResourceRegistry
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.permission.CapabilityMask
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext

/**
 * 帧账的**连接级收口**（§7.5 连接生命周期 × §9.2 帧所有权）。
 *
 * 此前是漏：native 帧表（`imgnative.cpp` 的 `g_frames`）**只有单帧 `imgnative_release`**、
 * 没有批量释放口，而 §7.4 承诺的 GC 兜底（`FinalizationRegistry` → `release(id, gen)`）在
 * `bridge/js` 里并不存在 —— `recycle()` 是脚本自愿调的。分析器又是 `PlatformWiring.of`
 * 装配期建的进程级单例，帧表跨 run 复用。三条叠起来：一个不 recycle 的扫屏循环
 * （1080×2400 RGBA ≈ 10.4 MB/帧，截图节流 333 ms → 约 31 MB/s）把 native 堆吃穿，
 * 且**脚本退出后也不还**。
 *
 * 收口点因此与投屏会话同一条：谁发帧谁在发的时候往**本连接**的
 * [ConnectionResourceRegistry] 登记一份"怎么还"，连接 abort / 排空结束 /
 * `abortConnection(id)` / `revokeRunResources(runId)` 四条路都走到这里。
 *
 * 判据（每条都有可观测后果）：
 * 1. 撤销收掉**它名下**的在场帧，native 帧号逐个还回去；
 * 2. **只收自己的** —— 另一条连接的帧原样在场（分析器是所有脚本共用的单例）；
 * 3. 显式 `release` 放完即**还掉登记**（不留一条永远不会被兑现的注册表条目）；
 * 4. 三个发帧口（`decode`/`ingest`/产出帧）同一张账，不留"哪条路忘了登记"的缝；
 * 5. **撤销在先、发帧在后** → 迟到的帧当场被收（`register` 在已撤销的注册表上立刻兑现）；
 * 6. 一帧 native 释放失败**不中断其余帧**，全试完再如实抛（经 `onError` 上报）；
 * 7. 没有认证身份（宿主直调/JVM 测试）**不登记** —— 登记到没人撤销的注册表等于假装有兜底。
 */
class NativeImageAnalyzerConnectionReclaimTest {

    /** 内存 native 替身：帧号自增，`release` 记录每一次归还（失败可指定到具体帧号）。 */
    private class FakeOps : NativeImageAnalyzer.Ops {
        private var nextNativeRef = 100L

        /** 每一次归还尝试（含失败的那次 —— "不中断全清"要看的是尝试次数）。 */
        val released = mutableListOf<Long>()

        /** 指定它非 0 即失败（模拟 native 拒绝归还某帧）。 */
        var failOn: Long? = null
        var failStatus = 3

        override fun decode(path: String, status: IntArray): Triple<Long, Int, Int>? {
            status[0] = 0
            return Triple(nextNativeRef++, 640, 480)
        }

        override fun ingest(rgba: ByteArray, width: Int, height: Int, status: IntArray): Triple<Long, Int, Int>? {
            status[0] = 0
            return Triple(nextNativeRef++, width, height)
        }

        override fun release(nativeRef: Long): Int {
            released += nativeRef
            return if (nativeRef == failOn) failStatus else 0
        }

        override fun match(h: Long, n: Long, t: Double, r: IntArray?, s: IntArray): ImageMatch? {
            s[0] = 0
            return null
        }

        override fun color(f: Long, c: IntArray, tol: Int, r: IntArray?, s: IntArray): ColorHit? {
            s[0] = 0
            return null
        }

        override fun gray(f: Long, s: IntArray): Triple<Long, Int, Int>? {
            s[0] = 0
            return Triple(nextNativeRef++, 640, 480)
        }

        override fun crop(f: Long, region: IntArray, s: IntArray): Triple<Long, Int, Int>? {
            s[0] = 0
            return Triple(nextNativeRef++, region[2], region[3])
        }

        override fun resize(f: Long, w: Int, h: Int, s: IntArray): Triple<Long, Int, Int>? {
            s[0] = 0
            return Triple(nextNativeRef++, w, h)
        }

        override fun rotate(f: Long, degrees: Double, s: IntArray): Triple<Long, Int, Int>? {
            s[0] = 0
            return Triple(nextNativeRef++, 480, 640)
        }

        override fun feature(scene: Long, template: Long, s: IntArray): FeatureHit? {
            s[0] = 0
            return null
        }
    }

    private val ops = FakeOps()
    private val analyzer = NativeImageAnalyzer(ops)

    /** 桥面请求（生产那条路的入口形状：`ImagesNamespaceHandler.handle`）。 */
    private fun req(id: Long, method: String, payload: String) =
        BridgeRequest(id, "images", method, payload, 5_000)

    /** 一条连接的收口口 + 认证身份（形状照 `NewlineFrameServer.Connection`：注册表在连接上，身份随协程走）。 */
    private class Conn(val id: Long, onError: (Throwable) -> Unit = {}) {
        val errors = mutableListOf<Throwable>()
        val registry = ConnectionResourceRegistry { t -> errors += t; onError(t) }
        val ctx: CoroutineContext = AuthenticatedRunContext(EngineId(0), id, id, CapabilityMask.ALL)
            .also { it.resources = registry }
    }

    // ── 1. 撤销收掉它名下全部在场帧 ─────────────────────────────────────

    @Test
    fun `连接撤销收掉它名下全部在场帧 —— native 帧号逐个还回去`() = runBlocking {
        val c = Conn(1)
        runBlocking(c.ctx) {
            analyzer.decode("/a.png")
            analyzer.decode("/b.png")
            analyzer.decode("/c.png")
        }
        assertEquals(3, analyzer.liveFrameCount(), "三帧在场")
        assertEquals(0, ops.released.size, "没人显式 release 时，native 一帧都不该被还")

        c.registry.revokeAll()

        assertEquals(3, ops.released.size, "撤销要把三帧全部还回 native：${ops.released}")
        assertEquals(0, analyzer.liveFrameCount(), "帧账清空")
        assertTrue(c.errors.isEmpty(), "正常归还不该上报错误：${c.errors}")
        Unit
    }

    // ── 2. 只收自己的 ─────────────────────────────────────────────────

    @Test
    fun `只收自己的 —— 另一条连接的帧原样在场`() = runBlocking {
        val a = Conn(1)
        val b = Conn(2)
        runBlocking(a.ctx) { analyzer.decode("/mine.png") }
        runBlocking(b.ctx) { analyzer.decode("/theirs.png") }
        assertEquals(2, analyzer.liveFrameCount())

        a.registry.revokeAll()

        assertEquals(1, ops.released.size, "只还 A 那一帧：${ops.released}")
        assertEquals(1, analyzer.liveFrameCount(), "B 的帧必须原样在场")
        // B 的帧仍然可用（在场性是帧表唯一事实源，撤销别人不许动它）
        val survivor = runBlocking(b.ctx) { analyzer.decode("/another.png") }
        assertEquals(2, analyzer.liveFrameCount())
        assertEquals(NativeImageAnalyzer.GENERATION, survivor.handle.generation)
        Unit
    }

    // ── 3. 显式 release 放完即还掉登记 ─────────────────────────────────

    @Test
    fun `一条连接一条登记 —— 扫屏循环不把共享注册表堆满`() = runBlocking {
        val c = Conn(1)
        runBlocking(c.ctx) {
            repeat(50) { analyzer.decode("/frame$it.png") }
        }
        // 判据是**条数**：一帧一条会在共享的注册表里堆出上万条（那张表还住着投屏会话的收口）。
        assertEquals(1, c.registry.size(), "50 帧仍只有一条登记：${c.registry.size()}")
        assertEquals(50, analyzer.liveFrameCount())

        c.registry.revokeAll()

        assertEquals(50, ops.released.size, "撤销把 50 帧全部还回 native")
        assertEquals(0, analyzer.liveFrameCount())
        Unit
    }

    @Test
    fun `显式 release 放完全部帧 —— 撤销是 no-op，绝不重复还帧`() = runBlocking {
        val c = Conn(1)
        val frame: ImageFrame = runBlocking(c.ctx) { analyzer.decode("/a.png") }
        assertEquals(1, c.registry.size(), "在册一条收口")

        analyzer.release(frame.handle)
        assertEquals(1, ops.released.size)
        assertEquals(0, analyzer.liveFrameCount())
        // 登记**刻意留着**（随连接生命周期走）：撤销时按账收，账空即 no-op。提前摘会打开
        // "放掉最后一帧与同时发一帧交错 → 新帧被当孤儿收掉"的窗口。
        assertEquals(1, c.registry.size(), "登记留在册（撤销按账收，不靠登记条数判有无帧）")

        c.registry.revokeAll()
        assertEquals(1, ops.released.size, "撤销不许把已放的帧再放一次：${ops.released}")
        Unit
    }

    @Test
    fun `放完全部帧再发一帧 —— 登记还在，新帧照样被收`() = runBlocking {
        val c = Conn(1)
        val first = runBlocking(c.ctx) { analyzer.decode("/a.png") }
        analyzer.release(first.handle)

        runBlocking(c.ctx) { analyzer.decode("/b.png") }
        assertEquals(1, c.registry.size(), "仍是同一条登记（不重复登记）")

        c.registry.revokeAll()
        assertEquals(2, ops.released.size, "两帧都还回 native：${ops.released}")
        assertEquals(0, analyzer.liveFrameCount())
        Unit
    }

    // ── 4. 三个发帧口同一张账 ─────────────────────────────────────────

    @Test
    fun `ingest（screen 帧）与产出帧同样在册 —— 三个发帧口不留缝`() = runBlocking {
        val c = Conn(1)
        runBlocking(c.ctx) {
            // ingest 是 `screen.capture()`/投屏 `nextFrame` 的落点：单帧最大（10.4 MB）、
            // 最容易被循环刷出来的一条 —— 它不登记就等于没修。
            analyzer.ingest(8, 8, ByteArray(8 * 8 * 4))
            val src = analyzer.decode("/src.png")
            analyzer.toGrayscale(src.handle)
            analyzer.crop(src.handle, listOf(0, 0, 4, 4))
            analyzer.resize(src.handle, 32, 32)
            analyzer.rotate(src.handle, 90.0)
        }
        assertEquals(6, analyzer.liveFrameCount(), "1 ingest + 1 decode + 4 产出帧")

        c.registry.revokeAll()

        assertEquals(6, ops.released.size, "全部还回 native：${ops.released}")
        assertEquals(0, analyzer.liveFrameCount())
        Unit
    }

    // ── 5. 撤销在先、发帧在后 ─────────────────────────────────────────

    @Test
    fun `撤销在先发帧在后 —— 迟到的帧当场被收，不留孤儿`() = runBlocking {
        val c = Conn(1)
        runBlocking(c.ctx) { analyzer.decode("/a.png") }
        c.registry.revokeAll()
        assertEquals(1, ops.released.size)

        // 连接已经没了，但帧还是发出来了（撤销与 open 赛跑的那条路）：
        // `ConnectionResourceRegistry.register` 在已撤销的注册表上立刻兑现 → 当场收掉。
        runBlocking(c.ctx) { analyzer.decode("/late.png") }

        assertEquals(2, ops.released.size, "迟到的帧必须当场被收：${ops.released}")
        assertEquals(0, analyzer.liveFrameCount(), "不留孤儿帧")
        Unit
    }

    // ── 6. 一帧失败不中断全清 ─────────────────────────────────────────

    @Test
    fun `一帧 native 释放失败不中断其余帧 —— 全试完再如实抛`() = runBlocking {
        val c = Conn(1)
        val frames = runBlocking(c.ctx) {
            listOf(analyzer.decode("/a.png"), analyzer.decode("/b.png"), analyzer.decode("/c.png"))
        }
        // 让第二帧的 native 帧号归还失败（3 = IO）
        ops.failOn = ops.released.size.let { frames[1].handle.refId }
            .let { refId ->
                // 帧号是 native 侧的（100 起）；按 refId 顺序对齐：decode 三帧 → 100/101/102
                100L + (refId - 1)
            }

        c.registry.revokeAll()

        assertEquals(3, ops.released.size, "失败那帧不许打断其余两帧：${ops.released}")
        assertEquals(0, analyzer.liveFrameCount(), "帧账仍要清空（在场性是宿主侧的事实）")
        assertEquals(1, c.errors.size, "失败要如实上报，不静默")
        assertTrue(c.errors[0] is AutojsException, "上报的是分类错误：${c.errors[0]}")
        Unit
    }

    // ── 7. 没有认证身份不登记 ─────────────────────────────────────────

    @Test
    fun `没有认证身份不登记 —— 宿主直调照旧只能显式 release`() = runBlocking {
        // 无 ctx（宿主直调/JVM 测试）：帧照发，但没有连接可撤销。
        val f1 = analyzer.decode("/host.png")
        val f2 = analyzer.ingest(4, 4, ByteArray(4 * 4 * 4))
        assertEquals(2, analyzer.liveFrameCount())

        val stray = ConnectionResourceRegistry()
        stray.revokeAll()
        assertEquals(0, ops.released.size, "登记到没人撤销的注册表等于假装有兜底 —— 不登记")
        assertEquals(2, analyzer.liveFrameCount(), "帧原样在场")

        // 显式 release 仍是唯一收口
        analyzer.release(f1.handle)
        analyzer.release(f2.handle)
        assertEquals(2, ops.released.size)
        assertEquals(0, analyzer.liveFrameCount())
        Unit
    }

    // ── 回归钉：收口不许改动既有语义 ────────────────────────────────────

    @Test
    fun `连接在册期间发帧不影响既有语义 —— 发号单调、显式 release 仍 STALE 判据不变`() = runBlocking {
        val c = Conn(1)
        val a = runBlocking(c.ctx) { analyzer.decode("/a.png") }
        val b = runBlocking(c.ctx) { analyzer.decode("/b.png") }
        assertEquals(a.handle.refId + 1, b.handle.refId, "号段仍单调（与无 ctx 那条路同一段）")
        assertEquals(NativeImageAnalyzer.GENERATION, a.handle.generation)

        analyzer.release(a.handle)
        val e = runCatching { analyzer.release(a.handle) }.exceptionOrNull()
        assertTrue(e is AutojsException, "放过的帧再放如实 STALE")
        assertEquals("ERR_STALE_HANDLE", (e as AutojsException).error.code)

        c.registry.revokeAll()
        assertEquals(2, ops.released.size, "撤销只还仍在场的那一帧：${ops.released}")
        Unit
    }

    @Test
    fun `撤销幂等 —— 重复撤销不炸不重复还帧`() = runBlocking {
        val c = Conn(1)
        runBlocking(c.ctx) { analyzer.decode("/a.png") }
        c.registry.revokeAll()
        c.registry.revokeAll()
        assertEquals(1, ops.released.size, "同一帧只还一次：${ops.released}")
        Unit
    }

    @Test
    fun `未知连接号撤销是无害 no-op`() {
        assertEquals(0, analyzer.releaseConnection(9_999L), "没登记过的连接号：0 帧")
        assertEquals(0, ops.released.size)
        Unit
    }

    @Test
    fun `直接调 releaseConnection 也收得掉（宿主与看门狗那条不经注册表的路）`() = runBlocking {
        val c = Conn(7)
        runBlocking(c.ctx) { analyzer.decode("/a.png") }
        assertEquals(1, analyzer.releaseConnection(7L))
        assertEquals(1, ops.released.size)
        assertEquals(0, analyzer.liveFrameCount())
        assertEquals(0, c.registry.size(), "直调收口也要把登记还掉（否则随连接数只增不减）")
        // 连接随后自然结束：注册表那条已被 releaseConnection 摘掉，撤销不再重复还帧
        c.registry.revokeAll()
        assertEquals(1, ops.released.size, "不许重复归还：${ops.released}")
        Unit
    }

    @Test
    fun `句柄常量 NO_CONNECTION 与桥路由的缺省 0 同值（"没身份"只有一个写法）`() {
        assertEquals(0L, NativeImageAnalyzer.NO_CONNECTION)
        Unit
    }

    // ── 生产路径：桥面 handler → SPI，归属从协程上下文取 ────────────────────

    @Test
    fun `桥面 decode 走同一条收口 —— 撤销连接即还清 native`() = runBlocking {
        // 钉的是**生产那条路**：`ImagesNamespaceHandler.decode` → `analyzer.decode`，
        // 归属不是参数传的，是 `AuthenticatedRunContext` 随协程带来的（不可自报）。
        val c = Conn(3)
        val handler = ImagesNamespaceHandler(analyzer)
        val resp = runBlocking(c.ctx) { handler.handle(req(7, "decode", """{"path":"/sdcard/icon.png"}""")) }
        assertTrue(resp is BridgeResponse.Ok, "decode 该成功：$resp")
        assertEquals(1, analyzer.liveFrameCount())
        assertEquals(1, c.registry.size(), "桥面发的帧同样登记在本连接名下")

        c.registry.revokeAll()

        assertEquals(1, ops.released.size, "撤销即还清 native：${ops.released}")
        assertEquals(0, analyzer.liveFrameCount())
        Unit
    }

    @Test
    fun `桥面 release 走 native 归还 —— 脚本自己收干净时撤销不再重复还`() = runBlocking {
        val c = Conn(4)
        val handler = ImagesNamespaceHandler(analyzer)
        val resp = runBlocking(c.ctx) { handler.handle(req(8, "decode", """{"path":"/sdcard/icon.png"}""")) }
            as BridgeResponse.Ok
        val ref = (DomainJson.decodeObject(resp.payload!!)["ref"] as DomainJson.Value.Obj).fields
        val refId = (ref["refId"] as DomainJson.Value.N).raw
        runBlocking(c.ctx) {
            handler.handle(req(9, "release", """{"ref":{"refId":$refId,"generation":1}}"""))
        }

        assertEquals(1, ops.released.size, "脚本自己 recycle 走的就是 native 归还")
        assertEquals(0, analyzer.liveFrameCount())

        c.registry.revokeAll()
        assertEquals(1, ops.released.size, "撤销是 no-op：${ops.released}")
        Unit
    }

    /** 占位：钉住"帧账是宿主侧唯一事实源"这条不因收口而松动（[HandleRef] 仍原样透传）。 */
    @Test
    fun `帧句柄原样透传 —— 收口不改发号侧归一`() = runBlocking {
        val c = Conn(1)
        val f = runBlocking(c.ctx) { analyzer.decode("/a.png") }
        assertEquals(HandleRef(f.handle.refId, f.handle.generation), f.handle)
        Unit
    }
}
