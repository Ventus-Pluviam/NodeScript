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
 * | 放行判据 | 无（重操作即脚本的判据） | 解析层白名单（脚本可跑性，无人工审批） |
 * | 输出 | 落控制台环 + `outputTail` | 落控制台环（真流式，批 94）+ 失败详情带尾部 |
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
 *
 * ## 输出与取消（2026-10-11 批 94 补齐）
 *
 * - **真流式**：npm 会话进程的 stdout（= npm 自己的播报 + 它捕获的脚本输出，`npm run`
 *   对子进程是 stdio 继承）由读流线程**边读边 [OutputSink.line]**，与批 90 安装会话那条
 *   逐字同纪律（空行跳过；报过流则失败异常**不再重复尾部**，防同一段文本出现两遍）。
 * - **取消可杀**：`waitFor` 改**切片轮询**（[CANCEL_POLL_SLICE_MILLIS] 一片），间隙查
 *   [ScriptOp.cancelSignal] —— 置位即 `destroyForcibly` + 关会话（桥侧子进程随
 *   `session.close()` 被收），这是「停止」按钮按下去后进程树**真死**的物理机制。
 *   超时路径不变（同一条轮询里判墙钟）。
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

    override suspend fun execute(op: ScriptOp, sink: ProgressSink, output: OutputSink): String =
        withContext(Dispatchers.IO) {
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
                val outcome = runNpm(op, session, output)
                if (outcome.exitCode != 0) {
                    // 报过流就不重复尾部（批 94，与批 90「报了流就不再补 outputTail」同纪律）：
                    // 那些行已经在控制台里逐行出现过，异常里再贴一遍 = 同一段文本两处。
                    // **[OutputSink.None] 例外**：那是调用方显式说「这边没人看流」——
                    // 尾部就成了失败详情的唯一输出来源，必须兜底（判据在调用方的选型上，
                    // 不在"我们报没报"：对 None 报了也是空操作，拿它当"已经让人看见了"是撒谎）。
                    val detail = if (outcome.streamed && output !is OutputSink.None) {
                        "（输出见上方）"
                    } else {
                        outcome.tail.ifBlank { "（无输出）" }
                    }
                    throw AutojsException(
                        ErrorCode.ERR_IO,
                        "npm ${op.npmArgs.joinToString(" ")} 退出码 ${outcome.exitCode}：$detail",
                    )
                }
                "npm ${op.action.replace('_', ' ')} ${op.what} 完成（退出码 0）"
            } finally {
                // 会话关断 = 收掉本次起的全部子进程（TERM → KILL）。放在 finally：异常/超时
                // 取消两条路都必须走到 —— 漏掉的那次留下的是一棵还在跑的进程树。
                session.close()
            }
        }

    /** 一次 npm 会话的进程侧结果。 */
    private class Outcome(val exitCode: Int, val tail: String, val streamed: Boolean)

    /**
     * 起 npm 会话并等它退。
     *
     * **工作目录 = 项目根**（与 [HostNodeExecutor] 的 work-prefix 模型刻意不同）：
     * `npm run` 读的是**项目自己的** `package.json` 与 `node_modules`，而 `--prefix` 会
     * 把 npm 的项目级配置也一并挪走（`.npmrc` 只看 prefix）。脚本就该在项目里跑，
     * 所以这里既不给 `--prefix` 也不另建 workDir。
     *
     * 输出**边读边报 + 攒尾部**（批 94）：流喂控制台（尽力而为），尾部进失败详情
     * （报过流就不带，见 [execute]）。
     */
    private fun runNpm(op: ScriptOp, session: T1SessionHandle, output: OutputSink): Outcome {
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
        // 读线程写、主线程在 reader.join() 之后读 —— join 自带 happens-before，普通 var 即可。
        var streamed = false
        val reader = Thread(
            {
                try {
                    proc.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            // 空行跳过（批 90 同款理由）：真实 npm 输出以空行结尾，环里塞空行
                            // 是在用户屏幕上画一条空白。
                            if (line.isNotEmpty()) {
                                streamed = true
                                output.line(line)
                            }
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
        // 切片轮询（批 94）：一片内 `waitFor` 是真阻塞（不让空转烧 CPU），片间查取消与墙钟。
        // 原先的单发 `waitFor(timeout)` 看不到取消 —— 「停止」按下后 npm 会话照跑到底，
        // TTL 契约里"合作式响应取消"那句在这里落不了地。
        val deadlineNanos = System.nanoTime() + op.timeoutMillis * 1_000_000
        while (!proc.waitFor(CANCEL_POLL_SLICE_MILLIS, TimeUnit.MILLISECONDS)) {
            if (op.cancelSignal.isCancelled()) {
                proc.destroyForcibly()
                // 会话跟着收：npm 死了但桥那侧起的子进程还在跑 —— 取消要收的正是它们。
                session.close()
                reader.join(READER_JOIN_MILLIS)
                throw AutojsException(
                    ErrorCode.ERR_ENGINE_STOPPED,
                    "脚本执行已取消：${op.what}（npm 会话与它起的子进程一并回收）",
                )
            }
            if (System.nanoTime() >= deadlineNanos) {
                proc.destroyForcibly()
                // 会话也要跟着收：npm 死了但桥那侧起的子进程还在跑 —— 那是这次超时最该收干净的东西。
                session.close()
                reader.join(READER_JOIN_MILLIS)
                throw AutojsException(
                    ErrorCode.ERR_TIMEOUT,
                    "T1 脚本超时（${op.timeoutMillis}ms）：${op.what}（npm 会话与它起的子进程一并回收）",
                )
            }
        }
        reader.join(READER_JOIN_MILLIS)
        return Outcome(
            exitCode = proc.exitValue(),
            tail = synchronized(collected) { collected.toString() }.trim(),
            streamed = streamed,
        )
    }

    private companion object {
        /** 失败详情里保留的输出尾部（够看错误栈）。 */
        const val TAIL_LIMIT = 4_000

        /** 等读流线程收尾的上限（与 `HostNodeExecutor` 同值同理由）。 */
        const val READER_JOIN_MILLIS = 2_000L

        /**
         * 取消/墙钟的轮询切片。200ms：「停止」按下去到进程被杀的延迟人感知不到，
         * 而一秒钟只自旋五次。用真实值验"到点会杀"的用例不会被这个数拖慢
         * （超时用例注入的是小 TTL，不是等这个切片转满）。
         */
        const val CANCEL_POLL_SLICE_MILLIS = 200L
    }
}
