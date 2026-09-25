package com.autoscript.shell

import com.autoscript.appservice.packager.NpmShellKit
import com.autoscript.appservice.packager.npm.HostNodeExecutor
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.scripts.ScriptPaths
import com.autoscript.platform.capabilities.CapabilityNamespaces
import com.autoscript.platform.capabilities.InMemoryUiTree
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * P0 回环（docs §19 切片 3+4 的最小闭合，一次装配走完全链）：
 * `npm.install axios`（真 heavy 执行体：宿主机 node 跑 vendored npm-cli.js，经
 * InstallCoordinator 事务链落位）→ `npm.list` 见包 → `a11y.findOne` 命中假树节点 →
 * `a11y.click` 为真 → console 落行经装配收集器可读 → `engines.poolStats` 在位。
 *
 * 诚实边界（每一处"假的"都写明，不按通过报）：
 * - a11y 是 [InMemoryUiTree] 假树（真 a11y 要 Android 服务）：走的是真
 *   `CapabilityNamespaces.a11y` 转接 + 真 `A11yNamespaceHandler` 逻辑（选择器/句柄/
 *   generation），只换 SPI 实现；
 * - `registryVerifier = null`：跳过多镜像交叉校验（那一路是纯 JVM + 双 HTTP，
 *   由 `NpmRegistryVerifierTest`/Coordinator 单测覆盖；回环只证"装得上"）；
 * - engines 槽只验 `poolStats` 可达（缺省 `UnavailableEngine` 如实 CRASHED ——
 *   脚本不跑，不断言执行）；
 * - 宿主机无 node/npm-cli.js 时 `assumeTrue` 自跳（CI 另经 `-PskipNpmE2E` 排除，
 *   见 `:app` 的 `build.gradle.kts`，与 `:app-service:packager` 的
 *   `HostNodeNpmE2ETest` 同一条纪律）。
 *
 * 桥 TTL 300s：`install` 在 Router 里是同步重操作（`enqueueHeavy` 内联执行，
 * 回 Ok 时事务已 commit），npm 装 axios 约数十秒，TTL 必须盖住它 ——
 * 小 TTL 在这里不是"严格"是"必红"（`withTimeout` 切出的 ERR_TIMEOUT 是误报）。
 */
class P0LoopbackTest {

    @TempDir
    lateinit var dir: Path

    companion object {
        private val npmCli: Path? = sequenceOf(
            "/usr/lib/node_modules/npm/bin/npm-cli.js",
            "/usr/local/lib/node_modules/npm/bin/npm-cli.js",
        ).map { Path.of(it) }.firstOrNull { Files.isRegularFile(it) }
    }

    private val files: Path get() = dir.resolve("files")
    private val cache: Path get() = dir.resolve("cache")

    private val provider = object : SchedulerProvider {
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
            TriggerHandle { }
        override suspend fun cancelTrigger(handle: TriggerHandle) = Unit
    }

    private fun kit(tree: InMemoryUiTree): AppShellKit.AssembledShell = AppShellKit.assemble(
        filesDir = files,
        cacheDir = cache,
        schedulerProvider = provider,
        screenGate = ScreenGate.AllowAll,
        a11yHandler = CapabilityNamespaces.a11y(tree, tree),
        npmHandler = NpmShellKit.assembleHandler(
            filesDir = files,
            cacheDir = cache,
            executor = HostNodeExecutor(npmCli!!, cache.resolve("npm-cache")),
            registryVerifier = null,
        ),
    )

    private fun parseRef(payload: String): Pair<Long, Long> {
        val id = Regex(""""refId":(\d+)""").find(payload)?.groupValues?.get(1)?.toLong()
            ?: throw AssertionError("findOne 回包无 refId：$payload")
        val gen = Regex(""""generation":(\d+)""").find(payload)?.groupValues?.get(1)?.toLong()
            ?: throw AssertionError("findOne 回包无 generation：$payload")
        return id to gen
    }

    @Test
    fun `P0 回环：装 axios → 读 UI 树 → 点节点 → 看 console`() = runBlocking {
        assumeTrue(npmCli != null, "宿主机无 npm-cli.js，跳过 P0 回环（与 HostNodeNpmE2ETest 同口径）")

        // 世界先行：项目 package.json（HostNodeExecutor.prepareWorkDir 的前置）+ 假树节点。
        val projectRoot = ScriptPaths.projectsRoot(files).resolve("p0")
        Files.createDirectories(projectRoot)
        Files.write(projectRoot.resolve("package.json"), """{"name":"p0","version":"0.0.1"}""".toByteArray())
        val tree = InMemoryUiTree()
        tree.add(InMemoryUiTree.Attrs(text = "确定", clickable = true))

        kit(tree).use { assembled ->
            // 1) 装 axios（真安装：npmmirror 拉包 → journal begin/commit → 原子落位）。
            val install = assembled.shell.router.dispatch(
                BridgeRequest(
                    1, "npm", "install",
                    """{"projectId":"p0","spec":"axios@1.20.0","timeout":300000}""",
                    300_000L,
                ),
            )
            val installed = assertInstanceOf(BridgeResponse.Ok::class.java, install, "npm.install axios 必须成功")
            assertTrue(installed.payload!!.contains("p0"), "句柄回包点名项目：${installed.payload}")

            // 2) 产物落位（stage → node_modules 原子提升 + lock 写回项目根）。
            assertTrue(
                Files.isDirectory(projectRoot.resolve("node_modules/axios")),
                "node_modules/axios 必须落盘",
            )
            assertTrue(Files.exists(projectRoot.resolve("package-lock.json")), "package-lock.json 必须写回项目根")

            // 3) 轻操作直读 lockfile：装完即可见（同一事务的读侧）。
            val list = assembled.shell.router.dispatch(
                BridgeRequest(2, "npm", "list", """{"projectId":"p0"}""", 10_000L),
            )
            val listed = assertInstanceOf(BridgeResponse.Ok::class.java, list)
            assertTrue(
                listed.payload!!.contains("axios") && listed.payload!!.contains("1.20.0"),
                "list 必须见 axios@1.20.0：${listed.payload}",
            )

            // 4) 读 UI 树（真 handler 逻辑 + 假树 SPI）。
            val found = assembled.shell.router.dispatch(
                BridgeRequest(3, "a11y", "findOne", """{"conditions":{"text":"确定"}}""", 10_000L),
            )
            val foundOk = assertInstanceOf(BridgeResponse.Ok::class.java, found, "findOne 必须命中假树节点")
            val (refId, gen) = parseRef(foundOk.payload!!)

            // 5) 点节点（句柄回灌 handler，generation 对得上才真点）。
            val clicked = assembled.shell.router.dispatch(
                BridgeRequest(
                    4, "a11y", "click",
                    """{"ref":{"refId":$refId,"generation":$gen}}""",
                    10_000L,
                ),
            )
            assertEquals(
                "true",
                assertInstanceOf(BridgeResponse.Ok::class.java, clicked).payload,
                "click 可点节点必须回 true",
            )

            // 6) 看 console（壳持有的收集器 + 装配读口，不是另开一份）。
            assembled.shell.console.append(runId = 0, level = "log", text = "p0-loopback-axios")
            val view = assembled.consoleView(sinceSeq = 0, maxLines = 100)
            assertTrue(view.lines.any { it.text == "p0-loopback-axios" }, "console 必须读到回环标记行")

            // 7) engines 槽在位（只验形状，不断言执行 —— 缺省 UnavailableEngine 诚实 CRASHED）。
            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                assembled.shell.router.dispatch(BridgeRequest(5, "engines", "poolStats", null, 10_000L)),
                "engines.poolStats 应可达",
            )
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }
}
