package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.NpmTargetScope
import com.autoscript.domain.scripts.ProjectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.spec.SecretKeySpec

class NpmPromotionTest {
    @TempDir
    lateinit var dir: Path
    private val layout get() = NpmProjectLayout(dir.resolve("scripts"))
    private val project get() = layout.projectRoot(ProjectId("demo"))

    private fun coordinator(
        executor: HeavyOpExecutor,
        free: Long = 10L * 1024 * 1024 * 1024,
        verifier: RegistryVerifier? = null,
        signer: LockSigner? = null,
    ) = InstallCoordinator(
        services = NpmServices(
            layout = layout,
            journal = InstallJournal(dir.resolve(".ns")),
            staging = InstallStaging(layout),
            cacheIndex = CacheIndex { false },
            registryVerifier = verifier,
            lockSigner = signer,
        ),
        executor = executor,
        freeSpaceProbe = { free },
    )

    @Test
    fun `缺包先按精确版本安装并声明全局再移除项目真树`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0", "@scope/tool" to "3.1.0")
        val exec = PromoteExecutor()
        val c = coordinator(exec)
        c.promoteToGlobal("demo")

        assertEquals(listOf(NpmTargetScope.GLOBAL, "demo"), exec.calls.map { it.projectId })
        val install = exec.calls.first().args
        assertTrue(install.containsAll(listOf("--save-exact", "--save", "axios@1.7.0", "@scope/tool@3.1.0")))
        assertFalse(install.any { '^' in it })
        assertEquals(listOf("uninstall", "--save", "--package-lock", "@scope/tool", "axios"), exec.calls.last().args)
        assertTrue(c.list("demo").isEmpty())
        assertEquals(setOf("axios", "@scope/tool"), c.list(NpmTargetScope.GLOBAL).map { it.name }.toSet())
        assertFalse(Files.exists(project.resolve("node_modules/axios")))
        assertFalse(Files.exists(project.resolve("node_modules/@scope/tool")))
        assertTrue(Files.exists(layout.globalNodeModules.resolve("axios/package.json")))
        assertTrue(InstallJournal(dir.resolve(".ns")).unfinished().isEmpty())
    }

    @Test
    fun `同版复用异版不覆盖并保留项目声明锁和物化包`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0", "left-pad" to "1.3.0")
        seedPromoteTree(layout.projectsRoot, "axios" to "1.7.0", "left-pad" to "2.0.0")
        val exec = PromoteExecutor()
        val c = coordinator(exec)
        c.promoteToGlobal("demo")

        assertEquals(listOf("demo"), exec.calls.map { it.projectId }, "同版不必重新安装")
        assertEquals(listOf("left-pad"), c.list("demo").map { it.name })
        assertEquals("1.3.0", PromoteTree.read(project).installed.getValue("left-pad").version)
        assertEquals("2.0.0", PromoteTree.read(layout.projectsRoot).installed.getValue("left-pad").version)
    }

    @Test
    fun `全局同版传递包先登记为直接依赖避免下次 prune 丢失`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0")
        seedPromoteTree(layout.projectsRoot, "axios" to "1.7.0")
        writeFixtureJson(layout.projectsRoot.resolve("package.json"), emptyMap())
        val exec = PromoteExecutor()
        coordinator(exec).promoteToGlobal("demo")
        assertEquals(2, exec.calls.size)
        assertTrue(PromoteTree.read(layout.projectsRoot).registryDeclaration("axios"))
        assertFalse(Files.exists(project.resolve("node_modules/axios")))
    }

    @Test
    fun `全局同名包缺失时补物化而不是仅信锁清单`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0")
        seedPromoteTree(layout.projectsRoot, "axios" to "1.7.0")
        layout.globalNodeModules.resolve("axios").toFile().deleteRecursively()
        val exec = PromoteExecutor()
        coordinator(exec).promoteToGlobal("demo")
        assertEquals(NpmTargetScope.GLOBAL, exec.calls.first().projectId)
        assertTrue(Files.exists(layout.globalNodeModules.resolve("axios/package.json")))
        assertFalse(Files.exists(project.resolve("node_modules/axios")))
    }

    @Test
    fun `全局安装失败或错版时项目清单锁与树原样保留`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0")
        val before = Files.readString(project.resolve("package.json"))
        val lock = Files.readString(project.resolve("package-lock.json"))
        val exec = PromoteExecutor { _, work -> writeFixturePackage(work, "axios", "9.0.0") }
        assertThrows(AutojsException::class.java) { runBlocking { coordinator(exec).promoteToGlobal("demo") } }
        assertEquals(listOf(NpmTargetScope.GLOBAL), exec.calls.map { it.projectId })
        assertEquals(before, Files.readString(project.resolve("package.json")))
        assertEquals(lock, Files.readString(project.resolve("package-lock.json")))
        assertEquals("1.7.0", PromoteTree.read(project).installed.getValue("axios").version)
        assertFalse(Files.exists(layout.globalNodeModules.resolve("axios")), "坏工作树不落位")
    }

    @Test
    fun `卸载异常不回报迁移完成且项目仍有原包`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0")
        val exec = PromoteExecutor { op, _ -> if (op.projectId == "demo") error("uninstall failed") }
        val c = coordinator(exec)
        val fail = assertThrows(IllegalStateException::class.java) { runBlocking { c.promoteToGlobal("demo") } }
        assertEquals("uninstall failed", fail.message)
        assertTrue(Files.exists(layout.globalNodeModules.resolve("axios/package.json")), "已成功的全局安装如实保留")
        assertEquals("1.7.0", PromoteTree.read(project).installed.getValue("axios").version)
        assertFalse(c.consoleOutput("demo", 0, 64).lines.any { it.line.text.startsWith("兼容依赖迁移完成") })
    }

    @Test
    fun `卸载后仍需顶层包则拒绝落位而不是强删传递依赖`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0")
        seedPromoteTree(layout.projectsRoot, "axios" to "1.7.0")
        val exec = PromoteExecutor { _, work -> writeFixturePackage(work, "axios", "1.7.0") }
        assertThrows(AutojsException::class.java) { runBlocking { coordinator(exec).promoteToGlobal("demo") } }
        assertTrue(PromoteTree.read(project).registryDeclaration("axios"))
        assertTrue(Files.exists(project.resolve("node_modules/axios")))
    }

    @Test
    fun `全局安装不得顺带升级已有包`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0")
        seedPromoteTree(layout.projectsRoot, "left-pad" to "1.3.0")
        val exec = PromoteExecutor { _, work -> writeFixturePackage(work, "left-pad", "2.0.0") }
        assertThrows(AutojsException::class.java) { runBlocking { coordinator(exec).promoteToGlobal("demo") } }
        assertEquals("1.3.0", PromoteTree.read(layout.projectsRoot).installed.getValue("left-pad").version)
        assertEquals("1.7.0", PromoteTree.read(project).installed.getValue("axios").version)
    }

    @Test
    fun `项目卸载不能顺带改变冲突保留项`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0", "left-pad" to "1.3.0")
        seedPromoteTree(layout.projectsRoot, "axios" to "1.7.0", "left-pad" to "2.0.0")
        val exec = PromoteExecutor { _, work -> writeFixturePackage(work, "left-pad", "2.0.0") }
        assertThrows(AutojsException::class.java) { runBlocking { coordinator(exec).promoteToGlobal("demo") } }
        assertEquals(setOf("axios", "left-pad"), PromoteTree.read(project).directNames)
        assertEquals("1.3.0", PromoteTree.read(project).installed.getValue("left-pad").version)
    }

    @Test
    fun `坏锁或项目错版在安装和卸载之前拒绝`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0")
        val exec = PromoteExecutor()
        Files.writeString(project.resolve("package-lock.json"), "{broken")
        assertThrows(AutojsException::class.java) { runBlocking { coordinator(exec).promoteToGlobal("demo") } }
        seedPromoteTree(project, "axios" to "1.7.0")
        writeFixturePackage(project, "axios", "9.0.0")
        assertThrows(AutojsException::class.java) { runBlocking { coordinator(exec).promoteToGlobal("demo") } }
        assertTrue(exec.calls.isEmpty())
    }

    @Test
    fun `全局无锁但已有包不能当空屋覆盖`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0")
        writeFixturePackage(layout.projectsRoot, "axios", "2.0.0")
        val exec = PromoteExecutor()
        assertThrows(AutojsException::class.java) { runBlocking { coordinator(exec).promoteToGlobal("demo") } }
        assertTrue(exec.calls.isEmpty())
    }

    @Test
    fun `仅空骨架的全局可以开始迁移`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0")
        Files.writeString(layout.projectsRoot.resolve("package.json"), ProjectManifest.minimal(""))
        val exec = PromoteExecutor()
        coordinator(exec).promoteToGlobal("demo")
        assertEquals(2, exec.calls.size)
    }

    @Test
    fun `磁盘拒绝和镜像分歧都不能进入卸载`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0")
        val exec = PromoteExecutor()
        val disk = assertThrows(AutojsException::class.java) { runBlocking { coordinator(exec, free = 0).promoteToGlobal("demo") } }
        assertEquals(ErrorCode.ERR_DISK_FULL, disk.error)
        val verifier = RegistryVerifier { name, _, _ ->
            NpmRegistryVerifier.Verdict.Disagreed(
                name, "1.7.0", NpmRegistryVerifier.Resolved("1.7.0", "sha512-a", null),
                NpmRegistryVerifier.Resolved("1.7.0", "sha512-b", null), "integrity mismatch",
            )
        }
        val registry = assertThrows(AutojsException::class.java) {
            runBlocking { coordinator(exec, verifier = verifier).promoteToGlobal("demo") }
        }
        assertEquals(ErrorCode.ERR_REGISTRY_UNAVAILABLE, registry.error)
        assertTrue(exec.calls.isEmpty())
        assertTrue(Files.exists(project.resolve("node_modules/axios")))
    }

    @Test
    fun `全局签名失败不继续删项目`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0")
        val signer = LockSigner(dir.resolve(".ns"), LockSigner.KeyProvider { error("key unavailable") })
        val exec = PromoteExecutor()
        val failure = assertThrows(AutojsException::class.java) { runBlocking { coordinator(exec, signer = signer).promoteToGlobal("demo") } }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, failure.error)
        assertEquals(1, exec.calls.size)
        assertTrue(Files.exists(project.resolve("node_modules/axios")))
    }

    @Test
    fun `取消原样传播且取消的全局工作树不提交`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0")
        val exec = PromoteExecutor { _, _ -> throw CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) { runBlocking { coordinator(exec).promoteToGlobal("demo") } }
        assertEquals(1, exec.calls.size)
        assertTrue(Files.exists(project.resolve("node_modules/axios")))
        assertFalse(Files.exists(layout.globalNodeModules.resolve("axios")))
    }

    @Test
    fun `全局签名通过后才卸载项目并签最后提交的项目锁`() = runBlocking {
        seedPromoteTree(project, "axios" to "1.7.0")
        val signer = LockSigner(dir.resolve(".ns"), LockSigner.KeyProvider { SecretKeySpec(ByteArray(32) { 1 }, "HmacSHA256") })
        var checkedGlobal = false
        val exec = PromoteExecutor { op, _ ->
            if (op.projectId == "demo") {
                signer.verifyOrThrow(NpmTargetScope.GLOBAL, layout.globalLockfile)
                checkedGlobal = true
            }
        }
        val c = coordinator(exec, signer = signer)
        c.promoteToGlobal("demo")
        assertTrue(checkedGlobal)
        signer.verifyOrThrow("demo", layout.lockfile(ProjectId("demo")))
        val events = c.drainEvents("demo", 0, 64).events
        assertTrue(events.any { (it.event as? InstallEvent.Finished)?.success == true })
    }

    @Test
    fun `全局作用域不能迁移自己无清单项目如实拒绝`() = runBlocking {
        val exec = PromoteExecutor()
        val c = coordinator(exec)
        assertThrows(AutojsException::class.java) { runBlocking { c.promoteToGlobal(NpmTargetScope.GLOBAL) } }
        assertThrows(AutojsException::class.java) { runBlocking { c.promoteToGlobal("demo") } }
        seedPromoteTree(project)
        c.promoteToGlobal("demo")
        assertTrue(exec.calls.isEmpty())
        assertTrue(c.consoleOutput("demo", 0, 32).lines.any { it.line.text.contains("没有可迁移") })
    }
}
