package com.autoscript.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * `:app` 各 E2E 共用的取件与开进程夹具（真 dist / 仓库根 / kBootstrap / node 拉起）。
 *
 * 为什么独立成文件而不是各自 private 一份：这四件都是**同一事实**（仓库根在哪、
 * `bridge/js/dist` 怎么读成资产 map、打包入口的引导串长什么样、怎么起一次 node），
 * 写两份必然漂移 —— 一处改了层数/转义规则，另一处照跑绿。Gradle 也不保证测试 cwd
 * 固定在模块目录（IDE 直跑 / `--tests` 过滤下都会变），所以 walk-up 不能简化成相对路径。
 */

/** 仓库根（沿 user.dir 向上找 `main.cpp`）——cwd 无论在哪个模块目录都能找到。 */
internal fun repoRoot(): Path {
    var cur: Path = Path.of("").toAbsolutePath()
    repeat(8) {
        if (Files.isRegularFile(cur.resolve("engine/node-process/src/main/cpp/main.cpp"))) return cur
        cur = cur.parent ?: error("walk-up 出了文件系统顶还没找到仓库根（user.dir=${Path.of("").toAbsolutePath()}）")
    }
    error("向上 8 层没找到 engine/node-process/src/main/cpp/main.cpp（cwd=${Path.of("").toAbsolutePath()}）")
}

/**
 * `bridge/js/dist` 全量读成扁平 map（`BridgeDistDeploy` 的入参形态）。
 *
 * dist 是 **tsc 构建产物**（不入 git）：缺件 = 没跑 npm build，不是仓库破损 ——
 * 本机 `npm --prefix bridge/js run build`，CI jvm-tests job 已前置同一条。
 */
internal fun readBridgeDist(): Map<String, ByteArray> {
    val dist = repoRoot().resolve("bridge/js/dist")
    check(Files.isDirectory(dist)) {
        "bridge/js/dist 缺件（tsc 构建产物：npm --prefix bridge/js run build；CI jvm-tests 已前置）"
    }
    return Files.list(dist).use { s ->
        s.filter { Files.isRegularFile(it) }.toList().associate {
            it.fileName.toString() to Files.readAllBytes(it)
        }
    }
}

/**
 * 现抽 main.cpp 的 kBootstrap（打包入口的引导串）。
 *
 * 从**源文件**抽而不是复制第二份：源改了测试跟着走 —— 引导串是 §12.4 交付轨的
 * 单一事实源，抄一份到测试里就是把"接线断了"变成"两边一起断，测不出来"。
 *
 * **逐字扫描，不用正则**（2026-10-16 CI 红换的）：原实现是
 * `Regex("""constexpr const char kBootstrap\[\]\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*)+)""")`
 * —— 嵌套量词让 JVM 正则引擎**按字符递归**，栈深随字面量块长度线性涨。块长到 1.1KB
 * （2026-10-16 加了第 4 段引导装配，块涨到 1.2KB+）当场 `StackOverflowError`：CI 上
 * `BridgeDistPackagingEntryTest` 三条用例全炸、栈里一千多层全是 `Pattern$Loop`。
 * 调大测试栈不是修法 —— 每加一段引导就重来一次。这里换成正则等价的**线性扫描**：
 * 定位声明 → 跳 `=` → 逐个收字面量（`\x` 两字符原样进 raw，交给下游解码）→
 * 下一个非空白不是 `"` 就收工。形态变了（改名/改写法）给可检索的 error，不静默抽错。
 */
internal fun extractBootstrap(mainCpp: String): String {
    val decl = "constexpr const char kBootstrap[]"
    val declAt = mainCpp.indexOf(decl)
    check(declAt >= 0) { "main.cpp 里找不到 kBootstrap 定义（改名了？本测锚的就是这个符号）" }
    var i = skipWhitespace(mainCpp, declAt + decl.length)
    check(mainCpp.getOrNull(i) == '=') { "kBootstrap 声明后面不是 `=`（写法变了？）" }
    i = skipWhitespace(mainCpp, i + 1)
    val parts = mutableListOf<String>()
    while (mainCpp.getOrNull(i) == '"') {
        val raw = StringBuilder()
        i = skipWhitespace(mainCpp, scanLiteral(mainCpp, i, raw))
        parts += raw.toString()
    }
    assertTrue(parts.isNotEmpty(), "kBootstrap 应由字符串字面量拼成")
    return parts.joinToString("") { decodeCEscapes(it) }
}

/** [from] 起跳空白，返回第一个非空白下标（到末尾就是 `length`）。 */
private fun skipWhitespace(src: String, from: Int): Int {
    var i = from
    while (i < src.length && src[i].isWhitespace()) i++
    return i
}

/**
 * 从 [start]（必须是开引号）扫到闭引号，把字面量原文追加进 [into]，返回闭引号的后一位。
 *
 * 转义**不解**（`\x` 两字符原样进 raw）—— 解码归 [decodeCEscapes]，两件事分开才看得清
 * "抽"与"还原"各自的边界。没闭合的引号 / 末尾孤零零一个 `\` 都当场报错（抽错了要响）。
 */
private fun scanLiteral(src: String, start: Int, into: StringBuilder): Int {
    var i = start + 1
    while (i < src.length && src[i] != '"') {
        val c = src[i]
        if (c != '\\') {
            into.append(c)
            i++
            continue
        }
        check(i + 1 < src.length) { "kBootstrap 的字面量末尾有没配对的 `\\`（抽不了）" }
        into.append(c).append(src[i + 1])
        i += 2
    }
    check(i < src.length) { "kBootstrap 的字面量没闭合（引号没配对）" }
    return i + 1
}

/** C 字符串转义还原（`\n`/`\t`/`\r`/`\"`/`\\`/`\'`；其余原样保留反斜杠）。 */
private fun decodeCEscapes(raw: String): String = buildString {
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
            when (raw[i + 1]) {
                'n' -> append('\n'); 't' -> append('\t'); 'r' -> append('\r')
                '"' -> append('"'); '\\' -> append('\\'); '\'' -> append('\'')
                else -> { append('\\'); append(raw[i + 1]) }
            }
            i += 2
        } else {
            append(c); i++
        }
    }
}

/** [extractBootstrap] 的仓库侧取件（各测都从同一个 main.cpp 现抽）。 */
internal fun repoBootstrap(): String =
    extractBootstrap(String(Files.readAllBytes(repoRoot().resolve("engine/node-process/src/main/cpp/main.cpp"))))

/**
 * 起一次 `node -e <bootstrap> -- <script>`（宿主 node 冒充 nodeN：引导串与 env 契约
 * 都是生产那份，只有可执行体不是 —— NAPI 面归 bridge/js 的 `AUTOSCRIPT_TEST_ADDON` 门禁）。
 *
 * @param bridgeDist 传 null = **剥掉** `AUTOSCRIPT_BRIDGE_DIST`（半接线形态：dist 落了但
 *   env 没给 → attachNative 不跑）。env 不继承宿主：显式给的才算数，免得本机恰好导出过的
 *   `AUTOSCRIPT_*` 把测试染绿。
 * @param extraEnv 逐条覆盖（`AUTOSCRIPT_RUN_ID=0` = 关掉心跳，见 DemoScriptTest：心跳会往
 *   addon 的 invoke 面插帧，断言"调用面恰好是这几条"的测必须把它摘掉）。
 */
internal fun runEntry(
    bootstrapJs: String,
    script: Path,
    addon: Path,
    bridgeDist: Path?,
    extraEnv: Map<String, String> = emptyMap(),
): Triple<Int, String, String> {
    val pb = ProcessBuilder(
        listOf("node", "-e", bootstrapJs, "--", script.toString()),
    ).redirectErrorStream(false)
    pb.environment()["AUTOSCRIPT_BRIDGE_ADDON"] = addon.toString()
    if (bridgeDist != null) pb.environment()["AUTOSCRIPT_BRIDGE_DIST"] = bridgeDist.toString()
    else pb.environment().remove("AUTOSCRIPT_BRIDGE_DIST")
    pb.environment()["AUTOSCRIPT_RUN_ID"] = "1"   // rid>0：把心跳行也走进（unref 不吊住）
    pb.environment().putAll(extraEnv)            // 逐条覆盖（含上面两个）
    val p = pb.start()
    val out = p.inputStream.bufferedReader().readText()
    val err = p.errorStream.bufferedReader().readText()
    val finished = p.waitFor(30, TimeUnit.SECONDS)
    assertTrue(finished, "node 超时未退出：out=$out err=$err")
    return Triple(p.exitValue(), out, err)
}
