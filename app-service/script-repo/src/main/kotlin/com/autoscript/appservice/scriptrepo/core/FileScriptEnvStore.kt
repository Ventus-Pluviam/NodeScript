package com.autoscript.appservice.scriptrepo.core

import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.scripts.ScriptEnvEntry
import com.autoscript.domain.scripts.ScriptEnvKeys
import com.autoscript.domain.scripts.ScriptEnvStore
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 脚本环境变量的落盘实现（docs §8.1 · `files/.ns/script-env.jsonl`）。
 *
 * 存储纪律与 [com.autoscript.appservice.scheduler.persist.FileTaskStore]（`tasks.jsonl`）
 * 逐条同款，不另立一套：
 * - **追加 + fsync**：每次写 `force(true)` 才返回 —— 用户点了"添加"就该是持久的，
 *   而这份表会直接影响下一次脚本执行（丢了 = 脚本拿到半个环境）；
 * - **replay 全量重建**：启动读一遍，最后状态即终态；
 * - **容忍最后半行**：崩溃截断只丢那半行，不连坐完好行（按字节扫 `\n`，不按行 split ——
 *   值里可能有换行，split 会把一行撕成两行）；
 * - **压实**（[compactIfOversize]）：append-only 只增不减，压实把已被覆盖的历史收掉。
 *   本表**可以**无损压实，因为读口要的是现状不是历史；`.ns/` 下那些「事实账」
 *   （`install.journal` / `approve-ledger.jsonl` / 系统日志 `host-log.jsonl`）
 *   **不可以**，同一手法搬过去就是把日志当缓存删。
 * - **零第三方依赖**：编解码走 `:domain` 的 [DomainJson]（仓内唯一 codec）。
 *
 * 行格式（冻结）：
 * - `{"op":"put","k":"<key>","v":"<value>"}`
 * - `{"op":"del","k":"<key>"}`
 *
 * `put` 后再 `del` 即真删（replay 按到达顺序收敛），**不做墓碑清理** —— 这是
 * append-only 的既有代价，与 `tasks.jsonl` 的 `del` 同一条口径。
 *
 * **key 的 `=` 与换行**由 [ScriptEnvKeys.reject] 在写入侧拦掉，JSON 转义由 codec 负责，
 * 故行内不会出现裸换行 —— replay 的"按 `\n` 扫"这条才成立。
 *
 * **不持有常开的 channel**（与 `FileTaskStore` 的差别）：这份表是**用户点出来的**
 * （添一条、删一条），写入频率以"次"计而不是以"条"计，为它常开一个 `FileChannel`
 * 换来的是"谁负责关"这个新问题（`AssembledShell.close` 关的是意图日志/档案/任务注册表
 * 那三件壳持有的持久件，本表不归壳）。改成每次追加现开现关 + `force(true)`：
 * 语义一样（返回前已落盘），少一个生命周期。
 */
class FileScriptEnvStore(
    private val dir: Path,
    /**
     * 盘上超过这个字节数就压实（生产用 [MAX_LIVE_BYTES]；可注入是为了让单测用小阈值
     * 走真代码，而不是把 256 KiB 写进测试里凑）。
     *
     * **可注入不是给生产开后门**：默认值就是生产值，唯一的调用方
     * （`AppShellApplication`）按原样构造，不传它。
     */
    private val maxLiveBytes: Long = MAX_LIVE_BYTES,
) : ScriptEnvStore {

    private val file: Path = dir.resolve(FILE_NAME)
    private val writeLock = Any()
    private val entries = LinkedHashMap<String, String>()   // replay 重建的收敛视图

    init {
        Files.createDirectories(dir)
        replay()
    }

    override fun all(): List<ScriptEnvEntry> = synchronized(writeLock) {
        entries.entries.sortedBy { it.key }.map { ScriptEnvEntry(it.key, it.value) }
    }

    override fun put(key: String, value: String) {
        ScriptEnvKeys.reject(key)?.let { throw IllegalArgumentException(it) }
        synchronized(writeLock) {
            appendLine("""{"op":"put","k":${q(key)},"v":${q(value)}}""" + "\n")
            entries[key] = value
            compactIfOversize()
        }
    }

    override fun remove(key: String): Unit = synchronized(writeLock) {
        // 幂等：没设过也落一行 del —— 与 FileTaskStore 的 tombstone 同口径。
        // 这里刻意**不**因"键不存在"提前返回：原由是「盘上没这行」与「盘上有一行、
        // 内存里没有」两种情况在重启后表现不同（前者不重现，后者会）。
        // **[compactIfOversize] 落地后这条区分被压掉了**（墓碑行不再与 put 行并存），
        // 但**行为仍照旧**：提前返回会省掉一次 fsync，代价是「删一个从没设过的键」
        // 与「删一个设过又删掉的键」在盘上彻底同形 —— 后者要能回答"它曾经存在过"。
        // 一条墓碑行几十字，换一个可回答的历史，不亏。
        appendLine("""{"op":"del","k":${q(key)}}""" + "\n")
        entries.remove(key)
        compactIfOversize()
    }

    /** 落盘文件（诊断/测试用；生产不读它，读的是 [all]）。 */
    fun file(): Path = file

    /** 压实阈值（诊断/测试用；生产用 [MAX_LIVE_BYTES]）。 */
    fun maxLiveBytes(): Long = maxLiveBytes

    /**
     * replay：**流式**扫盘（外审 B3 的「全量 `readAllBytes`」那半，2026-10-12 改）。
     *
     * 为什么必须流式：append-only 的长度只增不减，而这份表的写入方是**用户点出来的**
     * 「添加一条」—— 同一个 key 改一百次就是一百行，删掉的键也各留一行墓碑。`readAllBytes`
     * 会在每次冷启动把整份**历史**拉进堆，收敛后的结果可能只剩几十条。流式读把峰值压到
     * 「一行」量级。
     *
     * **自己按字节扫，不用 `readLine()`**：末行没有换行时 `BufferedReader.readLine()` **照样吐出来**
     * （它的语义是「读到 EOF 为止」），而末行半行必须**丢弃**（崩溃截断，见类 KDoc 与既有
     * 守卫「最后半行被丢弃」）。要区分「半行」与「整行」，就得知道这一行后面跟的是不是
     * `\n` —— 那是字节级的信息，`readLine` 不给。
     *
     * 为什么逐字节 `read()` 还不慢：`BufferedInputStream` 的缓冲让 syscall 落到块级，
     * 每字节只多一次数组读；这份表冷启动只扫一遍，与 Gradle/Node 那种真热点不同构。
     *
     * 值里可能有换行，但那是**转义后**的 `\n` 两个字符 —— 写入侧 [q] 走 [DomainJson.encode]，
     * 行内不会出现裸 `\n`。这条是「按 `\n` 扫」成立的前提，与类 KDoc 同一条依据。
     */
    private fun replay() {
        if (!Files.exists(file)) return
        BufferedInputStream(Files.newInputStream(file)).use { ins ->
            val line = ByteArrayOutputStream()   // 只留当前行
            while (true) {
                val b = ins.read()
                if (b < 0) return                          // 行缓冲里还有内容 = 最后半行 → 丢弃
                if (b == '\n'.code) {
                    val s = line.toString(StandardCharsets.UTF_8.name())
                    line.reset()
                    if (s.isNotBlank()) apply(s)
                } else {
                    line.write(b)
                }
            }
        }
    }

    /**
     * 盘上超过 [maxLiveBytes] 就**压实**成收敛视图（外审 B3 的「无压缩」那半，批 106）。
     *
     * 为什么这一份可以无损压实：读口 [all] 要的是**现状**，不是历史。append-only 只增不减，
     * 同一个键改一百次就是一百行、删掉的键各留一行墓碑，而收敛后可能只剩几十条 ——
     * 压实掉的每一行都是**已经被后一行覆盖**的历史，不是事实。
     *
     * 判据用 [Files.size] 而非数行数：那是 O(1) 的，而数行要么多扫一遍全文件（把压实
     * 自己变成第二个全量读），要么得在内存里另记一个与盘上可能已经漂移的计数。
     *
     * **改写是 tmp + 同目录 ATOMIC_MOVE**（与 `ScriptDeployRecovery.writeAtomic`、
     * `NpmCacheReclaim` 的桶重建同一条纪律）：直接 `TRUNCATE_EXISTING` 覆写会在写到一半
     * 崩溃时把一份**好文件**变成半截 —— 压实本该回收空间，却成了丢数据的途径。
     *
     * **不压实 tombstone 的必要性已经没有了**（见 [remove] 的 KDoc），但也不靠这个压实去
     * 回答"删过的键曾经存在" —— 那需要另一份记账，不是压缩能给的。
     */
    private fun compactIfOversize() {
        if (!Files.exists(file) || Files.size(file) <= maxLiveBytes) return
        val text = buildString {
            for (e in entries.entries.sortedBy { it.key }) {
                append("""{"op":"put","k":${q(e.key)},"v":${q(e.value)}}""").append("\n")
            }
        }
        atomicOverwrite(text.toByteArray(StandardCharsets.UTF_8))
    }

    /** 原子改写：写同目录临时文件 → fsync → ATOMIC_MOVE 就位；失败则删临时文件并抛出。 */
    @Suppress("TooGenericExceptionCaught") // 失败面宽是**刻意的**：压实失败的任何一种（写 tmp / fsync / move / 目录 fsync）都必须删掉 tmp 再原样上抛，一分类就等于放过某一种留垃圾
    private fun atomicOverwrite(bytes: ByteArray) {
        val tmp = Files.createTempFile(dir, "script-env-", ".compact")
        try {
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { ch ->
                val buf = ByteBuffer.wrap(bytes)
                while (buf.hasRemaining()) ch.write(buf)
                ch.force(true)
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            DeployPath.fsyncDirectory(dir)
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw t
        }
    }

    private fun apply(line: String) {
        val f = try {
            DomainJson.decodeObject(line)
        } catch (e: IllegalArgumentException) {
            throw IOException("script-env 行损坏：${e.message}", e)
        }
        val op = str(f, "op")
        val key = str(f, "k")
        when (op) {
            "put" -> entries[key] = str(f, "v")
            "del" -> entries.remove(key)
            else -> throw IOException("script-env 行损坏（op=$op）")
        }
    }

    /** 取字符串字段；缺失/类型不对一律**响亮**失败（静默跳过 = 用户以为存了其实没存）。 */
    private fun str(f: Map<String, DomainJson.Value>, name: String): String =
        (f[name] as? DomainJson.Value.S)?.v
            ?: throw IOException("script-env 行损坏：缺字符串字段 $name")

    /** 追加一行并 fsync 后才返回（见类 KDoc：不持有常开 channel）。 */
    private fun appendLine(s: String) {
        FileChannel.open(
            file,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
        ).use { ch ->
            val buf = ByteBuffer.wrap(s.toByteArray(StandardCharsets.UTF_8))
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
    }

    private fun q(s: String): String = DomainJson.encode(s)

    companion object {
        /** 落盘文件名（`files/.ns/` 下，与 `tasks.jsonl` / `approve-ledger.jsonl` 同族）。 */
        const val FILE_NAME: String = "script-env.jsonl"

        /**
         * 盘上超过这个字节数就压实（外审 B3 那半）。
         *
         * **256 KiB 不是随手取的**：一份收敛后的表有几十条、每条含用户可能写长的值，
         * 正常形态在几 KiB 量级；256 KiB 意味着「历史已累积到收敛视图的两三个数量级」，
         * 那时才值得付一次全量改写的代价。定得太小则是每次改一行都重写整份
         * （fsync 的成本与写入频率成正比），定得太大则空间回收迟迟不来。
         */
        const val MAX_LIVE_BYTES: Long = 256L * 1024
    }
}
