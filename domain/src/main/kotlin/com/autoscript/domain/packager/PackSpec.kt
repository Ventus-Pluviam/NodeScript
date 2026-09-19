package com.autoscript.domain.packager

/**
 * 打包规格与资产清单（docs/framework-design.md §14 P0 打包：模板 APK 改装 + 资产注入 + 签名向导）。
 *
 * P0 切片：纯 Kotlin 领域模型（零 Android 依赖、JVM 可单测）。
 * 真机改写（AXML/ARSC 编辑、apksigner 调用）是 Android 侧实现细节，
 * 消费这里的 PackSpec / AssetEntry / PackManifest，契约不变。
 */
data class PackSpec(
    val projectId: String,
    val appName: String,
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val entryScript: String = "main.js",
    // node_modules 默认入包（§10.11 打包向导联动）；false 仅用于最小调试包。
    val includeNodeModules: Boolean = true,
    // 完全离线变体：宿主预装 node_modules.zip，包内只留 lock 签名。
    val offlineVariant: Boolean = false,
    // .autojs.build.ignore 规则（gitignore-lite 子集，见 BuildIgnore）。
    val ignoreRules: List<String> = emptyList(),
) {
    init {
        require(projectId.isNotBlank()) { "projectId 不得为空" }
        require(appName.isNotBlank()) { "appName 不得为空" }
        require(versionCode > 0) { "versionCode 必须 > 0" }
        require(entryScript.isNotBlank()) { "entryScript 不得为空" }
    }
}

/** 待注入模板 APK 的单个资产（相对项目 root 的路径 + 内容哈希）。 */
data class AssetEntry(
    val relPath: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** 一次打包的资产清单：全部 AssetEntry + 规格快照 + 清单自哈希（防篡改）。 */
data class PackManifest(
    val spec: PackSpec,
    val assets: List<AssetEntry>,
    // 清单摘要：逐行 relPath + sha256 + size 的 sha256，打包器写入、安装器校验。
    val digest: String,
)

/**
 * .autojs.build.ignore 排除规则（gitignore-lite 子集，纯 Kotlin、无正则引擎依赖）：
 * 空行与井号开头忽略；斜杠结尾表示整目录排除；含星号为单段通配（星号不跨段）；
 * 其余为精确相对路径。
 */
object BuildIgnore {

    fun matches(relPath: String, rules: List<String>): Boolean {
        val path = relPath.trim('/').takeIf { it.isNotEmpty() } ?: return false
        for (raw in rules) {
            val rule = raw.trim()
            if (rule.isEmpty() || rule.startsWith("#")) continue
            if (rule.endsWith("/")) {
                val dir = rule.trim('/')
                if (path == dir || path.startsWith("$dir/")) return true
                continue
            }
            if ('*' in rule) {
                if (globSingle(rule, path)) return true
                continue
            }
            if (path == rule.trim('/')) return true
        }
        return false
    }

    // 星号不跨段的逐段通配。
    private fun globSingle(pattern: String, path: String): Boolean {
        val pSegs = pattern.trim('/').split('/')
        val sSegs = path.split('/')
        if (pSegs.size != sSegs.size) return false
        return pSegs.zip(sSegs).all { (p, s) -> segMatch(p, s) }
    }

    private fun segMatch(pattern: String, name: String): Boolean {
        // 单段翻译为正则：转义正则元字符，星号变点星（本段内已无斜杠，即全匹配）。
        val sb = StringBuilder()
        for (c in pattern) {
            if (c == '*') {
                sb.append(".*")
            } else {
                if (c in ".+?^()|[]\\$" || c == '{' || c == '}') sb.append('\\')
                sb.append(c)
            }
        }
        return Regex(sb.toString()).matches(name)
    }
}
