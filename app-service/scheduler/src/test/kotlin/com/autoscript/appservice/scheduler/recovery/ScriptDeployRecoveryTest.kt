package com.autoscript.appservice.scheduler.recovery

import com.autoscript.domain.scripts.ScriptPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 装配期补部署验证（§9.6 项目目录标准化）。
 *
 * 三条诚实边界逐条对着测，因为它们的反面都是"现场看起来正常、实际什么都没发生"：
 * 1. 只补缺不覆盖 —— 用户手改过的脚本必须原样留着（`Assertions` 比的是字节）；
 * 2. 没有清单就是没得补 —— 空 sources 回 [ScriptDeployRecovery.Report.changed] = false，
 *    不粉饰成"已恢复"；空字节来源如实进 failures（空脚本落盘 = 引擎读到一个空文件，
 *    那比"没补上"更难查）；
 * 3. 失败不投毒 —— 一个文件写失败不影响同一批的其它文件，失败项带原因列出。
 */
class ScriptDeployRecoveryTest {

    @TempDir
    lateinit var files: Path

    private fun recovery(sources: Map<String, Map<String, ByteArray>>) =
        ScriptDeployRecovery(files, sources)

    @Test
    fun `缺文件被补上，落位在约定路径`() {
        val report = recovery(
            mapOf("p1" to mapOf("main.js" to "console.log(1)".toByteArray())),
        ).run()

        assertEquals(1, report.deployed.size)
        assertTrue(report.changed)
        assertTrue(report.failures.isEmpty())
        val target = ScriptPaths.scriptFile(files, "p1", "main.js")
        assertEquals("console.log(1)", Files.readAllBytes(target).toString(Charsets.UTF_8))
        assertEquals(ScriptPaths.projectRoot(files, "p1"), target.parent, "落位在 files/scripts/p1 之下")
    }

    @Test
    fun `已存在的文件绝不覆盖：用户手改的内容原样留着`() {
        val target = ScriptPaths.scriptFile(files, "p1", "main.js")
        Files.createDirectories(target.parent)
        Files.write(target, "// 用户手改过的版本".toByteArray())

        val report = recovery(mapOf("p1" to mapOf("main.js" to "console.log(2)".toByteArray()))).run()

        assertTrue(report.deployed.isEmpty(), "已存在 = 不是本次补的（不记功）")
        assertFalse(report.changed)
        assertEquals(
            "// 用户手改过的版本", Files.readAllBytes(target).toString(Charsets.UTF_8),
            "绝不覆盖：宁可跑旧版，也不静默盖掉用户的改动",
        )
    }

    @Test
    fun `空清单不粉饰成已恢复`() {
        val report = recovery(emptyMap()).run()
        assertFalse(report.changed, "没有来源 = 什么都没补，不得报成恢复成功")
        assertTrue(report.deployed.isEmpty())
        assertTrue(report.failures.isEmpty(), "空清单不是失败，是没得补")
    }

    @Test
    fun `空字节来源如实进失败清单（不落一个空文件冒充脚本）`() {
        val report = recovery(mapOf("p1" to mapOf("empty.js" to ByteArray(0)))).run()

        assertTrue(report.deployed.isEmpty())
        assertEquals(1, report.failures.size)
        assertEquals("empty.js", report.failures.single().relPath)
        assertFalse(
            Files.exists(ScriptPaths.scriptFile(files, "p1", "empty.js")),
            "空来源不得落盘：空脚本会让引擎报语法错，把'没补上'伪装成'脚本坏了'",
        )
    }

    @Test
    fun `非法相对路径只带走自己那一笔，整批补部署照常`() {
        val report = recovery(
            mapOf(
                "p1" to mapOf(
                    "../escape.js" to "x".toByteArray(),
                    "/abs.js" to "x".toByteArray(),
                    "ok.js" to "ok".toByteArray(),
                ),
            ),
        ).run()

        assertEquals(listOf("ok.js"), report.deployed.map { it.relPath }, "合法条目照常补上")
        assertEquals(2, report.failures.size, "逃逸条目如实记账")
        assertFalse(Files.exists(ScriptPaths.projectsRoot(files).resolve("escape.js")), "逃逸路径绝不落盘")
    }

    @Test
    fun `多项目各自独立：一个项目的失败不影响另一个`() {
        val report = recovery(
            mapOf(
                "alpha" to mapOf("a.js" to "a".toByteArray()),
                "beta" to mapOf("b.js" to "b".toByteArray(), "bad" to ByteArray(0)),
            ),
        ).run()

        assertEquals(listOf("alpha/a.js", "beta/b.js"), report.deployed.map { "${it.projectId}/${it.relPath}" })
        assertEquals(1, report.failures.size)
        assertEquals("beta", report.failures.single().projectId)
    }

    @Test
    fun `子目录里的脚本同样落位（相对路径含目录段）`() {
        val report = recovery(mapOf("p1" to mapOf("lib/util.js" to "exports.x=1".toByteArray()))).run()

        assertEquals(1, report.deployed.size)
        val target = ScriptPaths.scriptFile(files, "p1", "lib/util.js")
        assertEquals("exports.x=1", Files.readAllBytes(target).toString(Charsets.UTF_8))
        assertTrue(Files.isDirectory(target.parent), "中间目录按需创建")
    }

    @Test
    fun `补部署不留临时文件（失败路径也清干净）`() {
        recovery(mapOf("p1" to mapOf("a.js" to "a".toByteArray(), "e.js" to ByteArray(0)))).run()

        val strays = Files.list(ScriptPaths.projectRoot(files, "p1")).use { s ->
            s.map { it.fileName.toString() }.filter { it.startsWith(".recover-") }.toList()
        }
        assertTrue(strays.isEmpty(), "临时文件必须清干净（残留会被当成项目文件索引进去）: $strays")
    }
}
