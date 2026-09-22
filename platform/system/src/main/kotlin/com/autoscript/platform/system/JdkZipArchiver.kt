package com.autoscript.platform.system

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.storage.ZipArchiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * `zip` 的 JVM 实现（docs §9.6；SPI 见 `:domain` 的 [ZipArchiver]）。
 * `java.util.zip` 零 Android 依赖 —— **本类没有 ops 缝**（README ops 表：
 * Android 接触面 = 无），整类进本机 JVM 单测，`Files.walk`/临时文件/rename
 * 全是真路径真 IO（@TempDir），不是替身回放。
 *
 * 三条契约在这里兑现，每条都有对应单测：
 *
 * 1. **zip-slip 先验后写**：每个条目 resolve + normalize 到目标根下，
 *    `startsWith(targetRoot)` 不成立（`../` 逃逸或绝对路径条目）→
 *    [AutojsException] `ERR_INVALID_PARAM`。**全包校验完才落字节** ——
 *    "前 100 个正常条目已写、第 101 个越界"不算防线（越界那个字节已经出去了）。
 *    所以分两拍（同一 [java.util.zip.ZipFile] 会话，中央目录打开时已固定）：
 *    第一拍全包条目名校验，第二拍逐条复验再写字节 —— 换的是"要么全在界内、
 *    要么一个字节没写"的干净语义。走 ZipFile 而非 ZipInputStream 是因为后者
 *    对垃圾字节是"零条目静默成功"，会把解了个寂寞冒充成功。
 * 2. **压缩原子落位**：先写 `<archive>.tmp` 再 rename（同目录同文件系统，
 *    ATOMIC_MOVE；个别文件系统不支持则退 REPLACE 移动）。中途失败删临时文件，
 *    不留半截 zip 冒充成品。
 * 3. **空目录以目录条目保留**：只拷文件不落目录条目的写法会让空目录消失
 *    （naive 实现的常见缺斤短两），这里目录条目显式 createDirectories。
 */
class JdkZipArchiver : ZipArchiver {

    override suspend fun compress(source: Path, archive: Path): Unit = withContext(Dispatchers.IO) {
        if (!Files.exists(source)) {
            throw AutojsException(ErrorCode.ERR_FILE_NOT_FOUND, "压缩源不存在: $source", null)
        }
        archive.parent?.let { Files.createDirectories(it) }
        val tmp = archive.resolveSibling("${archive.fileName}.tmp")
        try {
            ZipOutputStream(Files.newOutputStream(tmp)).use { zos ->
                if (Files.isDirectory(source)) {
                    // 目录：显式写目录条目（空目录也保得住），文件条目用相对源根的 / 路径。
                    val root = source.toAbsolutePath().normalize()
                    Files.walk(root).use { walk ->
                        walk.forEach { p ->
                            val rel = root.relativize(p).toString().replace('\\', '/')
                            if (rel.isEmpty()) return@forEach           // 源根自己不进包
                            val name = if (Files.isDirectory(p)) "$rel/" else rel
                            zos.putNextEntry(ZipEntry(name))
                            if (!Files.isDirectory(p)) Files.copy(p, zos)
                            zos.closeEntry()
                        }
                    }
                } else {
                    zos.putNextEntry(ZipEntry(source.fileName.toString()))
                    Files.copy(source, zos)
                    zos.closeEntry()
                }
            }
            try {
                Files.move(tmp, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, archive, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: AutojsException) {
            Files.deleteIfExists(tmp)
            throw e
        } catch (e: IOException) {
            Files.deleteIfExists(tmp)
            throw AutojsException(ErrorCode.ERR_IO, "压缩失败: ${e.message}", e)
        }
    }

    override suspend fun extract(archive: Path, targetDir: Path): Unit = withContext(Dispatchers.IO) {
        if (!Files.exists(archive)) {
            throw AutojsException(ErrorCode.ERR_FILE_NOT_FOUND, "归档不存在: $archive", null)
        }
        val targetRoot = Files.createDirectories(targetDir).toAbsolutePath().normalize()

        // ZipFile 走中央目录：不是合法 zip（连 EOCD 都没有的垃圾字节）构造期就抛
        // ZipException —— ZipInputStream 对垃圾是"零条目静默成功"，那会把
        // "解了个寂寞"冒充成功（单测「非法 zip 如实 ERR_IO」钉的就是这条）。
        try {
            ZipFile(archive.toFile()).use { zf ->
                val all = zf.entries().asSequence().toList()

                // ── 第一拍：全包条目名校验，任何一条越界都没开始写 ──
                for (entry in all) resolveIn(targetRoot, entry.name)

                // ── 第二拍：逐条复验 + 落字节（写它之前再验一次；同一 ZipFile 会话内
                //    中央目录已固定，两拍之间不存在换文件窗口） ──
                for (entry in all) {
                    val out = resolveIn(targetRoot, entry.name)
                    if (entry.isDirectory || entry.name.endsWith('/')) {
                        Files.createDirectories(out)
                    } else {
                        Files.createDirectories(out.parent)
                        zf.getInputStream(entry).use { ins ->
                            Files.copy(ins, out, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
            }
        } catch (e: AutojsException) {
            throw e
        } catch (e: ZipException) {
            throw AutojsException(ErrorCode.ERR_IO, "不是合法 zip: ${e.message}", e)
        } catch (e: IOException) {
            throw AutojsException(ErrorCode.ERR_IO, "解压失败: ${e.message}", e)
        }
    }

    /**
     * 条目名 → 目标根内的绝对路径；越界（`../` 逃逸 / 绝对路径条目）抛
     * `ERR_INVALID_PARAM`（zip-slip）。normalize 后比前缀：`a/../../x` 这类
     * 混了正常段的逃逸也会被剥出来判掉。
     */
    private fun resolveIn(targetRoot: Path, entryName: String): Path {
        if (entryName.isBlank()) {
            throw AutojsException(ErrorCode.ERR_INVALID_PARAM, "归档条目名为空", null)
        }
        val resolved = targetRoot.resolve(entryName).normalize()
        if (!resolved.startsWith(targetRoot)) {
            throw AutojsException(
                ErrorCode.ERR_INVALID_PARAM,
                "归档条目路径越界（zip-slip）: $entryName",
                null,
            )
        }
        return resolved
    }
}
