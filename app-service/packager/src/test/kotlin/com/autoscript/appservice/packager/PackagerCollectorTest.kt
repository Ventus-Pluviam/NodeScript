package com.autoscript.appservice.packager

import com.autoscript.domain.packager.ApkIdentity
import com.autoscript.domain.packager.PackManifests
import com.autoscript.domain.packager.PackSpec
import com.autoscript.domain.packager.TemplateApkPlans
import com.autoscript.domain.packager.TemplateInfo
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PackagerCollectorTest {

    @TempDir
    lateinit var dir: java.nio.file.Path

    private fun write(rel: String, text: String) {
        val f = dir.resolve(rel)
        Files.createDirectories(f.parent)
        Files.write(f, text.toByteArray(StandardCharsets.UTF_8))
    }

    @Test
    fun `收集排序产出可校验清单`() {
        write("main.js", "run()")
        write("lib/util.js", "u()")
        val m = PackagerCollector().collect(PackSpec(projectId = "p", appName = "P"), dir)
        assertEquals(listOf("lib/util.js", "main.js"), m.assets.map { it.relPath })
        assertTrue(PackManifests.verify(m))
    }

    @Test
    fun `ignore 规则与描述文件本身被排除`() {
        write("main.js", "run()")
        write(".autojs.build.ignore", "x")
        write("build.log", "noise")
        write("node_modules/axios/index.js", "axios")
        val spec = PackSpec(projectId = "p", appName = "P", ignoreRules = listOf("*.log"))
        val m = PackagerCollector().collect(spec, dir)
        assertTrue(m.assets.none { it.relPath == "build.log" || it.relPath == PackagerCollector.IGNORE_FILE })
        assertTrue(m.assets.any { it.relPath == "node_modules/axios/index.js" }, "node_modules 默认入包")
    }

    @Test
    fun `最小调试包可剔除 node_modules`() {
        write("main.js", "run()")
        write("node_modules/axios/index.js", "axios")
        val spec = PackSpec(projectId = "p", appName = "P", includeNodeModules = false)
        val m = PackagerCollector().collect(spec, dir)
        assertFalse(m.assets.any { it.relPath.startsWith("node_modules") })
    }

    @Test
    fun `入口缺失早失败`() {
        write("other.js", "x")
        assertThrows<IllegalArgumentException> {
            PackagerCollector().collect(PackSpec(projectId = "p", appName = "P"), dir)
        }
    }

    @Test
    fun `plan 一次产出改写计划且复验通过`() {
        write("main.js", "run()")
        val spec = PackSpec(projectId = "p", appName = "P")
        val plan = PackagerCollector().plan(
            spec, dir,
            ApkIdentity("com.example.demo", "Demo"),
            TemplateInfo("24.21.0"),
        )
        val manifest = PackagerCollector().collect(spec, dir)
        assertTrue(TemplateApkPlans.verify(plan, manifest))
        assertEquals(manifest.digest, plan.manifestDigest)
    }
}
