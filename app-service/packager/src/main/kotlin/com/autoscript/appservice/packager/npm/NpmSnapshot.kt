package com.autoscript.appservice.packager.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.npm.SnapshotRef
import java.io.BufferedOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 高信任快照导出/验签（docs/framework-design.md §10.9.4 + §10.7 [SnapshotRef]）。
 *
 * 产物 = `node_modules.zip`（含 `package.json` + `package-lock.json` + `node_modules` 全树
 * + `ledger/` 审批账本/审计史 + 随包同行的 `lock.sig`），外加一条 `snapshot.sig`：
 * `v1 <HMAC-SHA256>`，签名体 = `autojs-snapshot-v1|<projectId>|<lock.sig 原文>|<内容清单哈希>`。
 *
 * 为什么签**内容清单**而不是归档字节：归档头带时间戳/压缩方差，逐字节签会让同一棵树
 * 两次导出签不一致，导入方还得按位比对；签 `相对路径 + 逐文件 sha256` 的有序清单则
 * 与打包细节无关，重打包/换压缩级后仍可验（§10.9.4「验签」的语义就是「内容一致」）。
 *
 * 与 [LockSigner] 的关系：本类签的是「这一包内容」，[LockSigner] 签的是「这份 lock
 * 是本机认可的」。快照把项目的 `lock.sig` 一并打包，导入方取出 lock + lock.sig 后仍要
 * 过 [LockSigner.verifyOrThrow] —— 两层都过才算高信任闭环，缺一层都拒绝。
 *
 * key 走 [LockSigner.KeyProvider] 接缝（Android Keystore / 测试固定字节），本类纯 JVM 可单测。
 */
class NpmSnapshot(
    private val layout: NpmProjectLayout,
    /** `files/.autojs`（`approve-ledger.jsonl` / `install-history.jsonl` / `lock.sig` 的来源）。 */
    private val ledgerDir: Path? = null,
    private val key: LockSigner.KeyProvider,
) {

    /**
     * 默认交付：把 tmp zip 落到 [uri] 并按文件系统路径复制 + fsync。
     * Android 侧走 SAF（用户选定的位置）时，装配层包一层替身覆写这一跳——
     * 本类保持零 android.*（archUnit 守护），故只提供文件系统形态。
     */
    fun sync(built: Build, uri: String) {
        val dest = Path.of(uri)
        Files.createDirectories(dest.parent)
        Files.copy(built.zip, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        java.nio.channels.FileChannel.open(dest, StandardOpenOption.WRITE).use { it.force(true) }
    }

    /** 导出结果（zip 仍是临时文件，调用方负责复制后删除）。 */
    data class Build(
        val zip: Path,
        val sizeBytes: Long,
        /** 归档字节的 sha256（SnapshotRef.sha256）。 */
        val archiveSha256: String,
        /** 内容清单里登记的文件数（不含 snapshot.sig 自身）。 */
        val entries: Int,
        val contentBytes: Long,
    )

    /** 验签通过的快照摘要（供调用方决定下一步，如取 lock 走 LockSigner 复核）。 */
    data class Manifest(
        val entries: Int,
        val contentBytes: Long,
        /** 随包同行的 lock.sig 原文（null = 快照未带带外锚，导入侧须另行定夺）。 */
        val lockSig: String?,
    )

    /**
     * 导出到 [out]（调用方给临时路径）。缺失 `package-lock.json` 即失败 ——
     * 没有 lock 的快照无法在导入侧验签闭环，给出去反而是欺骗。
     */
    fun export(projectId: String, out: Path): Build {
        // projectId 合法性先过（防路径逃逸），再谈内容
        layout.projectRoot(projectId)
        val lock = layout.lockfile(projectId)
        if (!Files.isRegularFile(lock)) {
            throw AutojsException(ErrorCode.ERR_FILE_NOT_FOUND, "快照导出拒绝：项目无 lockfile（$lock）")
        }
        val lockSigText = ledgerDir?.resolve("lock.sig")?.takeIf { Files.isRegularFile(it) }
            ?.let { String(Files.readAllBytes(it), StandardCharsets.UTF_8).trim() }

        val body = StringBuilder()
        var contentBytes = 0L
        var count = 0
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { raw ->
            DigestOutputStream(BufferedOutputStream(raw), digest).use { counted ->
                ZipOutputStream(counted).use { zip ->
                    for ((rel, src) in collect(projectId, ledgerDir)) {
                        val bytes = Files.readAllBytes(src)
                        zip.putNextEntry(ZipEntry(rel).apply { time = DOS_EPOCH })
                        zip.write(bytes)
                        zip.closeEntry()
                        body.append(rel).append(' ').append(DirSizer.sha256(bytes)).append('\n')
                        contentBytes += bytes.size
                        count++
                    }
                    val sig = hmac(signBody(projectId, lockSigText, sha256(body.toString().toByteArray(StandardCharsets.UTF_8))))
                    zip.putNextEntry(ZipEntry(SIG_NAME).apply { time = DOS_EPOCH })
                    zip.write(("v1 " + sig + "\n").toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
        return Build(
            zip = out,
            sizeBytes = Files.size(out),
            archiveSha256 = hex(digest.digest()),
            entries = count,
            contentBytes = contentBytes,
        )
    }

    /**
     * 验签：通过则返回 [Manifest]；任何不符一律抛 [AutojsException]（ERR_PERMISSION_DENIED），
     * 缺 `snapshot.sig` 同样拒（TOFU 自签正是被批判的形态）。条目含 `..`/绝对路径 → ERR_INVALID_PARAM
     * （解包即路径逃逸，先拒再谈信任）。
     */
    fun verify(projectId: String, zipFile: Path): Manifest {
        if (!Files.isRegularFile(zipFile)) {
            throw AutojsException(ErrorCode.ERR_FILE_NOT_FOUND, "快照文件不存在：$zipFile")
        }
        val body = StringBuilder()
        var contentBytes = 0L
        var count = 0
        var sigText: String? = null
        var lockSig: String? = null
        ZipFile(File(zipFile.toUri())).use { zf ->
            val en = zf.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.isDirectory) continue
                val name = e.name
                if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                    throw AutojsException(ErrorCode.ERR_INVALID_PARAM, "快照含非法路径条目：$name")
                }
                val bytes = zf.getInputStream(e).readBytes()
                if (name == SIG_NAME) {
                    sigText = String(bytes, StandardCharsets.UTF_8).trim()
                    continue   // 签文件自身不入清单（否则签名要覆盖自己，永不可验）
                }
                // lock.sig **既**是清单条目（内容变了要验出来）**又**是签名体的一路输入
                // （本机对这份 lock 的认可）：两件事都要，不能二选一。
                if (name == "lock.sig") lockSig = String(bytes, StandardCharsets.UTF_8).trim()
                body.append(name).append(' ').append(DirSizer.sha256(bytes)).append('\n')
                contentBytes += bytes.size
                count++
            }
        }
        if (sigText == null) {
            throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "快照缺 snapshot.sig：未经本机签名的 node_modules.zip 拒绝导入（§10.9.4 高信任通道）",
            )
        }
        val given = sigText!!.removePrefix("v1").trim()
        val expect = hmac(signBody(projectId, lockSig, sha256(body.toString().toByteArray(StandardCharsets.UTF_8))))
        if (given.isEmpty() || !constantTimeHexEquals(given, expect)) {
            throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "快照验签失败：内容/项目/密钥至少有一项与签名不符（被改过/换过项目/换过设备）",
            )
        }
        return Manifest(entries = count, contentBytes = contentBytes, lockSig = lockSig)
    }

    // ══════ 内部 ══════

    /** 归档条目（相对路径排序 = 清单可复现）。node_modules 只收常规文件：.bin 的符号链接按目标内容实体化。 */
    private fun collect(projectId: String, ledgerDir: Path?): List<Pair<String, Path>> {
        val out = ArrayList<Pair<String, Path>>()
        fun add(rel: String, p: Path) {
            if (Files.isRegularFile(p)) out.add(rel to p)
        }
        val root = layout.projectRoot(projectId)
        add("package.json", root.resolve("package.json"))
        add("package-lock.json", root.resolve("package-lock.json"))
        if (ledgerDir != null) {
            add("ledger/approve-ledger.jsonl", ledgerDir.resolve("approve-ledger.jsonl"))
            add("ledger/install-history.jsonl", ledgerDir.resolve("install-history.jsonl"))
            add("lock.sig", ledgerDir.resolve("lock.sig"))
        }
        val nm = layout.nodeModules(projectId)
        if (Files.isDirectory(nm)) {
            Files.walk(nm).use { s ->
                s.filter { Files.isRegularFile(it) }.forEach { p ->
                    out.add("node_modules/" + nm.relativize(p).toString().replace('\\', '/') to p)
                }
            }
        }
        return out.sortedBy { it.first }
    }

    private fun signBody(projectId: String, lockSigText: String?, contentHash: String): ByteArray =
        ("autojs-snapshot-v1|" + projectId + "|" + (lockSigText ?: "none") + "|" + contentHash)
            .toByteArray(StandardCharsets.UTF_8)

    private fun sha256(bytes: ByteArray): String = DirSizer.sha256(bytes)

    private fun hmac(body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.keyBytes(), "HmacSHA256"))
        return hex(mac.doFinal(body))
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** 常量时间比较（与 [LockSigner] 同纪律：不因前缀匹配提前返回泄漏时序）。 */
    private fun constantTimeHexEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    companion object {
        /** 签文件在归档内的固定名。 */
        const val SIG_NAME = "snapshot.sig"

        /** ZIP 时间戳下界（DOS 时间无法表示 1980 前）：固定值 = 导出逐字节可复现。 */
        const val DOS_EPOCH = 315_532_800_000L
    }
}
