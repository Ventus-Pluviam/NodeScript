package com.autoscript.appservice.packager

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 打包管线装配器（docs/framework-design.md §14 P0「打包：模板 APK 改装 + 签名向导」）。
 *
 * 管线三个阶段（设计 §6 模块职责：模板 APK 改写、签名向导、加密资产注入）：
 * 1. **模板复制**：[templateApk] → 工作目录（保留原始模板，防重复打包互相污染）；
 * 2. **模板改写**：[patch] —— 身份换写由注入的 [TemplatePatch] 完成；P0 真实现是
 *    [IdentityTemplatePatch]（AXML 改 package/version + ARSC 改 label 资源），
 *    缺省仍是 [TemplatePatch.NONE]（不改写，骨架期语义）；
 * 3. **资产注入**：[injectAssets] 把项目文件以 `assets/project/<relPath>` 写进包内
 *    （ApkRepacker tmp+rename 原子写回，同 script-repo 语义；批量一次重写）。
 *
 * 后续 transform 步骤（`zipalign`、`apksigner` 签名 —— 后者见 [ApkSignerRunner]、
 * 引擎 ABI 哈希记录）同样以注入的缝接进来，装配器本身不动。
 */
class PackagerPipeline(
    private val workDir: Path,
    private val templateApk: Path,
    private val templatePatch: TemplatePatch = TemplatePatch.NONE,
) {
    private val repacker = ApkRepacker()

    /** 拷贝模板到工作目录；目标已存在则拒绝（避免覆盖上次产物）。 */
    fun prepare(): Path {
        Files.createDirectories(workDir)
        val target = workDir.resolve(templateApk.fileName)
        if (Files.exists(target)) {
            throw AutojsException(ErrorCode.ERR_FILE_EXISTS, "工作目录已有打包产物 $target，请清理后重试")
        }
        if (!Files.isRegularFile(templateApk)) {
            throw AutojsException(
                ErrorCode.ERR_FILE_NOT_FOUND,
                "打包模板 APK 不存在：$templateApk",
                java.nio.file.NoSuchFileException(templateApk.toString()),
            )
        }
        Files.copy(templateApk, target)
        return target
    }

    /**
     * 注入一个项目资产进 [apk] 包内（条目名 = [ASSET_PREFIX] + [relPath]；
     * §3 打包行「注入 assets/project」）。
     * 校验相对路径（禁越界/目录穿越）后走 [ApkRepacker.addEntries]：tmp+rename 原子写回，
     * 半截包永不就位。同名二次注入 = 覆盖（幂等）。批量注入走 [injectAssets]（一次重写）。
     */
    fun injectAsset(apk: Path, relPath: String, bytes: ByteArray) =
        injectAssets(apk, mapOf(relPath to bytes))

    /**
     * 批量注入：全部资产在**一次** zip 重写里落位（逐文件重写是 O(n²)，编排层用这条）。
     * [assets] 的键是项目相对路径；空表 = no-op（不白跑一次重写）。
     */
    fun injectAssets(apk: Path, assets: Map<String, ByteArray>) {
        if (assets.isEmpty()) return
        val additions = LinkedHashMap<String, ByteArray>(assets.size)
        for ((relPath, bytes) in assets) {
            require(!relPath.startsWith("/")) { "资产相对路径不得以 / 开头：$relPath" }
            require(".." !in relPath.split('/', '\\')) { "资产相对路径不得含 ..：$relPath" }
            additions[ASSET_PREFIX + relPath] = bytes
        }
        repacker.addEntries(apk, additions, apk)
    }

    /** 返回注入的补丁（缺省 [TemplatePatch.NONE] 不改写；生产注入 [IdentityTemplatePatch]）。 */
    fun patch(): TemplatePatch = templatePatch

    /** 字节级模板变换接口：实现 AXML/ARSC 编辑替换 application + 引擎 ABI 哈希记录（§14）。 */
    interface TemplatePatch {
        fun apply(apk: Path): Path
        companion object {
            val NONE = object : TemplatePatch {
                override fun apply(apk: Path): Path = apk
            }
        }
    }

    companion object {
        /** 包内项目资产前缀（§3 打包行「注入 assets/project」；打包宿主按此路径读入口脚本）。 */
        const val ASSET_PREFIX = "assets/project/"
    }
}
