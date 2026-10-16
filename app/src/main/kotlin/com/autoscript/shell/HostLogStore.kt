package com.autoscript.shell

import com.autoscript.bridge.ConsoleLine
import com.autoscript.domain.json.DomainJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * **宿主日志（系统日志）的落盘**（2026-10-17 用户令「系统日志不要每次进 app 都清除」）。
 *
 * 修的是一个很具体的体验缺口：管理面板 → 日志管理页的第一段「系统日志」
 * （= `ConsoleCollector` 里 `runId == 0` 的那些行，也就是[HostLog] 镜像进来的宿主事件）
 * 此前**只在内存里**（有界 2000 的环），进程一没就全没 —— 而用户来这一页的时机，
 * 十有八九正是"刚才出了点事，我重开看一眼"。
 *
 * **布局**：单个 `filesDir/.ns/host-log.jsonl`（一行一条 `{"at","level","text"}`）。
 *
 * **只记宿主行**（`runId != 0` 直接回）：脚本输出那条路走 [ScriptLogStore]
 * （按执行分文件、带索引），这里再落一份等于把"引擎外"与"这次执行"混进同一个文件。
 * 两边的守卫是**对偶**的（那边跳过 `runId == 0`，这边只收 `runId == 0`）—— 这就
 * 是"一个收集器、两条去向"的接线处，见 `AppShell.assemble` 里那个 `lineSink`。
 *
 * **落盘字段刻意只有三个**：`seq` 是**本次进程**的游标空间（回放时重新分配，存了也没用），
 * `runId` 在这一族里恒为 0（存一串恒等的值只会让格式看起来比它实际承载的多）。
 *
 * **无 retention**：只追加、不删不清 —— 与 [ScriptLogStore] 同一条口径
 * （backlog A13「已裁定不再跟进」：裁的是删）。**代价如实记**：文件会随宿主事件一直长，
 * 一部忙设备一年量级是几 MB～几十 MB；回放侧有 [MAX_REPLAY_BYTES] 的**尾读**上限，
 * 所以这件事伤的是磁盘，不是启动耗时。
 *
 * **写失败一律吞掉**（`runCatching`）：落盘是诊断面，为它把一次装配/一次投递掀翻是
 * 本末倒置（与 [ScriptLogStore] / `ConsoleHistory` 同一条纪律）。
 *
 * **读侧宽容**（[load]）：半行/坏行**跳过**，不让它把整份日志读不出来 ——
 * 本文件是"一行一次 `Files.write`"，崩溃时最后一行可能只有一半
 * （与 `ConsoleHistory` 的 `stringField` 同一条容忍）。
 */
class HostLogStore(
    private val file: Path,
) {
    private val lock = Any()

    /** 落一行宿主日志（`ConsoleCollector.lineSink` 的落点）。脚本行（runId != 0）直接回。 */
    fun append(line: ConsoleLine) {
        if (line.runId != 0L) return
        runCatching {
            synchronized(lock) {
                file.parent?.let { Files.createDirectories(it) }
                Files.write(
                    file,
                    (DomainJson.encode(
                        mapOf(
                            KEY_AT to line.atMillis,
                            KEY_LEVEL to line.level,
                            KEY_TEXT to line.text,
                        ),
                    ) + "\n").toByteArray(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
        }
    }

    /**
     * 读回可回放的那些行（按写入序，最新在最后）。
     *
     * **只读尾部** [MAX_REPLAY_BYTES]：环本身只装 2000 行，把整个文件读进来解析
     * 是纯浪费（用户要的是"重开还在"，不是"重开要等"）。截断点若落在一行中间，
     * 那半行解析必然失败 → 由宽容解析跳过，不额外写"跳过第一行"的特判
     * （少一条只在特定字节数下才走到的分支）。
     *
     * 返回的行的 `seq` 一律是 0：seq 由收集器在回放时**重新分配**
     * （见 `ConsoleCollector` 的 `restored` 参数）。
     */
    fun load(): List<ConsoleLine> =
        runCatching { readTail() }
            .getOrNull()
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { parse(it) }
            ?.toList()
            .orEmpty()

    private fun readTail(): String {
        if (!Files.isRegularFile(file)) return ""
        val size = Files.size(file)
        val from = if (size <= MAX_REPLAY_BYTES) 0L else size - MAX_REPLAY_BYTES
        Files.newByteChannel(file, StandardOpenOption.READ).use { ch ->
            ch.position(from)
            val buf = java.nio.ByteBuffer.allocate((size - from).toInt())
            while (buf.hasRemaining() && ch.read(buf) >= 0) {
                // 读满或到 EOF；`read` 返回 0 也会退出循环（不会自旋）。
            }
            return String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8)
        }
    }

    /** 一行 → [ConsoleLine]；形状不对回 null（跳过，不抛）。 */
    private fun parse(raw: String): ConsoleLine? {
        val obj = runCatching { DomainJson.decodeObject(raw) }.getOrNull() ?: return null
        val level = DomainJson.optStr(obj, KEY_LEVEL) ?: return null
        val text = DomainJson.optStr(obj, KEY_TEXT) ?: return null
        return ConsoleLine(
            seq = 0L,
            runId = 0L,
            level = level,
            text = text,
            atMillis = DomainJson.optLong(obj, KEY_AT) ?: 0L,
        )
    }

    companion object {
        /** 单文件名（`filesDir/.ns/` 之下；装配处拼路径，本类只管读写）。 */
        const val FILE_NAME: String = "host-log.jsonl"

        /** 回放时从文件尾部最多读这么多字节（见 [load]）。 */
        internal const val MAX_REPLAY_BYTES: Long = 1L shl 20

        private const val KEY_AT = "at"
        private const val KEY_LEVEL = "level"
        private const val KEY_TEXT = "text"
    }
}
