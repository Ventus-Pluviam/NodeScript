package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.scripts.ScriptPaths
import com.autoscript.testkit.HostNpm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * T1 spawn 桥的**真端到端**（§10.3 T1 下半段，2026-10-10 批 91）。
 *
 * 链上每一节都是真的：真 `node` 起真 npm CLI → npm 真发 `spawn("sh", ["-c", body])`
 * → shim 经真 unix socket 把帧报给宿主 → 宿主真起子进程 → stdout 经假管道回填 →
 * npm 收到退出码 → 脚本效果落在盘上。
 *
 * **为什么必须真跑**：这条链的失败模式全是"静默" —— shim 没被 `--require` 到、
 * socket 名对不上、帧字段拼错、stdout 没回填，四种错法在纯 Kotlin 断言下**全绿**
 * （与 `NpmSpawnGateTest` 真起 node 同一条理由）。而它们在生产里的表现都是
 * 「脚本好像跑了，但什么都没发生」。
 *
 * **不设 `assumeTrue`**（同 [NpmSpawnGateTest] / [T1BridgeNodeTest]，2026-10-10 批 91 定）：
 * 本仓 jvm-tests 的**前置步骤**就是 `npm --prefix bridge/js ci`（`:app` 的随包任务硬依赖
 * tsc 产物），所以"宿主没有 npm-cli.js"= 环境坏了，不是"诚实跳过"该盖住的情形。
 *
 * 更要紧的是**这一条不能是可跳过的**：它是全仓**唯一**证明「npm 真把 spawn 走到桥上」的
 * 用例（`T1BridgeNodeTest` 只证明桥自己是对的，证不了 npm 会用它），而它既不在
 * [com.autoscript.build.TestGuard.ENV_GATED] 里、也不在 `check-e2e-ran.sh` 的验尸名单里
 * —— 于是 `assumeTrue` 一旦触发就是**静默丢掉这层覆盖**（Gradle 报 skipped，而守卫那侧
 * 只有登记过的类才免红）。缺环境时红，才是如实。
 */
class T1BridgeE2ETest {

    @TempDir
    lateinit var dir: Path

    companion object {
        /** 宿主 npm CLI（[HostNpm] 三来源现查）；探不到时下面 [requireNpm] 当场红。 */
        private val npmCli: Path? = HostNpm.cliJs

        @JvmStatic
        @BeforeAll
        fun requireNpm() {
            // 红而不是跳过：见类 KDoc（本类是全仓唯一覆盖「npm 走桥」的用例，跳过 = 静默丢覆盖）。
            assertTrue(
                npmCli != null,
                "本测试要真起宿主 npm（桥的用途就是接住 npm 发的 spawn），但 HostNpm 三来源都没探到 " +
                    "npm-cli.js：`npm root -g` / `node -p process.execPath` 推 prefix / 静态位。",
            )
        }
    }

    private val files: Path get() = dir.resolve("files")

    /** 建一个带 [scripts] 的项目，返回项目根。 */
    private fun project(id: String, scripts: Map<String, String>): Path {
        val root = ScriptPaths.projectsRoot(files).resolve(id)
        Files.createDirectories(root)
        val body = scripts.entries.joinToString(",") { (k, v) ->
            "\"" + k + "\":\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        Files.write(
            root.resolve("package.json"),
            ("{\"name\":\"" + id + "\",\"version\":\"1.0.0\",\"scripts\":{" + body + "}}").toByteArray(),
        )
        return root
    }

    private fun executor(): NpmScriptExecutor {
        val shim = (NpmT1Bridge.deployShim(files) as NpmSpawnGate.Deploy.Ready).file
        return NpmScriptExecutor(
            npmCliJs = npmCli!!,
            nodeBin = "node",
            shimFile = shim,
            sessionFactory = SocketT1Sessions(NpmT1Bridge.fileSystemBinder()),
        )
    }

    private fun op(projectId: String, root: Path, what: String, timeoutMillis: Long = 60_000L) = ScriptOp(
        handleId = "h1",
        projectId = projectId,
        action = com.autoscript.domain.npm.InstallHistoryOp.RUN_SCRIPT,
        pkg = projectId,
        what = what,
        args = emptyList(),
        projectRoot = root,
        npmArgs = listOf("run", what),
        versionHash = "sha256:test",
        timeoutMillis = timeoutMillis,
    )

    @Test
    fun `npm run 的脚本经桥真跑起来，效果落在盘上`() = runBlocking {
        val root = project("p1", mapOf("hello" to "node -e \"require('fs').writeFileSync('t1-ran.txt','ok')\""))
        val summary = executor().execute(op("p1", root, "hello"), {}, OutputSink.None)
        assertTrue(summary.contains("完成"), "执行体该回一句人类可读的摘要：$summary")
        val written = root.resolve("t1-ran.txt")
        assertTrue(Files.isRegularFile(written), "脚本的副作用必须真发生（桥没把 spawn 接上的话这里什么都没有）")
        assertTrue(String(Files.readAllBytes(written)).contains("ok"), "内容也要对：${String(Files.readAllBytes(written))}")
    }

    @Test
    fun `脚本的 stdout 经假管道回到 npm，进而进宿主拿到的摘要`() = runBlocking {
        // npm 把脚本输出转发到自己的 stdout —— 这条链要成立，桥必须把 `out` 帧
        // 回填进 shim 那个 Readable，否则 npm 拿到的是空 stdout（脚本"跑了但没说话"）。
        val root = project("p2", mapOf("talk" to "node -e \"console.log('BRIDGE-SAID-HELLO')\""))
        val summary = executor().execute(op("p2", root, "talk"), {}, OutputSink.None)
        assertTrue(summary.contains("完成"), "退出码 0：$summary")
        // 摘要里没有脚本输出（那是 npm 自己的 stdout，本执行体只回摘要）—— 故这里验的是
        // **失败路径**能把它带出来（见下一例）。这一例只钉"跑成了"。
    }

    @Test
    fun `脚本非零退出时，它的输出出现在失败详情里（没报过流的兜底路）`() = runBlocking {
        val root = project(
            "p3",
            mapOf("boom" to "node -e \"console.error('BRIDGE-FAILURE-MARK'); process.exit(3)\""),
        )
        val e = assertThrows(AutojsException::class.java) {
            // OutputSink.None = 没报过流 → 尾部必须进失败详情（老路径，批 94 保留）。
            runBlocking { executor().execute(op("p3", root, "boom"), ProgressSink {}, OutputSink.None) }
        }
        val msg = e.message ?: ""
        assertTrue(msg.contains("退出码"), "非零退出要如实报码：$msg")
        assertTrue(
            msg.contains("BRIDGE-FAILURE-MARK"),
            "脚本的 stderr 必须经桥回到 npm、再进宿主详情 —— 少了它就是「跑了但看不见」：$msg",
        )
    }

    @Test
    fun `detached 在真链上被拒：shim 抛、npm 失败`() = runBlocking {
        // §10.3 T1 明写「shim 直接拒绝 detached:true」。这条在真链上验一次：
        // 拒绝发生在 shim 那侧（脚本进程里），而不是宿主侧兜底 —— 两者都要有，
        // 但只有这一条能证明 shim 真的被 --require 进去了。
        val root = project(
            "p4",
            mapOf("det" to "node -e \"require('child_process').spawn('echo',['x'],{detached:true})\""),
        )
        val e = assertThrows(AutojsException::class.java) {
            runBlocking { executor().execute(op("p4", root, "det"), ProgressSink {}, OutputSink.None) }
        }
        val msg = e.message ?: ""
        assertTrue(msg.contains("detached") || msg.contains("ERR_PERMISSION_DENIED"), "拒绝要能被看见：$msg")
    }

    @Test
    fun `脚本输出在进程退出前就到流上（批 94 真流式），失败详情不再重复尾部`() = runBlocking {
        // 脚本先吐标记、再睡三秒才退：流若"跑完才报"，标记的到达时间会贴近返回时间；
        // 真流式下它在脚本一开口就该到（npm 起来的那一两秒是共同底噪，不计）。
        val root = project(
            "p5",
            mapOf("slow" to "node -e \"console.log('STREAM-MARK'); setTimeout(()=>{},3000)\""),
        )
        val arrivals = mutableListOf<Pair<String, Long>>()
        val summary = executor().execute(
            op("p5", root, "slow"),
            {},
            OutputSink { text -> synchronized(arrivals) { arrivals += text to System.currentTimeMillis() } },
        )
        val returnedAt = System.currentTimeMillis()
        assertTrue(summary.contains("完成"), "退出码 0：$summary")
        val mark = synchronized(arrivals) { arrivals.firstOrNull { it.first.contains("STREAM-MARK") } }
        assertTrue(mark != null, "流上必须有脚本那行：$arrivals")
        assertTrue(
            returnedAt - mark!!.second > 1_500L,
            "标记必须远早于返回（脚本吐完又睡了 3s）—— 到达时间贴近返回 = 那是攒完再报不是流式：" +
                "到达差 ${returnedAt - mark.second}ms",
        )
    }

    @Test
    fun `取消探针置位后执行体在切片间隙杀掉会话（不等 60s TTL）`() = runBlocking {
        // 长睡脚本（60s）：不取消就该跑到 TTL。取消探针在 2s 后置位（npm 起来 + 子进程
        // 在飞），执行体必须在 200ms 切片内 destroyForcibly + 关会话，抛 ERR_ENGINE_STOPPED。
        // 断"远小于 TTL"而不是断精确毫秒（绝对 ms 不是常量）。
        val root = project("p6", mapOf("sleep" to "node -e \"setTimeout(()=>{},60000)\""))
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val op = op("p6", root, "sleep", timeoutMillis = 60_000L).copy(
            cancelSignal = OpCancelSignal { cancelled.get() },
        )
        val startedAt = System.currentTimeMillis()
        // 独立 scope（同 InstallCoordinatorTest.detached 的理由）：子协程失败会取消父作用域，
        // 那会让异常从测试方法自己抛出去、assertThrows 根本接不到。
        val job = CoroutineScope(Dispatchers.IO).async { executor().execute(op, ProgressSink {}, OutputSink.None) }
        delay(2_000L)
        cancelled.set(true)
        val e = assertThrows(AutojsException::class.java) { runBlocking { withTimeout(30_000L) { job.await() } } }
        assertEquals(
            com.autoscript.domain.core.ErrorCode.ERR_ENGINE_STOPPED,
            e.error,
            "取消的病理话术要如实（不是超时不是崩溃）：${e.message}",
        )
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue(
            elapsed < 30_000L,
            "取消后必须秒级收尾，而不是陪跑到 TTL：${elapsed}ms",
        )
    }
}
