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
import com.autoscript.appservice.npm.LockSigner
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

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
        lockKeys: LockKeyStore.HmacKeys? = null,
        gateDeploy: (Path) -> com.autoscript.appservice.npm.NpmSpawnGate.Deploy =
            { com.autoscript.appservice.npm.NpmSpawnGate.deploy(it) },
    ): AssembledShell = AppShellKit.assemble(
        filesDir = files,
        cacheDir = cache,
        intentStore = InMemoryIntentStore(),
        schedulerProvider = NoopProvider(),
        screenGate = ScreenGate.AllowAll,
        npmCliSource = source,
        npmNodeBin = nodeBin,
        npmLockKeys = lockKeys,
        npmGateDeploy = gateDeploy,
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
            // 门禁必须同时落位：它是 P0 承诺面，落不上就不注入执行体（见下一条用例）。
            assertEquals(
                com.autoscript.appservice.npm.NpmSpawnGate.gateFile(files),
                assembled.npmSpawnGate,
                "有宿主 = 真会起安装会话 → child_process 拦截 shim 必须落位",
            )
            assertTrue(
                Files.isRegularFile(com.autoscript.appservice.npm.NpmSpawnGate.gateFile(files)),
                "shim 必须真在盘上（会话进程 --require 得到它）",
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
    fun `门禁落位失败：不注入执行体（P0 承诺面，不许静默降级）`() {
        // 零 spawn 不变量是 §10.11 的 P0 承诺：shim 落不上时"照常装"等于把守卫悄悄摘掉，
        // 而用户看到的一切正常 —— 正是 §10.12 末行那条风险本身。故 fail closed。
        val src = MemSource(listOf("bin/npm-cli.js", "bin/npx-cli.js"))
        assembleWith(
            source = src,
            nodeBin = dir.resolve("libnoden.so").toString(),
            gateDeploy = { com.autoscript.appservice.npm.NpmSpawnGate.Deploy.Failed("磁盘只读（注入用）") },
        ).use { assembled ->
            assertNull(assembled.npmSpawnGate, "落位失败就不该报出落点")
            val why = assembled.npmCliFailure
            assertTrue(
                why != null && why.contains("拦截 shim 未落位") && why.contains("磁盘只读（注入用）"),
                "原因原文要点名 shim 落位失败：$why",
            )
            assertEquals(
                "ERR_NOT_IMPLEMENTED",
                installResult(assembled).first,
                "没守卫就不起安装会话：宁可不装，不可无门禁地装",
            )
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

    // —— T2 装配接线（§10.5-1 / §11.3 第 8 条）——

    /**
     * 假 Keystore：`present` 控库里有没有，`onCreate` 控新建会不会炸。
     * 别名与字节固定 ⇒ 同一棵装配里 sign/verify 用的是同一把（下面那条负向要靠这个）。
     */
    private class MemKeys(
        private val present: SecretKey?,
        private val onCreate: (String) -> SecretKey = { error("本用例不该新建") },
    ) : LockKeyStore.HmacKeys {
        override fun load(alias: String): SecretKey? = present
        override fun create(alias: String): SecretKey = onCreate(alias)
    }

    private fun memKey(tag: String): SecretKey =
        SecretKeySpec("test-app-key-$tag-aaaaaaaaaaaaaaaa".toByteArray(), "HmacSHA256")

    /** 装项目 lockfile（ci 验签的判据文件）。 */
    private fun writeLock(projectId: String, body: String): Path {
        val root = ScriptPaths.projectsRoot(files).resolve(projectId)
        Files.createDirectories(root)
        return root.resolve("package-lock.json").also { Files.write(it, body.toByteArray()) }
    }

    private fun ciResult(assembled: AssembledShell, projectId: String): Pair<String, String> = runBlocking {
        val r = assembled.shell.router.dispatch(
            BridgeRequest(2, "npm", "ci", """{"projectId":"$projectId"}""", 30_000L),
        )
        when (r) {
            is BridgeResponse.Err -> r.errorCode to (r.detail ?: "")
            is BridgeResponse.Ok -> "OK" to (r.payload ?: "")
        }
    }

    @Test
    fun `装配给了密钥面：ci 真的走验签（无签名 lock 诚实被拒）`() {
        // 这条是本批的核心判据：装配层把 lockKey 接上之前，ci 对没有签名的 lock
        // 是直接放行的（lockSigner == null ⇒ verifyOrThrow 根本没被调）。
        // 所以「没签名 ⇒ ERR_PERMISSION_DENIED」只能是接上了才成立。
        val keys = MemKeys(present = memKey("wired"))
        assembleWith(source = null, nodeBin = null, lockKeys = keys).use { assembled ->
            assertNull(assembled.npmLockKeyFailure, "密钥面给了就不该有失败原因：${assembled.npmLockKeyFailure}")
            writeLock("main", """{"lockfileVersion":3,"packages":{}}""")
            val (code, detail) = ciResult(assembled, "main")
            assertEquals("ERR_PERMISSION_DENIED", code, "没签名的 lock 必须被 ci 拒：$detail")
            assertTrue(detail.contains("lock.sig"), "原因要点名缺的是签名文件：$detail")
        }
    }

    @Test
    fun `装配给了密钥面：签过且未改的 lock 过验签（不是一律拒）`() {
        val keys = MemKeys(present = memKey("good"))
        assembleWith(source = null, nodeBin = null, lockKeys = keys).use { assembled ->
            val lock = writeLock("main", """{"lockfileVersion":3,"packages":{}}""")
            // 用同一把钥匙替执行体收尾时那次 sign（生产是 install 收尾写的）。
            LockSigner(files.resolve(".autojs"), LockKeyStore.resolve(keys)).sign("main", lock)
            val (code, detail) = ciResult(assembled, "main")
            // 验签过了 → 进到重操作入队；没有执行体 → 如实 ERR_NOT_IMPLEMENTED。
            assertEquals("ERR_NOT_IMPLEMENTED", code, "验签已过，卡在没执行体上才是对的：$detail")
            assertTrue(!detail.contains("lock.sig"), "验签已过就不该再提签名：$detail")
        }
    }

    @Test
    fun `装配没给密钥面：ci 仍直接放行（缺省诚实 = 不假装验过，且不谎称已接线）`() {
        assembleWith(source = null, nodeBin = null).use { assembled ->
            assertNull(assembled.npmLockKeyFailure, "没给密钥面 = 本来就没接，不算失败")
            writeLock("main", """{"lockfileVersion":3,"packages":{}}""")
            val (code, _) = ciResult(assembled, "main")
            assertEquals("ERR_NOT_IMPLEMENTED", code, "没装 lock 防线时验签不发生，直接走到没执行体")
        }
    }

    @Test
    fun `取钥失败：装配照过，防线原因原文进 npmLockKeyFailure（不掀翻整个壳）`() {
        val keys = MemKeys(present = null, onCreate = { throw IllegalStateException("Keystore 锁屏不可用") })
        assembleWith(source = null, nodeBin = null, lockKeys = keys).use { assembled ->
            val why = assembled.npmLockKeyFailure
            assertTrue(why != null && why.contains("锁屏"), "原因原文要点名取不动的原因：$why")
            writeLock("main", """{"lockfileVersion":3,"packages":{}}""")
            val (code, _) = ciResult(assembled, "main")
            assertEquals("ERR_NOT_IMPLEMENTED", code, "取钥失败那一档 = 本次不装 lock 防线（不是把 ci 全拒）")
        }
    }

    @Test
    fun `锁被换过：跨项目搬来的签名验不过（键绑 projectId 这条在装配面上活着）`() {
        val keys = MemKeys(present = memKey("bound"))
        assembleWith(source = null, nodeBin = null, lockKeys = keys).use { assembled ->
            val signer = LockSigner(files.resolve(".autojs"), LockKeyStore.resolve(keys))
            val lock = writeLock("main", """{"lockfileVersion":3,"packages":{}}""")
            signer.sign("main", lock)
            // 换个项目号重建同样的 lock：签名是给 "main" 的。
            writeLock("other", """{"lockfileVersion":3,"packages":{}}""")
            val (code, detail) = ciResult(assembled, "other")
            assertEquals("ERR_PERMISSION_DENIED", code, "跨项目搬锁必须被拒：$detail")
            assertTrue(detail.contains("跨项目"), "原因要点名跨项目：$detail")
        }
    }
}
