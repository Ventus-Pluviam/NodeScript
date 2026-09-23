package com.autoscript.appservice.packager

import com.autoscript.appservice.packager.axml.AxmlPatcher
import com.autoscript.appservice.packager.axml.ArscPatcher
import com.autoscript.appservice.packager.axml.ManifestAttrValue
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.packager.ApkIdentity
import java.nio.file.Path

/**
 * **真实**的模板改写补丁：把模板 APK 的 application 身份换成 [identity]，并按新身份落地
 * 组件类名与启动图标（docs/framework-design.md §14 P0「模板 APK 改装」；接在
 * [PackagerPipeline.TemplatePatch] 缝上）。一次 [ApkRepacker.rewrite] 同趟出包。
 *
 * 改写内容：
 * 1. `<manifest package>` → [ApkIdentity.packageName]（AXML 字面串）；
 * 2. `android:versionName` → [ApkIdentity.versionName]（字面串）；
 * 3. `android:versionCode` → [ApkIdentity.versionCode]（整型）；
 * 4. `<application android:label>` → [ApkIdentity.appLabel]：
 *    - 模板写的是**字面串** → 直接改 AXML；
 *    - 模板写的是 `@string/…`（REF 型）→ 走 [ArscPatcher] 按资源 id 改全局池，
 *      REF 的 `data`（资源 id）不变，AXML 无需重排；
 *    - ARSC 里**找不到**该资源（模板没带 resources.arsc / 该 id 未定义）→ **兜底降级
 *      为字面串**：显示名必须落到新身份上，宁可丢掉资源间接也不能留着模板的名字。
 * 5. **组件类名按旧包绝对化**：`application`/`activity`/`activity-alias`/`service`/
 *    `receiver`/`provider` 的 `android:name` 与 alias 的 `targetActivity` —— 相对名
 *    （`.Main`）与裸名（`Main`）改写成 `旧包+…`；**本就含 `.` 的绝对名原样**。依据：
 *    Android 按 `buildClassName(改写后的 package, android:name)` 解析组件类，而模板 dex
 *    的命名空间随模板编译定死在**旧包** —— 换掉 manifest `package` 后相对名会按新包
 *    解析，类并不存在，装上点开即崩；绝对名与外部类（`com.other.*`、框架类）指着字节码
 *    里真实存在的类，一个都不该动（旧 KDoc"相对名即可解析"正是这里漏的）。
 * 6. **启动图标**（[iconPng] 非空时，按 [ApkRepacker.entries] 实况枚举）：全部密度
 *    `res/(mipmap|drawable)-<密度限定词>/ic_launcher(_round).png` 换成用户字节；`anydpi` 自适应
 *    XML 一并剔除（API26+ 优先取自适应，留着会把新图标遮掉）；模板没有密度 PNG →
 *    [ErrorCode.ERR_NOT_FOUND] 如实拒绝，不产出"图标没换还说换了"的包。
 *
 * 同时剔除旧 v1 签名条目（`META-INF` 下的 `.SF`/`.RSA`/`.DSA`/`.EC`/`MANIFEST.MF`）—— 内容已变，
 * 留着验签必炸；新签名由 [ApkSignerRunner] 另起 apksigner 产出。
 *
 * **不做的事**：不碰非类查找的 `android:name`（`<action>`/`<uses-permission>`/权限名是
 * 字符串常量而非 `Class.forName` —— 声明与引用成对保留照样互相匹配，改包名不影响可运行性）；
 * 不解码/缩放 PNG（魔数过检即入包；缩放与 adaptive 分层编辑不属本补丁职责，对应深度定制需求已从 §14 移除）；
 * 不做 zipalign（独立 transform 步骤）；不签名（见上）。
 * 改写前的 planDigest 复验由调用方走 [com.autoscript.domain.packager.TemplateApkPlans.verify]，
 * 本类只负责"给定身份与图标，字节级落地"。
 */
internal class IdentityTemplatePatch(
    private val identity: ApkIdentity,
    private val repacker: ApkRepacker = ApkRepacker(),
    private val iconPng: ByteArray? = null,
) : PackagerPipeline.TemplatePatch {

    init {
        if (iconPng != null) requirePngIcon(iconPng)
    }

    override fun apply(apk: Path): Path {
        val manifestBytes = repacker.readEntry(apk, MANIFEST_ENTRY)
            ?: throw AutojsException(ErrorCode.ERR_NOT_FOUND, "模板 APK 缺 $MANIFEST_ENTRY")

        val axml = AxmlPatcher.parse(manifestBytes)

        // 旧包先取：组件绝对化的基准必须是模板编译时的包 —— 赶在 package 换新身份之前。
        val oldPkg = when (val pkg = axml.requireAttr(ELEMENT_MANIFEST, ATTR_PACKAGE)) {
            is ManifestAttrValue.Text -> pkg.value
            else -> throw AutojsException(
                ErrorCode.ERR_INVALID_PARAM,
                "模板 manifest package 非字面串，无法按旧包绝对化组件名",
            )
        }
        axml.transformStringAttrs(COMPONENT_ATTR_ELEMENTS, ATTR_NAME) { absolutizeClassName(it, oldPkg) }
        axml.transformStringAttrs(setOf(ELEMENT_ACTIVITY_ALIAS), ATTR_TARGET_ACTIVITY) {
            absolutizeClassName(it, oldPkg)
        }

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

        // 图标：换什么/删什么都以包内实况为准（正则只筛已有条目，不盲写名字）。
        var removals: Set<String> = emptySet()
        if (iconPng != null) {
            val names = repacker.entries(apk)
            val densityIcons = names.filter { DENSITY_ICON.matches(it) }
            if (densityIcons.isEmpty()) {
                throw AutojsException(
                    ErrorCode.ERR_NOT_FOUND,
                    "模板没有可替换的密度图标 PNG（res/(mipmap|drawable)-*/ic_launcher(_round).png），无法换图标",
                )
            }
            for (name in densityIcons) replacements[name] = iconPng
            removals = names.filter { ADAPTIVE_ICON.matches(it) }.toSet()
        }

        repacker.rewrite(apk, replacements, apk, removals)
        return apk
    }

    private companion object {
        const val MANIFEST_ENTRY = "AndroidManifest.xml"
        const val ARSC_ENTRY = "resources.arsc"
        const val ELEMENT_MANIFEST = "manifest"
        const val ELEMENT_APPLICATION = "application"
        const val ELEMENT_ACTIVITY_ALIAS = "activity-alias"
        const val ATTR_PACKAGE = "package"
        const val ATTR_VERSION_NAME = "versionName"
        const val ATTR_VERSION_CODE = "versionCode"
        const val ATTR_LABEL = "label"
        const val ATTR_NAME = "name"
        const val ATTR_TARGET_ACTIVITY = "targetActivity"

        /** 走 buildClassName 类查找的组件元素（alias 另有 targetActivity，见 apply）。 */
        val COMPONENT_ATTR_ELEMENTS = setOf(
            "application", "activity", "activity-alias", "service", "receiver", "provider",
        )

        /** 要替换的密度图标（`-v4` 等限定词后缀按实况吃进 `[A-Za-z0-9\-]+`）。 */
        val DENSITY_ICON = Regex("""res/(mipmap|drawable)-[A-Za-z0-9\-]+/ic_launcher(_round)?\.png""")

        /** 要剔除的自适应图标 XML（API26+ 会优先取它，遮住刚换的密度 PNG）。 */
        val ADAPTIVE_ICON = Regex("""res/(mipmap|drawable)-anydpi(-v\d+)?/ic_launcher(_round)?\.xml""")

        /**
         * 类名按旧包绝对化：`.Foo` / 裸 `Foo` → `oldPkg+…`（与改包前运行时的解析结果一致，
         * dex 命中）；本就含 `.` 的绝对名原样（模板字节码/外部库的命名空间）。空白名原样
         * 跳过（aapt2 产物不会有；畸形名留运行时如实报，不猜）。
         */
        fun absolutizeClassName(name: String, oldPkg: String): String = when {
            name.isBlank() -> name
            name.startsWith(".") -> oldPkg + name
            '.' in name -> name
            else -> "$oldPkg.$name"
        }
    }
}

/**
 * PNG 魔数校验（换图标入包前的格式门槛）：本补丁不解码、不缩放 —— 模板原样吃字节，
 * 尺寸/内容是否好看交给运行时与肉眼。[source] 只进报错文案（带路径更好定位）。
 */
internal fun requirePngIcon(bytes: ByteArray, source: String = "图标") {
    val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    if (bytes.size < magic.size || !magic.indices.all { bytes[it] == magic[it] }) {
        throw AutojsException(ErrorCode.ERR_INVALID_PARAM, "$source 不是 PNG（缺 89 50 4E 47 魔数）")
    }
}
