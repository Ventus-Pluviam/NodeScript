package com.autoscript.domain.packager

/**
 * APK 签名向导的领域契约（docs/framework-design.md §14 P0 打包：签名向导）。
 *
 * P0 切片：纯 Kotlin 领域模型（零 Android 依赖、JVM 可单测）。
 * 起进程与 Keystore 取密钥是实现侧细节，
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
 * apkSha256 由实现侧在改写产出 unsigned APK 后填写；领域层只做绑定校验。
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

    /** 签名前复验（起 apksigner 进程前）：请求仍对得上当初那份计划与清单。 */
    fun verify(request: SignRequest, plan: TemplateApkPlan, manifest: PackManifest): Boolean {
        if (!TemplateApkPlans.verify(plan, manifest)) return false
        return request.templatePlanDigest == plan.planDigest &&
            request.manifestDigest == manifest.digest
    }
}

/**
 * apksigner 参数表纯构造（`apksigner sign --ks … --out … <unsigned.apk>`）。
 * 只产字符串表，不起进程（起进程是实现侧细节，见 PackagerCollector 头注）。
 * 口令一律经 `env:<name>` 引用**环境变量**传递，不进参数表（防 ps 泄漏）。
 *
 * **语法钉死在 apksigner 的 OptionsParser 上**（不是猜的）：口令选项是
 * `--ks-pass <env:NAME>` / `--key-pass <env:NAME>` —— 选项与取值**两个独立 argv 项**，
 * 取值自带 `env:` 前缀。写成 `--ks-pass:env` 会被 apksigner 直接以
 * `Unsupported option` 拒绝（参数表形状看着对、一跑就炸），故此前的单体写法已改。
 * 该形态已对真 apksigner 手工验证（sign/verify 均过）；两项式 argv 形状由
 * packager 侧 `ApkSignerRunnerTest`（argv 与本表逐字比对）钉住。
 */
object ApkSignerArgs {

    /** 口令环境变量名（起进程的一侧必须把口令注入这两个名字，见 [ApkSignerRunner]）。 */
    const val KS_PASS_ENV = "AUTOSCRIPT_KS_PASS"
    const val KEY_PASS_ENV = "AUTOSCRIPT_KEY_PASS"

    fun build(
        request: SignRequest,
        unsignedApkPath: String,
        signedApkPath: String,
        keystorePath: String,
        ksPassEnv: String = KS_PASS_ENV,
        keyPassEnv: String = KEY_PASS_ENV,
    ): List<String> {
        require(unsignedApkPath.isNotBlank()) { "unsignedApkPath 不得为空" }
        require(signedApkPath.isNotBlank()) { "signedApkPath 不得为空" }
        require(keystorePath.isNotBlank()) { "keystorePath 不得为空" }
        return buildList {
            add("sign")
            add("--ks"); add(keystorePath)
            add("--ks-pass"); add("env:$ksPassEnv")
            when (request.spec.key) {
                is SigningKey.ReleaseKeystore -> {
                    add("--ks-key-alias"); add(request.spec.key.alias)
                    add("--key-pass"); add("env:$keyPassEnv")
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
