package com.autoscript.bridge

import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.permission.CapabilityMask
import com.autoscript.domain.permission.ScriptAuthorizationSnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 连接级资源收口在**真实连接生命周期**上的接线（§7.5 × §9.2）。
 *
 * 这条缝要堵的病是"脚本崩了但投屏还挂着"：投屏会话是进程级单资源，脚本进程没了、
 * 连接断了、执行被停了，它都不该活着（通知栏一条"正在投屏"、`VirtualDisplay` 占着
 * 编码器，却没有任何脚本在跑）。收口点必须在连接层，且要覆盖**全部**结束方式：
 *
 * - 硬撤销（对端断开 / 宿主 `close()`）；
 * - **按连接号从外部撤销**（脚本崩了/被看门狗掐了 —— 调用方手上只有连接号）；
 * - 正常退出（EOF 排空完，脚本跑完是最常见的一种）。
 *
 * 本文件钉接入端那一侧：注册表挂在连接身份上、每条连接一份、谁先到都恰好收一次、
 * 两条连接互不牵连。注册表本身的四条契约由 `ConnectionResourceRegistryTest` 钉；
 * 会话侧怎么还资源由 `:platform:capabilities` 的用例钉。
 */
class ConnectionResourceLifecycleTest {

    private val transport = JsonTransport()

    private fun req(id: Long = 1) =
        transport.encodeRequest(BridgeRequest(id, "probe", "m", null, 5_000)) + byteArrayOf(10)

    private fun issue(registry: RunIdentityRegistry, run: Long = 1) =
        registry.issue(EngineId(0), run, "resource-probe",
            ScriptAuthorizationSnapshot(mask = CapabilityMask.ALL)).also { it.confirmSpawn(null) { true } }

    /** 每条连接各自收掉的资源次数（按连接号记账 —— 隔离与否看这个）。 */
    private val closedByConnection = ConcurrentHashMap<Long, AtomicInteger>()

    /** A11 用：按 **runId** 记账（`revokeRunResources` 的入参是 runId，调用方不认连接号）。 */
    private val closedByRun = ConcurrentHashMap<Long, AtomicInteger>()

    private fun record(ctx: AuthenticatedRunContext) {
        ctx.resources.register {
            closedByConnection.computeIfAbsent(ctx.connectionId) { AtomicInteger() }.incrementAndGet()
            closedByRun.computeIfAbsent(ctx.engineRunId) { AtomicInteger() }.incrementAndGet()
        }
    }

    private fun closed(connectionId: Long): Int = closedByConnection[connectionId]?.get() ?: 0

    private fun closedRun(runId: Long): Int = closedByRun[runId]?.get() ?: 0

    @Test
    fun `硬撤销 —— 这条连接上的资源恰好收一次`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            BridgeRouter(RequestRegistry()).use { router ->
                val entered = CompletableDeferred<Long>()
                router.register("probe") {
                    val ctx = currentCoroutineContext()[AuthenticatedRunContext]!!
                    record(ctx)
                    entered.complete(ctx.connectionId)
                    // 挂住不返回：连接在请求在途时被撤销（对端断开 / 宿主 close）。
                    awaitCancellation()
                }
                NewlineFrameServer(router, identities).use { srv ->
                    val lease = issue(identities)
                    val input = ByteArrayInputStream(BridgeHandshake.hello(lease.token) + req())
                    val job = srv.serveConnection(input, ByteArrayOutputStream(), closeConnection = input::close)
                    val connectionId = withTimeout(2_000) { entered.await() }
                    withTimeout(2_000) { job.cancel() }
                    job.join()
                    assertEquals(1, closed(connectionId), "撤销必须收掉这条连接上的资源，且只收一次")
                }
            }
        }
        Unit
    }

    @Test
    fun `按连接号从外部撤销 —— 脚本崩了或被掐了那条路也收口`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            BridgeRouter(RequestRegistry()).use { router ->
                val entered = CompletableDeferred<Long>()
                router.register("probe") {
                    val ctx = currentCoroutineContext()[AuthenticatedRunContext]!!
                    record(ctx)
                    entered.complete(ctx.connectionId)
                    awaitCancellation()
                }
                NewlineFrameServer(router, identities).use { srv ->
                    val lease = issue(identities)
                    val input = ByteArrayInputStream(BridgeHandshake.hello(lease.token) + req())
                    val job = srv.serveConnection(input, ByteArrayOutputStream(), closeConnection = input::close)
                    val connectionId = withTimeout(2_000) { entered.await() }
                    // 调用方手上只有连接号（看门狗掐执行 / 宿主停脚本）—— 资源必须当场收掉，
                    // 而不是等 socket 自己断。
                    srv.abortConnection(connectionId)
                    assertEquals(1, closed(connectionId), "按号撤销要立刻收口")
                    // 随后连接自己结束：同一份资源不许被收第二次（旧会话的迟到收口
                    // 会把新会话的资源也拆掉 —— 这正是"恰好一次"要挡的）。
                    withTimeout(2_000) { job.cancel() }
                    job.join()
                    assertEquals(1, closed(connectionId), "收口是幂等的：连接结束不许再收一遍")
                }
            }
        }
        Unit
    }

    @Test
    fun `正常退出（EOF 排空完）也收口 —— 不只在硬撤销那条路上`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            BridgeRouter(RequestRegistry()).use { router ->
                val seen = CompletableDeferred<Long>()
                router.register("probe") {
                    val ctx = currentCoroutineContext()[AuthenticatedRunContext]!!
                    record(ctx)
                    seen.complete(ctx.connectionId)
                    BridgeResponse.Ok(it.id, null)
                }
                NewlineFrameServer(router, identities).use { srv ->
                    val lease = issue(identities)
                    val out = ByteArrayOutputStream()
                    withTimeout(2_000) {
                        srv.serveConnection(ByteArrayInputStream(BridgeHandshake.hello(lease.token) + req()), out).join()
                    }
                    assertTrue(out.toString(Charsets.UTF_8).contains("\"t\":\"ok\""))
                    val connectionId = withTimeout(2_000) { seen.await() }
                    assertEquals(
                        1,
                        closed(connectionId),
                        "脚本跑完正常退出是最常见的一种结束 —— 也必须收口",
                    )
                }
            }
        }
        Unit
    }

    /**
     * A11：**按 runId** 撤销（脚本把桥 fd 继承给子进程、主进程死掉不产生 EOF 的那条路）。
     *
     * 钉的是接线本身 —— 映射装上了、按 runId 能问到那条连接的资源、且**只**收资源不关 IO
     * （连接此刻仍可能在排空尾帧，硬关会把「脚本正常跑完」误走成硬撤销）。
     */
    @Test
    fun `按 runId 撤销 —— 不依赖 socket 断`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            BridgeRouter(RequestRegistry()).use { router ->
                val entered = CompletableDeferred<Long>()
                router.register("probe") {
                    val ctx = currentCoroutineContext()[AuthenticatedRunContext]!!
                    record(ctx)
                    entered.complete(ctx.engineRunId)
                    awaitCancellation()
                }
                NewlineFrameServer(router, identities).use { srv ->
                    val lease = issue(identities, 31)
                    val input = ByteArrayInputStream(BridgeHandshake.hello(lease.token) + req())
                    val job = srv.serveConnection(input, ByteArrayOutputStream(), closeConnection = input::close)
                    val runId = withTimeout(2_000) { entered.await() }
                    // 这就是 A11 的病灶形态：脚本主进程死了、socket 没断（fd 被子进程继承了），
                    // 连接不会 abort/dispose —— 但 run 已经终结，资源必须当场收掉。
                    srv.revokeRunResources(runId)
                    assertEquals(1, closedRun(31L), "按 runId 也要收口，且只收一次")
                    // 幂等：连着收两次不许变成 2。
                    srv.revokeRunResources(runId)
                    assertEquals(1, closedRun(31L), "收口幂等")
                    // 未知 runId 是无害的 no-op，不抛。
                    srv.revokeRunResources(9999L)
                    withTimeout(2_000) { job.cancel() }
                    job.join()
                    assertEquals(1, closedRun(31L), "连接随后自己结束也不许再收一遍")
                }
            }
        }
        Unit
    }

    /** A11：run 终结后映射不得留在表里（否则撤销会扑空到一个早已结束的连接上）。 */
    @Test
    fun `连接结束后 runId 映射被摘掉 —— 不留悬挂索引`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            BridgeRouter(RequestRegistry()).use { router ->
                val seen = CompletableDeferred<Long>()
                router.register("probe") {
                    val ctx = currentCoroutineContext()[AuthenticatedRunContext]!!
                    record(ctx)
                    seen.complete(ctx.engineRunId)
                    BridgeResponse.Ok(it.id, null)
                }
                NewlineFrameServer(router, identities).use { srv ->
                    val lease = issue(identities, 41)
                    withTimeout(2_000) {
                        srv.serveConnection(
                            ByteArrayInputStream(BridgeHandshake.hello(lease.token) + req()),
                            ByteArrayOutputStream(),
                        ).join()
                    }
                    withTimeout(2_000) { seen.await() }
                    // 连接早已结束（正常退出被 dispose 收过），再按 runId 撤销不得再收一次。
                    srv.revokeRunResources(41L)
                    assertEquals(1, closedRun(41L), "表项已摘 = no-op，不会二次收口")
                }
            }
        }
        Unit
    }

    @Test
    fun `两条连接各收各的 —— 撤销一条不多收另一条`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            BridgeRouter(RequestRegistry()).use { router ->
                // run 21 的那条挂住（等外部撤销），run 22 的那条正常跑完（EOF 收口）。
                val held = CompletableDeferred<Long>()
                router.register("probe") {
                    val ctx = currentCoroutineContext()[AuthenticatedRunContext]!!
                    record(ctx)
                    if (ctx.engineRunId == 21L) {
                        held.complete(ctx.connectionId)
                        awaitCancellation()
                    }
                    BridgeResponse.Ok(it.id, null)
                }
                NewlineFrameServer(router, identities).use { srv ->
                    val a = issue(identities, 21)
                    val b = issue(identities, 22)
                    val jobA = srv.serveConnection(
                        ByteArrayInputStream(BridgeHandshake.hello(a.token) + req()),
                        ByteArrayOutputStream(),
                    )
                    val connA = withTimeout(2_000) { held.await() }
                    withTimeout(2_000) {
                        srv.serveConnection(
                            ByteArrayInputStream(BridgeHandshake.hello(b.token) + req(2)),
                            ByteArrayOutputStream(),
                        ).join()
                    }
                    srv.abortConnection(connA)
                    withTimeout(2_000) { jobA.cancel() }
                    jobA.join()

                    assertEquals(1, closed(connA), "A 自己的资源收了一次")
                    // 两条连接各一份注册表：B 的收口不该被 A 的撤销再触发一次（否则总数会是 3）。
                    assertEquals(2, closedByConnection.values.sumOf { it.get() }, "两条连接各收各的")
                }
            }
        }
        Unit
    }
}
