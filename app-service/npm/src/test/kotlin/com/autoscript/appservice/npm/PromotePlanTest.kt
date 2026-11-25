package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.json.DomainJson.Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PromotePlanTest {
    @TempDir
    lateinit var dir: Path
    private val project get() = dir.resolve("project")
    private val global get() = dir.resolve("global")
    private fun plan(): PromotePlan = PromotePlan(PromoteTree.read(project), PromoteTree.read(global, allowEmpty = true))

    @Test
    fun `只认根层 lock 安装位置且 scope 包名完整保留`() {
        val locked = LockfileReader.parse(
            """{"packages":{"":{},"node_modules/axios":{"version":"1.7.0"},
            "node_modules/parent/node_modules/axios":{"version":"2.0.0"},
            "node_modules/@scope/tool":{"version":"3.1.0"}}}""",
        )
        assertEquals(listOf("axios" to "1.7.0", "@scope/tool" to "3.1.0"), locked.filter { it.topLevel }.map { it.name to it.version })
        assertEquals("axios", locked.single { !it.topLevel }.name)
    }

    @Test
    fun `全局只有嵌套同名包不冒充已有顶层冲突`() {
        seedPromoteTree(project, "axios" to "1.7.0")
        seedPromoteTree(global)
        val path = global.resolve("package-lock.json")
        val lock = readFixtureJson(path)
        val packages = (lock["packages"] as Value.Obj).fields.toMutableMap()
        packages["node_modules/parent/node_modules/axios"] = fixtureLockPackage("9.0.0")
        lock["packages"] = Value.Obj(packages)
        writeFixtureJson(path, lock)
        assertEquals(listOf("axios"), plan().additions.map { it.name })
    }

    @Test
    fun `alias file git workspace URL 和 tarball 不换成 registry 同名包`() {
        seedPromoteTree(project, "axios" to "1.7.0")
        for (spec in listOf("npm:other@1.7.0", "file:../local", "git+https://example.com/repo", "workspace:*",
            "https://example.com/a.tgz", "owner/repo", "../local", "/absolute/path", "local.tgz")) {
            changeFixtureDependency(project, "axios", spec)
            assertTrue(plan().packages.isEmpty(), spec)
            assertTrue(plan().additions.isEmpty(), spec)
        }
    }

    @Test
    fun `dev 声明迁移但 optional peer 或双重来源声明保留`() {
        seedPromoteTree(project, "axios" to "1.7.0")
        val path = project.resolve("package.json")
        writeFixtureJson(path, mapOf("devDependencies" to Value.Obj(mapOf("axios" to Value.S("~1.7.0")))))
        assertEquals(setOf("axios"), plan().packages.keys)
        for (section in listOf("optionalDependencies", "peerDependencies")) {
            seedPromoteTree(project, "axios" to "1.7.0")
            val manifest = readFixtureJson(path)
            manifest[section] = Value.Obj(mapOf("axios" to Value.S("^1.7.0")))
            writeFixtureJson(path, manifest)
            assertTrue(plan().packages.isEmpty(), section)
        }
        seedPromoteTree(project, "axios" to "1.7.0")
        val manifest = readFixtureJson(path)
        manifest["devDependencies"] = Value.Obj(mapOf("axios" to Value.S("file:../axios")))
        writeFixtureJson(path, manifest)
        assertTrue(plan().packages.isEmpty(), "任一声明为非 registry 来源都保留")
    }

    @Test
    fun `同版本不同 integrity 不当成可共享同一包`() {
        seedPromoteTree(project, "axios" to "1.7.0")
        seedPromoteTree(global, "axios" to "1.7.0")
        val path = global.resolve("package-lock.json")
        val lock = readFixtureJson(path)
        val packages = (lock["packages"] as Value.Obj).fields.toMutableMap()
        packages["node_modules/axios"] = Value.Obj(mapOf("version" to Value.S("1.7.0"), "integrity" to Value.S("sha512-other")))
        lock["packages"] = Value.Obj(packages)
        writeFixtureJson(path, lock)
        assertTrue(plan().packages.isEmpty())
    }

    @Test
    fun `符号链接包不作为 registry 候选`() {
        seedPromoteTree(project, "axios" to "1.7.0")
        val local = project.resolve("node_modules/axios")
        local.toFile().deleteRecursively()
        val external = Files.createDirectories(dir.resolve("external"))
        Files.createSymbolicLink(local, external)
        assertThrows(AutojsException::class.java) { plan() }
        assertTrue(Files.isSymbolicLink(local))
    }

    @Test
    fun `非法包名键不拼进 npm 选项或文件路径`() {
        seedPromoteTree(project)
        for (name in listOf("--global", ".", "..", "../escape", "@scope/../escape")) {
            changeFixtureDependency(project, name, "1.7.0")
            assertTrue(plan().packages.isEmpty(), name)
        }
    }

    @Test
    fun `已安装全局同名错版不覆盖即使锁版本相同`() {
        seedPromoteTree(project, "axios" to "1.7.0")
        seedPromoteTree(global, "axios" to "1.7.0")
        writeFixturePackage(global, "axios", "2.0.0")
        assertTrue(plan().packages.isEmpty())
        assertTrue(plan().summary().contains("留在项目：axios"))
        assertFalse(plan().summary().contains("迁移完成"))
    }
}
