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
 * 3. **资产注入**：[injectAsset] 模板 assets/ 内原子就位（tmp+rename，同 script-repo 语义）。
 *
 * 后续 transform 步骤（`zipalign`、`apksigner` 签名 —— 后者见 [ApkSignerRunner]、
 * 引擎 ABI 哈希记录）同样以注入的缝接进来，装配器本身不动。
 */
class PackagerPipeline(
    private val workDir: Path,
    private val templateApk: Path,
    private val templatePatch: TemplatePatch = TemplatePatch.NONE,
) {

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
     * 注入一个模板内资产：校验相对路径（禁越界/目录穿越），
     * 同目录临时文件写入 → 原子替换（半截文件永不就位，同 §9.6）。
     */
    fun injectAsset(apk: Path, relPath: String, bytes: ByteArray) {
        require(!relPath.startsWith("/")) { "资产相对路径不得以 / 开头：$relPath" }
        require(".." !in relPath.split('/', '\\')) { "资产相对路径不得含 ..：$relPath" }

        val target = apk.parent.resolve("unpacked").resolve(relPath)
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(".${target.fileName}.tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
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
}
