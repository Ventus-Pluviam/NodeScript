package com.autoscript.shell

import com.autoscript.appservice.npm.NpmCliDeployer
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.scripts.InMemoryIntentStore
import com.autoscript.domain.scripts.ScriptPaths
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * vendored npm CLI 落位 + 执行体注入在 [AppShellKit.assemble] 上的接线（§10.2 调用链首段）。
 *
 * 验的是**行为**不是字段：真把 `npm.install` 打到桥上，看回来的错误码是不是
 * `ERR_NOT_IMPLEMENTED`（= 执行体没接上）。「素材齐 + 有宿主」那例必须**不是**它，
 * 否则"接线"只是一行好看的装配代码 —— 这是本文件的重点。
 *
 * 素材用内存假源（[MemSource]），不依赖宿主机 npm：素材树长什么样由
 * `AssetTreeCliSource` 自己的测试管，这里只管装配层的接线。
 */
class AppShellNpmCliTest {

    @TempDir
    lateinit var dir: Path

    private val files: Path get() = dir.resolve("files")
    private val cache: Path get() = dir.resolve("cache")

    private class NoopProvider : SchedulerProvider {
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
            TriggerHandle { }

        override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
    }

    /** 内存素材源：键 = 相对素材根的路径（`bin/npm-cli.js` 形态，与 CliSource 契约同形）。 */
    private class MemSource(paths: List<String>) : NpmCliDeployer.CliSource {
        private val tree = paths.associateWith { "// $it\n".toByteArray() }
        override fun manifest() = com.autoscript.testkit.npmManifest(tree)
        override fun list(): List<String> = tree.keys.toList()
        override fun read(relPath: String): ByteArray? = tree[relPath]
    }

    private fun assembleWith(
        source: NpmCliDeployer.CliSource?,
        nodeBin: String?,
    ): AssembledShell = AppShellKit.assemble(
        filesDir = files,
        cacheDir = cache,
        intentStore = InMemoryIntentStore(),
        schedulerProvider = NoopProvider(),
        screenGate = ScreenGate.AllowAll,
        npmCliSource = source,
        npmNodeBin = nodeBin,
    )

    /**
     * 打一次真 `npm.install`，取回 (错误码, 详情)。
     *
     * 先把项目 `package.json` 放好：真执行体 [com.autoscript.appservice.npm.HostNodeExecutor]
     * 的头一道自身前置就是它（缺了会在 exec 之前如实 ERR_INVALID_PARAM）—— 不放的话
     * 「执行体接上了没有」的判据会被这条前置挡住，测出来的是另一件事。
     */
    private fun installResult(assembled: AssembledShell): Pair<String, String> = runBlocking {
        val projectRoot = ScriptPaths.projectsRoot(files).resolve("main")
        Files.createDirectories(projectRoot)
        Files.write(projectRoot.resolve("package.json"), """{"name":"main","version":"0.0.1"}""".toByteArray())
        val r = assembled.shell.router.dispatch(
            BridgeRequest(1, "npm", "install", """{"spec":"lodash@4.17.21","timeout":30000}""", 30_000L),
        )
        val err = assertInstanceOf(BridgeResponse.Err::class.java, r, "假宿主跑不起来，安装不可能成功")
        err.errorCode to (err.detail ?: "")
    }

    @Test
    fun `无素材来源：不注入执行体，重操作如实 ERR_NOT_IMPLEMENTED`() {
        assembleWith(source = null, nodeBin = "/nonexistent/libnoden.so").use { assembled ->
            assertNull(assembled.npmCli, "没来源就不该报出落位结果")
            assertEquals("无素材来源（assets/npm 未随包）", assembled.npmCliFailure)
            val (code, _) = installResult(assembled)
            assertEquals("ERR_NOT_IMPLEMENTED", code, "没接上执行体就必须如实 NOT_IMPLEMENTED")
        }
    }

    @Test
    fun `素材齐 + 有宿主：CLI 落位且执行体真接上（不再是 NOT_IMPLEMENTED）`() {
        val src = MemSource(
            listOf(
                "bin/npm-cli.js",
                "bin/npx-cli.js",
                "lib/cli.js",
                "node_modules/@npmcli/arborist/package.json",
            ),
        )
        val fakeHost = dir.resolve("libnoden.so").toString()   // 故意不存在：证明"真去 exec 了"
        assembleWith(source = src, nodeBin = fakeHost).use { assembled ->
            assertNull(assembled.npmCliFailure, "素材齐 + 有宿主 = 不该有失败原因")
            val ready = assertInstanceOf(NpmCliDeployer.Outcome.Ready::class.java, assembled.npmCli)
            assertEquals(NpmCliDeployer.cliJsPath(files), ready.cliJs, "部署点 = cliJsPath 的拼点")
            assertTrue(Files.isRegularFile(ready.cliJs), "CLI 必须真落盘：${ready.cliJs}")
            assertTrue(
                Files.isRegularFile(ScriptPaths.projectsRoot(files).parent.resolve("npm/.cli-manifest.sha256")),
                "幂等锚（.cli-manifest.sha256）必须落盘",
            )
            val (code, detail) = installResult(assembled)
            assertNotEquals("ERR_NOT_IMPLEMENTED", code, "执行体已注入：不该再是 NOT_IMPLEMENTED")
            assertTrue(detail.contains("libnoden"), "失败原因该指向假宿主（= 真走到 exec 了）：$detail")
        }
    }

    @Test
    fun `素材齐但没宿主：CLI 照样落位，执行体不注入（不把必失败伪装成已接线）`() {
        val src = MemSource(listOf("bin/npm-cli.js", "bin/npx-cli.js"))
        assembleWith(source = src, nodeBin = null).use { assembled ->
            assertInstanceOf(
                NpmCliDeployer.Outcome.Ready::class.java,
                assembled.npmCli,
                "落位与执行体是两件事：没宿主也该把 CLI 落好（省得下次重来）",
            )
            assertTrue(Files.isRegularFile(NpmCliDeployer.cliJsPath(files)))
            val why = assembled.npmCliFailure
            assertTrue(why != null && why.contains("没有 Node 宿主"), "原因要点名缺宿主：$why")
            assertEquals("ERR_NOT_IMPLEMENTED", installResult(assembled).first)
        }
    }

    @Test
    fun `半瘫素材（缺锚）：装配不塌，原因原文点名锚文件`() {
        val src = MemSource(listOf("bin/npm-cli.js"))   // 少 npx-cli.js
        assembleWith(source = src, nodeBin = dir.resolve("libnoden.so").toString()).use { assembled ->
            assertNull(assembled.npmCli, "缺锚不得落位（半瘫 CLI 比没交付更糟）")
            val why = assembled.npmCliFailure
            assertTrue(why != null && why.contains("npx-cli.js"), "原因必须点名缺的锚：$why")
            assertTrue(!Files.exists(NpmCliDeployer.cliJsPath(files)), "缺锚绝不能让 cliJs 就位")
            assertEquals("ERR_NOT_IMPLEMENTED", installResult(assembled).first)
        }
    }
}
