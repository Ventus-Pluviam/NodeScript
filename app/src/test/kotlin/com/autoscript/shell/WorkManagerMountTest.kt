package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.InMemoryIntentLog
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.permission.TrustTierMasks
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * `workManager` 的**装配层恒挂载**验证（§12.2 接线现状表）——
 * 2026-09-30 审查步骤 6：handler 本体迁 `:app-service:scheduler` 后，挂载缝回到
 * `:app` 侧测（scheduler 模块见不到 `AppShell`/引擎假件，那半测不了）：
 * `AppShell.assemble` 不经注入束就把 `workManager` 挂上 Router，建任务即达。
 * handler 的方法面/错误分类测试随迁 `:app-service:scheduler`
 * （`WorkManagerNamespaceHandlerTest`）。
 */
class WorkManagerMountTest {

    private class NoopProvider : SchedulerProvider {
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
            TriggerHandle { }
        override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
    }

    @Test
    fun `装配壳恒挂 workManager：无需注入即可建任务`() = runBlocking {
        val shell = AppShell.assemble(
            engineFactory = { id, _ -> FakeEngineForDispatcher(id) },
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

    /**
     * §11 派生运行入口的**自建 / 跨脚本二分**（批 78）：`workManager.create` 要过
     * 装配层那道授权闸（[AppShell] 的 `authorizeCreate`）。判据不是一刀切要求跨脚本位，
     * 而是「caller 的项目号（由认证点从 lease 装填，不可自报）与目标项目号相同 → 自建，放行；
     * 不同 → 走跨脚本全套」。缺来源档（`UNKNOWN_DEFAULT`，无 `CROSS_SCRIPT_CONTROL`）的脚本
     * 建**自己**的任务必须成功 —— `workManager` 是 §14 P0 用户故事明写的闭环之一。
     */
    @Test
    fun `缺来源档脚本能建自己的任务，替别人建仍拒`() = runBlocking {
        val shell = AppShell.assemble(
            engineFactory = { id, _ -> FakeEngineForDispatcher(id) },
            schedulerProvider = NoopProvider(),
            intentLog = InMemoryIntentLog(),
        )
        shell.use {
            val ctx = AuthenticatedRunContext(
                EngineId(0), 7, 42, TrustTierMasks.UNKNOWN_DEFAULT,
            ).apply { projectId = "own" }
            suspend fun create(id: Long, project: String): BridgeResponse =
                withContext(ctx) {
                    shell.router.dispatch(
                        BridgeRequest(
                            id, "workManager", "create",
                            """{"id":"w$id","name":"n","projectId":"$project","scriptPath":"a.js","schedule":{"kind":"once","delaySeconds":60}}""",
                            5_000,
                        ),
                    )
                }

            // (a) 自建 → 放行，且真的落进注册表
            assertInstanceOf(BridgeResponse.Ok::class.java, create(2L, "own"))
            assertEquals(listOf("w2"), shell.scheduler.tasks().map { it.id })

            // (b) 替别人建 → 仍拒（缺 CROSS_SCRIPT_CONTROL），且**不落盘**
            val denied = assertInstanceOf(
                BridgeResponse.Err::class.java, create(3L, "other"),
            )
            assertEquals("ERR_PERMISSION_DENIED", denied.errorCode)
            assertEquals(listOf("w2"), shell.scheduler.tasks().map { it.id }, "被拒的建任务不得留下记录")

            // (c) projectId 未知（空串）→ 不套默认项目名，走跨脚本那条（拒）
            val unknown = AuthenticatedRunContext(
                EngineId(0), 7, 43, TrustTierMasks.UNKNOWN_DEFAULT,
            )
            val blank = withContext(unknown) {
                shell.router.dispatch(
                    BridgeRequest(
                        4, "workManager", "create",
                        """{"id":"w4","name":"n","projectId":"","scriptPath":"a.js","schedule":{"kind":"once","delaySeconds":60}}""",
                        5_000,
                    ),
                )
            }
            // 关键是**拒**，不是拒在哪个码上：空 projectId 过不了 handler 的参数校验
            // （`ERR_INVALID_PARAM`），也过不了授权闸（`ERR_PERMISSION_DENIED`）。这里只钉
            // 「没被当成自建放行」—— 若哪天有人把它写成默认项目名，这条会红。
            assertInstanceOf(
                BridgeResponse.Err::class.java,
                blank,
                "caller.projectId 空串（未知）不得被当成自建放行",
            )
            assertEquals(listOf("w2"), shell.scheduler.tasks().map { it.id }, "空 projectId 也不落盘")
        }
        Unit
    }
}
