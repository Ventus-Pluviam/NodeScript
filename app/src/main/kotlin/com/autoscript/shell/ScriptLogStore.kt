package com.autoscript.shell

import com.autoscript.bridge.ConsoleLine
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 脚本执行的**输出日志持久化**（2026-10-13 用户令；`filesDir/.ns/script-logs/`）。
 *
 * 用户要的是两件事：脚本跑的时候有个悬浮窗实时看日志；跑完（或中途）去日志管理页
 * 「脚本输出」那一栏按**每次执行**找到这次的输出 —— 控制台那份内存里的
 * `ConsoleCollector`（有界 2000、丢最老的）两件事都做不到（关应用就没、跑两回就混）。
 *
 * **布局**：每执行一个文件 `&lt;runId&gt;.log`（JSONL，一行一条），外加 `index.jsonl`
 * （run 启动时追加一条 `{run,project,path,at}`）。文件名键用 engineRunId：它是
 * `RuntimeController.start` 里 receipt 带出来的递增 id（`nodeEngineRunIds` 计数器），
 * 一次执行一个号，不会两条执行写进同一个文件；同一 runId 若崩溃重记（index 重复行）
 * 读取侧 last-wins。**无 retention**：append-only、不删不清（与 backlog A13
 * 「已裁定不再跟进」同一口径 —— 那里裁的是删，落的是别的日志面；本面从生下来就是不删）。
 *
 * **与谁分工（长得像但纪律不同，别抄混）**：
 * - `ConsoleHistory`（`:app-service:npm`）是**便利缓存**：记"敲过什么"，超量整体重写；
 * - 本类是**诊断事实**：记"这次执行输出了什么"，只追加、永不修剪 —— 丢掉的是现场，
 *   不是"很久以前的一条输入"；
 * - `ConsoleCollector` 仍是实时面（有界内存、游标拉取）：落盘只发生在 [appendLine]，
 *   收集器的淘汰照旧，互不干扰。
 *
 * **只记脚本行**（`runId != 0`）：宿主自己的行（runId 0）已经由意图日志/任务日志
 * 记事实了，这里再落一份等于把"引擎外"与"这次执行"的输出混进同一个文件 ——
 * 详情页打开就分不清哪句是谁说的。
 *
 * **写失败一律吞掉**（`runCatching`）：落盘是诊断面，为它把一条本来能跑的执行
 * 变成失败是本末倒置（与 `ConsoleHistory.record` 同一条纪律）。
 */
class ScriptLogStore(
    private val dir: Path,
) {
    private val lock = Any()
    private val indexFile: Path = dir.resolve(INDEX_NAME)

    /**
     * 一次执行开始时记一条索引（`RuntimeController.onRunStarted` 的落点）。
     *
     * 线程安全：start 与 appendLine 可能并发（执行一起日志就到），同锁串行。
     */
    fun begin(runId: Long, projectId: String, scriptPath: String, atMillis: Long) {
        runCatching {
            synchronized(lock) {
                Files.createDirectories(dir)
                Files.write(
                    indexFile,
                    // **键序是契约的一部分**：`run` 必须排在最前 —— 读侧
                    // 取字段用 `indexOf`，而 `project`/`path` 的值里完全可能含有
                    // `"run":"` 这样的字样（用户起什么项目名都有可能）；先写的键
                    // 其值受项目号约束（不含引号，见 `ProjectId`），
                    // 故「第一次出现」恒等于真的那个键（`ConsoleHistory` 同款手法）。
                    (buildString {
                        append("""{"run":""").append(runId)
                        append(""","project":""").append(q(projectId))
                        append(""","path":""").append(q(scriptPath))
                        append(""","at":""").append(atMillis)
                        append("}\n")
                    }).toByteArray(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
        }
    }

    /** 落一行脚本输出（`ConsoleCollector.lineSink` 的落点）。宿主行（runId 0）直接回。 */
    fun appendLine(line: ConsoleLine) {
        if (line.runId == 0L) return
        runCatching {
            synchronized(lock) {
                Files.createDirectories(dir)
                Files.write(
                    dir.resolve(logName(line.runId)),
                    (buildString {
                        append("""{"seq":""").append(line.seq)
                        append(""","at":""").append(line.atMillis)
                        append(""","level":""").append(q(line.level))
                        append(""","text":""").append(q(line.text))
                        append("}\n")
                    }).toByteArray(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
        }
    }

    companion object {
        const val INDEX_NAME = "index.jsonl"
        fun logName(runId: Long): String = "$runId.log"

        /**
         * JSON 字符串字面量：**两端补引号** + 转义（引号/反斜杠/控制字符）。
         *
         * 名字与形状都跟 `ConsoleHistory.q` / `InstallJournal.q` 对齐（那两份都是
         * 「补引号 + 转义」）—— 2026-10-14 之前这里只转义、不补引号，writer 于是写出
         * `{"run":7,"project":demo,...}`：**不是合法 JSON**，读侧 `stringField`
         * 找 `"project":"` 恒落空 → 每一行 index 都解析失败、列表恒空（CI 上 6 条全红）。
         *
         * 为什么不能只转义：调用点是原始字符串 `""","project":"""` —— 结尾那个
         * `"` 被 Kotlin 当成了定界符的一部分吃掉，写出的字面量里没有开引号，
         * 所以引号只能由本函数补（别处调用点自己写了 `append("\\"")`，那是另一回事）。
         */
        fun q(raw: String): String = buildString(raw.length + 2) {
            append('"')
            for (c in raw) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
    }
}