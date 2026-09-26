package com.autoscript.appservice.packager.npm

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.npm.ApprovalAction
import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.InstallFlags
import com.autoscript.domain.npm.PackageSpec
import com.autoscript.domain.scripts.ScriptPaths
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * npm 事件**拉取口**单测（§10.7 `drainEvents`/`drainApprovals` + §9.1 游标口径）。
 *
 * 立这条口的原因：桥的入站面只有「按 requestId 结算的 ok/err」（§7.5），宿主没有
 * 主动推给脚本的通道 —— 于是 JS 的 `onProgress`/`onWarning`/`onApproval` 在本测试之前
 * **没有生产投递方**（订阅了但永远不响，正是 npm.ts 自己 KDoc 说的「比没有这个 API 更糟」）。
 * 钉死三件事：
 * 1. 环：有界丢最旧、seq 空洞可见、按项目过滤、batch 截断、空增量以游标为准；
 * 2. 装配：安装链发出的事件落环（`emit` 是唯一投递点），审批入队同理；
 * 3. wire：phase/kind/action 逐字映射到 JS 联合（`post-check` 不是 `post_check`）。
 */
class NpmEventDrainTest {

    @TempDir
    lateinit var dir: Path

    private val layout get() = NpmProjectLayout(ScriptPaths.projectsRoot(dir))

    /** 重操作假体：只回摘要，不碰网络/磁盘（安装编排照走真路径）。 */
    private class OkExecutor : InstallCoordinator.HeavyOpExecutor {
        override suspend fun execute(op: InstallCoordinator.HeavyOp, sink: InstallCoordinator.ProgressSink): String = "ok"
    }

    private fun coordinator(): InstallCoordinator = InstallCoordinator(
        services = NpmServices(
            layout = layout,
            journal = InstallJournal(dir.resolve(".autojs")),
            staging = InstallStaging(layout),
            ledger = ApprovalLedger(),
            cacheIndex = CacheIndex { false },
        ),
        executor = OkExecutor(),
        freeSpaceProbe = { 10L * 1024 * 1024 * 1024 },
    )

    private fun handler(c: InstallCoordinator) = NpmBridgeHandler(c)

    private fun req(method: String, payload: String?) =
        BridgeRequest(id = 1, namespace = "npm", method = method, payload = payload, ttlMillis = 10_000)

    private fun json(vararg kv: Pair<String, Any?>): String = NpmBridgeJson.encode(mapOf(*kv))

    // ═══ 环（与 A11yEventRing 同纪律） ═══

    @Test
    fun `空增量以游标为准（first 等于 sinceSeq，不以空数组终结）`() {
        val ring = InstallCoordinator.SeqRing<String>(capacity = 4)
        val empty = ring.drain("main", 0L, 8)
        assertEquals(0L, empty.first, "没有事件时 first=since，调用方以游标前进")
        assertEquals(0L, empty.second)
        assertTrue(empty.third.isEmpty())
        ring.push("main", "a")
        val one = ring.drain("main", 1L, 8)
        assertEquals(1L, one.first, "已取到的游标再取即空增量")
        assertTrue(one.third.isEmpty())
    }

    @Test
    fun `环有界丢最旧，seq 空洞可见（first 大于 sinceSeq+1 就是丢过）`() {
        val ring = InstallCoordinator.SeqRing<String>(capacity = 3)
        repeat(5) { ring.push("main", "e$it") }   // seq 1..5，环里只剩 3..5
        val got = ring.drain("main", 0L, 8)
        assertEquals(3L, got.first, "最旧两条已被挤掉，空洞要看得见（静默断流才是要禁的）")
        assertEquals(5L, got.second)
        assertEquals(listOf(3L, 4L, 5L), got.third.map { it.first })
    }

    @Test
    fun `按项目过滤，batch 截断后从 last 续取`() {
        val ring = InstallCoordinator.SeqRing<String>(capacity = 16)
        ring.push("main", "m1")
        ring.push("other", "x1")
        ring.push("main", "m2")
        ring.push("main", "m3")
        val first = ring.drain("main", 0L, 2)
        assertEquals(listOf("m1", "m2"), first.third.map { it.second }, "别的项目的事件不掺进来")
        val rest = ring.drain("main", first.second, 2)
        assertEquals(listOf("m3"), rest.third.map { it.second })
        assertTrue(ring.drain("main", rest.second, 2).third.isEmpty(), "取完即空增量")
    }

    // ═══ 装配：事件真的落环 ═══

    @Test
    fun `安装链发出的事件落环，拉取口能拿到且游标前进`() = runBlocking {
        val c = coordinator()
        c.install("main", listOf(PackageSpec("lodash", "4.17.21")), InstallFlags())

        val first = c.drainEvents("main", 0L, 32)
        assertTrue(first.events.isNotEmpty(), "emit 是唯一投递点，落环必须发生（否则 JS 的 onProgress 永远空转）")
        val kinds = first.events.map { it.event }
        assertTrue(kinds.any { it is InstallEvent.Progress && it.phase == InstallEvent.Phase.QUEUED }, "排队事件须在场：$kinds")
        assertTrue(kinds.any { it is InstallEvent.Finished }, "终态事件须在场（成功或失败都要能被脚本看到）：$kinds")

        val rest = c.drainEvents("main", first.lastSeq, 32)
        assertTrue(rest.events.isEmpty(), "游标推进到末尾后即空增量（不重复投递）")
        assertEquals(first.lastSeq, rest.firstSeq, "空增量回 since")

        val other = c.drainEvents("other-project", 0L, 32)
        assertTrue(other.events.isEmpty(), "按项目过滤：别的项目看不到 main 的事件")
    }

    @Test
    fun `requestApprove 落审批环，approvals 拉取口拿得到`() = runBlocking {
        val c = coordinator()
        c.requestApprove("main", "esbuild", "hash-1", ApprovalAction.RUN_SCRIPT)
        val got = c.drainApprovals("main", 0L, 8)
        assertEquals(1, got.requests.size)
        val r = got.requests.single()
        assertEquals("esbuild", r.request.pkg)
        assertEquals(ApprovalAction.RUN_SCRIPT, r.request.action)
        assertTrue(r.seq > 0L, "序号必须随批带上（脚本拿它当下一次 sinceSeq）")
        assertTrue(c.drainApprovals("main", r.seq, 8).requests.isEmpty(), "取过即空增量")
    }

    // ═══ wire 映射（JS 联合的逐字对偶） ═══

    @Test
    fun `phase 的 wire 逐字对齐 JS 联合（post-check 不是 post_check）`() {
        val h = handler(coordinator())
        val wire = InstallEvent.Phase.entries.associateWith {
            h.phaseWire(it)
        }
        assertEquals(
            listOf("queued", "resolve", "download", "reify", "post-check", "done"),
            InstallEvent.Phase.entries.map { wire.getValue(it) },
            "enum.name.lowercase() 会把 POST_CHECK 折成 post_check，脚本的 switch 整段落 default",
        )
    }

    @Test
    fun `kind 与 action 的 wire 逐字对齐 JS 联合（feedWarning 认不出就抛）`() {
        val h = handler(coordinator())
        assertEquals(
            listOf("scripts-skipped", "trust-downgraded", "low-memory", "registry-fallback", "disk-quota"),
            InstallEvent.Kind.entries.map { h.kindWire(it) },
        )
        assertEquals(
            listOf("install_script", "run_script", "exec"),
            ApprovalAction.entries.map { h.actionWire(it) },
        )
    }

    @Test
    fun `三种事件的回包形状逐字对齐 JS（type 判别 + 可空字段原样）`() {
        val h = handler(coordinator())
        val p = h.encodeEvent(7L, InstallEvent.Progress("main", "h1", InstallEvent.Phase.DOWNLOAD, pkg = "lodash", percent = 42f))
        assertEquals("progress", p["type"]); assertEquals(7L, p["seq"])
        assertEquals("download", p["phase"]); assertEquals("lodash", p["name"]); assertEquals(42f, p["percent"])

        val w = h.encodeEvent(8L, InstallEvent.Warning("main", "h1", InstallEvent.Kind.SCRIPTS_SKIPPED, listOf("esbuild"), "脚本没跑"))
        assertEquals("warning", w["type"]); assertEquals("scripts-skipped", w["kind"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("esbuild"), w["pkgs"] as List<String>)

        val f = h.encodeEvent(9L, InstallEvent.Finished("main", "h1", success = false, detail = "boom"))
        assertEquals("finished", f["type"]); assertEquals(false, f["success"]); assertEquals("boom", f["detail"])

        val p2 = h.encodeEvent(1L, InstallEvent.Progress("main", "h1", InstallEvent.Phase.QUEUED))
        assertEquals(null, p2["name"], "没填的可空字段原样 null，不编默认值")
        assertEquals(null, p2["percent"])
    }

    // ═══ 桥面：方法路由与参数 ═══

    @Test
    fun `events 上桥返回 first 与 last 与 events 三件套`() = runBlocking {
        val c = coordinator()
        c.install("main", listOf(PackageSpec("axios", null)), InstallFlags())
        val r = handler(c).handle(req("events", json("sinceSeq" to 0, "batch" to 64)))
        assertTrue(r is BridgeResponse.Ok, "events 必须 Ok（实为 $r）")
        val payload = (r as BridgeResponse.Ok).payload!!
        assertTrue(payload.contains("\"first\":"), payload)
        assertTrue(payload.contains("\"last\":"), payload)
        assertTrue(payload.contains("\"events\":["), payload)
        assertTrue(payload.contains("\"type\":\"progress\""), payload)
    }

    @Test
    fun `events 空增量回 first 等于 last 等于 sinceSeq`() = runBlocking {
        val payload = (handler(coordinator()).handle(req("events", json("sinceSeq" to 5))) as BridgeResponse.Ok).payload!!
        assertEquals("{\"first\":5,\"last\":5,\"events\":[]}", payload)
    }

    @Test
    fun `events 的 batch 非法一律 ERR_INVALID_PARAM（不静默套默认）`() = runBlocking {
        for (bad in listOf(json("batch" to 0), json("batch" to -3))) {
            val r = handler(coordinator()).handle(req("events", bad))
            assertTrue(r is BridgeResponse.Err, "batch<=0 必须拒：$r")
            assertEquals(ErrorCode.ERR_INVALID_PARAM.code, (r as BridgeResponse.Err).errorCode)
        }
    }

    @Test
    fun `approvals 上桥带 action 的 wire 名`() = runBlocking {
        val c = coordinator()
        c.requestApprove("main", "esbuild", "h", ApprovalAction.INSTALL_SCRIPT)
        val payload = (handler(c).handle(req("approvals", json("sinceSeq" to 0))) as BridgeResponse.Ok).payload!!
        assertTrue(payload.contains("\"requests\":["), payload)
        assertTrue(payload.contains("\"action\":\"install_script\""), "wire 名与 JS ApprovalRequest.action 对齐：$payload")
        assertTrue(payload.contains("\"seq\":"), payload)
    }
}
