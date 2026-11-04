package com.autoscript.appservice.npm

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 离线 bundle 导入（§10.2 存储布局 · `files/npm-import/`，§10.9 UX 4「离线包导入」）。
 *
 * 形态：一个 zip，内含 cacache 子树（`_cacache/content-v2/…`，可带 `index-v5/`）。
 * 导入 = 把这些条目按 cacache 布局合入 `cacheDir`，让随后的 `npm ci --offline`
 * 直接命中（命中语义见 [NpmCacheSeedDeployer.CACHE_HIT_NOTE]）。
 *
 * 信任口径（与种子不同的关键点）：
 * - **种子**信 assets（APK 内、随包签名、不可被第三方改），故侧车即可；
 * - **bundle** 是用户从 SAF 递进来的外部文件（桌面 `npm ci` 产物/别人导出的包），
 *   字节不保证忠于其声明的 integrity，故**导入时逐条目 sha512 复核**：
 *   `contentPath(integrity)` 与解出的内容字节不符 → 该条目不入 cache
 *   （否则就是往缓存里投放命名错误的内容，之后真装会 EINTEGRITY，报错还指不到源头）。
 *
 * 安全：先过路径检疫（`..`/绝对路径/反斜杠/盘符逃逸）再解包——zip slip 是这条路径的
 * 真实威胁面（用户文件不可信），不做等于给任意写。只接受**常规文件**条目，
 * 目录/符号链接形态一律忽略（cacache 不需要链接即成）。
 *
 * **资源上限（补，backlog B12）**：两道闸，[MAX_BUNDLE_BYTES]（整包）与
 * [MAX_ENTRY_BYTES]（单条目）。这是同 `:platform:system` 的 `ShellCaptureLimit` 一类的
 * 账 —— 那条是「宿主自己读回来的输出」，这条是「用户递进来的外部文件」，
 * 都是**在被撑爆之前先兜住**。为什么必须有：这条路径的调用方是 SAF 选进来的文件，
 * **用户可能只是选错了**（视频、系统镜像、整个 Downloads 打成的一个包）。
 * 超限**整包拒收**而不是截断：截断一个 zip 得到的不是「半个包」而是一批摘要对不上
 * 的条目，那只会把「选错文件」变成「一屏看不懂的 rejected」。
 *
 * 单条目的闸为什么是「边读边卡」而不是「先看声明再读」：`ZipEntry.getSize()` 是包
 * **自己声明**的数，可以撒谎（把中央目录那条改小即可，本文件测试里有这个用例），
 * 所以真正的上限只能在 [readCapped] 里 —— 那是唯一一处内存还没被分配出去的地方。
 *
 * 与 [NpmSnapshot] 的关系：snapshot 是「高信任项目交付」（HMAC 签内容清单，导入侧验签
 * 后 reify 直接给 node_modules）；bundle 是「给缓存喂 tarball」，两者服务不同问句，
 * 信任门槛也按来源分级（SAF 外部文件 < APK assets < 签过快照）。
 */
object NpmOfflineBundleImporter {

    /** 导入结果（诚实报数：跳过/拒绝的条目数 != 0 时调用方该提示，别当无事）。 */
    data class Result(
        val imported: Int,
        val skipped: Int,
        val rejected: List<String>,
        val bytes: Long,
    ) {
        val clean: Boolean get() = rejected.isEmpty()
    }

    /**
     * 离线 bundle 的体积上限（**解包前先看 zip 自身大小**，不是逐条目累加）。
     *
     * **为什么是 512 MiB**：cacache 里躺的是 npm tarball 的**压缩体**，实测素材级
     * bundle（随包 npm CLI 全量）是 9 MiB 量级；一个项目的依赖闭包按 lock 物化出来的
     * bundle 在数十 MiB。512 MiB 对合法用途是「够不着」的余量，只拦「选错文件」
     * （视频、系统镜像、整个 Downloads 目录打成的一个包）。
     *
     * **为什么按 zip 自身大小判而不是累加条目**：`ZipEntry.getSize()` 是**包自己声明的
     * 数**，可以撒谎（压缩炸弹就是这么来的）；而文件系统上的字节数骗不了人。先卡住
     * 这一层（`Files.size`），单条目那道闸见 [MAX_ENTRY_BYTES] / [readCapped]。
     */
    const val MAX_BUNDLE_BYTES: Long = 512L * 1024 * 1024

    /**
     * 单条目上限：与整包同量级即可（合法条目是 tarball 压缩体，实测最大数 MiB）。
     * 由 [readCapped] 强制执行 —— 不是「读完了再量」，是**读到顶就停**。
     */
    const val MAX_ENTRY_BYTES: Long = 64L * 1024 * 1024

    /**
     * 整包超限的**类型化**信号。
     *
     * 为什么单独立一个类型而不是让调用方看 message：调用方要按它分错误码
     * （超限 = 参数问题 → `ERR_INVALID_PARAM`；文件不存在 = `ERR_FILE_NOT_FOUND`），
     * 而**拿字符串当判据**在改一次文案之后就会静默失效 —— 那正是本仓记过的
     * 「注释承诺 > 实现」的同类病。它同时继承 [IllegalArgumentException]：既有调用方
     * （含测试）对「导入件参数非法」的既有口径一个字不用改。
     */
    class BundleTooLargeException(message: String) : IllegalArgumentException(message)

    /**
     * 把 [zipFile] 里 `_cacache/content-v2` 下的条目合入 [cacheDir]。
     *
     * @return [Result]；`rejected` 非空表示有条目因路径非法/摘要不符/超限被拒（调用方如实告知）。
     * @throws IllegalArgumentException 文件不存在。
     * @throws BundleTooLargeException 超过 [MAX_BUNDLE_BYTES]（**整包拒收**，不返回部分结果
     *   —— 见类注释「超限整包拒收」）。它是 [IllegalArgumentException] 的子类，调用方想粗粒度
     *   兜底也行；要分错误码就按类型分。
     */
    fun import(zipFile: Path, cacheDir: Path): Result =
        import(zipFile, cacheDir, MAX_BUNDLE_BYTES, MAX_ENTRY_BYTES)

    /**
     * 上限可注入的重载：**只给测试开这个缝**，生产一律走 [import]（默认值即那两个常量）。
     *
     * 为什么要缝：边界要按真值测就得造一条 64 MiB+ 的条目，而**测「一条不该进内存的
     * 条目」却先为它吃满 64 MiB 内存**是拿边界的坑去测边界。缝只开在上限上 ——
     * 两个入口跑的是同一段循环，解包/复核/落盘一条不分叉。
     */
    internal fun import(
        zipFile: Path,
        cacheDir: Path,
        maxBundleBytes: Long,
        maxEntryBytes: Long,
    ): Result {
        if (!Files.isRegularFile(zipFile)) {
            throw IllegalArgumentException("离线 bundle 不存在：$zipFile")
        }
        val bundleBytes = Files.size(zipFile)
        if (bundleBytes > maxBundleBytes) {
            throw BundleTooLargeException(
                "离线 bundle 超过上限：$bundleBytes 字节 > $maxBundleBytes" +
                    "（${bundleBytes / 1024 / 1024} MiB）—— 选错文件了？",
            )
        }
        var imported = 0
        var skipped = 0
        var bytes = 0L
        val rejected = mutableListOf<String>()
        Files.createDirectories(NpmCacheSeedDeployer.cacacheDir(cacheDir))
        ZipFile(zipFile.toFile()).use { zf ->
            val en = zf.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                // 检疫顺序要紧：路径逃逸是威胁，与它声称在哪个子树无关——先拒再谈归属。
                if (isUnsafePath(name)) {
                    rejected += name
                    continue
                }
                if (!isCacacheContent(name)) continue   // index-v5/其他文件：不导入（见类注释）
                // 从路径反推 integrity：content-v2/<alg>/<xx>/<yy>/<rest(hex)>
                val seg = name.removePrefix("_cacache/content-v2/").split('/')
                if (seg.size != 4) {
                    rejected += name
                    continue
                }
                val (alg, a, b, rest) = seg
                val hex = a + b + rest
                val integrity = "$alg-${integrityBase64Of(hex)}"
                val target = NpmCacheSeedDeployer.contentPath(cacheDir, integrity)
                // 第二道闸（便宜的早退）：**声明**就超顶的条目不必读。声明可以撒谎，
                // 所以这道只是省事，不是保证 —— 保证在下一行的 readCapped 里。
                if (e.size > maxEntryBytes) {
                    rejected += name
                    continue
                }
                // 第三道闸（真正的上限）：读到顶就停，**不把整条读进内存**。
                // 声明撒谎（或声明 -1）的条目在这里照样撞顶 —— 它们最多让宿主分配
                // maxEntryBytes + 一个读缓冲，而不是「先 OOM 一次再发现超了」。
                val data = zf.getInputStream(e).use { readCapped(it, maxEntryBytes) }
                if (data == null) {
                    rejected += name
                    continue
                }
                val actual = hexOf(alg, data)
                if (!actual.equals(hex, ignoreCase = true)) {
                    rejected += name     // 内容与路径声明的摘要不符：不往缓存里投放冒名条目
                    continue
                }
                if (Files.isRegularFile(target) && Files.size(target) == data.size.toLong()) {
                    skipped++           // 内容寻址幂等
                    bytes += data.size
                    continue
                }
                Files.createDirectories(target.parent)
                val tmp = Files.createTempFile(NpmCacheSeedDeployer.cacacheDir(cacheDir), "bundle-", ".tmp")
                try {
                    Files.write(tmp, data)
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(tmp)
                }
                imported++
                bytes += data.size
            }
        }
        return Result(imported, skipped, rejected, bytes)
    }

    /**
     * 读一条条目，**至多** [cap] 字节：到顶即返回 null，绝不把整条读进内存。
     *
     * 这是本文件唯一一处「内存还没被分配出去」的地方，所以上限必须落在这里。
     * `readBytes()` 不行：它对单条目无上限，而 `ZipEntry.getSize()` 是**包自己声明的
     * 数**（本文件测试 `声明撒谎的条目` 会把中央目录那条改小，实测读出来仍是原大小）。
     * 缓冲上界 = cap + 一个读块，与条目真实大小无关。
     */
    internal fun readCapped(input: InputStream, cap: Long): ByteArray? {
        val out = ByteArrayOutputStream(minOf(cap, 1L shl 20).toInt())
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 只收 content-v2 常规文件；index-v5 由 npm 自己按需生成（导索引 = 导出「缓存出现过的历史」）。 */
    internal fun isCacacheContent(name: String): Boolean = name.startsWith("_cacache/content-v2/")

    /**
     * zip slip 检疫：`..` 段、绝对路径、盘符形态一律拒（cacheDir 之外一字节都不写）。
     *
     * **修**：KDoc 一直写着「盘符形态」，实现却只查了前两类 —— 注释承诺 >
     * 实现。第三类补上 `[DRIVE_LETTER]`。危害本来就不大（本导入器从不把条目名当路径用，
     * 落点由 integrity 十六进制经 [NpmCacheSeedDeployer.contentPath] 反推，见 import()），
     * 但「注释说的比代码做的多」在安全面上是会误导下一个改这里的人的，按实现补齐注释或
     * 按注释补齐实现，二选一；这里选后者（拒绝一个 `C:…` 条目零代价）。
     */
    internal fun isUnsafePath(name: String): Boolean {
        if (name.startsWith("/") || name.contains("\\")) return true
        if (DRIVE_LETTER.containsMatchIn(name.take(2))) return true
        return name.split('/').any { it == ".." }
    }

    /**
     * 盘符形态（`C:foo` / `C:/foo`）—— 只判**开头两字符**：`C:foo` 在 Windows 上是
     * 「C 盘的当前目录」这种驱动器相对路径，不是绝对路径，Java 的 `Paths.get("C:/x")`
     * 在 Linux 上更只是个普通相对名，所以前两类检查都拦不住它。段中间的冒号不判：
     * 那不是盘符，误伤合法文件名。
     */
    private val DRIVE_LETTER = Regex("^[A-Za-z]:")

    private fun integrityBase64Of(hex: String): String {
        val raw = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return java.util.Base64.getEncoder().encodeToString(raw)
    }

    private fun hexOf(alg: String, data: ByteArray): String =
        java.security.MessageDigest.getInstance(alg).digest(data)
            .joinToString("") { "%02x".format(it) }
}
