package com.autoscript.appservice.npm

import com.autoscript.domain.scripts.ScriptPaths
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * npm 项目布局（§10.2 存储布局）+ 轻操作（§10.6：:main Kotlin 直读，零 Node 进程）。
 *
 * - 项目根 `files/scripts/<projectId>/`：`package.json` / `package-lock.json` / `node_modules/` / `.npmrc`；
 * - `list` = 直读 lockfile v3 `packages` 段 + node_modules 目录存在性，不解析 semver；
 * - `storage` = 目录遍历累计尺寸（不用 du，§10.6）；
 * - `offlineGap` = lock 闭包 − cache index（cache 命中由实现侧注入 [CacheIndex]，本类只算差集）。
 */
class NpmProjectLayout(val projectsRoot: Path) {

    /** 防路径逃逸（与 FsProjectStore 同规约）。判据在 [ScriptPaths.PROJECT_ID]（唯一一份）。 */
    fun projectRoot(projectId: String): Path {
        require(ScriptPaths.isValidProjectId(projectId)) { "非法 projectId: $projectId" }
        return projectsRoot.resolve(projectId)
    }

    fun lockfile(projectId: String): Path = projectRoot(projectId).resolve("package-lock.json")
    fun nodeModules(projectId: String): Path = projectRoot(projectId).resolve("node_modules")
    fun npmrc(projectId: String): Path = projectRoot(projectId).resolve(".npmrc")

    companion object {
        /**
         * 项目号判据的**别名**（2026-10-09 批 84）：字面量已上提到
         * [com.autoscript.domain.scripts.ScriptPaths.PROJECT_ID] —— 界面侧的命令行校验
         * 也要用它，而 `:ui` 够不到本模块。留这个别名是为了让既有引用点零改动，
         * **URL/正则字面量从此只有一份**（与批 83 把 `NpmRegistryVerifier.OFFICIAL`
         * 改成 `NpmRegistryKeys.OFFICIAL` 的别名是同一手法）。
         */
        val PROJECT_ID: Regex = ScriptPaths.PROJECT_ID
    }
}

/** lockfile v3 已锁定包（name@version + integrity + 尺寸不可得→0，由 cache/tarball 头补）。 */
data class LockedPkg(val name: String, val version: String, val integrity: String?, val resolvedSize: Long = 0)

/**
 * 缓存索引接缝（cacache content-v2 的查询抽象；生产实现读 cacheDir/npm-cache/_cacache）。
 *
 * **为什么 `contentBytes` 有缺省实现**：它是**读数**而不是判据，而绝大多数替身
 * （测试里 `CacheIndex { false }` 那种）只关心 `has` 的答案。给它一个缺省值
 * 就不必让每个替身都写一遍「我量不到体积」——缺省 0 也是这里唯一诚实的值
 * （替身背后没有缓存目录，就是 0 字节）。`fun interface` 仍成立：唯一的抽象方法
 * 还是 `has`，带缺省体的方法不参与 SAM 转换。
 */
fun interface CacheIndex {
    /** 该 integrity（sha512-…）是否已物化在缓存。 */
    fun has(integrity: String): Boolean

    /**
     * content-v2 的总字节（§10.9 第 5 条的 `npm-cache` 尺寸栏）。
     *
     * 只算 content-v2：这个数字的用途是回答「回收缓存能腾出多少」，而回收动的正是它。
     */
    fun contentBytes(): Long = 0
}

/** 极简 lockfile v3 读取（手写解析：只取 packages 段的 version/integrity，不引入 JSON 库）。 */
object LockfileReader {

    fun readLocked(lockFile: Path): List<LockedPkg> {
        if (!Files.exists(lockFile)) return emptyList()
        val text = String(Files.readAllBytes(lockFile), StandardCharsets.UTF_8)
        return parse(text)
    }

    /** 只解析 "packages" 对象内各键的 "version"/"integrity"；其余段忽略。 */
    fun parse(json: String): List<LockedPkg> {
        val out = ArrayList<LockedPkg>()
        val pkgsIdx = findObjectBounds(json, "\"packages\"") ?: return out
        var i = pkgsIdx.first
        val end = pkgsIdx.second
        while (i < end) {
            // 找下一个键："node_modules/<name>" : { ... }
            val keyStart = json.indexOf('"', i)
            if (keyStart < 0 || keyStart >= end) break
            val keyEnd = json.indexOf('"', keyStart + 1)
            if (keyEnd < 0 || keyEnd >= end) break
            val key = json.substring(keyStart + 1, keyEnd)
            val colon = json.indexOf(':', keyEnd)
            if (colon < 0 || colon >= end) break
            val valBounds = findObjectBounds(json, null, colon + 1) ?: break
            val body = json.substring(valBounds.first, valBounds.second)
            if (key.isNotEmpty()) {   // "" 是根包
                // 嵌套 node_modules/a/node_modules/b → 顶层逻辑名取最后一段（lock v3 扁平键）
                val name = key.substringAfterLast("node_modules/")
                out.add(
                    LockedPkg(
                        name = name,
                        version = extractString(body, "version") ?: "0.0.0",
                        integrity = extractString(body, "integrity"),
                    ),
                )
            }
            i = valBounds.second
        }
        return out
    }

    private fun extractString(obj: String, key: String): String? {
        val pat = "\"$key\""
        val idx = obj.indexOf(pat) ?: return null
        val colon = obj.indexOf(':', idx + pat.length)
        if (colon < 0) return null
        val q1 = obj.indexOf('"', colon + 1)
        if (q1 < 0) return null
        val q2 = obj.indexOf('"', q1 + 1)
        if (q2 < 0) return null
        return obj.substring(q1 + 1, q2)
    }

    /** keyOrNull：定位 `"key": {`；否则从 [from] 找下一个 `{`。返回 (contentStart, matchingClose+1)。 */
    private data class IntPair(val first: Int, val second: Int)

    private fun findObjectBounds(json: String, keyOrNull: String?, from: Int = 0): IntPair? {
        val start = if (keyOrNull != null) {
            val ki = json.indexOf(keyOrNull, from)
            if (ki < 0) return null
            val colon = json.indexOf(':', ki + keyOrNull.length)
            if (colon < 0) return null
            json.indexOf('{', colon)
        } else {
            json.indexOf('{', from)
        }
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until json.length) {
            val c = json[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return IntPair(start + 1, i + 1)   // 内容起 → 闭合括号+1
                }
            }
        }
        return null
    }
}

/** 目录尺寸遍历（storage 轻操作）+ 字节哈希（部署校验）。 */
object DirSizer {
    fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    /** cacache 的默认摘要算法（内容路径按 sha512 分段；与 lockfile v3 integrity 同口径）。 */
    fun sha512(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-512")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun sizeBytes(root: Path): Long {
        if (!Files.isDirectory(root)) return 0
        var total = 0L
        Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) }.forEach { total += Files.size(it) }
        }
        return total
    }
}
