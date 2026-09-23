package com.autoscript.appservice.scriptrepo.core

import com.autoscript.domain.scripts.ScriptPaths
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * bridge addon 单文件落位（§19 交付轨）的诚实边界：没货不建目录、空字节拒绝、
 * 字节即版本、落位根 = [ScriptPaths.bridgeAddonFile]（与引擎 `addonPath` 同源）。
 */
class BridgeAddonDeployTest {

    @TempDir
    lateinit var dir: Path

    private val addon: Path get() = ScriptPaths.bridgeAddonFile(dir)

    @Test
    fun `没货不动盘——不粉饰成 addon 已就位`() {
        val r = BridgeAddonDeploy(dir, null).run()
        assertFalse(r.changed)
        assertEquals(null, r.failure)
        assertTrue(!Files.exists(addon), "null 来源连 lib 目录都不该建")
    }

    @Test
    fun `首落写到位——落位根就是引擎 addonPath 的拼点`() {
        val r = BridgeAddonDeploy(dir, "addon-bytes".toByteArray()).run()
        assertTrue(r.changed)
        assertTrue(r.failure == null, "failure=${r.failure}")
        assertTrue(Files.isRegularFile(addon), "落位根=${ScriptPaths.bridgeAddonFile(dir)}")
        assertEquals("addon-bytes", String(Files.readAllBytes(addon)))
    }

    @Test
    fun `字节相同不动盘——异则原位替换（字节即版本）`() {
        BridgeAddonDeploy(dir, "v1".toByteArray()).run()
        val same = BridgeAddonDeploy(dir, "v1".toByteArray()).run()
        assertFalse(same.changed)
        assertTrue(same.unchanged)

        val diff = BridgeAddonDeploy(dir, "v2".toByteArray()).run()
        assertTrue(diff.changed)
        assertEquals("v2", String(Files.readAllBytes(addon)))
    }

    @Test
    fun `空字节拒绝——旧件原样留着，失败点名`() {
        BridgeAddonDeploy(dir, "keep-me".toByteArray()).run()
        val r = BridgeAddonDeploy(dir, ByteArray(0)).run()
        assertFalse(r.changed)
        assertTrue(r.failure != null && r.failure.contains("空"), "failure=${r.failure}")
        assertEquals("keep-me", String(Files.readAllBytes(addon)), "失败不得丢件")
    }
}
