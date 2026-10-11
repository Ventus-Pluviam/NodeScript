package com.autoscript.appservice.scheduler

import com.autoscript.appservice.scheduler.core.InMemoryIntentLog
import com.autoscript.appservice.scheduler.core.InMemoryRunArchive
import com.autoscript.domain.scripts.InMemoryTaskStore
import com.autoscript.appservice.scheduler.core.RunDispatcher
import com.autoscript.appservice.scheduler.core.RunOutcome
import com.autoscript.appservice.scheduler.core.Scheduler
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.autoscript.domain.scripts.CronTab

/**
 * `workManager` 桥面验证（§8.6/§9.6 定时 API 的脚本建任务链路）：
 * JS `auto.workManager.createTimedTask` 发桥调用 → 本 handler 登记 Scheduler
 * （直写注册表）→ `cancel` 撤销 → `list` 列举。cron 表达式校验与 UI 侧 Ops
 * 同口径（校验出处都是调度器的 `CronTab.parse`，两侧各测）。
 */
class WorkManagerNamespaceHandlerTest {

    private class NoopProvider : SchedulerProvider {
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
            TriggerHandle { }
        override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
    }

    /**
     * 夹具：默认注入一个**放行**的授权判据。
     *
     * 为什么默认放行：本类主体测的是 workManager 的**方法面**（登记/取消/列举/载荷校验），
     * 与授权无关；真判据住在 `:app`（本模块 arch 门禁禁 `domain.permission`）。
     * 授权本身单独由本类的授权用例 + `:app` 的装配测试钉 —— 不在这里顺带。
     * 传 `authorizeCreate = null` 才复现"装配层没接授权缝"的 fail-closed 态。
     */
    private fun handler(
        store: InMemoryTaskStore = InMemoryTaskStore(),
        authorizeCreate: (suspend (String) -> String?)? = { null },
    ): Pair<WorkManagerNamespaceHandler, Scheduler> {
        val s = Scheduler(
            provider = NoopProvider(),
            log = InMemoryIntentLog(),
            dispatcher = RunDispatcher { RunOutcome.Succeeded },
            archive = InMemoryRunArchive(),
            taskStore = store,
        )
        return WorkManagerNamespaceHandler(s, authorizeCreate) to s
    }

    private fun req(id: Long, method: String, payload: String?) =
        BridgeRequest(id, "workManager", method, payload, 5_000)

    private suspend fun ok(h: WorkManagerNamespaceHandler, method: String, payload: String?): String? {
        val r = h.handle(req(1, method, payload))
        return assertInstanceOf(BridgeResponse.Ok::class.java, r).payload
    }

    private suspend fun errCode(h: WorkManagerNamespaceHandler, method: String, payload: String?): String {
        val r = h.handle(req(2, method, payload))
        return assertInstanceOf(BridgeResponse.Err::class.java, r).errorCode
    }

    @Test
    fun `create daily 后 list 可见，cancel 后消失（注册表直写）`() = runBlocking {
        val store = InMemoryTaskStore()
        val (h, s) = handler(store)
        val created = ok(
            h, "create",
            """{"id":"t1","name":"早安","projectId":"p","scriptPath":"a.js","schedule":{"kind":"daily","hourOfDay":8,"minuteOfHour":30}}""",
        )
        assertTrue(created!!.contains("t1"), "回包带 id：$created")
        assertEquals(listOf("t1"), s.tasks().map { it.id }, "登记进内存注册表")
        assertEquals(listOf("t1"), store.loadAll().map { it.id }, "直写持久注册表")

        val listed = ok(h, "list", null)!!
        assertTrue(listed.contains("t1") && listed.contains("daily"), "list 同形状回显：$listed")

        assertEquals("true", ok(h, "cancel", """{"id":"t1"}"""))
        assertTrue(s.tasks().isEmpty())
        assertTrue(store.loadAll().isEmpty(), "cancel 落 tombstone")
        Unit
    }

    @Test
    fun `create 缺 id 时服务端分配`() = runBlocking {
        val (h, s) = handler()
        val created = ok(
            h, "create",
            """{"name":"n","projectId":"p","scriptPath":"a.js","schedule":{"kind":"once","delaySeconds":60}}""",
        )!!
        val id = created.substringAfter("\"id\":\"").substringBefore("\"")
        assertTrue(id.isNotBlank(), "分配了 id：$created")
        assertEquals(listOf(id), s.tasks().map { it.id })
        Unit
    }

    @Test
    fun `cron 合法放行非法 INVALID_PARAM —— 与 UI 侧 Ops 同口径`() = runBlocking {
        val (h, s) = handler()
        val created = ok(
            h, "create",
            """{"id":"c1","name":"n","projectId":"p","scriptPath":"a.js","schedule":{"kind":"cron","expr":"0 9 * * 1"}}""",
        )
        assertTrue(created!!.contains("c1"), "合法 cron 登记：$created")
        assertEquals(listOf("c1"), s.tasks().map { it.id })
        val listed = ok(h, "list", null)!!
        assertTrue(listed.contains("cron") && listed.contains("0 9 * * 1"), "list 同形状回显：$listed")
        assertEquals(
            "ERR_INVALID_PARAM",
            errCode(h, "create", """{"name":"n","projectId":"p","scriptPath":"a.js","schedule":{"kind":"cron","expr":"61 9 * * *"}}"""),
            "非法 cron → INVALID_PARAM（与 Ops 侧样本同源）",
        )
        assertEquals(
            "ERR_INVALID_PARAM",
            errCode(h, "create", """{"name":"n","projectId":"p","scriptPath":"a.js","schedule":{"kind":"cron"}}"""),
            "缺 expr → INVALID_PARAM",
        )
        Unit
    }

    @Test
    fun `非法载荷 INVALID_PARAM，未知方法 NOT_IMPLEMENTED`() = runBlocking {
        val (h, _) = handler()
        assertEquals(
            "ERR_INVALID_PARAM",
            errCode(h, "create", """{"name":"n","projectId":"p"}"""),
            "缺字段 → INVALID_PARAM",
        )
        assertEquals(
            "ERR_INVALID_PARAM",
            errCode(h, "create", """{"name":"n","projectId":"p","scriptPath":"a.js","schedule":{"kind":"daily","hourOfDay":99,"minuteOfHour":0}}"""),
            "越界钟点 → INVALID_PARAM（Daily init require）",
        )
        assertEquals("ERR_INVALID_PARAM", errCode(h, "create", "not-json"), "非 JSON → INVALID_PARAM")
        assertEquals("ERR_NOT_IMPLEMENTED", errCode(h, "nope", null), "未知方法 → NOT_IMPLEMENTED")
        // cancel 幂等：从未登记的 id 照样 true
        val r = h.handle(req(9, "cancel", """{"id":"ghost"}"""))
        assertEquals("true", assertInstanceOf(BridgeResponse.Ok::class.java, r).payload)
        Unit
    }

    // ── A5：建任务的授权闸（§11 派生运行入口；判据由 :app 注入，本模块只留缝） ──

    private val createPayload =
        """{"id":"auth1","name":"n","projectId":"p","scriptPath":"a.js","schedule":{"kind":"once","delaySeconds":60}}"""

    @Test
    fun `缺授权缝时 create 全拒——fail closed，且不留下任务`() = runBlocking {
        // 装配层没注入判据 = 没有授权来源。**必须拒**：若这里默认放行，任何一次装配疏漏
        // 都会让脚本凭空获得"建持久任务"这条派生入口（§11 的越权路径）。
        val store = InMemoryTaskStore()
        val (h, s) = handler(store, authorizeCreate = null)
        assertEquals(
            "ERR_PERMISSION_DENIED",
            errCode(h, "create", createPayload),
            "缺授权缝 → 拒（不默认放行）",
        )
        assertTrue(s.tasks().isEmpty(), "被拒的 create 不得进内存注册表")
        assertTrue(store.loadAll().isEmpty(), "被拒的 create 不得落盘（不能先落盘再拒）")
        // 读面不受影响：拒的是"建"，不是整个命名空间。
        assertEquals("[]", ok(h, "list", null))
        Unit
    }

    @Test
    fun `判据回绝时 create 拒且不落盘`() = runBlocking {
        val store = InMemoryTaskStore()
        val seen = mutableListOf<String>()
        val (h, s) = handler(store) { projectId ->
            seen += projectId
            "本次执行未授权启动其他脚本（测试注入的拒绝）"
        }
        val r = h.handle(req(1, "create", createPayload))
        val err = assertInstanceOf(BridgeResponse.Err::class.java, r)
        assertEquals("ERR_PERMISSION_DENIED", err.errorCode)
        assertTrue(err.detail!!.contains("测试注入的拒绝"), "拒绝原因原样透出：${err.detail}")
        assertEquals(listOf("p"), seen, "判据拿到的是**被建任务**的项目号（将来会拿到授权的那一个）")
        assertTrue(s.tasks().isEmpty())
        assertTrue(store.loadAll().isEmpty())
        Unit
    }

    @Test
    fun `判据放行时 create 正常登记`() = runBlocking {
        val store = InMemoryTaskStore()
        val (h, s) = handler(store) { null }
        assertTrue(ok(h, "create", createPayload)!!.contains("auth1"))
        assertEquals(listOf("auth1"), s.tasks().map { it.id })
        assertEquals(listOf("auth1"), store.loadAll().map { it.id })
        Unit
    }

    @Test
    fun `闸门先于其余解析——被拒时不进解析，放行后才报载荷错`() = runBlocking {
        // 顺序纪律：projectId 是**授权判据的入参**，所以它必须先读（读不到就 INVALID_PARAM，
        // 那不算绕过 —— 没有项目号就没有可授权的对象）。但**其余**字段（schedule/name/…）
        // 的解析必须在闸门**之后**：否则一条"其余字段非法"的请求会先报载荷错，
        // 让人以为"没到授权那一步"，而实际语义应是"未授权就别谈载荷"。
        // 这里用同一条载荷（缺 schedule）配两种判据，把顺序钉死。
        val store = InMemoryTaskStore()
        val (denying, s) = handler(store) { "拒" }
        assertEquals(
            "ERR_PERMISSION_DENIED",
            errCode(denying, "create", """{"projectId":"p","name":"n"}"""),
            "闸门在先：缺 schedule 也先回授权错",
        )
        assertTrue(s.tasks().isEmpty() && store.loadAll().isEmpty())
        val (allowing, s2) = handler(store) { null }
        assertEquals(
            "ERR_INVALID_PARAM",
            errCode(allowing, "create", """{"projectId":"p","name":"n"}"""),
            "放行后才轮到载荷校验报错",
        )
        assertTrue(s2.tasks().isEmpty() && store.loadAll().isEmpty())
        Unit
    }
}
