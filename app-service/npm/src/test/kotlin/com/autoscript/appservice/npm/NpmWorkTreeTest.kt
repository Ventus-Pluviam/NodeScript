package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class NpmWorkTreeTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `校验型会话复制真树修改工作副本不影响原包`() {
        val root = dir.resolve("scripts/demo")
        seedPromoteTree(root, "axios" to "1.7.0")
        val cli = Files.writeString(dir.resolve("npm-cli.js"), "// stub")
        val executor = HostNodeExecutor(cli, dir.resolve("cache"))
        val op = HeavyOp("n1", "demo", listOf("uninstall", "axios"), root, root.resolve("stage"), 30_000, { _, _ -> })
        val work = root.resolve("work")
        executor.prepareWorkDir(op, work)
        writeFixturePackage(work, "axios", "9.0.0")
        assertEquals("1.7.0", PromoteTree.read(root).installed.getValue("axios").version)
        assertEquals("9.0.0", PromoteTree.read(work).installed.getValue("axios").version)
        assertTrue(Files.exists(root.resolve("node_modules/axios")))
    }

    @Test
    fun `复制保留树内相对 bin 链接不跟随到外部`() {
        val source = dir.resolve("modules")
        Files.createDirectories(source.resolve("tool"))
        Files.writeString(source.resolve("tool/cli.js"), "// cli")
        Files.createDirectories(source.resolve(".bin"))
        Files.createSymbolicLink(source.resolve(".bin/tool"), Path.of("../tool/cli.js"))
        val target = dir.resolve("copy")
        copyNpmWorkTree(source, target)
        assertTrue(Files.isSymbolicLink(target.resolve(".bin/tool")))
        assertEquals(Path.of("../tool/cli.js"), Files.readSymbolicLink(target.resolve(".bin/tool")))
        Files.writeString(target.resolve(".bin/tool"), "// edited copy")
        assertEquals("// cli", Files.readString(source.resolve("tool/cli.js")))
    }

    @Test
    fun `外部相对链接或绝对链接拒绝不触碰目标`() {
        val outside = Files.writeString(dir.resolve("outside"), "keep")
        val source = Files.createDirectories(dir.resolve("modules"))
        val link = source.resolve("unsafe")
        for (target in listOf(outside, Path.of("../outside"))) {
            Files.createSymbolicLink(link, target)
            assertThrows(AutojsException::class.java) { copyNpmWorkTree(source, dir.resolve("copy-${target.isAbsolute}")) }
            Files.delete(link)
        }
        assertEquals("keep", Files.readString(outside))
    }

    @Test
    fun `尚无 node_modules 时复制不凭空创建副本`() {
        copyNpmWorkTree(dir.resolve("missing"), dir.resolve("copy"))
        assertFalse(Files.exists(dir.resolve("copy")))
    }
}
