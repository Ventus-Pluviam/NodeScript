package com.autoscript.appservice.scheduler.recovery

import com.autoscript.domain.scripts.ProjectId
import com.autoscript.domain.scripts.RelPath
import com.autoscript.domain.scripts.ScriptPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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

    private fun recovery(sources: Map<ProjectId, Map<RelPath, ByteArray>>) =
        ScriptDeployRecovery(files, sources)

    private fun src(id: String, vararg entries: Pair<String, ByteArray>): Map<ProjectId, Map<RelPath, ByteArray>> =
        mapOf(ProjectId(id) to entries.associate { RelPath(it.first) to it.second })

    @Test
    fun `缺文件被补上，落位在约定路径`() {
        val report = recovery(
            src("p1", "main.js" to "console.log(1)".toByteArray()),
        ).run()

        assertEquals(1, report.deployed.size)
        assertTrue(report.changed)
        assertTrue(report.failures.isEmpty())
        val target = ScriptPaths.scriptFile(files, ProjectId("p1"), RelPath("main.js"))
        assertEquals("console.log(1)", Files.readAllBytes(target).toString(Charsets.UTF_8))
        assertEquals(ScriptPaths.projectRoot(files, ProjectId("p1")), target.parent, "落位在 files/scripts/p1 之下")
    }

    @Test
    fun `已存在的文件绝不覆盖：用户手改的内容原样留着`() {
        val target = ScriptPaths.scriptFile(files, ProjectId("p1"), RelPath("main.js"))
        Files.createDirectories(target.parent)
        Files.write(target, "// 用户手改过的版本".toByteArray())

        val report = recovery(src("p1", "main.js" to "console.log(2)".toByteArray())).run()

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
        val report = recovery(src("p1", "empty.js" to ByteArray(0))).run()

        assertTrue(report.deployed.isEmpty())
        assertEquals(1, report.failures.size)
        assertEquals(RelPath("empty.js"), report.failures.single().relPath)
        assertFalse(
            Files.exists(ScriptPaths.scriptFile(files, ProjectId("p1"), RelPath("empty.js"))),
            "空来源不得落盘：空脚本会让引擎报语法错，把'没补上'伪装成'脚本坏了'",
        )
    }

    /**
     * **批 109 的行为变更**：非法相对路径**不再**是「运行期记一笔失败」，而是
     * **装不进 `sources` 这张表** —— 键类型是 [RelPath]，构造期就抛。
     *
     * 原来那条用例（`../escape.js` 与 `/abs.js` 各记一笔失败、合法条目照常补）
     * 表达的是「读侧兜住坏条目」；现在这张表**装不下**坏条目，所以那条性质变成了
     * 「坏条目在装表时就响亮失败」，而「一个坏项目不带走整批」改由下面的多项目用例覆盖。
     *
     * **这个取舍不是白得的**：装配层若从不可信来源喂表，现在会**抛**而不是**跳过**。
     * 生产接线（`AppShellKit` 的 `assetReader`）喂的判据来自 assets 枚举与文件路径，
     * 两者都由本仓生成 —— 真出现非法项时，响亮失败优于静默跳过（那会让「没补上」
     * 与「本来就不用补」看起来一模一样）。
     */
    @Test
    fun `非法相对路径装不进 sources——构造期即抛，不留逃逸落盘`() {
        assertThrows(IllegalArgumentException::class.java) { RelPath("../escape.js") }
        assertThrows(IllegalArgumentException::class.java) { RelPath("/abs.js") }
        assertFalse(Files.exists(ScriptPaths.projectsRoot(files).resolve("escape.js")), "逃逸路径绝不落盘")
    }

    @Test
    fun `多项目各自独立：一个项目的失败不影响另一个`() {
        val report = recovery(
            mapOf(
                ProjectId("alpha") to mapOf(RelPath("a.js") to "a".toByteArray()),
                ProjectId("beta") to mapOf(RelPath("b.js") to "b".toByteArray(), RelPath("bad") to ByteArray(0)),
            ),
        ).run()

        assertEquals(listOf("alpha/a.js", "beta/b.js"), report.deployed.map { "${it.projectId}/${it.relPath}" })
        assertEquals(1, report.failures.size)
        assertEquals(ProjectId("beta"), report.failures.single().projectId)
    }

    @Test
    fun `子目录里的脚本同样落位（相对路径含目录段）`() {
        val report = recovery(src("p1", "lib/util.js" to "exports.x=1".toByteArray())).run()

        assertEquals(1, report.deployed.size)
        val target = ScriptPaths.scriptFile(files, ProjectId("p1"), RelPath("lib/util.js"))
        assertEquals("exports.x=1", Files.readAllBytes(target).toString(Charsets.UTF_8))
        assertTrue(Files.isDirectory(target.parent), "中间目录按需创建")
    }

    @Test
    fun `补部署不留临时文件（失败路径也清干净）`() {
        recovery(src("p1", "a.js" to "a".toByteArray(), "e.js" to ByteArray(0))).run()

        val strays = Files.list(ScriptPaths.projectRoot(files, ProjectId("p1"))).use { s ->
            s.map { it.fileName.toString() }.filter { it.startsWith(".recover-") }.toList()
        }
        assertTrue(strays.isEmpty(), "临时文件必须清干净（残留会被当成项目文件索引进去）: $strays")
    }
}
