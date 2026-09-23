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
 * `BridgeDistDeploy` 契约（docs §12.4 资产交付轨）：
 * 字节即版本（异则替）、空来源没货、空字节不落、逃逸名拒绝、
 * 孤儿只在**全量成功**后清（读失败时不删 —— 怕把没读到的当旧版清掉）。
 */
class BridgeDistDeployTest {

    @TempDir
    lateinit var dir: Path

    private val files: Path get() = dir.resolve("files")

    private fun target(): Path = ScriptPaths.autoModuleRoot(files)

    private fun writeTarget(name: String, bytes: ByteArray) {
        val p = target().resolve(name)
        Files.createDirectories(p.parent)
        Files.write(p, bytes)
    }

    @Test
    fun `空来源不动盘——没货不是已部署`() {
        val report = BridgeDistDeploy(files, emptyMap()).run()
        assertFalse(report.changed, "空来源 changed 必须 false（不粉饰成已就位）")
        assertTrue(report.deployed.isEmpty() && report.pruned.isEmpty() && report.failures.isEmpty())
        assertTrue(!Files.exists(target()), "连目录都不该建")
    }

    @Test
    fun `首落全写——缺位则补`() {
        val report = BridgeDistDeploy(
            files,
            mapOf(
                "index.js" to "index".toByteArray(),
                "bootstrap.js" to "boot".toByteArray(),
            ),
        ).run()
        assertTrue(report.changed)
        assertEquals(listOf("bootstrap.js", "index.js"), report.deployed, "按字典序稳定输出")
        assertEquals(0, report.unchanged)
        assertTrue(report.failures.isEmpty())
        assertEquals("index", String(Files.readAllBytes(target().resolve("index.js"))))
    }

    @Test
    fun `字节相同不动盘——异则原位替换(字节即版本)`() {
        writeTarget("index.js", "old".toByteArray())
        writeTarget("bootstrap.js", "same".toByteArray())
        val report = BridgeDistDeploy(
            files,
            mapOf(
                "index.js" to "new".toByteArray(),
                "bootstrap.js" to "same".toByteArray(),
            ),
        ).run()
        assertTrue(report.changed, "有异则替换才算 changed")
        assertEquals(listOf("index.js"), report.deployed)
        assertEquals(1, report.unchanged, "字节一致的不动盘")
        assertEquals("new", String(Files.readAllBytes(target().resolve("index.js"))), "app 升级换新 facade 靠异则替")
        assertEquals("same", String(Files.readAllBytes(target().resolve("bootstrap.js"))))
    }

    @Test
    fun `空字节拒绝——空文件比缺文件更难查`() {
        val report = BridgeDistDeploy(files, mapOf("index.js" to ByteArray(0))).run()
        assertTrue(report.failures.isNotEmpty())
        assertTrue(report.failures[0].reason.contains("空"), "原因点名空字节")
        assertTrue(!Files.exists(target().resolve("index.js")), "空文件不落盘")
        assertFalse(report.changed)
    }

    @Test
    fun `逃逸文件名拒绝——dist 必须平铺`() {
        val report = BridgeDistDeploy(
            files,
            mapOf(
                "../evil.js" to "x".toByteArray(),
                "a/b.js" to "x".toByteArray(),
                "ok.js" to "fine".toByteArray(),
            ),
        ).run()
        assertEquals(2, report.failures.size, "../ 与子路径各拒一笔")
        assertTrue(report.failures.all { it.reason.contains("非法") })
        assertEquals(listOf("ok.js"), report.deployed, "坏名不带走好文件")
        assertTrue(!Files.exists(files.resolve("evil.js")), "逃逸目标未被写出")
    }

    @Test
    fun `孤儿只在全量成功后清——有失败就不删`() {
        writeTarget("stale.js", "old".toByteArray())
        // 成功路径：stale 不在 sources → 清掉
        val ok = BridgeDistDeploy(files, mapOf("index.js" to "v".toByteArray())).run()
        assertTrue(ok.changed)
        assertEquals(listOf("stale.js"), ok.pruned)
        assertTrue(!Files.exists(target().resolve("stale.js")))
        assertTrue(ok.failures.isEmpty())

        // 失败路径：stale 重新放回去 + sources 带一条空字节（失败）→ stale 必须留下
        writeTarget("stale.js", "old".toByteArray())
        val bad = BridgeDistDeploy(
            files,
            mapOf(
                "index.js" to "v".toByteArray(),
                "broken.js" to ByteArray(0),
            ),
        ).run()
        assertTrue(bad.failures.isNotEmpty())
        assertTrue(bad.pruned.isEmpty(), "有失败绝不清孤儿（怕把没读到的当旧版清掉）")
        assertTrue(Files.exists(target().resolve("stale.js")), "失败宁可留旧，不可丢件")
    }

    @Test
    fun `落位根在 filesDir node_modules auto——require 解析点只有一个出处`() {
        BridgeDistDeploy(files, mapOf("index.js" to "v".toByteArray())).run()
        assertTrue(
            Files.isRegularFile(files.resolve("node_modules/auto/index.js")),
            "落位根 = ScriptPaths.autoModuleRoot（Node 解析走位第 3 站，见其 KDoc）",
        )
    }
}
