package com.autoscript.appservice.scriptrepo.core

import com.autoscript.domain.scripts.ScriptProject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * 项目索引（docs/framework-design.md §9.6）：由仓库布局派生轻量元数据 → [:domain] 的 [ScriptProject]。
 * 骨架实现：入口来自 package.json 的 main 字段，缺省回退 main.js/ index.js。
 * 完整清单（资源哈希、版本语义化）留给 :app-service:packager（§9 packager，P1）。
 */
class ProjectIndex(private val store: ProjectStore) {

    fun list(): List<ScriptProject> = store.listIds().mapNotNull(::read)

    fun read(id: String): ScriptProject? {
        if (!store.exists(id)) return null
        val root = store.rootFor(id)
        val attrs = Files.readAttributes(root, BasicFileAttributes::class.java)
        return ScriptProject(
            id = id,
            name = id,
            version = readVersion(root),
            mainScript = resolveMain(root),
            createdAtMillis = attrs.creationTime().toMillis(),
        )
    }

    fun delete(id: String): Boolean {
        if (!store.exists(id)) return false
        store.delete(id)
        return true
    }

    private fun resolveMain(root: Path): String {
        val pkg = root.resolve("package.json")
        if (Files.isRegularFile(pkg)) {
            // 不用 Files.readString（Java 11 API，Android android.jar 的 java.nio.file 存根不提供）；
            // readAllBytes 在 API26+ 存根可用，手动解码 UTF-8。
            val text = Files.readAllBytes(pkg).toString(StandardCharsets.UTF_8)
            MAIN_FIELD_REGEX.find(text)?.let { return it.groupValues[1] }
        }
        return when {
            Files.exists(root.resolve("main.js")) -> "main.js"
            Files.exists(root.resolve("index.js")) -> "index.js"
            else -> ""
        }
    }

    private fun readVersion(root: Path): Int {
        val pkg = root.resolve("package.json")
        if (!Files.isRegularFile(pkg)) return 1
        val text = Files.readAllBytes(pkg).toString(StandardCharsets.UTF_8)
        return VERSION_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    companion object {
        // 骨架解析：仅抓 main/version 两个字段；完整 JSON 解析在 packager 模块统一承担
        private val MAIN_FIELD_REGEX =
            Regex("\"main\"\\s*:\\s*\"([^\"]+)\"")
        private val VERSION_REGEX =
            Regex("\"version\"\\s*:\\s*\"(\\d+)")
    }
}