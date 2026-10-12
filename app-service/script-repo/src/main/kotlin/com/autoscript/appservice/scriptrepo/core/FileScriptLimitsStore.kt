package com.autoscript.appservice.scriptrepo.core

import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.scripts.ScriptLimitsStore
import com.autoscript.domain.scripts.ScriptRuntimeLimits
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 脚本资源限额的落盘实现（批 107；docs §8.1 · `files/.ns/script-limits.json`）。
 *
 * **与 [FileScriptEnvStore] 的分工**：那份是 **append-only 账**（用户点出来的历史，
 * 追加 + `force(true)` + replay 收敛），本类是**当前值**（一份覆盖写的单对象）。
 * 故存储纪律也整套不同，**不是「同款纪律的另一份实现」**：
 *
 * | | [FileScriptEnvStore] | 本类 |
 * |---|---|---|
 * | 形态 | jsonl 追加 | 单个 JSON 对象、整份覆盖 |
 * | 写入 | 每次追加 + `force(true)` | tmp + fsync + 同目录 `ATOMIC_MOVE` |
 * | 半行容忍 | 有（崩溃截断丢最后半行） | **不适用**（覆盖写没有「半份账」这个状态） |
 * | 压实 | 有（收敛视图重写） | **不适用**（本来就是收敛形态） |
 *
 * 为什么覆盖写仍然要 tmp + `ATOMIC_MOVE`：直接 `TRUNCATE_EXISTING` 覆写会在写到一半崩溃时
 * 把文件变成半截 JSON，下次启动读不出来 → **限额整个打不开**，而它恰好是内存/线程闸 ——
 * 「配置坏了于是不设限」比「配置坏了于是启动失败」更危险。故解析失败按「响亮失败」处理
 * （见 [load]），而**写入本身**保证不会留下半截。
 *
 * **不用 jsonl 的理由不是「习惯」**：append 对一份「当前值」只会让读侧每次 replay，
 * 而这份值的读者（spawn 前、每请求）比环境变量那张表更频繁。
 *
 * **缺省回填**：字段缺失一律取 [ScriptRuntimeLimits.DEFAULTS] 的那一项，而不是判废 ——
 * 老设备升级后新增的字段（本类的第一版就有五项，将来还会加）不该让**整份配置失效**。
 * 字段存在但**类型不对**（不是数字 / 是字符串 / 是负数）则**响亮失败**：
 * 那说明有人手改过文件，而「猜他要多少」比「告诉他写坏了」危险得多（堆上限猜错的后果是
 * 脚本 OOM 崩在用户看不懂的地方）。
 */
class FileScriptLimitsStore(private val dir: Path) : ScriptLimitsStore {

    private val file: Path = dir.resolve(FILE_NAME)
    private val lock = Any()

    override fun load(): ScriptRuntimeLimits = synchronized(lock) {
        if (!Files.isRegularFile(file)) return ScriptRuntimeLimits.DEFAULTS   // 从没改过 = 合法
        val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        if (text.isBlank()) return ScriptRuntimeLimits.DEFAULTS
        val fields = try {
            DomainJson.decodeObject(text)
        } catch (e: IllegalArgumentException) {
            throw IOException("脚本限额配置不是合法 JSON：${file.fileName}：${e.message}", e)
        }
        val d = ScriptRuntimeLimits.DEFAULTS
        return ScriptRuntimeLimits(
            maxHeapMb = num(fields, "maxHeapMb", d.maxHeapMb),
            uvThreadPoolSize = num(fields, "uvThreadPoolSize", d.uvThreadPoolSize),
            bridgeInFlightPerConnection = num(fields, "bridgeInFlightPerConnection", d.bridgeInFlightPerConnection),
            bridgeBytesPerConnectionMb = num(fields, "bridgeBytesPerConnectionMb", d.bridgeBytesPerConnectionMb),
            bridgeReadThreadsPerConnection = num(fields, "bridgeReadThreadsPerConnection", d.bridgeReadThreadsPerConnection),
        )
    }

    override fun save(limits: ScriptRuntimeLimits) {
        ScriptRuntimeLimits.reject(limits)?.let { throw IllegalArgumentException(it) }
        synchronized(lock) {
            // 手拼而不是 DomainJson.encode：这份文件的键序/字段名是**冻结的磁盘格式**
            // （读侧按名取键），而 encode 的 map 形态不保证顺序 —— 一份顺序随机的配置
            // 文件在 git diff 里全是噪声，故键序写死（与 FileTaskStore.Codec 同一条纪律）。
            val text = buildString {
                fun field(name: String, value: Int, last: Boolean = false) {
                    append("\"")
                    append(name)
                    append("\":")
                    append(value)
                    if (!last) append(',')
                }
                append('{')
                field("maxHeapMb", limits.maxHeapMb)
                field("uvThreadPoolSize", limits.uvThreadPoolSize)
                field("bridgeInFlightPerConnection", limits.bridgeInFlightPerConnection)
                field("bridgeBytesPerConnectionMb", limits.bridgeBytesPerConnectionMb)
                field("bridgeReadThreadsPerConnection", limits.bridgeReadThreadsPerConnection, last = true)
                append('}')
            }
            Files.createDirectories(dir)
            atomicOverwrite(text.toByteArray(StandardCharsets.UTF_8))
        }
    }

    /** 落盘文件（诊断/测试用；生产不读它，读的是 [load]）。 */
    fun file(): Path = file

    /**
     * 取一个整数字段：**缺失回缺省、形状不对响亮失败**（见类 KDoc 末尾那段理由）。
     *
     * 不接受 `N`（数字的原始字面量）之外的任何形态 —— 布尔/数组/对象都该是配置写坏了，
     * 而 `"192"`（字符串）与 `192` 在用户心里是同一个意思却确实是不同的形状，故字符串也拒：
     * 「写坏了就说写坏了」比「我猜你想要这个」可靠。
     */
    private fun num(fields: Map<String, DomainJson.Value>, name: String, fallback: Int): Int {
        val value = fields[name] ?: return fallback
        val raw = (value as? DomainJson.Value.N)?.raw
            ?: throw IOException("脚本限额配置字段 $name 不是数字（${file.fileName}）")
        return raw.toIntOrNull()
            ?: throw IOException("脚本限额配置字段 $name 不是整数：$raw（${file.fileName}）")
    }

    /** 原子覆盖：写同目录临时文件 → fsync → `ATOMIC_MOVE` 就位 → fsync 目录；失败删 tmp 并上抛。 */
    @Suppress("TooGenericExceptionCaught") // 写失败面宽是刻意的：写 tmp / fsync / move / 目录 fsync 任一失败都必须删掉 tmp 再原样上抛，一分类就等于放过某一种留垃圾
    private fun atomicOverwrite(bytes: ByteArray) {
        val tmp = Files.createTempFile(dir, "script-limits-", ".tmp")
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

    companion object {
        /** 落盘文件名（`files/.ns/` 下，与 `script-env.jsonl` 同目录不同族）。 */
        const val FILE_NAME: String = "script-limits.json"
    }
}
