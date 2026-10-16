package com.autoscript.appservice.npm

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 控制台的**命令历史**（2026-10-10 批 90；`files/.ns/console-history.jsonl`）。
 *
 * 修的是一个很具体的体验缺口：控制台关掉再进来，敲过的命令就没了 ——
 * 而控制台的用处一半在"把上次那条改一改再跑"。
 *
 * **与操作事实那条去向的分工**（2026-10-17 起那条不再是一本自己的账：
 * 见 [OpLogSink] —— 事实只交出去，落不落、落哪儿由装配层定）：
 * - 操作事实（[OpLogSink]）是**结论**：一次操作成没成、为什么没成。它今天落在
 *   系统日志上（`HostLog`），那份日志**只追加**、不修剪；
 * - 本类是**便利缓存**：它记的是"用户敲过什么"，唯一的消费者是输入行的补全。
 *   故它**允许修剪**（[record] 超过 [TRIM_THRESHOLD] 行时整体重写成最近 [maxEntries] 条）
 *   —— 丢掉的只是"很久以前敲过的一条命令"，没有任何事实随之消失。
 *   把这条差别写在这里，是因为两者长得几乎一样：**下一个人很容易把修剪也搬到
 *   那条去向的落点上**，那才是真错。
 *
 * **去重按整行**（读取侧 [recent] 里做）：连敲两次同一条命令只该在历史里出现一次，
 * 且保留**最近**那次的位置 —— 与 shell 的 `HISTCONTROL=erasedups` 同一条直觉。
 * 写入侧不去重：那是原始账式的记法（"这条命令被敲过几次"将来可能有用），
 * 而"显示什么"是读取侧的事。
 *
 * **按项目分开读**（[recent] 收 `projectId`）：控制台是"命令跑在某个项目上"的面
 * （顶部先选项目），历史跟着同一个作用域走 —— 在 A 项目敲的 `npm install axios`
 * 翻到 B 项目去点，落的是 B 的 `node_modules`，而按钮上那行字一模一样。
 * 这与操作事实**不分项目**不矛盾（[OpLogSink.log] 只带一个 projectId、去向侧
 * 原样收下）：那边问的是"这个宿主发生过什么"，这边问的是"我在这个项目里敲过什么"。
 *
 * **带凭据形态的行不记**（[looksSecret]）：见那条的 KDoc —— 这是本类唯一一处
 * 「宁可不记」的取舍，与 `InstallCoordinator.runConsoleCommand` 里"记给去向的只记
 * `listOf(cmd.sub)` 而不记完整 argv"是同一条纪律的落点。
 */
class ConsoleHistory(
    private val dir: Path,
    /** 保留条数上限（读取侧取多少、修剪时留多少，同一个数）。 */
    private val maxEntries: Int = MAX_ENTRIES,
) {

    private val file: Path = dir.resolve("console-history.jsonl")
    private val lock = Any()

    /**
     * 文件超过这个行数才重写（避免每敲一条都重写一遍整份历史）。
     *
     * 由 [maxEntries] 派生而**不是**常量：留 50 条却攒到 200 条才修剪，是"两个数
     * 各说各的"；而 `maxEntries` 一旦被调用方调小（测试就是这么用的），一个写死的
     * 阈值会让修剪看起来"没生效"——实测踩过。
     */
    private val trimThreshold: Int = maxEntries * TRIM_FACTOR

    /**
     * 记一条（**用户敲的那行原文**，含 `su`/`shizuku`/裸 shell 行 —— 它们同样是
     * "上次敲过的东西"，把它们排除在外只会让特权模式下的历史空掉）。
     *
     * 空行不记（没有信息量）。落盘失败**吞掉**：历史是便利面，
     * 为它把一条本来能跑的命令变成失败是本末倒置。
     */
    fun record(projectId: String, line: String, atMillis: Long = System.currentTimeMillis()) {
        val text = line.trim()
        if (text.isEmpty()) return
        if (looksSecret(text)) return
        runCatching {
            synchronized(lock) {
                Files.createDirectories(dir)
                Files.write(
                    file,
                    // **键序是契约的一部分**：`project` 必须排在 `line` 前面 ——
                    // 取字段用的是 `indexOf`（[stringField]），而 `line` 的值里完全
                    // 可能含有 `"project":"` 这样的字样（用户敲什么都有可能）。
                    // 前一个键的值里不可能出现后一个键名（项目号受
                    // `ScriptPaths.PROJECT_ID` 约束，不含引号），故这个顺序让
                    // 「第一次出现」恒等于「真的那个键」。
                    (buildString {
                        append("""{"project":""").append(q(projectId))
                        append(""","line":""").append(q(text))
                        append(""","at":""").append(atMillis)
                        append("}\n")
                    }).toByteArray(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
                trimIfNeeded()
            }
        }
    }

    /**
     * [projectId] 下最近敲过的若干条，**最近的在最前**、按整行去重（同一条只留最近那次）。
     *
     * 读失败 / 文件不存在一律回空表：历史读不出来不是错误，只是"没有历史可补"。
     * 别的项目的行**不参与去重也不占名额**（先滤项目再入集合）—— 否则 A 项目敲过的
     * `npm ls` 会把 B 项目那条同名的挤掉，而 B 的历史里就少了一格。
     */
    fun recent(projectId: String, limit: Int = maxEntries): List<String> = synchronized(lock) {
        if (!Files.exists(file)) return emptyList()
        val lines = runCatching {
            Files.readAllLines(file, StandardCharsets.UTF_8)
        }.getOrElse { return emptyList() }
        // 先滤出本项目那些行的正文（**最近的在最前**），再去重取前 limit 条 ——
        // 两步分开是为了让"滤项目"与"去重"各自读得懂：拧在一个循环里就是
        // 两个 continue 加一个 break，而它们回答的是两个不同的问题。
        val mine = lines.asReversed()
            .filter { stringField(it, "project") == projectId }
            .mapNotNull { stringField(it, "line") }
        val seen = LinkedHashSet<String>()
        for (text in mine) {
            if (seen.size >= limit) break
            seen.add(text)
        }
        seen.toList()
    }

    /** 超过 [trimThreshold] 行就整体重写成最近 [maxEntries] 行（见类 KDoc 的分工说明）。 */
    private fun trimIfNeeded() {
        val lines = runCatching { Files.readAllLines(file, StandardCharsets.UTF_8) }.getOrElse { return }
        if (lines.size <= trimThreshold) return
        val kept = lines.takeLast(maxEntries)
        runCatching {
            Files.write(
                file,
                (kept.joinToString("") { it + "\n" }).toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        }
    }

    /**
     * 取一个字符串字段；形状不对的行**跳过**（半行/截断行不该让整份历史读不出来）。
     *
     * 手写扫描而不是上 `DomainJson`：`:domain` 的 JSON 依赖本模块**看得见**，但这里
     * 要的是"半行也能读"，而解析器对坏输入是整体失败 —— 一份被写到一半的历史
     * 不该让前面几十条一起读不出来（与 [InstallJournal] 同一条容忍）。
     */
    private fun stringField(raw: String, name: String): String? {
        val text = raw.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        val key = "\"" + name + "\":"
        val start = text.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        if (from >= text.length || text[from] != '"') return null
        val sb = StringBuilder()
        var i = from + 1
        while (i < text.length) {
            when (val c = text[i]) {
                '\\' -> {
                    val next = text.getOrNull(i + 1) ?: return null
                    sb.append(
                        when (next) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            else -> return null
                        },
                    )
                    i += 2
                }
                '"' -> return sb.toString()
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    /** JSON 字符串转义（与 [InstallJournal] 同一份写法：控制字符一律 `\uXXXX`）。 */
    private fun q(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /**
     * 这行看起来夹着凭据 → **不记**（best-effort，不是安全边界）。
     *
     * 为什么需要：`InstallCoordinator.runConsoleCommand` 里那条「记给去向的只记
     * `listOf(cmd.sub)`，完整 argv 一个字不改地交给 npm」的注释已经点破了这个风险
     * —— `npm install --//registry.example.com/:_authToken=…` 是**能敲进控制台的**。
     * 内存里的控制台环随进程消失，而这份历史落盘、跨重启还在。
     *
     * **为什么是整条跳过，而不是打码后记录**：打码后的历史看起来是一条能跑的命令，
     * 用户点它填进输入框，得到的是「认证失败」——一个由历史自己造出来的假故障。
     * 而"这条没进历史"最多是少一格便利。两个坏结果里选小的那个。
     *
     * **边界（如实写在契约里）**：认的是**参数名的形态**，不是熵/长度启发式 ——
     * 用户把 token 直接当包名敲（`npm install sk-live-…`）不会被认出来，那不在
     * 本类的职责里（本类是便利缓存，不是 DLP）。
     */
    private fun looksSecret(line: String): Boolean {
        val lower = line.lowercase()
        return SECRET_MARKERS.any { lower.contains(it) }
    }

    private companion object {
        /** 读取侧默认给多少条（够翻一屏，不至于把输入行挤没了）。 */
        const val MAX_ENTRIES = 50

        /** 攒到「保留条数」的几倍才重写一次（见 [trimThreshold]）。 */
        const val TRIM_FACTOR = 4

        /**
         * 凭据形态参数名的片段（**小写比**）。覆盖 npm 认的那几个写法：
         * `//host/:_authToken=` / `:_auth=` / `:_password=` / `--otp` / `NPM_TOKEN`。
         */
        val SECRET_MARKERS = listOf("_auth", "_password", "--otp", "npm_token", "authtoken")
    }
}
