package com.autoscript.appservice.npm

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [NpmGlobalConfig] / [NpmrcFile] 的读写契约（§10.2 userconfig 层）。
 *
 * 最值得单独测的两件事：**整文件重写不能吃掉别的键**（`.npmrc` 是用户手编的文件，
 * 一次「设镜像源」把用户的 proxy/token 行删了是最坏的失败形态），以及
 * **删键要真删**（行级 append 表达不出「删」）。
 */
class NpmGlobalConfigTest {

    @Test
    fun `没设过读回 null`(@TempDir dir: Path) {
        assertNull(NpmGlobalConfig(dir).readRegistry())
    }

    @Test
    fun `写入后可读回且文件落在 files 根`(@TempDir dir: Path) {
        val c = NpmGlobalConfig(dir)
        c.writeRegistry("https://registry.npmmirror.com")
        assertEquals("https://registry.npmmirror.com", c.readRegistry())
        assertEquals(dir.resolve(".npmrc"), c.file(), "全局 .npmrc 在 files 根，不在 .autojs 也不在 npm/")
    }

    @Test
    fun `设镜像源不吃掉文件里别的键`(@TempDir dir: Path) {
        val c = NpmGlobalConfig(dir)
        Files.write(
            c.file(),
            listOf("# 用户手编", "https-proxy=http://127.0.0.1:8080", "registry=https://old.example.com"),
            StandardCharsets.UTF_8,
        )
        c.writeRegistry("https://new.example.com")
        val lines = Files.readAllLines(c.file())
        assertTrue(lines.contains("# 用户手编"), "注释行必须原样留着：$lines")
        assertTrue(lines.contains("https-proxy=http://127.0.0.1:8080"), "别的键必须原样留着：$lines")
        assertTrue(lines.contains("registry=https://new.example.com"))
        assertFalse(lines.any { it.startsWith("registry=https://old.example.com") }, "旧值不得残留：$lines")
        assertEquals(1, lines.count { it.startsWith("registry=") }, "同一个键只许一行：$lines")
    }

    @Test
    fun `null 是删键而不是写空值`(@TempDir dir: Path) {
        val c = NpmGlobalConfig(dir)
        c.writeRegistry("https://x.example.com")
        c.writeRegistry(null)
        assertNull(c.readRegistry())
        assertFalse(Files.readAllLines(c.file()).any { it.startsWith("registry=") }, "行必须真没了，不是写成 registry=")
    }

    @Test
    fun `重复写同一个键不累积行`(@TempDir dir: Path) {
        val c = NpmGlobalConfig(dir)
        c.writeRegistry("https://a.example.com")
        c.writeRegistry("https://b.example.com")
        c.writeRegistry("https://c.example.com")
        assertEquals(1, Files.readAllLines(c.file()).count { it.startsWith("registry=") })
        assertEquals("https://c.example.com", c.readRegistry())
    }

    @Test
    fun `最后一行生效——与 npm 的后写赢同口径`(@TempDir dir: Path) {
        val c = NpmGlobalConfig(dir)
        Files.write(
            c.file(),
            listOf("registry=https://first.example.com", "registry=https://second.example.com"),
            StandardCharsets.UTF_8,
        )
        assertEquals("https://second.example.com", c.readRegistry())
    }

    @Test
    fun `空值行不算设过`(@TempDir dir: Path) {
        val c = NpmGlobalConfig(dir)
        Files.write(c.file(), listOf("registry="), StandardCharsets.UTF_8)
        assertNull(c.readRegistry(), "空值 = 没设过（与 readRegistryFromNpmrc 同口径），不是「设为空串」")
    }
}
