package com.autoscript.engine.nodeprocess

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `main.cpp` 拼 node argv 的**契约门**（2026-10-14 真机 bug 的回归门）。
 *
 * **它挡的是什么**：`main.cpp` 把 `noden` 收到的参数拼成 `node::Start` 的 argv。
 * node 的解析规则是「先吃选项、遇 `--` 收手，其后全是**位置参数**」，而 `-e BOOTSTRAP`
 * 里的引导串用 `require(process.argv[1])` 取脚本路径 —— 于是**选项必须落在 `--` 之前**。
 * 2026-10-14 真机：批 107 的堆上限（`--max-old-space-size`，argv 注入，默认 192MB）
 * 被原样排在 `--` 之后，node 把它当成**脚本名**去 require，设备上原文
 * `Cannot find module '--max-old-space-size=192'`、退出码 1、引擎判 CRASHED、
 * 悬浮窗「本次执行未开始」。桌面全绿：真 spawn 测试用的是**系统 node**，
 * `main.cpp` 这段拼装根本没被执行到。
 *
 * **为什么是读源码**：`main.cpp` 是 C++，本机禁编（2026-10-13 用户令），
 * 唯一能在 JVM 单测里看住它的形态就是读源码钉结构 —— 与 `:app` 的
 * `AppShellEnginePathsTest`（同一场真机 bug 的另一半）同一条纪律。
 *
 * **判据三条**（都从源码现推，不写死字面量）：
 * 1. 拼 argv 的那段里**存在**「先收选项」的循环（读 `argv[i][0] == '-'`）；
 * 2. 选项的 push 落在 `--`（`dd`）的 push **之前**；
 * 3. 位置参数段的 push 落在 `dd` 的 push **之后**。
 *
 * 三条一起才等价于「选项在 `--` 前、位置参数在 `--` 后」—— 只判其中一条的话，
 * 把 `dd` 挪个位置就能让另一条静默失效。
 */
class MainCppArgvContractTest {

    @Test
    fun `main_cpp 把 node 选项拼在 -- 之前、位置参数拼在 -- 之后`() {
        val source = String(
            Files.readAllBytes(repoRoot().resolve(MAIN_CPP)),
            Charsets.UTF_8,
        )

        // 拼 argv 的那一段（从 `std::vector<char*> node_argv;` 到 node_start 调用）——
        // 只在这一段里找，免得文件里别处的 `--`（如 usage 文本）混进来。
        val start = source.indexOf(ARGV_BLOCK_START)
        assertTrue(start >= 0, "main.cpp 里找不到 `$ARGV_BLOCK_START` —— 拼 argv 的写法改了？改了就同步改本门。")
        val end = source.indexOf(NODE_START_CALL, start)
        assertTrue(end > start, "main.cpp 里找不到 `$NODE_START_CALL` —— node::Start 的调用点改了？")
        val block = source.substring(start, end)

        val ddPush = block.indexOf(DD_PUSH)
        assertTrue(ddPush >= 0, "拼 argv 的段里找不到 `$DD_PUSH`（`--` 分隔符）—— 它没了就等于位置参数直接接在选项后。")

        val optionPushes = Regex("""node_argv\.push_back\(argv\[i\]\)""").findAll(block).map { it.range.first }.toList()
        val optionGuard = Regex("""argv\[i\]\[0\]\s*==\s*'-'""").find(block)
        assertTrue(
            optionGuard != null,
            "拼 argv 的段里没有「`-` 开头即选项」的判别 —— 选项与位置参数没法分开，" +
                "堆上限这类 node 选项会被当成脚本名（2026-10-14 真机 bug 的形态）。",
        )

        // 判据 2：至少有一次 argv[i] 的 push 落在 `--` 之前 = 选项段。
        assertTrue(
            optionPushes.any { it < ddPush },
            "没有任何参数被拼在 `--` 之前 —— node 选项（如 --max-old-space-size）会落到 `--` 之后" +
                "被当成脚本名（2026-10-14 真机：Cannot find module '--max-old-space-size=192'）。",
        )
        // 判据 3：位置参数段（`--` 之后的透传循环）确实在 `dd` 之后。
        val tail = block.substring(ddPush)
        assertTrue(
            Regex("""for\s*\(int\s+\w+\s*=\s*\w+\s*;\s*\w+\s*<\s*argc""").containsMatchIn(tail),
            "`--` 之后没有位置参数透传循环 —— 脚本路径到不了 node（脚本跑不起来）。",
        )
        // 反向：收选项的那个循环必须**在第一个位置参数处停下**（`break`）—— 否则它会把
        // 脚本路径也当成选项塞进 `--` 之前，脚本就再也到不了 node。
        val beforeDd = block.substring(0, ddPush)
        assertTrue(
            Regex("""\bbreak\s*;""").containsMatchIn(beforeDd),
            "`--` 之前的收选项循环没有 `break` —— 它会把位置参数（脚本路径）也当成选项，" +
                "脚本到不了 node（`--` 之后没有位置参数）。",
        )
    }

    private companion object {
        const val MAIN_CPP = "engine/node-process/src/main/cpp/main.cpp"
        const val ARGV_BLOCK_START = "std::vector<char*> node_argv;"
        const val DD_PUSH = "node_argv.push_back(dd)"
        const val NODE_START_CALL = "node_start("

        /** 仓库根（沿 user.dir 向上找 `settings.gradle.kts`）—— 与 `:app` 的 repoRoot 同一惯例。 */
        fun repoRoot(): Path {
            var cur: File = File(System.getProperty("user.dir")).absoluteFile
            while (!File(cur, "settings.gradle.kts").isFile) {
                cur = cur.parentFile ?: error("上溯未见 settings.gradle.kts（user.dir=${System.getProperty("user.dir")}）")
            }
            return cur.toPath()
        }
    }
}
