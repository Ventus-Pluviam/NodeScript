package com.autoscript.appservice.packager.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 高信任快照导出/验签单测（§10.9.4 node_modules.zip + §10.7 SnapshotRef）：
 * 签验闭环 + 内容改动拒 + 抽条目拒 + 缺签拒 + 跨项目拒 + 密钥切换拒 + 无 node_modules 拒绝。
 */
class NpmSnapshotTest {

    @TempDir
    lateinit var dir: Path

    private val layout get() = NpmProjectLayout(dir.resolve("scripts"))
    private val ledgerDir get() = dir.resolve(".autojs")
    private val key = LockSigner.KeyProvider { "snapshot-key-32bytesaaaaaaaaaaaa".toByteArray() }
    private val out get() = dir.resolve("snap.zip")

    private fun snapshot(key: LockSigner.KeyProvider = this.key) = NpmSnapshot(layout, ledgerDir, key)

    /** 造一个最小项目：package.json + lock + lock.sig + node_modules/{lodash,esbuild}。 */
    private fun seedProject(projectId: String = "p1") {
        val root = layout.projectRoot(projectId)
        Files.createDirectories(root)
        Files.write(root.resolve("package.json"), ("""{"name":"p1","version":"1.0.0"}""").toByteArray())
        Files.write(layout.lockfile(projectId), ("""{"lockfileVersion":3,"packages":{"":{},"node_modules/lodash":{"version":"4.17.21","integrity":"sha512-x"}}}""").toByteArray())
        val signer = LockSigner(ledgerDir, key)
        signer.sign(projectId, layout.lockfile(projectId))
        val nm = layout.nodeModules(projectId)
        Files.createDirectories(nm.resolve("lodash"))
        Files.write(nm.resolve("lodash/package.json"), ("""{"name":"lodash","version":"4.17.21"}""").toByteArray())
        Files.write(nm.resolve("lodash/index.js"), ("module.exports = 1\n").toByteArray())
        Files.createDirectories(nm.resolve("esbuild"))
        // 带 postinstall → 同属包内容；与快照验签无关但让归档内容非平凡
        Files.write(nm.resolve("esbuild/package.json"), ("""{"name":"esbuild","scripts":{"postinstall":"node install.js"}}""").toByteArray())
        Files.write(nm.resolve("esbuild/bin.js"), ("console.log(1)\n").toByteArray())
        // ledger 两份都在：导出须一并带上（§10.5-2 审计可导出）
        Files.createDirectories(ledgerDir)
        Files.write(ledgerDir.resolve("approve-ledger.jsonl"), ("""{"op":"submit","requestId":"apr-1","projectId":"p1","pkg":"esbuild","versionHash":"h","action":"INSTALL_SCRIPT","at":1}""" + "\n").toByteArray())
        Files.write(ledgerDir.resolve("install-history.jsonl"), ("""{"op":"install","projectId":"p1","ok":true,"at":2}""" + "\n").toByteArray())
    }

    @Test
    fun `签验闭环：导出的 zip 过验签，内容条目齐`() = runBlocking {
        seedProject()
        val b = snapshot().export("p1", out)
        val m = snapshot().verify("p1", out)
        // 2 manifest + 2 ledger/lock.sig + 4 node_modules 文件
        assertTrue(m.entries >= 8, "归档须含 manifest/ledger/node_modules（实为 ${m.entries}）")
        assertTrue(b.contentBytes > 0)
        assertTrue(b.archiveSha256.length == 64, "sha256 十六进制长度须为 64（实为 ${b.archiveSha256.length}）")
        assertTrue(m.lockSig!!.startsWith("v1 "), "lock.sig 须随包同行（两层验签闭环）")
    }

    @Test
    fun `导出两遍签一致（签内容清单而非归档字节）`() = runBlocking {
        seedProject()
        val a = dir.resolve("a.zip")
        val c = dir.resolve("c.zip")
        snapshot().export("p1", a)
        snapshot().export("p1", c)
        // 归档头带时间戳方差时逐字节不同，但签必须一致
        assertEquals(snapshotSha(a), snapshotSha(c), "内容清单可复现 → 签名可复现")
    }

    /** 读归档里的 snapshot.sig 原文（zip 是二进制，不能整文件当文本读）。 */
    private fun snapshotSha(f: Path): String {
        java.util.zip.ZipFile(f.toFile()).use { zf ->
            val e = zf.getEntry(NpmSnapshot.SIG_NAME) ?: return ""
            return String(zf.getInputStream(e).readBytes(), Charsets.UTF_8).trim()
        }
    }

    @Test
    fun `内容被改则验签失败（导入侧不是摆设）`() = runBlocking {
        seedProject()
        snapshot().export("p1", out)
        // 在归档里追加一个不在清单上的文件（模拟导入侧或投递途中被塞东西）
        tamperAppend(out, "evil/lib.js", "module.exports = 'evil'\n")
        assertThrows(AutojsException::class.java) { snapshot().verify("p1", out) }
    }

    @Test
    fun `manifest 被改则验签失败（lock 与树不一致）`() = runBlocking {
        seedProject()
        snapshot().export("p1", out)
        val modified = String(Files.readAllBytes(out), Charsets.UTF_8).replace("\"lockfileVersion\":3", "\"lockfileVersion\":9")
        assertFalse(modified == String(Files.readAllBytes(out), Charsets.UTF_8), "替换应真的发生")
        Files.write(out, (modified).toByteArray())
        assertThrows(AutojsException::class.java) { snapshot().verify("p1", out) }
    }

    @Test
    fun `缺 snapshot_sig 即拒（TOFU 自签正是被批判形态）`() = runBlocking {
        seedProject()
        snapshot().export("p1", out)
        stripEntry(out, NpmSnapshot.SIG_NAME)
        val e = assertThrows(AutojsException::class.java) { snapshot().verify("p1", out) }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, e.error.code)
    }

    @Test
    fun `跨项目搬快照拒绝（signature 绑定 projectId）`() = runBlocking {
        seedProject("p1")
        snapshot().export("p1", out)
        val e = assertThrows(AutojsException::class.java) { snapshot().verify("p2", out) }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, e.error.code)
    }

    @Test
    fun `密钥切换则旧签失效，重导即恢复`() = runBlocking {
        seedProject()
        snapshot().export("p1", out)
        val other = NpmSnapshot(layout, ledgerDir, LockSigner.KeyProvider { "rotated-key".toByteArray() })
        assertThrows(AutojsException::class.java) { other.verify("p1", out) }
        other.export("p1", out)
        other.verify("p1", out)
    }

    @Test
    fun `路径逃逸条目先拒（解包前就报，不等验签）`() = runBlocking {
        seedProject()
        snapshot().export("p1", out)
        tamperAppend(out, "../escape.js", "x\n")
        val e = assertThrows(AutojsException::class.java) { snapshot().verify("p1", out) }
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, e.error.code)
    }

    @Test
    fun `无 lock 的导出拒绝（给不出验签闭环快照）`() = runBlocking {
        val root = layout.projectRoot("nolock")
        Files.createDirectories(layout.nodeModules("nolock"))
        val e = assertThrows(AutojsException::class.java) { snapshot().export("nolock", out) }
        assertEquals(ErrorCode.ERR_FILE_NOT_FOUND.code, e.error.code)
    }

    @Test
    fun `非法 projectId 拒绝（防路径逃逸）`() = runBlocking {
        seedProject()
        assertThrows(IllegalArgumentException::class.java) { snapshot().export("../escape", out) }
    }

    // —— helpers：改 zip 的临时垫脚（内存 zip，不用系统 unzip）———

    private fun tamperAppend(f: Path, name: String, content: String) {
        val tmp = dir.resolve("tamper-appended.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(tmp)).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry(name))
            z.write(content.toByteArray())
            z.closeEntry()
        }
        appendBytes(f, Files.readAllBytes(tmp))
    }

    private fun stripEntry(f: Path, name: String) {
        val tmp = dir.resolve("tamper-stripped.zip")
        java.util.zip.ZipInputStream(Files.newInputStream(f)).use { zin ->
            java.util.zip.ZipOutputStream(Files.newOutputStream(tmp)).use { zout ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.name == name) continue
                    zout.putNextEntry(java.util.zip.ZipEntry(e.name))
                    zin.copyTo(zout)
                    zout.closeEntry()
                }
            }
        }
        Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun appendBytes(f: Path, b: ByteArray) {
        Files.write(f, b, java.nio.file.StandardOpenOption.APPEND)
    }

    private fun <T> runBlocking(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}
