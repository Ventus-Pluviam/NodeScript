package com.autoscript.appservice.scriptrepo.core

import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.scripts.ScriptEnvEntry
import com.autoscript.domain.scripts.ScriptEnvKeys
import com.autoscript.domain.scripts.ScriptEnvStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 脚本环境变量的落盘实现（docs §8.1 · `files/.autojs/script-env.jsonl`）。
 *
 * 存储纪律与 [com.autoscript.appservice.scheduler.persist.FileTaskStore]（`tasks.jsonl`）
 * 逐条同款，不另立一套：
 * - **追加 + fsync**：每次写 `force(true)` 才返回 —— 用户点了"添加"就该是持久的，
 *   而这份表会直接影响下一次脚本执行（丢了 = 脚本拿到半个环境）；
 * - **replay 全量重建**：启动读一遍，最后状态即终态；
 * - **容忍最后半行**：崩溃截断只丢那半行，不连坐完好行（按字节扫 `\n`，不按行 split ——
 *   值里可能有换行，split 会把一行撕成两行）；
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
class FileScriptEnvStore(private val dir: Path) : ScriptEnvStore {

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
        }
    }

    override fun remove(key: String): Unit = synchronized(writeLock) {
        // 幂等：没设过也落一行 del —— 与 FileTaskStore 的 tombstone 同口径。
        // 这里刻意**不**因"键不存在"提前返回：提前返回会让"盘上没这行、内存里也没有"
        // 与"盘上有一行、内存里没有"两种情况在重启后表现不同（前者不会重现，后者会）。
        appendLine("""{"op":"del","k":${q(key)}}""" + "\n")
        entries.remove(key)
    }

    /** 落盘文件（诊断/测试用；生产不读它，读的是 [all]）。 */
    fun file(): Path = file

    private fun replay() {
        if (!Files.exists(file)) return
        val bytes = Files.readAllBytes(file)
        var pos = 0
        while (true) {
            val nl = findNewline(bytes, pos)
            if (nl < 0) return                       // 最后半行：丢弃（崩溃截断）
            val line = String(bytes, pos, nl - pos, StandardCharsets.UTF_8)
            pos = nl + 1
            if (line.isNotBlank()) apply(line)
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

    private fun findNewline(b: ByteArray, from: Int): Int {
        for (i in from until b.size) if (b[i] == '\n'.code.toByte()) return i
        return -1
    }

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
        /** 落盘文件名（`files/.autojs/` 下，与 `tasks.jsonl` / `approve-ledger.jsonl` 同族）。 */
        const val FILE_NAME: String = "script-env.jsonl"
    }
}
