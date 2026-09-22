package com.autoscript.appservice.packager

import com.autoscript.domain.packager.ApkIdentity
import com.autoscript.domain.packager.AssetEntry
import com.autoscript.domain.packager.BuildIgnore
import com.autoscript.domain.packager.PackManifest
import com.autoscript.domain.packager.PackManifests
import com.autoscript.domain.packager.PackSpec
import com.autoscript.domain.packager.TemplateApkPlan
import com.autoscript.domain.packager.TemplateApkPlans
import com.autoscript.domain.packager.TemplateInfo
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 打包资产收集（docs/framework-design.md §14 P0 打包：模板 APK 资产注入）。
 *
 * 遍历项目目录产出 [PackManifest]：`node_modules` 默认入包（§10.11 打包向导联动，
 * `includeNodeModules=false` 仅用于最小调试包）；[BuildIgnore] 规则 + 构建描述文件
 * 本身（`.autojs.build.ignore`）一律排除；入口脚本缺失直接拒绝（早失败，不产出坏包）。
 *
 * AXML/ARSC 改写与签名调用是后续 Android 实现，本类只做"哪些文件进包 + 清单摘要"。
 * [plan] 由清单组装模板改写计划（改写前凭 planDigest 复验，防清单错配）。
 */
class PackagerCollector {

    /**
     * 由 [PackSpec] + 身份 + 模板描述一次产出改写计划。
     * 清单自哈希失败直接抛（TemplateApkPlans.build 的拒绝语义），不产出坏计划。
     */
    fun plan(
        spec: PackSpec,
        projectRoot: Path,
        identity: ApkIdentity,
        template: TemplateInfo,
    ): TemplateApkPlan = TemplateApkPlans.build(
        identity = identity,
        template = template,
        manifest = collect(spec, projectRoot),
        offlineVariant = spec.offlineVariant,
    )

    fun collect(spec: PackSpec, projectRoot: Path): PackManifest {
        require(Files.isDirectory(projectRoot)) { "项目目录不存在: $projectRoot" }
        val assets = ArrayList<AssetEntry>()
        Files.walk(projectRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                val rel = projectRoot.relativize(file).joinToString("/")
                if (rel == IGNORE_FILE) return@forEach
                if (!spec.includeNodeModules && (rel == "node_modules" || rel.startsWith("node_modules/"))) return@forEach
                if (BuildIgnore.matches(rel, spec.ignoreRules)) return@forEach
                assets.add(AssetEntry(rel, sha256(file), Files.size(file)))
            }
        }
        require(assets.any { it.relPath == spec.entryScript }) { "入口脚本缺失: ${spec.entryScript}" }
        return PackManifests.build(spec, assets)
    }

    private fun sha256(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val IGNORE_FILE = ".autojs.build.ignore"
    }
}
