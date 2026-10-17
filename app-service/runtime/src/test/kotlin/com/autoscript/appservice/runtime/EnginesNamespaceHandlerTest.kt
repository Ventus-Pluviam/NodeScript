package com.autoscript.appservice.runtime

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.json.DomainJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.permission.BridgeCapability
import com.autoscript.domain.permission.CapabilityMask
import com.autoscript.domain.permission.ScriptAuthorizationPolicy
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnginesNamespaceHandlerTest {

    private fun fullRig(capacity: Int = 1): Triple<EnginesNamespaceHandler, RuntimeController, MutableList<FakeEngine>> {
        val engines = MutableList(capacity) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity)
        val controller = RuntimeController(pool)
        return Triple(EnginesNamespaceHandler(controller), controller, engines)
    }

    private fun handler(capacity: Int = 1): Pair<EnginesNamespaceHandler, MutableList<FakeEngine>> {
        val (h, _, engines) = fullRig(capacity)
        return h to engines
    }

    /** 桥侧 Request 工厂（步骤 4 后自定义 Request 已删；缺省 TTL = 常规桥请求量级）。 */
    private fun enginesReq(id: Long, method: String, payload: String?, ttlMillis: Long = 30_000) =
        BridgeRequest(id, "engines", method, payload, ttlMillis)

    /**
     * exec 载荷工厂。**timeoutMillis 缺省带上**：桥这条路必须显式声明执行期限
     * （见 handler 方法表 `exec` 条），绝大多数用例验的不是"缺它会怎样"，
     * 让它们各自抄一遍期限只会把噪音写进每条断言。传 null 才是不带该键。
     */
    private fun execPayload(
        projectId: String = "p1",
        scriptPath: String = "a.js",
        extra: String = "",
        timeoutMillis: Long? = 60_000,
    ): String {
        val t = if (timeoutMillis == null) "" else ",\"timeoutMillis\":$timeoutMillis"
        return "{\"projectId\":\"$projectId\",\"scriptPath\":\"$scriptPath\"$t$extra}"
    }

    @Test
    fun `exec 成功回 runId 与 handle`() = runBlocking {
        val (h, engines) = handler()
        val resp = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(
                enginesReq(
                    1,
                    "exec",
                    """{"projectId":"p1","scriptPath":"a.js","timeoutMillis":60000,"args":["x","y"],"runNonce":"n1"}""",
                ),
            ),
        )
        val o = DomainJson.decodeObject(resp.payload!!)
        val runId = (o["runId"] as DomainJson.Value.N).raw.toLong()
        val handle = o["handle"] as DomainJson.Value.Obj
        assertEquals(runId, ((handle.fields["refId"] as DomainJson.Value.N).raw.toLong()))
        assertEquals("1", (handle.fields["generation"] as DomainJson.Value.N).raw)
        // 幂等锚点透传引擎（§8.5）
        assertEquals("n1", engines[0].executed.single().runNonce)
        assertEquals(listOf("x", "y"), engines[0].executed.single().args)
    }

    @Test
    fun `exec 缺字段回 ERR_INVALID_PARAM`() = runBlocking {
        val (h, _) = handler()
        val cases = listOf(
            null,
            """{"projectId":"p1"}""",
            """{"projectId":"","scriptPath":"a.js"}""",
            "不是json",
            """{"projectId":"p1","scriptPath":"a.js","args":"x"}""",
        )
        for ((i, p) in cases.withIndex()) {
            val resp = assertInstanceOf(
                BridgeResponse.Err::class.java,
                h.handle(enginesReq(10L + i, "exec", p)),
            )
            assertEquals("ERR_INVALID_PARAM", resp.errorCode, "case $i: $p")
        }
    }

    @Test
    fun `exec 满池排队超时回 ERR_TIMEOUT`() = runBlocking {
        val (h, _) = handler()
        val first = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "exec", execPayload())),
        )
        val held =
            (DomainJson.decodeObject(first.payload!!)["runId"] as DomainJson.Value.N).raw.toLong()
        // 排队上限 = payload waitTimeoutMillis 优先，否则桥侧 TTL：槽位被 held 占着，
        // 第二个请求未带显式上限，等满 300ms TTL → ERR_TIMEOUT，绝不无限挂住（§7.4 每次跨进程操作必有 TTL）。
        val queued = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(
                enginesReq(
                    2,
                    "exec",
                    execPayload("p2", "b.js"),
                    ttlMillis = 300,
                ),
            ),
        )
        assertEquals("ERR_TIMEOUT", queued.errorCode, "满池 + TTL 到期 → ERR_TIMEOUT")
        // 在途者不受排队失败影响：stop 后槽位归池，同参数请求随即拿到槽（池容量未缩水）。
        val stopHeld = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(
                enginesReq(3, "stop", """{"runId":$held}""", ttlMillis = 5_000),
            ),
        )
        assertEquals("true", stopHeld.payload)
        val retry = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(
                enginesReq(4, "exec", execPayload("p2", "b.js"), ttlMillis = 5_000),
            ),
        )
        val retryId =
            (DomainJson.decodeObject(retry.payload!!)["runId"] as DomainJson.Value.N).raw.toLong()
        assertTrue(retryId != held, "释放后的槽位应分配给新 run")
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `exec 缺 timeoutMillis 回 ERR_INVALID_PARAM（桥路径无人 await，期限须显式声明）`() = runBlocking {
        // 这条是把 §8.6 的诚实边界钉死：桥 exec 拿到句柄就返回，没人 await 终结 ——
        // 缺省值在这里没有诚实来源（编一个 = 替脚本静默决定它能跑多久）。
        // 缺键与显式 null 同判：都表示"没声明期限"。
        val (h, _, engines) = fullRig()
        for ((i, extra) in listOf("", ",\"timeoutMillis\":null").withIndex()) {
            val resp = assertInstanceOf(
                BridgeResponse.Err::class.java,
                h.handle(enginesReq(20L + i, "exec", execPayload(timeoutMillis = null, extra = extra))),
            )
            assertEquals("ERR_INVALID_PARAM", resp.errorCode, "case $i")
        }
        assertTrue(engines[0].executed.isEmpty(), "守卫生效：拒绝先于投递，一条都没起")
    }

    @Test
    fun `exec 非正 timeoutMillis 回 ERR_INVALID_PARAM（0 与负数都是漏配）`() = runBlocking {
        // 与 §8.6 queueTimeoutMillis 传 0 视为漏配同一条纪律：0 = "永不允许跑"，
        // 与"缺省"长得一样但含义相反，静默夹到某个下限会让脚本跑得比声明的久。
        val (h, _, engines) = fullRig()
        for ((i, bad) in listOf(0L, -1L, -60_000L).withIndex()) {
            val resp = assertInstanceOf(
                BridgeResponse.Err::class.java,
                h.handle(enginesReq(30L + i, "exec", execPayload(timeoutMillis = bad))),
            )
            assertEquals("ERR_INVALID_PARAM", resp.errorCode, "timeoutMillis=$bad")
        }
        assertTrue(engines[0].executed.isEmpty())
    }

    @Test
    fun `exec 声明的期限随锚点交给看门狗（归属 WATCHDOG，不是缺省 AWAITER）`() = runBlocking {
        // 接线钉子：handler 只负责"把期限与归属一起递给池"，期限线本身由看门狗落
        // （EngineWatchdogTest 验）。这里验的是**这条桥的请求真的带了期限归属** ——
        // 少了它，声明过的期限在池里只是个没人读的字段，run 照样悬挂。
        val (h, controller, _) = fullRig()
        val resp = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "exec", execPayload(timeoutMillis = 60_000))),
        )
        val runId = (DomainJson.decodeObject(resp.payload!!)["runId"] as DomainJson.Value.N).raw.toLong()
        val anchor = controller.watchAnchors().single()
        assertEquals(runId, anchor.runId)
        assertEquals(anchor.startedAtMillis + 60_000, anchor.deadlineMillis, "期限 = 启动时刻 + 声明值")
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `exec 排队上限 payload 显式值优先于桥 TTL`() = runBlocking {
        val (h, _) = handler()
        val first = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "exec", execPayload())),
        )
        val held =
            (DomainJson.decodeObject(first.payload!!)["runId"] as DomainJson.Value.N).raw.toLong()
        val begin = System.currentTimeMillis()
        val queued = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(
                enginesReq(
                    2,
                    "exec",
                    execPayload("p2", "b.js", ",\"waitTimeoutMillis\":200"),
                    ttlMillis = 30_000,
                ),
            ),
        )
        val elapsed = System.currentTimeMillis() - begin
        assertEquals("ERR_TIMEOUT", queued.errorCode, "显式排队上限到期 → ERR_TIMEOUT")
        assertTrue(elapsed < 10_000, "显式上限应先于 TTL 到期（实际 ${elapsed}ms）")
        h.handle(enginesReq(3, "stop", "{\"runId\":$held}"))
        Unit
    }

    @Test
    fun `exec 非法 waitTimeoutMillis 回 ERR_INVALID_PARAM`() = runBlocking {
        val (h, _) = handler()
        val resp = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(
                enginesReq(
                    1,
                    "exec",
                    execPayload("p1", "a.js", ",\"waitTimeoutMillis\":\"soon\""),
                ),
            ),
        )
        assertEquals("ERR_INVALID_PARAM", resp.errorCode, "排队上限非数字 → 参数错误")
        Unit
    }

    @Test
    fun `exec 引擎启动失败回 ERR_ENGINE_CRASHED`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }.also { it[0].failOnExecute = true }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, 1)
        val h = EnginesNamespaceHandler(RuntimeController(pool))
        val resp = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(1, "exec", execPayload())),
        )
        assertEquals("ERR_ENGINE_CRASHED", resp.errorCode)
        assertTrue(resp.detail.orEmpty().contains("fake boot failure"))
    }

    @Test
    fun `stop 干净回 true，未知 runId 回 ERR_NOT_FOUND`() = runBlocking {
        val (h, _) = handler()
        val exec = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "exec", execPayload())),
        )
        val runId = (DomainJson.decodeObject(exec.payload!!)["runId"] as DomainJson.Value.N).raw
        val stopped = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(2, "stop", """{"runId":$runId}""")),
        )
        assertEquals("true", stopped.payload)
        // 二次 stop：如实 NOT_FOUND，不静默吞掉
        val gone = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(3, "stop", """{"runId":$runId}""")),
        )
        assertEquals("ERR_NOT_FOUND", gone.errorCode)
        // 非法 runId 载荷
        val bad = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(4, "stop", """{"runId":"x"}""")),
        )
        assertEquals("ERR_INVALID_PARAM", bad.errorCode)
    }

    @Test
    fun `poolStats 回 capacity-free-busy`() = runBlocking {
        val (h, _) = handler()
        val before = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "poolStats", null)),
        )
        assertEquals("""{"capacity":1,"free":1,"busy":0}""", before.payload)
        val exec = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(2, "exec", execPayload())),
        )
        val runId = (DomainJson.decodeObject(exec.payload!!)["runId"] as DomainJson.Value.N).raw
        val during = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(3, "poolStats", null)),
        )
        assertEquals("""{"capacity":1,"free":0,"busy":1}""", during.payload)
        h.handle(enginesReq(4, "stop", """{"runId":$runId}"""))
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `heartbeat 打到未知 runId 回 false 且不建账`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val controller = RuntimeController(FixedEnginePool({ id -> engines[id.poolIndex] }, 1))
        val h = EnginesNamespaceHandler(controller)
        val resp = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            withContext(AuthenticatedRunContext(EngineId(0), 999, 1, CapabilityMask.ALL)) { h.handle(enginesReq(1, "heartbeat", """{"runId":999,"seq":1}""")) },
        )
        assertEquals("false", resp.payload, "未知 runId → Ok false（不是调用方错误，不 4xx）")
        assertTrue(controller.heartbeats().trackedRuns().isEmpty(), "无主心跳不得建账")
    }

    @Test
    fun `heartbeat 无身份或冒用其他执行均拒绝且不刷新账本`() = runBlocking {
        val (h, controller, _) = fullRig()
        val exec = h.handle(enginesReq(1, "exec", execPayload())) as BridgeResponse.Ok
        val runId = (DomainJson.decodeObject(exec.payload!!)["runId"] as DomainJson.Value.N).raw.toLong()
        try {
            val request = enginesReq(2, "heartbeat", """{"runId":$runId,"seq":1}""")
            val anonymous = h.handle(request) as BridgeResponse.Err
            val forged = withContext(AuthenticatedRunContext(EngineId(0), runId + 1, 1, CapabilityMask.ALL)) {
                h.handle(request)
            } as BridgeResponse.Err
            assertEquals("ERR_PERMISSION_DENIED", anonymous.errorCode)
            assertEquals("ERR_PERMISSION_DENIED", forged.errorCode)
            assertTrue(controller.heartbeats().trackedRuns().isEmpty())
            // 拒绝帧不能占用合法 seq：真身份随后发同 seq 仍是第一次心跳。
            val accepted = withContext(AuthenticatedRunContext(EngineId(0), runId, 2, CapabilityMask.ALL)) {
                h.handle(request)
            } as BridgeResponse.Ok
            assertEquals("true", accepted.payload)
        } finally {
            h.handle(enginesReq(3, "stop", """{"runId":$runId}"""))
        }
    }

    @Test
    fun `heartbeat 记账到宿主账本，重复 seq 不被采纳`() = runBlocking {
        val (h, _) = handler()
        val exec = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "exec", execPayload())),
        )
        val runId = (DomainJson.decodeObject(exec.payload!!)["runId"] as DomainJson.Value.N).raw.toLong()

        val first = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            withContext(AuthenticatedRunContext(EngineId(0), runId, 1, CapabilityMask.ALL)) {
                h.handle(enginesReq(2, "heartbeat", """{"runId":$runId,"seq":5}"""))
            },
        )
        assertEquals("true", first.payload)
        val dup = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            withContext(AuthenticatedRunContext(EngineId(0), runId, 1, CapabilityMask.ALL)) {
                h.handle(enginesReq(3, "heartbeat", """{"runId":$runId,"seq":5}"""))
            },
        )
        assertEquals("false", dup.payload, "同 seq 不刷时间戳：积压帧不得让死掉的 run 装作活着")

        val bad = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(4, "heartbeat", """{"runId":$runId}""")),
        )
        assertEquals("ERR_INVALID_PARAM", bad.errorCode)
        h.handle(enginesReq(5, "stop", """{"runId":$runId}"""))
        Unit
    }

    @Test
    fun `未知方法回 ERR_NOT_IMPLEMENTED`() = runBlocking {
        val (h, _) = handler()
        val resp = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(1, "reboot", null)),
        )
        assertEquals("ERR_NOT_IMPLEMENTED", resp.errorCode)
    }

    @Test
    fun `status 在途回引擎状态名，结算后如实 NOT_FOUND 不伪造 STOPPED`() = runBlocking {
        val (h, _) = handler()
        val exec = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "exec", execPayload())),
        )
        val runId = (DomainJson.decodeObject(exec.payload!!)["runId"] as DomainJson.Value.N).raw

        val live = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(2, "status", "{\"runId\":$runId}")),
        )
        assertEquals("\"RUNNING\"", live.payload, "FakeEngine execute 后即 RUNNING（枚举名逐字，JS 字面量对齐）")

        h.handle(enginesReq(3, "stop", "{\"runId\":$runId}"))
        val gone = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(4, "status", "{\"runId\":$runId}")),
        )
        assertEquals("ERR_NOT_FOUND", gone.errorCode, "结算后无状态可读：不得伪造 STOPPED 掩盖 CRASHED")
    }

    @Test
    fun `status 未知 runId 与非法载荷`() = runBlocking {
        val (h, _) = handler()
        val gone = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(1, "status", "{\"runId\":999}")),
        )
        assertEquals("ERR_NOT_FOUND", gone.errorCode)
        val bad = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(2, "status", "{\"runId\":\"x\"}")),
        )
        assertEquals("ERR_INVALID_PARAM", bad.errorCode)
    }

    @Test
    fun `channel 建查发拉关全链路`() = runBlocking {
        val (h, _) = handler()
        // 建（复用同名）
        val created = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "channel", """{"name":"progress"}""")),
        )
        val co = DomainJson.decodeObject(created.payload!!)
        assertEquals("progress", (co["name"] as DomainJson.Value.S).v)
        val channelId = (co["channelId"] as DomainJson.Value.N).raw
        val again = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(2, "channel", """{"name":"progress"}""")),
        )
        assertEquals(created.payload, again.payload, "同名通道复用")

        // 发两条
        h.handle(enginesReq(3, "channelEmit", """{"channelId":$channelId,"event":"tick","payload":"1"}"""))
        h.handle(enginesReq(4, "channelEmit", """{"channelId":$channelId,"event":"tick"}"""))

        // 拉（游标分页）
        val page1 = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(5, "channelDrain", """{"channelId":$channelId,"sinceSeq":0,"max":1}""")),
        )
        val p1 = DomainJson.decodeObject(page1.payload!!)
        assertEquals("1", (p1["last"] as DomainJson.Value.N).raw)
        assertEquals(1, (p1["events"] as DomainJson.Value.Arr).items.size)
        val last1 = (p1["last"] as DomainJson.Value.N).raw.toLong()
        val page2 = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(6, "channelDrain", """{"channelId":$channelId,"sinceSeq":$last1}""")),
        )
        val p2 = DomainJson.decodeObject(page2.payload!!)
        assertEquals(1, (p2["events"] as DomainJson.Value.Arr).items.size)
        assertEquals("2", (p2["last"] as DomainJson.Value.N).raw)

        // 关 → 再发/拉如实 NOT_FOUND；同名重建得新 id
        val closed = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(7, "channelClose", """{"channelId":$channelId}""")),
        )
        assertEquals("true", closed.payload)
        val emitGone = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(8, "channelEmit", """{"channelId":$channelId,"event":"x"}""")),
        )
        assertEquals("ERR_NOT_FOUND", emitGone.errorCode)
        val rebuilt = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(9, "channel", """{"name":"progress"}""")),
        )
        val newId = (DomainJson.decodeObject(rebuilt.payload!!)["channelId"] as DomainJson.Value.N).raw
        assertTrue(newId != channelId, "关闭后重建得新 id")
    }

    @Test
    fun `channel 非法载荷与空名`() = runBlocking {
        val (h, _) = handler()
        for ((i, p) in listOf(null, """{"name":""}""", """{"name":1}""").withIndex()) {
            val resp = assertInstanceOf(
                BridgeResponse.Err::class.java,
                h.handle(enginesReq(20L + i, "channel", p)),
            )
            assertEquals("ERR_INVALID_PARAM", resp.errorCode)
        }
        val emitBad = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(30, "channelEmit", """{"channelId":999,"event":"x"}""")),
        )
        assertEquals("ERR_NOT_FOUND", emitBad.errorCode)
    }

    @Test
    fun `channel 有界丢最老`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, 1)
        val h = EnginesNamespaceHandler(RuntimeController(pool), channelCapacity = 2)
        val created = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "channel", """{"name":"c"}""")),
        )
        val channelId = (DomainJson.decodeObject(created.payload!!)["channelId"] as DomainJson.Value.N).raw
        repeat(4) { i ->
            h.handle(enginesReq(10L + i, "channelEmit", """{"channelId":$channelId,"event":"e$i"}"""))
        }
        val drained = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(20, "channelDrain", """{"channelId":$channelId,"sinceSeq":0}""")),
        )
        val events = (DomainJson.decodeObject(drained.payload!!)["events"] as DomainJson.Value.Arr).items
        assertEquals(2, events.size)
        val names = events.map { ((it as DomainJson.Value.Obj).fields["event"] as DomainJson.Value.S).v }
        assertEquals(listOf("e2", "e3"), names)
    }

    @Test
    fun `channel 并发 emit 不丢`() = runBlocking {
        val (h, _) = handler()
        val created = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "channel", """{"name":"cc"}""")),
        )
        val channelId = (DomainJson.decodeObject(created.payload!!)["channelId"] as DomainJson.Value.N).raw
        (1..50).map { i ->
            async {
                h.handle(enginesReq(100L + i, "channelEmit", """{"channelId":$channelId,"event":"e$i"}"""))
            }
        }.awaitAll()
        val drained = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(200, "channelDrain", """{"channelId":$channelId,"sinceSeq":0,"max":100}""")),
        )
        val events = (DomainJson.decodeObject(drained.payload!!)["events"] as DomainJson.Value.Arr).items
        assertEquals(50, events.size)
    }

    // ── A5：跨脚本目标授权（§11；判据在 CrossScriptAuthorizer，经 controller 过闸） ──

    /** 起一个在途 run（宿主发起：无认证上下文），返回其 runId。 */
    private suspend fun EnginesNamespaceHandler.liveRunId(reqId: Long = 1): Long {
        val exec = assertInstanceOf(BridgeResponse.Ok::class.java, handle(enginesReq(reqId, "exec", execPayload())))
        return (DomainJson.decodeObject(exec.payload!!)["runId"] as DomainJson.Value.N).raw.toLong()
    }

    @Test
    fun `stop 自己任何掩码都放行——窄掩码脚本必须能停自己`() = runBlocking {
        val (h, _) = handler()
        val runId = h.liveRunId()
        // 掩码只有 LOCAL_STORAGE：没有任何跨脚本位，但目标是"自己这次执行"。
        val ctx = AuthenticatedRunContext(EngineId(0), runId, 1, CapabilityMask.of(BridgeCapability.LOCAL_STORAGE))
        val resp = withContext(ctx) { h.handle(enginesReq(2, "stop", """{"runId":$runId}""")) }
        assertInstanceOf(BridgeResponse.Ok::class.java, resp)
    }

    @Test
    fun `stop 别的执行缺 CROSS_SCRIPT_CONTROL 拒绝，且不真的停掉目标`() = runBlocking {
        val (h, controller, _) = fullRig()
        val victim = h.liveRunId(1)
        // 调用方自己的 runId 是别的号（= 它想停的是 victim），掩码没有控制位。
        val ctx = AuthenticatedRunContext(EngineId(0), victim + 100, 2, CapabilityMask.of(BridgeCapability.LOCAL_STORAGE))
        val resp = withContext(ctx) { h.handle(enginesReq(2, "stop", """{"runId":$victim}""")) }
        assertEquals("ERR_PERMISSION_DENIED", assertInstanceOf(BridgeResponse.Err::class.java, resp).errorCode)
        assertTrue(controller.activeRunIds().contains(victim), "被拒的 stop 不得产生副作用")
    }

    @Test
    fun `stop 别的执行有 CROSS_SCRIPT_CONTROL 且覆盖目标掩码才放行`() = runBlocking {
        val (h, controller, _) = fullRig()
        val victim = h.liveRunId(1)
        // 目标（宿主发起、无来源元数据）拿的是 UNKNOWN_DEFAULT；调用方要停它，
        // 除了控制位还得**覆盖目标掩码**（跨脚本不得触达授权不低于自己的执行）。
        val ctx = AuthenticatedRunContext(EngineId(0), victim + 100, 2, CapabilityMask.ALL)
        val resp = withContext(ctx) { h.handle(enginesReq(2, "stop", """{"runId":$victim}""")) }
        assertInstanceOf(BridgeResponse.Ok::class.java, resp)
        assertFalse(controller.activeRunIds().contains(victim), "放行后目标应已结算")
    }

    @Test
    fun `stop 别的执行：有控制位但覆盖不了目标掩码仍拒——低信任不得停高信任`() = runBlocking {
        // 这条是「目标分档」与「只看调用方有没有控制位」的分水岭：调用方**确实**有
        // CROSS_SCRIPT_CONTROL，但它自己的掩码比目标窄（目标 = UNKNOWN_DEFAULT，含
        // ACCESSIBILITY 等它没有的面）。若只查控制位，一个低信任脚本就能停掉一个
        // 高信任执行 —— 那正是 §11「低信任不得控制高信任引擎」要挡的。
        val (h, controller, _) = fullRig()
        val victim = h.liveRunId(1)
        val ctx = AuthenticatedRunContext(
            EngineId(0), victim + 100, 2,
            CapabilityMask.of(BridgeCapability.CROSS_SCRIPT_CONTROL, BridgeCapability.LOCAL_STORAGE),
        )
        val resp = withContext(ctx) { h.handle(enginesReq(2, "stop", """{"runId":$victim}""")) }
        assertEquals(
            "ERR_PERMISSION_DENIED",
            assertInstanceOf(BridgeResponse.Err::class.java, resp).errorCode,
            "有控制位但覆盖不了目标掩码 → 仍拒",
        )
        assertTrue(controller.activeRunIds().contains(victim), "被拒的 stop 不得产生副作用")
    }

    @Test
    fun `目标授权快照缺失 fail closed——不因"查不到目标档"而放行`() {
        // 直接钉纯函数判据（不经 handler）：target == null 必须拒，而不是当作"目标无掩码"。
        // 「在不在途」不归本函数管（那是 RuntimeController.authorizeTarget 的活，见其 KDoc）。
        val authorizer = CrossScriptAuthorizer()
        val caller = AuthenticatedRunContext(EngineId(0), 100, 1, CapabilityMask.ALL)
        val denied = assertThrows(AutojsException::class.java) {
            authorizer.authorizeTarget(
                caller = caller,
                targetRunId = 7,
                required = BridgeCapability.CROSS_SCRIPT_CONTROL,
                target = null,
            )
        }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, denied.error)
        // 自我操作不受此限：目标是自己时任何掩码都放行（连快照都不需要）。
        val narrow = AuthenticatedRunContext(EngineId(0), 7, 1, CapabilityMask.of(BridgeCapability.LOCAL_STORAGE))
        authorizer.authorizeTarget(
            caller = narrow,
            targetRunId = 7,
            required = BridgeCapability.CROSS_SCRIPT_CONTROL,
            target = null,
        )
    }

    @Test
    fun `目标不在途由 controller 判 NOT_FOUND——纯函数不再拿整张在途表`() = runBlocking {
        val (h, controller, _) = fullRig()
        val victim = h.liveRunId(1)
        // 全量掩码（授权上无懈可击）也一样：不在途就是 NOT_FOUND，
        // 不给"这个号存在过"的探测面（与 status/stop 既有诚实口径一致）。
        val ctx = AuthenticatedRunContext(EngineId(0), victim + 100, 2, CapabilityMask.ALL)
        val resp = withContext(ctx) { h.handle(enginesReq(2, "status", """{"runId":99999}""")) }
        assertEquals("ERR_NOT_FOUND", assertInstanceOf(BridgeResponse.Err::class.java, resp).errorCode)
        // 直接调 controller 也同码（判据住在这里，不是 handler 里另抄一份）。
        val ex = assertThrows(AutojsException::class.java) {
            runBlocking { controller.authorizeTarget(ctx, 99999, BridgeCapability.CROSS_SCRIPT_OBSERVE) }
        }
        assertEquals(ErrorCode.ERR_NOT_FOUND, ex.error)
    }

    @Test
    fun `status 别的执行缺 CROSS_SCRIPT_OBSERVE 拒绝，看自己要观察位`() = runBlocking {
        val (h, _) = handler()
        val victim = h.liveRunId(1)
        val observer = AuthenticatedRunContext(
            EngineId(0), victim + 100, 2,
            CapabilityMask.of(BridgeCapability.LOCAL_STORAGE),
        )
        val denied = withContext(observer) { h.handle(enginesReq(2, "status", """{"runId":$victim}""")) }
        assertEquals("ERR_PERMISSION_DENIED", assertInstanceOf(BridgeResponse.Err::class.java, denied).errorCode)

        val selfCtx = AuthenticatedRunContext(
            EngineId(0), victim, 3,
            CapabilityMask.of(BridgeCapability.LOCAL_STORAGE),
        )
        val self = withContext(selfCtx) { h.handle(enginesReq(3, "status", """{"runId":$victim}""")) }
        assertInstanceOf(BridgeResponse.Ok::class.java, self)
    }

    @Test
    fun `目标不在途一律 NOT_FOUND——授权判据不泄露"这个号存在过"`() = runBlocking {
        val (h, _) = handler()
        val ctx = AuthenticatedRunContext(
            EngineId(0), 1, 1,
            CapabilityMask.ALL, // 就算掩码全量，不在途也查不到
        )
        val resp = withContext(ctx) { h.handle(enginesReq(1, "status", """{"runId":99999}""")) }
        assertEquals("ERR_NOT_FOUND", assertInstanceOf(BridgeResponse.Err::class.java, resp).errorCode)
    }

    @Test
    fun `exec 派生不得提权：子掩码超出父掩码拒绝`() = runBlocking {
        // 子掩码由 controller 的 authorization 策略算（这里显式注入"要全量"的策略）。
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val controller = RuntimeController(
            FixedEnginePool({ id -> engines[id.poolIndex] }, 1),
            authorization = ScriptAuthorizationPolicy(override = CapabilityMask.ALL),
        )
        val h = EnginesNamespaceHandler(controller)
        // 父有控制位但没有全量 → 覆盖不了子掩码 → 拒。
        val parent = AuthenticatedRunContext(
            EngineId(0), 1, 1,
            CapabilityMask.of(BridgeCapability.CROSS_SCRIPT_CONTROL, BridgeCapability.SCHEDULER_WRITE),
        )
        val resp = withContext(parent) { h.handle(enginesReq(1, "exec", execPayload())) }
        assertEquals(
            "ERR_PERMISSION_DENIED",
            assertInstanceOf(BridgeResponse.Err::class.java, resp).errorCode,
            "窄掩码不得靠 exec 一个高信任项目去拿自己本来没有的面",
        )
    }

    @Test
    fun `exec 宿主发起（无身份）不受跨脚本判据限制`() = runBlocking {
        val (h, _) = handler()
        assertInstanceOf(BridgeResponse.Ok::class.java, h.handle(enginesReq(1, "exec", execPayload())))
    }

    /**
     * `stdinIdle`（2026-10-16 用户令「答完自己退」）：脚本侧 `stdin-idle.ts` 判出
     * "这次执行的 stdin 上没有读者了"之后报上来的那一下 —— handler 把它落成
     * "关掉**调用方自己**那条执行的写端（发 EOF）"。
     *
     * 三条一起钉：**目标恒是自己**（runId 取自认证连接身份，payload 里没有这个字段）、
     * **缺身份拒绝且不碰引擎**（宿主直调/未认证不许猜它想关谁）、**关不了如实回 false
     * 而不是错误码**（脚本那边读口已经关了，这不是它的问题；与 `heartbeat` 的
     * `Ok false` 同一条"如实告知未被采纳"口径）。
     */
    @Test
    fun `stdinIdle 关的是调用方自己那条执行的写端`() = runBlocking {
        val (h, _, engines) = fullRig()
        val exec = assertInstanceOf(BridgeResponse.Ok::class.java, h.handle(enginesReq(1, "exec", execPayload())))
        val runId = (DomainJson.decodeObject(exec.payload!!)["runId"] as DomainJson.Value.N).raw.toLong()
        try {
            val resp = withContext(AuthenticatedRunContext(EngineId(0), runId, 1, CapabilityMask.ALL)) {
                h.handle(enginesReq(2, "stdinIdle", null))
            }
            assertEquals(
                "true",
                assertInstanceOf(BridgeResponse.Ok::class.java, resp).payload,
                "在途 → 关成功，如实回 true",
            )
            assertEquals(1, engines.single().closeStdinCalls, "目标恒是自己：runId 来自连接身份，不是 payload")

            engines.single().closeStdinResult = false
            val again = withContext(AuthenticatedRunContext(EngineId(0), runId, 1, CapabilityMask.ALL)) {
                h.handle(enginesReq(3, "stdinIdle", null))
            }
            assertEquals(
                "false",
                assertInstanceOf(BridgeResponse.Ok::class.java, again).payload,
                "写端已关 → Ok false（不是 4xx：脚本读口已经关了，这不是它的错）",
            )
        } finally {
            h.handle(enginesReq(4, "stop", """{"runId":$runId}"""))
        }
        Unit // 表达式体测试末尾补 Unit：否则 JUnit5 不把它当测试（本仓踩过的假绿）
    }

    @Test
    fun `stdinIdle 缺执行身份拒绝且不碰引擎`() = runBlocking {
        val (h, engines) = handler()
        val exec = assertInstanceOf(BridgeResponse.Ok::class.java, h.handle(enginesReq(1, "exec", execPayload())))
        val runId = (DomainJson.decodeObject(exec.payload!!)["runId"] as DomainJson.Value.N).raw.toLong()
        try {
            val resp = assertInstanceOf(
                BridgeResponse.Err::class.java,
                h.handle(enginesReq(2, "stdinIdle", null)),
            )
            assertEquals("ERR_PERMISSION_DENIED", resp.errorCode, "没有执行身份 → 不猜它想关谁的 stdin")
            assertEquals(0, engines.single().closeStdinCalls, "拒了就不许碰引擎")
        } finally {
            h.handle(enginesReq(3, "stop", """{"runId":$runId}"""))
        }
        Unit // 同上：表达式体测试末尾补 Unit
    }
}
