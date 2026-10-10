package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.host.ShellConsoleResult
import com.autoscript.domain.npm.ApprovalAction
import com.autoscript.domain.npm.ShellConsoleMode
import com.autoscript.domain.npm.InstallEvent
import java.nio.file.Path

/**
 * [InstallCoordinator] 的对外接缝与上下文 DTO（2026-10-01 D7 自 `InstallCoordinator.kt`
 * 原样外迁，语义逐字未改）。
 *
 * 为什么单独一件：这些类型是**装配层与执行体**（`:app` 的 `AppShellKit`、同包的
 * `HostNodeExecutor`、测试替身）唯一该看见的面 —— 摆在近千行的协调器里当嵌套类，
 * 读的人得先翻过整个类才知道「接缝长什么样」。原有名字一律不加前缀：它们本来就是
 * 这个包的公共词汇（`HeavyOpExecutor`/`ProgressSink`/`ScriptOpExecutor`/`HeavyOp`/
 * `ScriptOp`），外迁只是把嵌套去掉、不发明新名。
 *
 * 唯一改名的是 `Config`：出到包级后叫 `Config` 太泛，改叫 [InstallConfig]，
 * 字段与语义一个没动。
 */

data class InstallConfig(
    val minFreeBytes: Long = 500L * 1024 * 1024,       // §10.2 磁盘 free≥500MB 预检
    val projectQuotaBytes: Long = 512L * 1024 * 1024,  // 项目 node_modules 配额（100% 拦）
    val quotaWarnRatio: Double = 0.8,                  // 80% 黄
)

/**
 * 一次重操作的结果。
 *
 * [summary] 进审计与 `Finished.detail`（一句话的人类可读结论）。
 *
 * [outputTail] 是**命令自己的输出尾部**（2026-10-09 批 84 新增）：控制台要把
 * 「npm 到底说了什么」显示出来，而 `Finished.detail` 只放得下一句摘要。
 * null = 该执行体给不出（[HeavyOpExecutor.Unavailable]、测试替身）——
 * **不拿摘要冒充输出**：控制台会把它渲染成「本次没有捕获到命令输出」，
 * 而不是把摘要原样贴第二遍。
 *
 * 为什么是**尾部**而不是全量：npm 装一个大依赖能刷出几万行，而用户真正要看的是
 * 最后那段（错误栈、警告、`added N packages in Xs`）。有界截断是契约的一部分，
 * 全量 stdout 是另一条面（真流式，本批明确不做）。
 */
data class HeavyOpOutcome(
    val summary: String,
    val outputTail: String? = null,
)

fun interface HeavyOpExecutor {
    /**
     * 在已分配的事务上下文里执行重操作；args 为 npm CLI 参数（install/ci/…）。
     * 实现方负责进度事件（经 [ProgressSink]）。
     *
     * [output] 是**命令自己的输出**（2026-10-10 批 90 新增，真流式）：实现方**边读边报**，
     * 宿主把它逐行落进控制台环 —— 用户看到的是 npm 正在说什么，而不是跑完之后的一大坨。
     * 不报就传 [OutputSink.None]（只回 [HeavyOpOutcome.outputTail] 的老路径）。
     * 两条路并存是刻意的：`outputTail` 是**摘要面**（谁都要得到），流是**过程面**
     * （拿不到也不该让安装失败）。
     *
     * **没有缺省值**：`fun interface` 的抽象方法不许带缺省值（Kotlin 明确禁止，
     * 编译期就拦）—— 而这正好逼每个实现方显式表态「我报不报流」。
     *
     * **TTL 契约（铁律 3）**：协调器已对本次调用套 [op.timeoutMillis]（withTimeoutOrNull），
     * 超时即取消并回 err 路径收尾（journal fail + 残骸清扫 + 锁释放）。故实现方必须
     * 合作式响应取消（阻塞 IO 拆成可中断段、子进程随取消销毁）——不响应取消的执行体
     * 会在超时后变成孤儿：项目锁虽已释放，但它仍可能与新会话争抢同一 stageDir。
     */
    suspend fun execute(op: HeavyOp, sink: ProgressSink, output: OutputSink): HeavyOpOutcome

    /** 默认：无引擎可用 → 如实 ERR_NOT_IMPLEMENTED。 */
    object Unavailable : HeavyOpExecutor {
        override suspend fun execute(
            op: HeavyOp,
            sink: ProgressSink,
            output: OutputSink,
        ): HeavyOpOutcome {
            throw AutojsException(
                ErrorCode.ERR_NOT_IMPLEMENTED,
                "安装会话引擎未接入：重操作 ${op.args.joinToString(" ")} 未执行（编排已完成：journal=${op.nonce}）",
            )
        }
    }
}

/** 一次重操作的完整上下文（编排层 → 执行体）。 */
data class HeavyOp(
    val nonce: String,
    val projectId: String,
    val args: List<String>,
    val projectRoot: java.nio.file.Path,
    val stageDir: java.nio.file.Path,
    val timeoutMillis: Long,
)

/** 进度事件回传缝（执行体 → 协调器事件流）。 */
fun interface ProgressSink {
    suspend fun emit(event: InstallEvent)
}

/**
 * 命令**输出**回传缝（执行体 → 控制台环，2026-10-10 批 90）。
 *
 * 与 [ProgressSink] 分开而不是合并：那条装的是 [InstallEvent]（**脚本侧的契约形状**，
 * `bridge/js` 的 `onProgress` 逐字对齐），这条装的是 npm 自己吐的原文 ——
 * 把后者塞进事件契约等于为一个宿主界面去改脚本侧的面。
 *
 * 与 [HeavyOpOutcome.outputTail] 的分工：`outputTail` 是**跑完之后**的尾部（有界、
 * 谁都拿得到，进审计与终态行）；本缝是**跑的当中**逐行报，只喂控制台。
 * 两条并存的理由很实际 —— 流是**尽力而为**的（实现方可以在没有订阅者时不报），
 * 而 `outputTail` 是结果的一部分，缺了控制台就没东西可显示。
 *
 * **实现方不必为它失败**：调用方给的是不抛的实现（推环而已）。真抛了也**不该**
 * 让安装失败 —— 那是宿主自己的显示面。
 */
fun interface OutputSink {
    /**
     * 报一行（**非挂起**：这是宿主自己的显示面，读流的那条线程不该为一个 push 让出）。
     * 实现方必须**不抛** —— 抛了会打断读流循环，而"界面少显示一行"绝不该让安装失败。
     */
    fun line(text: String)

    /** 缺省：不报（老执行体与只回尾部的替身零改动）。 */
    object None : OutputSink {
        override fun line(text: String) {}
    }
}

/**
 * T1 lifecycle 脚本执行体接缝（§10.3 T1「spawn 桥 → `:main` 沿 EnginePool 同路径拉临时
 * 引擎执行」的执行侧契约）。
 *
 * 为什么不复用 [HeavyOpExecutor]：那条通道编排的是「事务」——stageDir + journal +
 * 原子落位，为的是 npm CLI 会重写 `node_modules`。lifecycle 脚本不做 reify，
 * 走那条链会为一个不改依赖树的操作凭空造暂存目录与 commit 记录，
 * `unfinished()` 里多出没有产物的残骸。两条通道共用 TTL/取消/事件面，差异只在落位。
 *
 * [ScriptOp.npmArgs] 交出的是 npm CLI 口径的参数（`run <name> -- <args>` /
 * `exec <args> -- <bin>`）—— 真执行体把它交给 vendored npm 或直接按 shim 拦截后的
 * 语义展开，两条路都在宿主侧，故此处不替实现方决定。
 *
 * **TTL 契约**同 [HeavyOpExecutor]：协调器已套 [ScriptOp.timeoutMillis] 的
 * withTimeoutOrNull，实现方必须合作式响应取消（子进程随取消销毁，见 §10.3 T1 的
 * TERM→超时→SIGKILL 回收顺序），否则超时后会成为争抢同一项目锁的孤儿。
 */
fun interface ScriptOpExecutor {
    suspend fun execute(op: ScriptOp, sink: ProgressSink): String   // 返回摘要（人类可读）

    /** 默认：spawn 桥未接入 → 如实 ERR_NOT_IMPLEMENTED（门禁已过也不假装跑过）。 */
    object Unavailable : ScriptOpExecutor {
        override suspend fun execute(op: ScriptOp, sink: ProgressSink): String {
            throw AutojsException(
                ErrorCode.ERR_NOT_IMPLEMENTED,
                "T1 spawn 桥未接入：已获批的 ${op.what}（${op.action.name}）未执行。" +
                    "放行门禁与审批账本已就位，缺的是 child_process shim → 临时引擎这一段（§10.3 T1）",
            )
        }
    }
}

/**
 * 一次已放行 lifecycle 执行的上下文。
 *
 * [what] 是脚本名或 bin 名（审计与错误信息的抓手）；[npmArgs] 是 npm CLI 口径参数；
 * [versionHash] 带上是为了让执行体可复述「我跑的是哪一份」——审计条目只记摘要不够，
 * 用户问「我批的那份脚本现在还在不在」时要有据可查。
 */
data class ScriptOp(
    val handleId: String,
    val projectId: String,
    val action: ApprovalAction,
    /** 审批主体 = 归属包名（run 侧是项目自身包名，exec 侧是提供该 bin 的包名）。 */
    val pkg: String,
    /** 脚本名或 bin 名。 */
    val what: String,
    val args: List<String>,
    val projectRoot: java.nio.file.Path,
    val npmArgs: List<String>,
    val versionHash: String,
    val timeoutMillis: Long,
) {
    /** 审批键的主体段（`"<pkg>|<what>"`）—— 账本键与审计条目同款，不再各拼各的。 */
    val subject: String get() = "$pkg|$what"
}

/**
 * 控制台 shell 面的执行缝（2026-10-09）。
 *
 * **为什么是缝而不是直接调 `:platform:system`**：`:app-service:npm` 的 `ArchitectureTest`
 * 禁 `com.autoscript.platform..`（依赖方向铁律），控制台命令面住在本模块，编译期看不到
 * `AndroidShellExecutor` / `ShizukuInput`。缝住 `:domain`，真实现由 `:app` 的装配层
 * （`PlatformWiring`，唯一同时看得见两个平台模块的地方）注入 —— 与
 * [HeavyOpExecutor]/[ScriptOpExecutor] 同一条分工。
 *
 * **TTL 契约**同 [HeavyOpExecutor]：调用方已套 withTimeoutOrNull，实现方必须合作式
 * 响应取消（阻塞 IO 拆成可中断段、子进程随取消销毁）。
 */
fun interface ShellOpExecutor {
    /** 跑一条 shell 命令；[ShellConsoleMode.DEFAULT] 一律拒（需要 root 或 Shizuku）。 */
    suspend fun execute(command: String, mode: ShellConsoleMode, timeoutMillis: Long): ShellConsoleResult

    /** 缺省：未接线 → 如实 ERR_NOT_IMPLEMENTED（不假装跑过）。 */
    object Unavailable : ShellOpExecutor {
        override suspend fun execute(
            command: String,
            mode: ShellConsoleMode,
            timeoutMillis: Long,
        ): ShellConsoleResult = throw AutojsException(
            ErrorCode.ERR_NOT_IMPLEMENTED,
            "控制台 shell 面未接线：本宿主没有接上 shell 执行入口（命令未执行：$command）",
        )
    }
}
