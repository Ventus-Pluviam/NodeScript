package com.autoscript.e2e

import com.autoscript.appservice.scriptrepo.core.BridgeDistDeploy
import com.autoscript.appservice.scheduler.recovery.ScriptDeployRecovery
import com.autoscript.domain.scripts.ScriptPaths
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 随包示例脚本（`app/src/main/assets/scripts/demo/`）的门禁：**它就是 APK 上唯一能跑的东西**，
 * 所以它必须和引擎、facade 一起被门看着 —— 否则 dist 改一次签名、桥改一次调用面，
 * 示例脚本就在真机上变成一段「按下运行什么也不发生」的东西，而 CI 一片绿。
 *
 * 走的是生产那条链，只换掉两处不可搬进 JVM 单测的件：
 * - 资产来源：生产是 `AndroidAssetsSource`（AssetManager），本测**从盘上递归读同一批文件**
 *   （`app/src/main/assets/scripts/demo/` 下那批文件就是单一事实源，不是另抄一份清单）；
 * - 引擎可执行体：生产是 `libnoden.so`，本测是宿主 `node`（引导串与 env 契约仍是生产那份，
 *   NAPI 面归 `bridge/js` 的 `AUTOSCRIPT_TEST_ADDON` 门禁）。
 *
 * 断言的是**脚本打到控制台的那五行原文 + 调用面**，而不是"进程退 0"：假 addon 逐条打调用
 * marker、并把 `console.*` 的正文原样回打，于是「多要一项能力」「多碰一个命名空间」
 * 「少打一行」都会让本测红 —— 示例脚本的调用面与输出都是对外承诺的一部分
 * （用户在真机上看的正是这五行，见 §7.3 `console` namespace）。
 */
class DemoScriptTest {

    @TempDir
    lateinit var dir: Path

    /** 假 addon 打的调用 marker（与下面 JS 里那份同一个来源，见 [answeringAddonJs]）。 */
    private val callMark = "__bridge_call__ "

    /** 假 addon 把 `console.log` 的正文原样回打的 marker —— 断言的就是控制台屏那几行。 */
    private val textMark = "__console__ "

    /**
     * 假 addon：**答话**（回帧）而不是记账，两条命名空间都认。
     *
     * 为什么必须回帧、不能靠返回值：`attachNative` 装到桥上的 handler 会**丢掉**
     * `addon.invoke` 的返回值（恒 undefined = 已投递），结算只走 `addon.setup` 注册的
     * onFrame（生产 = Kotlin Router → TSF → 这一路）。所以假 addon 得自己把
     * `{t:'ok', id, payload}` 帧喂回去 —— 顺带把**回包结算面**也纳进了本测。
     *
     * 两条命名空间：`device.*` 回假设备值；`console.log` 回空并把**文本原样打到 stdout**
     * —— 于是本测断言的正是用户在控制台屏上会看到的那几行（示例脚本的输出走桥上
     * `console` namespace，不是裸 console.log：引擎的排水线程对子进程 stdout 读即弃）。
     * 白名单外的调用回 `ERR_NOT_IMPLEMENTED` 错误帧：示例脚本的调用面是承诺，
     * 多一条就该红（错误原文会照 demo 的失败路径进 stderr）。
     *
     * marker 与 console 文本都用 `fs.writeSync` 同步写：`process.exit` 不 flush 异步管道
     * 写，本仓实测被截断过（裸 console.log 的成功路径只出来前三行）—— 测试夹具不能自带抖动。
     */
    private val answeringAddonJs: String = """
        const fs = require('fs');
        const say = (s) => fs.writeSync(1, s + '\n');
        let onFrame = null;
        const ok = (reqId, value) => onFrame(JSON.stringify({ t: 'ok', id: reqId, payload: JSON.stringify(value) }));
        module.exports = {
          setSocketFd(fd) { global.__fd = fd },
          setup(f) { onFrame = f },
          invoke(ns, method, payload, reqId, ttl) {
            say('$callMark' + ns + '.' + method);
            if (ns === 'console' && method === 'log') {
              const p = JSON.parse(payload);
              say('$textMark' + p.level + ' ' + p.text);
              ok(reqId, null);
            } else if (ns === 'device' && method === 'model') {
              ok(reqId, 'Pixel-Test');
            } else if (ns === 'device' && method === 'sdkInt') {
              ok(reqId, 34);
            } else {
              onFrame(JSON.stringify({ t: 'err', id: reqId, code: 'ERR_NOT_IMPLEMENTED', detail: '白名单外的能力：' + ns + '.' + method }));
            }
          },
        };
    """.trimIndent()

    /**
     * 递归读示例脚本资产（相对路径 → 字节）—— 生产走 `AssetsWalk`/`AndroidAssetsSource`
     * 的同一条 BFS 语义（`assets/scripts/<projectId>/` 下的**子目录一起走**），
     * 本测从盘上读同一批文件：`lib/greet.js` 必须在返回值里，否则"递归部署"这句话没有证据。
     */
    private fun demoAssets(): Map<String, ByteArray> {
        val root = repoRoot().resolve("app/src/main/assets/scripts/demo")
        check(Files.isDirectory(root)) { "示例脚本资产不在位：$root（它随 APK 分发，是唯一能跑的东西）" }
        return Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) }.toList().associate {
                root.relativize(it).toString().replace('\\', '/') to Files.readAllBytes(it)
            }
        }
    }

    /**
     * 从已打印的 console 行里取 node 版本号（`Node v22.23.1 已启动…` 那条）。
     * 版本号随本机 node 走 —— 断言里写死版本号 = 换一台机器就红，而它跟本测要证的事无关。
     */
    private fun nodeVersionOf(lines: List<String>): String {
        val line = lines.firstOrNull { it.startsWith("log [demo] Node ") }
            ?: error("没打出 node 版本那一行：$lines")
        return line.removePrefix("log [demo] Node ").substringBefore(" 已启动")
    }

    /** 把示例脚本按生产那条补部署落位，返回落位后的 main.js 路径。 */
    private fun deployDemo(files: Path): Path {
        val assets = demoAssets()
        assertTrue(assets.containsKey("lib/greet.js"), "子目录必须一起随包（递归部署）：${assets.keys}")
        val report = ScriptDeployRecovery(files, mapOf("demo" to assets)).run()
        assertTrue(report.failures.isEmpty(), "补部署不该有失败项：${report.failures}")
        assertEquals(assets.size, report.deployed.size, "每个资产文件都要落位（deployed=${report.deployed}）")
        return ScriptPaths.scriptFile(files, "demo", "main.js")
    }

    @Test
    fun `随包示例脚本在真 dist 上跑通——调用面恰好是取型号与 API 级别两条`() {
        val files = dir.resolve("files")
        assertTrue(BridgeDistDeploy(files, readBridgeDist()).run().failures.isEmpty(), "真 dist 首落应成功")
        val main = deployDemo(files)

        val addon = dir.resolve("answering-addon.js")
        Files.write(addon, answeringAddonJs.toByteArray())

        val (code, out, err) = runEntry(
            repoBootstrap(), main, addon,
            bridgeDist = ScriptPaths.autoModuleRoot(files),
            // 心跳（`engines.heartbeat`）也走 addon 的 invoke 面，会往 marker 流里插帧 ——
            // 本测断言的是"脚本的调用面恰好是这两条"，所以把心跳关掉（rid=0），
            // 而不是把它过滤掉：过滤等于给"心跳改走别的路"开口子。
            extraEnv = mapOf("AUTOSCRIPT_RUN_ID" to "0"),
        )

        // 调用面：命名空间恰好这两个；能力调用恰好这两条且有序（示例脚本的对外承诺）。
        val calls = out.lines().filter { it.startsWith(callMark) }.map { it.removePrefix(callMark) }
        assertEquals(
            setOf("console", "device"), calls.map { it.substringBefore('.') }.toSet(),
            "示例脚本碰了新增的命名空间（调用面是对外承诺，改它要连着改这条断言）：$out",
        )
        assertEquals(
            listOf("device.model", "device.sdkInt"), calls.filter { it.startsWith("device.") },
            "取型号与 API 这两条的次序/条数变了：$out",
        )

        // 输出面：这五行就是用户在控制台屏上会看到的（脚本走桥上 console namespace）。
        val consoleLines = out.lines().filter { it.startsWith(textMark) }.map { it.removePrefix(textMark) }
        assertEquals(
            listOf(
                "log [demo] 你好，AutoScript",
                "log [demo] Node ${nodeVersionOf(consoleLines)} 已启动（引擎进程活着）",
                "log [demo] 跨进程桥 installed=true",
                "log [demo] 设备 Pixel-Test / API 34",
                "log [demo] 四层都通了（引擎 → facade → 桥 → 平台面）",
            ),
            consoleLines,
            "控制台那五行变了（递归落位的 lib/greet.js 没用上 / 桥没装上 / 回包没走到脚本都会是这个形状）：$out",
        )
        assertEquals(0, code, "exit=$code out=$out err=$err")
        assertTrue(err.isEmpty(), "成功路径不该有 stderr 噪声：$err")
    }

    @Test
    fun `桥未装——示例脚本如实报 ERR_ENGINE_STOPPED 并以 1 退出（不粉饰成跑通）`() {
        val files = dir.resolve("files")
        // dist 落了但 env 没给：attachNative 不跑（半接线形态）—— 与生产里
        // "assets/bridge-dist 缺件"同形，脚本必须如实失败而不是打印个假设备名。
        BridgeDistDeploy(files, readBridgeDist()).run()
        val main = deployDemo(files)

        val addon = dir.resolve("answering-addon.js")
        Files.write(addon, answeringAddonJs.toByteArray())

        val (code, out, err) = runEntry(
            repoBootstrap(), main, addon,
            bridgeDist = null,
            extraEnv = mapOf("AUTOSCRIPT_RUN_ID" to "0"),
        )

        assertEquals(1, code, "桥没装上时示例脚本必须以非 0 退出：out=$out err=$err")
        // 桥断了 auto.console 也发不出去（fire-and-forget 吞掉）—— 唯一的实话在退出码 + 这一行 stderr。
        assertTrue(err.contains("[demo] 失败："), "失败要有一句人话，不只是静默退 1：$err")
        assertTrue(err.contains("ERR_ENGINE_STOPPED"), "错误码要如实带出来（§7 错误面）：$err")
        assertTrue(
            out.lines().none { it.startsWith(callMark) },
            "桥未装却仍投了调用（凭空多出一条 invoke）：$out",
        )
        assertTrue(
            out.lines().none { it.startsWith(textMark) },
            "桥未装却仍打出了控制台行（那些行在设备上根本到不了控制台屏）：$out",
        )
    }
}
