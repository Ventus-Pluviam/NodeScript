package com.autoscript.appservice.npm

import com.autoscript.domain.npm.NpmTargetScope
import com.autoscript.domain.scripts.ProjectId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class NpmPromotionConcurrencyTest {
    @TempDir
    lateinit var dir: Path
    private val layout get() = NpmProjectLayout(dir.resolve("scripts"))

    private fun coordinator(executor: HeavyOpExecutor, probe: (Path) -> Long = { 10L * 1024 * 1024 * 1024 }) = InstallCoordinator(
        services = NpmServices(layout, InstallJournal(dir.resolve(".ns")), InstallStaging(layout), cacheIndex = CacheIndex { false }),
        executor = executor, freeSpaceProbe = probe,
    )

    @Test
    fun `两个项目冲突版本并发迁移按会话串行后一个保留`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        seedPromoteTree(layout.projectRoot(ProjectId("one")), "axios" to "1.7.0")
        seedPromoteTree(layout.projectRoot(ProjectId("two")), "axios" to "2.0.0")
        val exec = PromoteExecutor { op, _ ->
            if (op.projectId == NpmTargetScope.GLOBAL) { entered.complete(Unit); release.await() }
        }
        val c = coordinator(exec)
        withTimeout(10_000) {
            val first = async { c.promoteToGlobal("one") }
            entered.await()
            val second = async { c.promoteToGlobal("two") }
            release.complete(Unit)
            first.await()
            second.await()
        }
        assertEquals(listOf(NpmTargetScope.GLOBAL, "one"), exec.calls.map { it.projectId })
        assertEquals("1.7.0", PromoteTree.read(layout.projectsRoot).installed.getValue("axios").version)
        assertTrue(Files.exists(layout.projectRoot(ProjectId("two")).resolve("node_modules/axios")))
        assertFalse(Files.exists(layout.projectRoot(ProjectId("one")).resolve("node_modules/axios")))
    }

    @Test
    fun `普通全局写入不能插入全局准备与项目卸载之间`() = runBlocking {
        seedPromoteTree(layout.projectRoot(ProjectId("demo")), "axios" to "1.7.0")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val queued = CompletableDeferred<Unit>()
        val exec = PromoteExecutor { op, _ ->
            if (op.args.first() == "install") { entered.complete(Unit); release.await() }
        }
        val c = coordinator(exec) { root ->
            if (root == layout.projectsRoot && entered.isCompleted) queued.complete(Unit)
            10L * 1024 * 1024 * 1024
        }
        withTimeout(10_000) {
            val promotion = async { c.promoteToGlobal("demo") }
            entered.await()
            val ordinary = async { c.uninstall(NpmTargetScope.GLOBAL, "axios") }
            queued.await()
            release.complete(Unit)
            promotion.await()
            ordinary.await()
        }
        assertEquals(listOf("install", "uninstall", "uninstall"), exec.calls.map { it.args.first() })
        assertEquals(listOf(NpmTargetScope.GLOBAL, "demo", NpmTargetScope.GLOBAL), exec.calls.map { it.projectId })
    }

    @Test
    fun `协程取消中的复制工作树被丢弃且释放会话供重试`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        seedPromoteTree(layout.projectRoot(ProjectId("demo")), "axios" to "1.7.0")
        var block = true
        val exec = PromoteExecutor { _, _ -> if (block) { entered.complete(Unit); wait.await() } }
        val c = coordinator(exec)
        withTimeout(10_000) {
            val task = async { c.promoteToGlobal("demo") }
            entered.await()
            task.cancelAndJoin()
            assertTrue(Files.exists(layout.projectRoot(ProjectId("demo")).resolve("node_modules/axios")))
            assertFalse(Files.exists(layout.globalNodeModules.resolve("axios")))
            block = false
            c.promoteToGlobal("demo")
        }
        assertFalse(Files.exists(layout.projectRoot(ProjectId("demo")).resolve("node_modules/axios")))
        assertTrue(InstallJournal(dir.resolve(".ns")).unfinished().isEmpty())
    }
}
