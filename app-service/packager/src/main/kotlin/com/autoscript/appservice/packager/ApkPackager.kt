package com.autoscript.appservice.packager

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.packager.ApkIdentity
import com.autoscript.domain.packager.PackManifest
import com.autoscript.domain.packager.PackSpec
import com.autoscript.domain.packager.SignPlans
import com.autoscript.domain.packager.TemplateApkPlan
import com.autoscript.domain.packager.TemplateApkPlans
import com.autoscript.domain.packager.TemplateInfo
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 打包全链编排（docs §14 P0「写一个项目 → 独立 APK」在 `:app-service:packager` 内的闭环）：
 *
 * ```
 * planWithManifest ─► prepare ─► 模板改写 ──► 资产注入 ─► zipalign ─► apksigner
 *   (清单自哈希)      (复制模板) (身份/组件/图标) (assets/project) (对齐)      (签名)
 * ```
 *
 * **复验两道，缺一不可**（都来自领域契约，不自创）：
 * 1. [TemplateApkPlans.verify] —— 进场先验计划与清单仍是同源那份（wizard"看过计划再打包"
 *    的那条缝：plan 可以很早产出，pack 时必须重验）；
 * 2. 逐文件 sha256 对清单 —— collect 与注入之间文件被改/被删即拒绝（关 TOCTOU 窗口；
 *    SignPlans 的摘要绑的是清单，清单绑的是这些哈希）。
 *
 * **两个外进程各走注入的 runner**（[ZipAlignRunner] / [ApkSignerRunner]，与
 * `HostNodeExecutor` 同一缝惯例）：本类不猜二进制路径、不拼 argv —— 测试注入记录型/
 * 假可执行体，生产由调用方（打包向导）给真前缀。顺序铁律在本类里焊死：**先对齐再签名**
 * （签完再动条目布局 = v2 验签必炸），[SignPlans.build] 的 apkSha256 取**对齐后**的字节。
 *
 * **诚实边界**：不取 Keystore 口令（Android Keystore 取密是
 * 调用方的事，口令只进 [Signing] 内存与 apksigner 的环境变量，不进 argv）、
 * 不做加密资产/自定义 loader（§3 打包行的后续项）。打包向导 UI 尚未落地 ——
 * 本类是它脚下的那条链，先在纯 JVM 上闭环可测。
 */
class ApkPackager(
    private val workDir: Path,
    private val templateApk: Path,
    private val zipAligner: ZipAlignRunner,
    /** null = 不签名（产物是对齐后的 unsigned 包）；给了就必须给 [zipAligner] 且签名器在位。 */
    private val signer: ApkSignerRunner? = null,
    /**
     * 启动图标源文件（PNG；null = 模板图标原样）。与 [templateApk] 同级的**装配配置** ——
     * 不入 planDigest（模板文件本身也不入；向导应在 plan 前定好图标）。给了就在模板改写
     * 同趟换掉全部密度 `ic_launcher(_round).png` 并剔除 anydpi 自适应 XML（API26+ 会拿
     * 自适应遮住 PNG）；文件缺 / 非 PNG 魔数 / 模板无密度 PNG 都在动模板**之前**如实失败。
     * 注入 [templatePatch] 的调用方拿同一份字节自行落地（不接 = 显式放弃，调用方自知）。
     */
    private val iconPng: Path? = null,
    private val collector: PackagerCollector = PackagerCollector(),
    /**
     * 模板改写实现注入缝（默认真补丁 [IdentityTemplatePatch]；测试可换成记事本补丁）。
     * 第二参 = [iconPng] 读出且过魔数校验的字节（null = 没配图标）。
     */
    private val templatePatch: (ApkIdentity, ByteArray?) -> PackagerPipeline.TemplatePatch =
        { id, icon -> IdentityTemplatePatch(id, iconPng = icon) },
) {

    /**
     * 签名所需的一切。口令只活在本对象与 apksigner 的环境变量里
     * （[com.autoscript.domain.packager.ApkSignerArgs] 的 argv 只带 `env:NAME` 引用）。
     */
    data class Signing(
        val spec: com.autoscript.domain.packager.SignSpec,
        val keystorePath: String,
        val ksPass: String,
        val keyPass: String,
    )

    /** 打包产物 + 过程凭据（计划/清单交还调用方展示与归档）。 */
    data class Result(
        /** 交付物：签名产物；未签名时是对齐后的 unsigned 包。 */
        val apk: Path,
        /** 对齐后的未签名包（签名路径的中间产物，始终存在）。 */
        val unsignedAligned: Path,
        /** apksigner 产物；[Signing] 为空时 null。 */
        val signed: Path?,
        val plan: TemplateApkPlan,
        val manifest: PackManifest,
    )

    /**
     * 立计划：收集清单（自哈希）+ 组装改写计划。向导"先给用户看，确认后再 [pack]"的前半段。
     * 清单自哈希失败/入口缺失由 [PackagerCollector] 直接抛（不产出坏计划）。
     */
    fun plan(
        spec: PackSpec,
        projectRoot: Path,
        identity: ApkIdentity,
        template: TemplateInfo,
    ): PackagerCollector.Planned = collector.planWithManifest(spec, projectRoot, identity, template)

    /** 一条龙：内部 [plan] → [pack]（适合无预览的批场景；向导走两段式）。 */
    fun pack(
        spec: PackSpec,
        projectRoot: Path,
        identity: ApkIdentity,
        template: TemplateInfo,
        signing: Signing? = null,
    ): Result = pack(plan(spec, projectRoot, identity, template), projectRoot, signing)

    /**
     * 执行打包（两段式的后半段）。[planned] 可以是很久以前 [plan] 出来的 ——
     * 进场先 [TemplateApkPlans.verify] 重验，再逐文件对清单哈希，两道都过才动模板。
     */
    fun pack(
        planned: PackagerCollector.Planned,
        projectRoot: Path,
        signing: Signing? = null,
    ): Result {
        if (signing != null && signer == null) {
            throw AutojsException(ErrorCode.ERR_INVALID_PARAM, "请求了签名但本包装配器未装 ApkSignerRunner")
        }
        val (plan, manifest) = planned
        if (!TemplateApkPlans.verify(plan, manifest)) {
            throw AutojsException(
                ErrorCode.ERR_INVALID_PARAM,
                "改写计划与资产清单对不上（清单被换过或两段式之间现场已漂），拒绝打包",
            )
        }

        // 逐文件复核清单哈希：collect 之后文件被改/删 —— 在动模板之前就拒绝。
        val assets = LinkedHashMap<String, ByteArray>()
        for (entry in manifest.assets.sortedBy { it.relPath }) {
            val file = projectRoot.resolve(entry.relPath).normalize()
            if (!file.startsWith(projectRoot.normalize())) {
                throw AutojsException(ErrorCode.ERR_INVALID_PARAM, "资产路径越出项目根：${entry.relPath}")
            }
            val bytes = try {
                Files.readAllBytes(file)
            } catch (e: java.nio.file.NoSuchFileException) {
                throw AutojsException(ErrorCode.ERR_NOT_FOUND, "清单里的资产已不存在：${entry.relPath}", e)
            }
            if (sha256Hex(bytes) != entry.sha256) {
                throw AutojsException(
                    ErrorCode.ERR_INVALID_PARAM,
                    "资产内容与清单不符（计划产出后文件被改过）：${entry.relPath}",
                )
            }
            assets[entry.relPath] = bytes
        }

        // 图标读出 + 魔数校验：赶在 prepare 之前（与"动模板前早失败"同一条纪律）。
        val iconBytes = iconPng?.let { p ->
            val bytes = try {
                Files.readAllBytes(p)
            } catch (e: java.nio.file.NoSuchFileException) {
                throw AutojsException(ErrorCode.ERR_NOT_FOUND, "图标文件不存在：$p", e)
            }
            requirePngIcon(bytes, "图标文件 $p")
            bytes
        }

        // 1) 复制模板 → 2) 模板改写（身份/组件/图标）→ 3) 资产注入（一次批量重写）
        val pipeline = PackagerPipeline(workDir, templateApk, templatePatch(plan.identity, iconBytes))
        var apk = pipeline.prepare()
        apk = pipeline.patch().apply(apk)
        pipeline.injectAssets(apk, assets)

        // 4) zipalign（先对齐）—— 产出就是"待签字节"，apkSha256 从这里取。
        val unsignedAligned = workDir.resolve(alignedName(templateApk))
        zipAligner.align(apk, unsignedAligned)

        // 5) apksigner（后签名）：build 内含 verify，签名前再 verify 一道（领域契约要求）。
        val signed = if (signing == null) {
            null
        } else {
            val request = SignPlans.build(plan, manifest, sha256Hex(unsignedAligned), signing.spec)
            if (!SignPlans.verify(request, plan, manifest)) {
                throw AutojsException(ErrorCode.ERR_INVALID_PARAM, "签名前复验失败，拒绝起 apksigner")
            }
            signer!!.sign(
                request,
                unsignedAligned,
                workDir.resolve(signedName(templateApk)),
                signing.keystorePath,
                signing.ksPass,
                signing.keyPass,
            )
        }

        return Result(
            apk = signed ?: unsignedAligned,
            unsignedAligned = unsignedAligned,
            signed = signed,
            plan = plan,
            manifest = manifest,
        )
    }

    private fun alignedName(template: Path): String {
        val n = template.fileName.toString()
        return if (n.endsWith(".apk")) n.removeSuffix(".apk") + "-aligned.apk" else "$n-aligned"
    }

    private fun signedName(template: Path): String {
        val n = template.fileName.toString()
        return if (n.endsWith(".apk")) n.removeSuffix(".apk") + "-signed.apk" else "$n-signed"
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** APK 级摘要流式算（几十 MB 的包不 whole-read 进堆）。 */
    private fun sha256Hex(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
