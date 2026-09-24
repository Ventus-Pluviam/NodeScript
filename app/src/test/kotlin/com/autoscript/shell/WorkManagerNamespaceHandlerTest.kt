package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.InMemoryIntentLog
import com.autoscript.appservice.scheduler.core.InMemoryRunArchive
import com.autoscript.appservice.scheduler.core.InMemoryTaskStore
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

    private fun handler(store: InMemoryTaskStore = InMemoryTaskStore()): Pair<WorkManagerNamespaceHandler, Scheduler> {
        val s = Scheduler(
            provider = NoopProvider(),
            log = InMemoryIntentLog(),
            dispatcher = RunDispatcher { RunOutcome.Succeeded },
            archive = InMemoryRunArchive(),
            taskStore = store,
        )
        return WorkManagerNamespaceHandler(s) to s
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

    @Test
    fun `装配壳恒挂 workManager：无需注入即可建任务`() = runBlocking {
        val shell = AppShell.assemble(
            engineFactory = { id -> FakeEngineForDispatcher(id) },
            schedulerProvider = NoopProvider(),
            intentLog = InMemoryIntentLog(),
        )
        shell.use {
            val r = shell.router.dispatch(
                BridgeRequest(
                    1, "workManager", "create",
                    """{"id":"w1","name":"n","projectId":"p","scriptPath":"a.js","schedule":{"kind":"once","delaySeconds":60}}""",
                    5_000,
                ),
            )
            assertInstanceOf(BridgeResponse.Ok::class.java, r)
            assertEquals(listOf("w1"), shell.scheduler.tasks().map { it.id })
        }
        Unit
    }
}
