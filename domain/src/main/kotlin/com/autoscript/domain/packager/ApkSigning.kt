package com.autoscript.domain.packager

/**
 * APK 签名向导的领域契约（docs/framework-design.md §14 P0 打包：签名向导）。
 *
 * P0 切片：纯 Kotlin 领域模型（零 Android 依赖、JVM 可单测）。
 * 真机签名（apksigner 调用、Android Keystore 取密钥）是 Android 侧实现细节，
 * 消费这里校验过的 [SignRequest] 与 [ApkSignerArgs] 产出的参数表，契约不变。
 *
 * 惯例对齐 [LockSigner]：密钥来源是接缝（生产走 Android Keystore，测试走固定字节）；
 * 签名体绑定计划摘要 —— 只签当初改写的那份清单，清单错配即拒绝。
 */
sealed interface SigningKey {

    /** 调试密钥（开发包：构建期临时生成，不进发布通道）。 */
    data object DebugEphemeral : SigningKey

    /** 发布密钥库（Android 侧实现凭描述打开 Keystore，领域层不碰文件）。 */
    data class ReleaseKeystore(
        val keystorePath: String,
        val alias: String,
    ) : SigningKey {
        init {
            require(keystorePath.isNotBlank()) { "keystorePath 不得为空" }
            require(alias.isNotBlank()) { "alias 不得为空" }
        }
    }
}

/** 一次签名的规格：密钥 + 签名方案开关（v1 JAR / v2 APK，默认全开）。 */
data class SignSpec(
    val key: SigningKey,
    val v1Enabled: Boolean = true,
    val v2Enabled: Boolean = true,
) {
    init {
        require(v1Enabled || v2Enabled) { "v1/v2 至少开一个，否则产出未签名包" }
    }
}

/**
 * 一次签名的请求：待签 APK 内容摘要 + 所依据的改写计划摘要 + 规格。
 * apkSha256 由 Android 侧在改写产出 unsigned APK 后填写；领域层只做绑定校验。
 */
data class SignRequest(
    val templatePlanDigest: String,
    val manifestDigest: String,
    val apkSha256: String,
    val spec: SignSpec,
)

object SignPlans {

    /**
     * 由已复验的 [TemplateApkPlan] 组装签名请求。
     * plan 必须先过 [TemplateApkPlans.verify] —— 不对"来历不明"的计划签名。
     */
    fun build(plan: TemplateApkPlan, manifest: PackManifest, apkSha256: String, spec: SignSpec): SignRequest {
        require(TemplateApkPlans.verify(plan, manifest)) { "改写计划与资产清单对不上，拒绝签名" }
        require(apkSha256.isNotBlank()) { "apkSha256 不得为空" }
        return SignRequest(
            templatePlanDigest = plan.planDigest,
            manifestDigest = manifest.digest,
            apkSha256 = apkSha256,
            spec = spec,
        )
    }

    /** 签名前复验（Android 侧调用 apksigner 前）：请求仍对得上当初那份计划与清单。 */
    fun verify(request: SignRequest, plan: TemplateApkPlan, manifest: PackManifest): Boolean {
        if (!TemplateApkPlans.verify(plan, manifest)) return false
        return request.templatePlanDigest == plan.planDigest &&
            request.manifestDigest == manifest.digest
    }
}

/**
 * apksigner 参数表纯构造（`apksigner sign --ks … --out … <unsigned.apk>`）。
 * 只产字符串表，不起进程（起进程是 Android 侧实现，见 PackagerCollector 头注）。
 * 口令一律经 `--ks-pass:env` / `--key-pass:env` 环境变量传递，不进参数表（防 ps 泄漏）。
 */
object ApkSignerArgs {

    fun build(
        request: SignRequest,
        unsignedApkPath: String,
        signedApkPath: String,
        keystorePath: String,
        ksPassEnv: String = "AUTOSCRIPT_KS_PASS",
        keyPassEnv: String = "AUTOSCRIPT_KEY_PASS",
    ): List<String> {
        require(unsignedApkPath.isNotBlank()) { "unsignedApkPath 不得为空" }
        require(signedApkPath.isNotBlank()) { "signedApkPath 不得为空" }
        require(keystorePath.isNotBlank()) { "keystorePath 不得为空" }
        return buildList {
            add("sign")
            add("--ks"); add(keystorePath)
            add("--ks-pass:env"); add(ksPassEnv)
            when (request.spec.key) {
                is SigningKey.ReleaseKeystore -> {
                    add("--ks-key-alias"); add(request.spec.key.alias)
                    add("--key-pass:env"); add(keyPassEnv)
                }
                SigningKey.DebugEphemeral -> {
                    add("--ks-key-alias"); add("androiddebugkey")
                }
            }
            add("--v1-signing-enabled"); add(request.spec.v1Enabled.toString())
            add("--v2-signing-enabled"); add(request.spec.v2Enabled.toString())
            add("--out"); add(signedApkPath)
            add(unsignedApkPath)
        }
    }
}
