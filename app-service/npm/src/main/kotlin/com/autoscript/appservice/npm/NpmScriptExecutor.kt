package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.npm.InstallEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * T1 脚本执行体：**起一次 npm 会话进程，把它的 `child_process` 接到 [NpmT1Bridge]**
 * （docs §10.3 T1 的执行侧，2026-10-10 批 91）。
 *
 * 与 [HostNodeExecutor] 的分工是**两条通道**（协调器那边讲得很清楚：lifecycle 脚本不做
 * reify，走事务链会凭空造 stageDir 与 commit 记录）：
 *
 * | | [HostNodeExecutor] | 本类 |
 * |---|---|---|
 * | 跑什么 | `npm install` 这类会重写 `node_modules` 的命令 | `npm run <script>` / `npm exec <bin>` |
 * | 事务 | stageDir + journal + 原子落位 | **没有事务**（`TrackedOp.journaled = false`） |
 * | 工作目录 | `stageDir` 旁的 `npmwork-<nonce>` | **项目根**（脚本就该在项目里跑） |
 * | spawn 门禁 | `npm-spawn-gate.cjs`（**一律拒**） | `npm-t1-bridge.cjs`（**经桥放行**） |
 * | 输出 | 落控制台环 + `outputTail` | 只回摘要（脚本的输出经桥回到 npm 自己，再由 npm 决定怎么显示） |
 *
 * **两条 shim 是互斥的**，不是叠加：安装会话要的是"零 spawn"，T1 会话要的恰恰是
 * "spawn 走桥"。把门禁 shim 也注进去，T1 的每一次 spawn 都会先撞门禁 —— 那不是安全，
 * 是把刚接上的路又堵死。故 `NODE_OPTIONS` 在这里**只**注 T1 桥那一份。
 *
 * ## 这一版**没有**做的（如实记账，不假装）
 *
 * - **不用引擎池**：§10.3 T1 写的是"沿 EnginePool 同路径拉临时引擎"，本版起的是
 *   `node <npm-cli.js> run <name>`。引擎池那条路要求"让引擎去执行一段 JS"，而这里要执行的
 *   是 **npm 自己**（它要 require 整棵 arborist 树），两者的 spawn 面不同。用引擎池的收益
 *   是统一的 TTL/看门狗/槽位账 —— 那些本版由协调器的 `withTimeoutOrNull` + 本类的
 *   `destroyForcibly` 承担。**这是一条真实的架构欠账**，登记在流水里。
 * - **不做最小 CapabilityMask**（§10.5-4）：脚本进程与 App 同 UID。设备上没有可用的隔离
 *   手段（沙箱已裁），故"最小能力"这一条现在只能靠"宿主只跑认得的那条命令"来近似。
 * - **不做输出流式**：脚本的 stdout 由 npm 自己捕获（它是父进程），宿主这侧只拿到
 *   npm 的摘要。控制台要看脚本输出，得等 T1 那条 `npm run` 的输出经 `HostNodeExecutor`
 *   的读流线程 —— 而那是**另一条命令**（用户在控制台敲的那次 `npm run`），不是本类。
 */
class NpmScriptExecutor(
    private val npmCliJs: Path,
    private val nodeBin: String,
    private val shimFile: Path,
    /** 桥会话（每次执行新开一条：一条 npm 会话一个凭据，用完即弃）。 */
    private val sessionFactory: T1SessionFactory,
    /** 基础环境（生产 = 父进程环境；测试可固定）。 */
    private val baseEnv: Map<String, String> = emptyMap(),
    private val userConfig: Path? = null,
) : ScriptOpExecutor {

    override suspend fun execute(op: ScriptOp, sink: ProgressSink): String = withContext(Dispatchers.IO) {
        if (!Files.isRegularFile(npmCliJs)) {
            throw AutojsException(
                ErrorCode.ERR_NOT_IMPLEMENTED,
                "T1 执行体缺 npm CLI：$npmCliJs（素材未落位，见 AssembledShell.npmCliFailure）",
            )
        }
        if (!Files.isRegularFile(shimFile)) {
            throw AutojsException(
                ErrorCode.ERR_NOT_IMPLEMENTED,
                "T1 桥 shim 未落位：$shimFile（装配层 fail closed：缺它不许假装脚本跑过）",
            )
        }
        // 桥会话先开：**端口与凭据必须在 spawn 之前就绪**，否则 npm 会话一起来就发不出
        // 第一条 spawn 帧（shim 的 `ensureConn` 读不到 env 会当场回 ERR_NOT_IMPLEMENTED）。
        val session = sessionFactory.open() ?: throw AutojsException(
            ErrorCode.ERR_NOT_IMPLEMENTED,
            "T1 桥 socket 绑定失败（名字被抢/平台不支持）：本次脚本未执行",
        )
        session.start()
        try {
            sink.emit(InstallEvent.Progress(op.projectId, op.handleId, InstallEvent.Phase.QUEUED))
            val outcome = runNpm(op, session)
            if (outcome.exitCode != 0) {
                throw AutojsException(
                    ErrorCode.ERR_IO,
                    "npm ${op.npmArgs.joinToString(" ")} 退出码 ${outcome.exitCode}：${outcome.tail.ifBlank { "（无输出）" }}",
                )
            }
            "npm ${op.action.name.lowercase()} ${op.what} 完成（退出码 0）"
        } finally {
            // 会话关断 = 收掉本次起的全部子进程（TERM → KILL）。放在 finally：异常/超时
            // 取消两条路都必须走到 —— 漏掉的那次留下的是一棵还在跑的进程树。
            session.close()
        }
    }

    /** 一次 npm 会话的进程侧结果。 */
    private class Outcome(val exitCode: Int, val tail: String)

    /**
     * 起 npm 会话并等它退。
     *
     * **工作目录 = 项目根**（与 [HostNodeExecutor] 的 work-prefix 模型刻意不同）：
     * `npm run` 读的是**项目自己的** `package.json` 与 `node_modules`，而 `--prefix` 会
     * 把 npm 的项目级配置也一并挪走（`.npmrc` 只看 prefix）。脚本就该在项目里跑，
     * 所以这里既不给 `--prefix` 也不另建 workDir。
     *
     * 输出**累积尾部**（不落控制台环）：脚本的 stdout 由 npm 捕获后自己处理，宿主这侧
     * 拿到的只是 npm 的摘要与可能的错误栈 —— 那些进异常详情，够定位。
     */
    private fun runNpm(op: ScriptOp, session: T1SessionHandle): Outcome {
        val argv = buildList {
            add(nodeBin)
            add(npmCliJs.toAbsolutePath().toString())
            addAll(op.npmArgs)
            userConfig?.let { add("--userconfig"); add(it.toAbsolutePath().toString()) }
        }
        val pb = ProcessBuilder(argv)
        pb.directory(op.projectRoot.toFile())
        val env = pb.environment()
        env.putAll(baseEnv)
        // **只注 T1 桥那一份**（见类 KDoc：与安装会话的门禁 shim 互斥）。
        env[NpmSpawnGate.ENV_NODE_OPTIONS] =
            NpmSpawnGate.mergeNodeOptions(env[NpmSpawnGate.ENV_NODE_OPTIONS], shimFile)
        NpmT1Bridge.applyEnv(env, session.connectTarget, session.token)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val collected = StringBuilder()
        val reader = Thread(
            {
                try {
                    proc.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            synchronized(collected) {
                                collected.append(line).append('\n')
                                // 只留尾部：脚本可能刷几万行，宿主只需要"最后说了什么"。
                                if (collected.length > TAIL_LIMIT * 2) {
                                    collected.delete(0, collected.length - TAIL_LIMIT)
                                }
                            }
                        }
                    }
                } catch (_: IOException) {
                    // 超时强杀时这条流以 IOException 收尾 —— 预期结束方式，不是病因。
                }
            },
            "t1-npm-reader",
        ).apply {
            isDaemon = true
            start()
        }
        val exited = proc.waitFor(op.timeoutMillis, TimeUnit.MILLISECONDS)
        if (!exited) {
            proc.destroyForcibly()
            // 会话也要跟着收：npm 死了但桥那侧起的子进程还在跑 —— 那是这次超时最该收干净的东西。
            session.close()
            reader.join(READER_JOIN_MILLIS)
            throw AutojsException(
                ErrorCode.ERR_TIMEOUT,
                "T1 脚本超时（${op.timeoutMillis}ms）：${op.what}（npm 会话与它起的子进程一并回收）",
            )
        }
        reader.join(READER_JOIN_MILLIS)
        return Outcome(proc.exitValue(), synchronized(collected) { collected.toString() }.trim())
    }

    private companion object {
        /** 失败详情里保留的输出尾部（够看错误栈）。 */
        const val TAIL_LIMIT = 4_000

        /** 等读流线程收尾的上限（与 `HostNodeExecutor` 同值同理由）。 */
        const val READER_JOIN_MILLIS = 2_000L
    }
}
