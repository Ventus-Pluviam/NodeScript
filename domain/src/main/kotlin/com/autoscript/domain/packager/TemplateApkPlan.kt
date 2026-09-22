package com.autoscript.domain.packager

import java.security.MessageDigest

/**
 * 打包 APK 身份补丁（docs/framework-design.md §3 打包行 / §14 P0 打包：
 * 模板 APK 改写时替换 application 身份的一组字段）。
 *
 * P0 切片：纯 Kotlin 领域模型（零 Android 依赖、JVM 可单测）。
 * 真机改写（AXML/ARSC 编辑）是 Android 侧实现细节，消费这里校验过的身份，契约不变。
 */
data class ApkIdentity(
    /** 应用包名（模板的 applicationId 被整体替换；APK 依赖宿主引擎版本，见 [TemplateInfo]）。 */
    val packageName: String,
    /** 启动器显示名（application label）。 */
    val appLabel: String,
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
) {
    init {
        require(isValidPackageName(packageName)) { "非法包名: $packageName" }
        require(appLabel.isNotBlank()) { "appLabel 不得为空" }
        require(versionName.isNotBlank()) { "versionName 不得为空" }
        require(versionCode > 0) { "versionCode 必须 > 0" }
    }

    companion object {
        private val PKG = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

        /** AAPT/aapt2 拒绝 Java 关键字作包名段（`com.class.x` 编译期炸），提前拦。 */
        private val JAVA_KEYWORDS = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while",
            "true", "false", "null",
        )

        fun isValidPackageName(name: String): Boolean {
            if (!PKG.matches(name)) return false
            return name.split('.').none { it in JAVA_KEYWORDS }
        }
    }
}

/**
 * 模板 APK 输入描述（Android 侧实现提供真实模板文件；领域层只认描述，不碰二进制）。
 *
 * 为什么记 engineVersion：打包行锚定「APK 依赖宿主引擎版本」——模板与 libnode.so
 * 同源，升级宿主不断旧包，但新包必须声明所配引擎版本，装机侧可核对。
 */
data class TemplateInfo(
    val engineVersion: String,
    val abis: List<String> = listOf("arm64-v8a"),
) {
    init {
        require(engineVersion.isNotBlank()) { "engineVersion 不得为空" }
        require(abis.isNotEmpty()) { "abis 不得为空" }
    }
}

/**
 * 一次模板改写的完整计划：身份补丁 + 模板描述 + 待装配资产清单摘要 + 变体。
 * planDigest = sha256(identity 各字段 + template + manifestDigest + offlineVariant)，
 * Android 侧改写前复验：计划与清单对不上即拒绝（防"拿 A 项目的清单装进 B 包"）。
 */
data class TemplateApkPlan(
    val identity: ApkIdentity,
    val template: TemplateInfo,
    /** 装配的 [PackManifest.digest]（清单自哈希，防篡改）。 */
    val manifestDigest: String,
    val assetCount: Int,
    val offlineVariant: Boolean,
    val planDigest: String,
)

object TemplateApkPlans {

    fun build(
        identity: ApkIdentity,
        template: TemplateInfo,
        manifest: PackManifest,
        offlineVariant: Boolean = false,
    ): TemplateApkPlan {
        require(PackManifests.verify(manifest)) { "资产清单自哈希校验失败，拒绝组装改写计划" }
        val digest = digestOf(identity, template, manifest.digest, manifest.assets.size, offlineVariant)
        return TemplateApkPlan(
            identity = identity,
            template = template,
            manifestDigest = manifest.digest,
            assetCount = manifest.assets.size,
            offlineVariant = offlineVariant,
            planDigest = digest,
        )
    }

    /** 改写前复验（安装器/Android 侧双侧共用）：计划摘要一致 + 清单仍是当初那份。 */
    fun verify(plan: TemplateApkPlan, manifest: PackManifest): Boolean {
        if (!PackManifests.verify(manifest)) return false
        if (manifest.digest != plan.manifestDigest) return false
        if (manifest.assets.size != plan.assetCount) return false
        return digestOf(plan.identity, plan.template, manifest.digest, manifest.assets.size, plan.offlineVariant) ==
            plan.planDigest
    }

    private fun digestOf(
        identity: ApkIdentity,
        template: TemplateInfo,
        manifestDigest: String,
        assetCount: Int,
        offlineVariant: Boolean,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val body = buildString {
            append(identity.packageName).append('\n')
            append(identity.appLabel).append('\n')
            append(identity.versionName).append('\n')
            append(identity.versionCode).append('\n')
            append(template.engineVersion).append('\n')
            append(template.abis.sorted().joinToString(",")).append('\n')
            append(manifestDigest).append('\n')
            append(assetCount).append('\n')
            append(offlineVariant).append('\n')
        }
        return md.digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
