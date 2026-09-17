package com.autoscript.appservice.scriptrepo.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * 部署目录/路径安全（docs/framework-design.md §9.6：assets→filesDir 原子部署 + 防逃逸）。
 * 全部相对路径写入前必须过 [isSafeRelPath] 与 [resolveIn]。
 */
object DeployPath {

    /** relPath 合法：非空、相对、段内无 `..`/`.`/空段/反斜杠。 */
    fun isSafeRelPath(relPath: String): Boolean {
        if (relPath.isBlank()) return false
        if (relPath.startsWith("/") || relPath.startsWith("\\")) return false
        for (seg in relPath.split('/')) {
            if (seg == ".." || seg == "." || seg.isEmpty() || seg.contains('\\')) return false
        }
        return true
    }

    /** 解析到 root 之下并做二次校验（防符号链接/边界绕行）。 */
    fun resolveIn(root: Path, relPath: String): Path {
        require(isSafeRelPath(relPath)) { "非法相对路径: $relPath" }
        val base = root.toAbsolutePath().normalize()
        val target = base.resolve(relPath).normalize()
        require(target.startsWith(base)) { "路径逃逸拒绝: $relPath" }
        return target
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(file: Path): String = sha256(Files.readAllBytes(file))

    /** 目录 fsync：先创建目录（若缺），再对目录句柄 force。 */
    fun fsyncDirectory(dir: Path) {
        Files.createDirectories(dir)
        FileChannelForce.force(dir)
    }

    /** 单文件 fsync。 */
    fun fsyncFile(file: Path) {
        Files.newByteChannel(file, StandardOpenOption.WRITE).use { ch ->
            (ch as? java.nio.channels.FileChannel)?.force(true) ?: Unit
        }
    }

    private object FileChannelForce {
        fun force(dir: Path) {
            Files.newByteChannel(dir).use { ch ->
                (ch as? java.nio.channels.FileChannel)?.force(true) ?: Unit
            }
        }
    }
}

/** URL 编码关键字段，避免行式 journal 被分隔符污染。 */
internal object FieldCodec {
    fun enc(s: String): String = java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)
    fun dec(s: String): String = java.net.URLDecoder.decode(s, StandardCharsets.UTF_8)
}