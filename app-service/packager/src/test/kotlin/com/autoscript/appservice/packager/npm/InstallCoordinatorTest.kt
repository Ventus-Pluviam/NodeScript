package com.autoscript.appservice.packager.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.npm.ApprovalAction
import com.autoscript.domain.npm.ApprovalDecision
import com.autoscript.domain.npm.ApprovalStatus
import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.InstallFlags
import com.autoscript.domain.npm.PackageSpec
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InstallCoordinatorTest {

    @TempDir
    lateinit var dir: Path

    private val layout get() = NpmProjectLayout(dir.resolve("scripts"))
    private val journal get() = InstallJournal(dir.resolve(".autojs"))
    private val staging get() = InstallStaging(layout)
    private val ledger get() = ApprovalLedger()

    /** 假执行体：在暂存目录写包内容，模拟 reify 产物。 */
    private class FakeExecutor(
        val block: suspend (InstallCoordinator.HeavyOp) -> Unit = {},
    ) : InstallCoordinator.HeavyOpExecutor {
        val calls = mutableListOf<InstallCoordinator.HeavyOp>()
        override suspend fun execute(op: InstallCoordinator.HeavyOp, sink: InstallCoordinator.ProgressSink): String {
            calls += op
            block(op)
            return "ok:${op.args.first()}"
        }
    }

    private fun newHistory() = InstallHistory(dir.resolve(".autojs"))

    private fun coordinator(
        executor: InstallCoordinator.HeavyOpExecutor = FakeExecutor(),
        free: Long = 10L * 1024 * 1024 * 1024,
        cache: CacheIndex = CacheIndex { false },
        ledger: ApprovalLedger = ApprovalLedger(),
        history: InstallHistory? = newHistory(),
    ) = InstallCoordinator(
        layout = layout,
        journal = journal,
        staging = staging,
        ledger = ledger,
        cacheIndex = cache,
        executor = executor,
        freeSpaceProbe = { free },
        history = history,
    )

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
            Files.write(op.stageDir.resolve("axios.js"), "console.log(1)".toByteArray())
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
        Files.write(layout.nodeModules("p1").resolve("old.js"), "old".toByteArray())
        val exec = FakeExecutor { throw RuntimeException("registry 502") }
        val c = coordinator(executor = exec)

        assertThrows(RuntimeException::class.java) {
            runBlocking { c.install("p1", listOf(PackageSpec("axios"))) }
        }
        assertEquals(InstallJournal.State.FAIL, journal.all().last().state)
        assertTrue(journal.unfinished().isEmpty(), "fail 封口后不再是未完成事务")
        // 旧 node_modules 原样保留（墓碑回滚）
        assertEquals("old", Files.readString(layout.nodeModules("p1").resolve("old.js")))
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

    // ═══ 审计史（§10.2 install-history） ═══

    @Test
    fun `带 install 脚本的包装完发 SCRIPTS_SKIPPED 显式警告（禁止静默）`() = runBlocking {
        // T0 契约全程 --ignore-scripts：脚本必然没跑，必须显式告知而不是假装无事
        val exec = FakeExecutor { op ->
            val pkg = op.stageDir.resolve("esbuild")
            Files.createDirectories(pkg)
            Files.writeString(pkg.resolve("package.json"), """{"name":"esbuild","version":"0.19.0","scripts":{"postinstall":"node install.js"}}""")
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
            Files.writeString(pkg.resolve("package.json"), """{"name":"lodash","version":"4.17.21","scripts":{"test":"echo x"}}""")
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
            Files.write(op.stageDir.resolve("axios.js"), "console.log(1)".toByteArray())
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
    fun `registry 变更入史（可审计）`() = runBlocking {
        val h = newHistory()
        coordinator(history = h).config("p1", com.autoscript.domain.npm.NpmConfigKey.REGISTRY, "https://registry.npmjs.org")
        val e = h.all().single()
        assertEquals(InstallHistory.Op.REGISTRY, e.op)
        assertTrue(e.detail?.contains("registry=https://registry.npmjs.org") == true, "明细须含键值：${e.detail}")
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

    @Test
    fun `runScript 与 exec 未审批一律拒绝（诚实拒绝优于静默放行）`() = runBlocking {
        val c = coordinator()
        val ex = assertThrows(AutojsException::class.java) {
            runBlocking { c.runScript("p1", "postinstall") }
        }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, ex.error)
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
    fun `storage 遍历算尺寸`() = runBlocking {
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

    // —— helpers ——

    private fun writeLock(projectId: String, content: String) {
        val f = layout.lockfile(projectId)
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
    }

    @Suppress("UNCHECKED_CAST")
    private fun cHandles(c: InstallCoordinator): Map<String, Any> {
        val f = InstallCoordinator::class.java.getDeclaredField("handles")
        f.isAccessible = true
        return f.get(c) as Map<String, Any>
    }
}
