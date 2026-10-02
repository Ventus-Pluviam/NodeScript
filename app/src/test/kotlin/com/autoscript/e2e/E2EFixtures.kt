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
 * C 字符串字面量拼接 + 转义还原（不能用「到第一个 `;`」—— JS 语句的分号就在字面量里面）。
 */
internal fun extractBootstrap(mainCpp: String): String {
    val block = Regex("""constexpr const char kBootstrap\[\]\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*)+)""")
        .find(mainCpp)?.groupValues?.get(1)
        ?: error("main.cpp 里找不到 kBootstrap 定义（改名了？本测锚的就是这个符号）")
    val parts = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(block).map { it.groupValues[1] }.toList()
    assertTrue(parts.isNotEmpty(), "kBootstrap 应由字符串字面量拼成")
    return parts.joinToString("") { raw ->
        buildString {
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
