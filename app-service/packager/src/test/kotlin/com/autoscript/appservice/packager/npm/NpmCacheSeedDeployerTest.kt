package com.autoscript.appservice.packager.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 精选缓存种子部署器单测（§10.11 P0 缓存种子）+ 离线首装金标准（§10.12）：
 *
 * - **布局**：`cacheDir/_cacache/content-v2/<alg>/<xx>/<yy>/<rest>`（cacache hash-to-segments）；
 * - **信任**：sidecar `sha512-<base64>` 与内容不符 → 整体拒绝（不是静默跳过）；
 * - **幂等**：同 integrity 第二次零重写（内容寻址下同摘要 = 同内容）；
 * - **金标准**：`npm ci --offline` 只用种子 cache（**断网不可达**原路径 = 离线真义）装出 lodash
 *   —— 本机/CI 有 node 才跑；无 node 如实跳过（不假扮通过）。
 */
class NpmCacheSeedDeployerTest {

    @TempDir
    lateinit var dir: Path

    private val cacheDir get() = dir.resolve("cache")

    /** sidecar（`<tgz>.sha512`）+ tarball 的素材源；host = assets 侧形态。 */
    private class TarballSource(val root: Path) : NpmCacheSeedDeployer.SeedSource {
        override fun list(): List<String> =
            if (Files.isDirectory(root)) {
                Files.walk(root).use { s ->
                    s.filter { Files.isRegularFile(it) }
                        .map { root.relativize(it).toString().replace('\\', '/') }
                        .sorted()
                        .toList()
                }
            } else emptyList()

        override fun read(relPath: String): ByteArray? {
            val f = root.resolve(relPath)
            return if (Files.isRegularFile(f)) Files.readAllBytes(f) else null
        }
    }

    /** 造一个 tar.gz（gzip 头 + 载荷即可——cache 里它只是不透明字节）。 */
    private fun tarballBytes(payload: String): ByteArray {
        val raw = payload.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    private fun writeSource(name: String, version: String, bytes: ByteArray, corruptSide: Boolean = false): Path {
        val src = Files.createDirectories(dir.resolve("seed-src-$name-$version"))
        val file = "$name-$version.tgz"
        Files.write(src.resolve(file), bytes)
        // sidecar = integrity 原文（`sha512-<base64>`），_hash helper 与主代码同口径（sha512 摘要）
        val b64 = digestBase64(bytes, "SHA-512").removePrefix("sha512-")
        val line = if (corruptSide) "sha512-" + b64.replaceRange(6, 8, "AA") else "sha512-$b64"
        Files.write(src.resolve("$file.sha512"), (line + "\n").toByteArray())
        return src
    }

    @Test
    fun `播种落位 content-v2 且按 hash-to-segments 分段`() {
        val bytes = tarballBytes("lodash-4.17.21")
        val src = writeSource("lodash", "4.17.21", bytes)
        val r = NpmCacheSeedDeployer.deploy(cacheDir, TarballSource(src))
        assertEquals(1, r.deployed)
        assertEquals(0, r.skipped)

        val p = NpmCacheSeedDeployer.contentPath(cacheDir, base64(bytes))
        assertTrue(Files.isRegularFile(p), "content 必须在 cacache content-v2 约定路径：$p")
        assertTrue(bytes.contentEquals(Files.readAllBytes(p)))
        val rel = cacheDir.relativize(p).toString().replace('\\', '/')
        assertTrue(rel.startsWith("_cacache/content-v2/sha512/"), "布局根必须是 _cacache/content-v2/<alg>")
    }

    @Test
    fun `幂等：同源二次播种零重写`() {
        val bytes = tarballBytes("axios-1.7.9")
        val src = writeSource("axios", "1.7.9", bytes)
        val first = NpmCacheSeedDeployer.deploy(cacheDir, TarballSource(src))
        assertEquals(1, first.deployed)
        val p = NpmCacheSeedDeployer.contentPath(cacheDir, base64(bytes))
        val mtime = Files.getLastModifiedTime(p)

        val second = NpmCacheSeedDeployer.deploy(cacheDir, TarballSource(src))
        assertEquals(0, second.deployed, "内容寻址幂等：已就位不得重写")
        assertEquals(1, second.skipped)
        assertEquals(mtime, Files.getLastModifiedTime(p), "幂等命中不得动文件")
    }

    @Test
    fun `sidecar 与内容不符 → 整体拒绝（绝不播坏种子）`() {
        val bytes = tarballBytes("dayjs-1.11.13")
        val src = writeSource("dayjs", "1.11.13", bytes, corruptSide = true)
        val e = assertThrows_IAE { NpmCacheSeedDeployer.deploy(cacheDir, TarballSource(src)) }
        assertTrue(e.message!!.contains("integrity"), "报错必须指名 integrity 不符：${e.message}")
        assertFalse(
            Files.exists(NpmCacheSeedDeployer.contentPath(cacheDir, base64(bytes))),
            "坏种子绝不能落进 cache",
        )
    }

    @Test
    fun `manifest 形态：file integrity 成对声明才播`() {
        val bytes = tarballBytes("cheerio-1.0.0")
        val src = Files.createDirectories(dir.resolve("seed-src-manifest"))
        Files.write(src.resolve("cheerio-1.0.0.tgz"), bytes)
        Files.write(src.resolve("manifest.json"), ("""{"entries":[{"file":"cheerio-1.0.0.tgz","integrity":"${base64(bytes)}"}]}""").toByteArray())
        val r = NpmCacheSeedDeployer.deploy(cacheDir, TarballSource(src))
        assertEquals(1, r.deployed)
        assertTrue(Files.isRegularFile(NpmCacheSeedDeployer.contentPath(cacheDir, base64(bytes))))
    }

    @Test
    fun `空素材源 → 如实失败（不可校验内容不播种）`() {
        val src = Files.createDirectories(dir.resolve("seed-src-empty"))
        Files.write(src.resolve("random.txt"), ("junk").toByteArray())
        assertThrows_ISE { NpmCacheSeedDeployer.deploy(cacheDir, TarballSource(src)) }
    }

    @Test
    fun `非 sha512 integrity 拒绝（种子里不引弱摘要）`() {
        val bytes = tarballBytes("weak")
        val src = Files.createDirectories(dir.resolve("seed-src-sha1"))
        Files.write(src.resolve("weak-1.0.0.tgz"), bytes)
        Files.write(src.resolve("weak-1.0.0.tgz.sha512"), (digestBase64(bytes, "SHA-1") + "\n").toByteArray())
        val e = assertThrows_IAE { NpmCacheSeedDeployer.deploy(cacheDir, TarballSource(src)) }
        assertTrue(e.message!!.contains("sha512"), "必须点名只支持 sha512：${e.message}")
    }

    @Test
    fun `missingIntegrities：已播的命中，缺的指名`() {
        val bytes = tarballBytes("lodash-4.17.21")
        val src = writeSource("lodash", "4.17.21", bytes)
        NpmCacheSeedDeployer.deploy(cacheDir, TarballSource(src))
        val miss = NpmCacheSeedDeployer.missingIntegrities(
            cacheDir,
            listOf(base64(bytes), base64(tarballBytes("other")), null, ""),
        )
        assertEquals(listOf(base64(tarballBytes("other"))), miss, "已播的不得算缺口；null/空不占位（无可指名对象）")
    }

    // ═══ 离线首装金标准（§10.12：仅凭种子 npm ci --offline）══

    private val npmCli: Path? = sequenceOf(
        "/usr/lib/node_modules/npm/bin/npm-cli.js",
        "/usr/local/lib/node_modules/npm/bin/npm-cli.js",
    ).map { Path.of(it) }.firstOrNull { Files.isRegularFile(it) }

    /**
     * 取一个本机 npm cache 里真实存在的 gzip tarball。
     *
     * 为什么不自己造：npm 对 fa-keyed/下载过的 entry 是**缓存复用**，但 `npm ci --offline`
     * 的 lock 必须**按 integrity 引用这个 tarball** 才算真离线命中——用假造的 gzip 写进 lock
     * 只会跑到「找不到包的失败路径」。故从 `~/.npm/_cacache/content-v2` 取现成字节，
     * 用它的 sha512 当 lock 里的 integrity（内容寻址：文件名无关，只认摘要）。
     */
    private fun realTarball(): ByteArray? {
        val roots = sequenceOf(dir.resolve("real-cache"), Path.of("/root/.npm/_cacache/content-v2"))
        for (root in roots) {
            if (!Files.isDirectory(root)) continue
            // 懒扫：walk 惰性 + findFirst 命中即停，每文件只读头两字节判魔数，
            // 命中单个文件才全量读 —— 本机 ~/.npm/_cacache 可达 GB 级，旧写法
            // （toList + 全读全文件再 firstOrNull）把整个缓存搬进堆，
            // 测试 worker 默认堆直接 OOM（Files.read → readAllBytes 栈顶见真章）。
            val hit: Path? = Files.walk(root).use { s ->
                s.filter { p ->
                    try {
                        Files.isRegularFile(p) && isGzip(p)
                    } catch (_: Exception) {
                        false   // 扫描中途文件被删/无权限：跳过，不炸测试
                    }
                }.findFirst().orElse(null)
            }
            if (hit == null) continue
            try {
                return Files.readAllBytes(hit)
            } catch (_: Exception) {
                continue   // 命中与读取之间文件消失：换下一个根（无则整体跳过）
            }
        }
        return null
    }

    /**
     * 只读头两字节判 gzip 魔数（`0x1f 0x8b`），不把整文件搬进堆 ——
     * 大缓存下全量读即 OOM（见 [realTarball]）。
     */
    private fun isGzip(p: Path): Boolean {
        return try {
            Files.newInputStream(p).use { ins ->
                val head = ByteArray(2)
                var off = 0
                while (off < 2) {
                    val n = ins.read(head, off, 2 - off)
                    if (n < 0) break
                    off += n
                }
                off == 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    @Test
    fun `金标准：仅凭种子 cache 离线 ci 装出依赖（lock 按 integrity 引用种子）`() = runBlocking {
        val bytes = realTarball() ?: return@runBlocking   // 无本机 tarball：跳过（不造数据）
        if (npmCli == null) return@runBlocking
        val integ = digestBase64(bytes, "SHA-512")

        val src = Files.createDirectories(dir.resolve("seed-real"))
        Files.write(src.resolve("real-1.0.0.tgz"), bytes)
        Files.write(src.resolve("real-1.0.0.tgz.sha512"), (integ + "\n").toByteArray())
        val seedOut = NpmCacheSeedDeployer.deploy(cacheDir, TarballSource(src))
        assertEquals(1, seedOut.deployed)

        val proj = Files.createDirectories(dir.resolve("proj"))
        // package.json 也要声明同一依赖：npm ci 以 lock 为准，但 root 的 dependencies 缺项时
        // 该包会被判为「不在依赖闭包里」而跳过 reify（"up to date" = 什么都没装 = 空过）。
        Files.write(proj.resolve("package.json"), ("""{"name":"seed-e2e","version":"1.0.0","dependencies":{"real":"1.0.0"}}""").toByteArray())
        // lock 按 integrity 引用种子：URL 指向一个**不可达**的注册表路径 —— 离线真义就是
        // 网络解析这条路必须根本不被走（走了就 ENOTCACHED/EAI_AGAIN），npm 只许命中 content-v2
        Files.write(proj.resolve("package-lock.json"), ("""{"lockfileVersion":3,"packages":""" +
                """{"":{"name":"seed-e2e","version":"1.0.0","dependencies":{"real":"1.0.0"}},""" +
                """"node_modules/real":{"version":"1.0.0","resolved":"https://registry.invalid.example/real/-/real-1.0.0.tgz","integrity":""" +
                """"$integ"}}}""").toByteArray())
        val pb = ProcessBuilder(
            "node", npmCli.toString(), "ci", "--offline", "--ignore-scripts",
            "--no-audit", "--no-fund", "--cache", cacheDir.toString(),
        )
        pb.directory(proj.toFile())
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val ok = proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
        if (!ok) proc.destroyForcibly()
        assertTrue(ok, "npm ci --offline 必须在其 TTL 内收尾（不挂起）")
        assertEquals(0, proc.exitValue(), "仅凭种子 cache 的离线 ci 必须成功：\n$out")
        assertTrue(Files.isDirectory(proj.resolve("node_modules/real")), "reify 产物必须落位：\n$out")
    }

    /** plan 阶段（素材不可信）→ IllegalStateException（与 [NpmCliDeployer] 缺锚同口径）。 */
    private fun assertThrows_ISE(block: () -> Unit): IllegalStateException =
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java, block)

    /** deploy 阶段（契约违背）→ IllegalArgumentException（require）。 */
    private fun assertThrows_IAE(block: () -> Unit): IllegalArgumentException =
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java, block)

    /** `<alg>-<base64>` 的 integrity 原文（与 lock v3 / cacache 的 integrity 同形状）。 */
    private fun digestBase64(bytes: ByteArray, alg: String): String =
        alg.lowercase().replace("-", "") + "-" + java.util.Base64.getEncoder()
            .encodeToString(java.security.MessageDigest.getInstance(alg).digest(bytes))

    /** 默认 sha512 的 integrity 原文（测试里绝大多数场景用）。 */
    private fun base64(bytes: ByteArray): String = digestBase64(bytes, "SHA-512")
}
