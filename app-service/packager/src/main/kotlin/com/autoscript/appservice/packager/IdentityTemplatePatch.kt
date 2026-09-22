package com.autoscript.appservice.packager

import com.autoscript.appservice.packager.axml.AxmlPatcher
import com.autoscript.appservice.packager.axml.ArscPatcher
import com.autoscript.appservice.packager.axml.ManifestAttrValue
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.packager.ApkIdentity
import java.nio.file.Path

/**
 * **真实**的模板改写补丁：把模板 APK 的 application 身份换成 [identity]
 * （docs/framework-design.md §14 P0「模板 APK 改装」；接在 [PackagerPipeline.TemplatePatch] 缝上）。
 *
 * 改写四处：
 * 1. `<manifest package>` → [ApkIdentity.packageName]（AXML 字面串）；
 * 2. `android:versionName` → [ApkIdentity.versionName]（字面串）；
 * 3. `android:versionCode` → [ApkIdentity.versionCode]（整型）；
 * 4. `<application android:label>` → [ApkIdentity.appLabel]：
 *    - 模板写的是**字面串** → 直接改 AXML；
 *    - 模板写的是 `@string/…`（REF 型）→ 走 [ArscPatcher] 按资源 id 改全局池，
 *      REF 的 `data`（资源 id）不变，AXML 无需重排；
 *    - ARSC 里**找不到**该资源（模板没带 resources.arsc / 该 id 未定义）→ **兜底降级
 *      为字面串**：显示名必须落到新身份上，宁可丢掉资源间接也不能留着模板的名字。
 *
 * 同时剔除旧 v1 签名条目（`META-INF` 下的 `.SF`/`.RSA`/`.DSA`/`.EC`/`MANIFEST.MF`）—— 内容已变，
 * 留着验签必炸；新签名由 [ApkSignerRunner] 另起 apksigner 产出。
 *
 * **不做的事**：不碰 `android:name`（组件类名属于模板自身代码，换包名靠 manifest 的
 * `package` + 组件相对名即可解析）；不做 zipalign（独立 transform 步骤）；不签名（见上）。
 * 改写前的 planDigest 复验由调用方走 [com.autoscript.domain.packager.TemplateApkPlans.verify]，
 * 本类只负责"给定身份，字节级落地"。
 */
internal class IdentityTemplatePatch(
    private val identity: ApkIdentity,
    private val repacker: ApkRepacker = ApkRepacker(),
) : PackagerPipeline.TemplatePatch {

    override fun apply(apk: Path): Path {
        val manifestBytes = repacker.readEntry(apk, MANIFEST_ENTRY)
            ?: throw AutojsException(ErrorCode.ERR_NOT_FOUND, "模板 APK 缺 $MANIFEST_ENTRY")

        val axml = AxmlPatcher.parse(manifestBytes)
        axml.setStringAttr(ELEMENT_MANIFEST, ATTR_PACKAGE, identity.packageName)
        axml.setStringAttr(ELEMENT_MANIFEST, ATTR_VERSION_NAME, identity.versionName)
        axml.setIntAttr(ELEMENT_MANIFEST, ATTR_VERSION_CODE, identity.versionCode)

        // label 走资源时改的是另一份字节（resources.arsc），成功则不重排 AXML。
        var arscReplacement: ByteArray? = null
        when (val label = axml.readAttr(ELEMENT_APPLICATION, ATTR_LABEL)) {
            null -> throw AutojsException(
                ErrorCode.ERR_INVALID_PARAM,
                "模板 manifest 缺 <application> 的 $ATTR_LABEL 属性",
            )

            is ManifestAttrValue.ResourceRef -> {
                val arscBytes = repacker.readEntry(apk, ARSC_ENTRY)
                if (arscBytes != null) {
                    val arsc = ArscPatcher.parse(arscBytes)
                    if (arsc.replaceStringResource(label.resourceId, identity.appLabel) > 0) {
                        arscReplacement = arsc.toByteArray()
                    }
                }
                // 资源没找到 → 落到下面的字面串兜底。
                if (arscReplacement == null) {
                    axml.setStringAttr(ELEMENT_APPLICATION, ATTR_LABEL, identity.appLabel)
                }
            }

            // 字面串 / bool / float / 未知类型：一律收敛成新的字面显示名。
            else -> axml.setStringAttr(ELEMENT_APPLICATION, ATTR_LABEL, identity.appLabel)
        }

        val replacements = LinkedHashMap<String, ByteArray>()
        replacements[MANIFEST_ENTRY] = axml.toByteArray()
        arscReplacement?.let { replacements[ARSC_ENTRY] = it }

        repacker.rewrite(apk, replacements, apk)
        return apk
    }

    private companion object {
        const val MANIFEST_ENTRY = "AndroidManifest.xml"
        const val ARSC_ENTRY = "resources.arsc"
        const val ELEMENT_MANIFEST = "manifest"
        const val ELEMENT_APPLICATION = "application"
        const val ATTR_PACKAGE = "package"
        const val ATTR_VERSION_NAME = "versionName"
        const val ATTR_VERSION_CODE = "versionCode"
        const val ATTR_LABEL = "label"
    }
}
