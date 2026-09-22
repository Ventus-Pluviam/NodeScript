package com.autoscript.appservice.packager

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * APK（zip）条目级重写：替换若干条目、丢弃旧签名、其余条目原样搬运。
 *
 * 为什么不用 `java.nio.file.FileSystems.newFileSystem(zip:)`：那条路对 STORED 条目
 * 会重算 crc/size 却不管对齐，且改完关不干净容易留半截文件。这里显式走
 * [ZipFile] 读 + [ZipOutputStream] 写：每个条目自己决定压缩方式，写入走 tmp+rename，
 * 半截文件永不就位（与 §9.6 同语义）。
 *
 * **诚实边界**：本类**不做 zipalign**。重写后的包 uncompressed 条目不再按 4 字节对齐，
 * 装机前仍须过 `zipalign`（设计 §14 把它列为独立 transform 步骤）。
 */
internal class ApkRepacker {

    /**
     * 读 [apk]，把 [replacements] 里给出的条目换成新字节，剔除旧签名，写回 [target]。
     * 未在 [replacements] 中出现的条目名照抄原字节（含压缩方式）。
     * **替换项必须都真的存在于模板里**：打错包名/路径早失败，不产出"少一个条目"的坏包。
     */
    fun rewrite(apk: Path, replacements: Map<String, ByteArray>, target: Path) =
        rewriteInternal(apk, replacements, target, allowNew = false)

    /**
     * 往 [apk] 追加/覆盖 [additions] 条目并写回 [target] —— 与 [rewrite] 的分工：
     * 这里的名字**可以是模板没有的新条目**（资产注入 `assets/project/…` 走这条），
     * 已存在的则替换字节。条目名做路径校验（禁 `..` / 绝对路径），防御性双保险
     * （调用方 [PackagerPipeline.injectAssets] 已先校验相对路径）。
     * 新条目一律 DEFLATED（项目资产没有"必须 STORED"的约束；对齐交 zipalign）。
     */
    fun addEntries(apk: Path, additions: Map<String, ByteArray>, target: Path) {
        for (name in additions.keys) {
            require(!name.startsWith("/") && ".." !in name.split('/', '\\')) {
                "包内条目名不得是绝对路径或含 ..：$name"
            }
        }
        rewriteInternal(apk, additions, target, allowNew = true)
    }

    private fun rewriteInternal(
        apk: Path,
        replacements: Map<String, ByteArray>,
        target: Path,
        allowNew: Boolean,
    ) {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(".${target.fileName}.tmp")
        try {
            ZipFile(apk.toFile()).use { zf ->
                if (!allowNew) {
                    val names = zf.entries().asSequence().map { it.name }.toSet()
                    val missing = replacements.keys - names
                    if (missing.isNotEmpty()) {
                        throw AutojsException(ErrorCode.ERR_NOT_FOUND, "模板 APK 缺条目：${missing.sorted()}")
                    }
                }
                ZipOutputStream(Files.newOutputStream(tmp)).use { out ->
                    val written = HashSet<String>()
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name
                        if (isV1SignatureEntry(name)) continue // 旧签名必须剔除（内容已变，留着验签必炸）
                        if (!written.add(name)) continue        // 重复条目：只留第一个
                        val replacement = replacements[name]
                        if (replacement != null) {
                            // 覆盖既有条目：新字节按原条目的压缩方式写回（目录仍 STORED）。
                            writeEntry(out, name, entry.method, replacement)
                        } else {
                            writeEntry(out, name, entry.method, zf.getInputStream(entry).readBytes())
                        }
                    }
                    // allowNew 时把模板没有的新条目补在表尾（顺序 = 调用方 map 迭代序）。
                    if (allowNew) {
                        for ((name, bytes) in replacements) {
                            if (name in written) continue
                            writeEntry(out, name, ZipEntry.DEFLATED, bytes)
                            written.add(name)
                        }
                    }
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AutojsException) {
            Files.deleteIfExists(tmp)
            throw e
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw AutojsException(ErrorCode.ERR_IO, "APK 重写失败：${e.message}", e)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** 读单个条目字节（不存在返回 null）。 */
    fun readEntry(apk: Path, name: String): ByteArray? {
        ZipFile(apk.toFile()).use { zf ->
            val entry = zf.getEntry(name) ?: return null
            return zf.getInputStream(entry).readBytes()
        }
    }

    private fun writeEntry(out: ZipOutputStream, name: String, method: Int, bytes: ByteArray) {
        val entry = ZipEntry(name)
        // 目录条目按原样保留（空字节 + STORED），否则解包时少了目录语义。
        if (method == ZipEntry.STORED) {
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            val crc = CRC32()
            crc.update(bytes)
            entry.crc = crc.value
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        out.putNextEntry(entry)
        out.write(bytes)
        out.closeEntry()
    }

    private fun isV1SignatureEntry(name: String): Boolean {
        val upper = name.uppercase()
        if (!upper.startsWith("META-INF/")) return false
        return upper.endsWith(".SF") || upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") || upper.endsWith(".EC") ||
            upper == "META-INF/MANIFEST.MF"
    }
}
