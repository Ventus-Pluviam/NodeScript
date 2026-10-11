package com.autoscript.platform.system.zip

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UncheckedIOException
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
 * 4. **体积上限（2026-10-06 补，backlog B13）**：见 [MAX_ENTRY_BYTES] / [MAX_TOTAL_BYTES]。
 *    与 zip-slip 同层 —— 都是「解压一个不可信归档」这条路上的契约级底线，所以同样
 *    **先验后写**（诚实的炸弹在写任何字节之前就被拦下），撒谎的声明由第二拍的计数闸兜住。
 */
class JdkZipArchiver(
    /**
     * 两个上限做成**构造参数**（缺省即下面那两个常量）：生产走 `SystemSpis.of` 的
     * 无参构造，测试把上限压到可测的规模。
     *
     * 为什么要这个缝：边界要按真值测就得造 256 MiB+ 的条目，而**为一条不该落盘的条目
     * 先写满 256 MiB 磁盘**是拿边界的坑去测边界（同 `NpmOfflineBundleImporter` 的
     * 上限缝，2026-10-06 backlog B12）。缝只开在上限上 —— 解包/校验/落盘一条不分叉。
     */
    private val maxEntryBytes: Long = MAX_ENTRY_BYTES,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    /**
     * 条目**个数**上限（2026-10-11 批 99，外审 M7）。
     *
     * 为什么字节上限不够：一个「零字节 × 50 万条」的归档能过掉 [maxTotalBytes]（合计 0）
     * 与 [maxEntryBytes]（每条 0）两道闸，却在解包时造 50 万个目录项/文件 inode ——
     * 这是「用小体积换大计数」的那一类攻击，与字节上限**正交**，必须单独设闸。
     * 与字节上限同层（zip-slip 同层），同样**先验后写**。
     */
    private val maxEntries: Int = MAX_ENTRIES,
) : ZipArchiver {

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
                    //
                    // 两条外审 M7 的修法在这一段：
                    // ① **`.tmp` 不自含**：临时文件是 `archive.resolveSibling(...)`，当用户把
                    //    归档写进**源目录里面**时，`Files.walk` 会走到它（walk 是边走边枚举，
                    //    而 tmp 在 zos 打开那一刻就已经存在）—— 于是包把自己包了进去，解出来
                    //    是「一个 zip 里装着那个 zip 的半截」。判据用**绝对路径**（不用
                    //    `startsWith(source)` 那种前缀比较：源目录名是别的目录名前缀时会误判）。
                    // ② **walk 的 `UncheckedIOException`**：`Files.walk` 的迭代器把 IO 错误包成
                    //    `UncheckedIOException`，它**不是** `IOException` —— 原来的
                    //    `catch (e: IOException)` 接不住，于是 `deleteIfExists(tmp)` 也被跳过，
                    //    留下半截 tmp 并把错误类型原样抛给调用方（不是本类的 `ERR_IO`）。
                    val root = source.toAbsolutePath().normalize()
                    val tmpAbs = tmp.toAbsolutePath().normalize()
                    val skipTmp = { p: Path -> p.toAbsolutePath().normalize() == tmpAbs }
                    try {
                        Files.walk(root).use { walk ->
                            val iterator = walk.iterator()
                            while (iterator.hasNext()) {
                                val p = iterator.next()
                                // 两个 continue 合成一个判据（detekt LoopWithTooManyJumpStatements）：
                                // 「本次 tmp」与「源根自己」都不进包。
                                val rel = root.relativize(p).toString().replace('\\', '/')
                                if (skipTmp(p) || rel.isEmpty()) continue
                                val name = if (Files.isDirectory(p)) "$rel/" else rel
                                zos.putNextEntry(ZipEntry(name))
                                if (!Files.isDirectory(p)) Files.copy(p, zos)
                                zos.closeEntry()
                            }
                        }
                    } catch (e: UncheckedIOException) {
                        // walk 的**迭代期** IO 错误（目录在遍历中被删、权限回卷…）走这里：
                        // `UncheckedIOException` **不是** `IOException`，外层的
                        // `catch (e: IOException)` 接不住 → tmp 不会被删、错误码也不是 ERR_IO。
                        // 故折成 IOException 再抛。
                        throw IOException("遍历源目录失败: ${e.cause?.message ?: e.message}", e)
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
                if (all.size > maxEntries) {
                    throw tooManyEntries(all.size, maxEntries)
                }

                // ── 第一拍：全包条目名 + **声明体积**校验，任何一条不合格都没开始写 ──
                var declaredTotal = 0L
                for (entry in all) {
                    resolveIn(targetRoot, entry.name)
                    // 声明为 -1（zip 未写该字段）不拦：那类条目由第二拍的计数闸兜住。
                    if (entry.size > maxEntryBytes) {
                        throw tooLarge(entry.name, entry.size, maxEntryBytes, "单条目")
                    }
                    if (entry.size > 0) declaredTotal += entry.size
                }
                if (declaredTotal > maxTotalBytes) {
                    throw tooLarge("(整包合计)", declaredTotal, maxTotalBytes, "总解压量")
                }

                // ── 第二拍：逐条复验 + 落字节（写它之前再验一次；同一 ZipFile 会话内
                //    中央目录已固定，两拍之间不存在换文件窗口）。体积在这里**按真读出来的
                //    字节数**再判一次 —— 第一拍读的是包的声明，声明可以撒谎。 ──
                var writtenTotal = 0L
                for (entry in all) {
                    val out = resolveIn(targetRoot, entry.name)
                    if (entry.isDirectory || entry.name.endsWith('/')) {
                        Files.createDirectories(out)
                    } else {
                        Files.createDirectories(out.parent)
                        val written = try {
                            zf.getInputStream(entry).use { ins ->
                                Files.newOutputStream(out).use { os ->
                                    copyCapped(ins, os, entry.name, writtenTotal)
                                }
                            }
                        } catch (e: AutojsException) {
                            // 半截文件不留：这一条已经确定是废的，留着它只会让用户以为
                            // "解出来一部分"。先前条目留在盘上 —— 那与任何中途 IO 失败同形，
                            // 本类不改这个既有语义（诚实炸弹在第一拍就零写入，走不到这里）。
                            Files.deleteIfExists(out)
                            throw e
                        }
                        writtenTotal += written
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
     * 抄字节，**边抄边数**：单条目超 [MAX_ENTRY_BYTES] 或累计超 [MAX_TOTAL_BYTES] 即停并抛。
     *
     * 这是本类唯一一处「内存/磁盘还没被写出去」的地方，所以上限必须落在这里 ——
     * `Files.copy` 不行，它对字节数无上限，而 `ZipEntry.getSize()` 是包自己声明的数。
     * 缓冲是一个固定大小的块，与条目真实大小无关；到顶即抛，不留半截。
     *
     * @param priorTotal 本次 [extract] 此前已落盘的字节数（总量的闸要连它一起算）。
     * @return 本条实际写出的字节数。
     */
    private fun copyCapped(
        ins: InputStream,
        os: OutputStream,
        entryName: String,
        priorTotal: Long,
    ): Long {
        val buf = ByteArray(COPY_CHUNK_BYTES)
        var entryBytes = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            entryBytes += n
            if (entryBytes > maxEntryBytes) {
                throw tooLarge(entryName, entryBytes, maxEntryBytes, "单条目")
            }
            if (priorTotal + entryBytes > maxTotalBytes) {
                throw tooLarge(entryName, priorTotal + entryBytes, maxTotalBytes, "总解压量")
            }
            os.write(buf, 0, n)
        }
        return entryBytes
    }

    /**
     * 超限的统一出口。**如实报真值**（哪一条、解出来多少、上限多少）—— 与 zip-slip
     * 同码 `ERR_INVALID_PARAM`：两者都是「这个归档本身不合格」，是**输入**的问题，
     * 不是目标盘的问题（后者才是 `ERR_DISK_FULL`，判据不在本类）。
     */
    private fun tooLarge(what: String, actual: Long, limit: Long, kind: String): AutojsException =
        AutojsException(
            ErrorCode.ERR_INVALID_PARAM,
            "$kind 超过解压上限（疑似压缩炸弹）: $what 解出 $actual 字节 > $limit 上限",
            null,
        )

    /**
     * 条目数超限的统一出口（批 99，外审 M7）。与 [tooLarge] 同码同层：都是「这个归档本身
     * 不合格」（**输入**的问题），话术必须点名「个数」以免与字节闸混淆。
     */
    private fun tooManyEntries(actual: Int, limit: Int): AutojsException =
        AutojsException(
            ErrorCode.ERR_INVALID_PARAM,
            "条目数超过解压上限（疑似零字节条目炸弹）: $actual 条 > $limit 上限",
            null,
        )

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

    companion object {
        /**
         * 单条目解压后上限（[extract] 强制）。
         *
         * **为什么需要它**：`auto.zip.extract` 的调用方是**脚本**，输入是一个 zip 路径 ——
         * 脚本作者不会想到要防「一个 1 MB 的包写出 10 GB」。这不是假想：zip 的压缩比可以到
         * 1000:1（全零/全同字节），而 [java.util.zip.ZipEntry.getSize] 是**包自己声明的数**，
         * 可以撒谎。所以真正的上限只能落在**字节流经过的地方**（[copyCapped]），声明那道只是
         * 便宜的早退。
         *
         * **为什么是 256 MiB**：合法用途是「解一个下载下来的资源/模型包」，实测这类包的单件
         * 在数十 MiB 量级；256 MiB 对合法用途是够不着的余量，只拦「这个包根本不是给我解的」。
         * 真要解更大的包，改这个常量即可 —— 它是**策略**，不是物理限制。
         *
         * **为什么不查剩余空间**：那是**配额面**的事（判据在安装会话 / 包大小管理页，见 §10.9
         * UX 5），不是解压器的。这里管的是「输入本身离谱」，与目标盘还剩多少无关 ——
         * 两件事混在一起会让「盘满了」和「包是炸弹」报同一个错，用户没法判断该删东西还是换文件。
         */
        const val MAX_ENTRY_BYTES: Long = 256L * 1024 * 1024

        /**
         * 整包解压后总上限（[extract] 强制，**声明与实读各判一次**）。
         *
         * 单条目上限挡不住「一万个小条目」——每个都不超顶、加起来写满盘。所以两道都要有：
         * 单条目的闸防「一个巨物」，总量的闸防「一群小东西」。
         */
        const val MAX_TOTAL_BYTES: Long = 1024L * 1024 * 1024

        /**
         * 条目个数上限（批 99，外审 M7）：**零字节条目绕过字节闸的那一档**。
         *
         * 取值依据：合法归档（脚本包/离线 bundle/录屏产物切片）的条目数在**数百到数千**
         * 量级；1 万条对合法用途是够不着的余量，而 50 万条零字节条目足以把 inode 与
         * 目录项耗满（Android 上更早触发的是「目录项过多」的 ENAMETOOLONG/ENOSPC）。
         * 与字节上限一样：它是**策略**，不是物理限制 —— 真要更大改此常量即可。
         */
        const val MAX_ENTRIES: Int = 10_000

        /** 抄字节的块大小（[copyCapped]）；与 `ShellCaptureLimit` 的读块同量级。 */
        private const val COPY_CHUNK_BYTES = 64 * 1024
    }
}
