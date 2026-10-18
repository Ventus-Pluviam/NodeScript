package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.host.ShellConsoleResult
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

/**
 * 重操作的资源闸。**2026-10-17 起只剩磁盘这一道**：用户口径「node_modules 取消 512MB 限制」
 * 「取消上限设置吧」把项目 node_modules 的配额整个摘了（`projectQuotaBytes` /
 * `quotaWarnRatio` 两个字段随之下线），此前它是「80% 黄 · 100% 拦」的那条线。
 *
 * [minFreeBytes] 因此成了**唯一**还会拒安装的资源判据：可用空间低于它就是 `ERR_DISK_FULL`
 * （`InstallCoordinator.enqueueHeavy` 的预检）。`InstallEvent.Kind.DISK_QUOTA` 这个事件名
 * **留用、判据换锚** —— 它现在说的是「可用空间逼近这道预检下限」，见 `enqueueHeavy`。
 */
data class InstallConfig(
    val minFreeBytes: Long = 500L * 1024 * 1024,       // §10.2 磁盘 free≥500MB 预检
)

/**
 * 一次重操作的结果。
 *
 * [summary] 进操作事实（[OpLogSink] 的 `detail`）与 `Finished.detail`（一句话的人类可读结论）。
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
 * 谁都拿得到，进操作事实与终态行）；本缝是**跑的当中**逐行报，只喂控制台。
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
 * **操作事实的去向缝**（一次重操作/轻操作的最终结局 + 可读明细）。
 *
 * 2026-10-17 用户令：**去掉审计**（依赖管理页那颗「审计」进的那个子页，以及它背后那份
 * 只追加的落盘账 `files/.ns/install-history.jsonl`），这些记录改打**系统日志**。
 * 于是这一族的形状从「一本自己的文件」变成「一条去向」：
 *
 * - 写侧（[InstallCoordinator]）只交出**事实**（op / projectId / 成败 / 明细），
 *   不决定它去哪儿、什么格式、什么级别；
 * - 去向由装配层给（生产 = `:app` 的 `HostLog`：logcat + 日志管理页「系统日志」段
 *   runId=0 镜像；见 `AppShellKit.assemble` 的 `opLog` 参数）。
 *
 * **为什么必须是一条缝而不是直接调用**：`:app-service:npm` 是纯 JVM 模块
 * （连 `android.util.Log` 都碰不到），而系统日志那面住 `:app`。这与
 * [HeavyOpExecutor]/[ScriptOpExecutor]/[ShellOpExecutor] 是同一条分工。
 *
 * **实现方不许抛**：这是诊断面 —— 为它把一条本来能跑完的安装变成失败是本末倒置
 * （与 `ConsoleHistory`/`ScriptLogStore` 的 `runCatching` 同一条纪律；`HostLog`
 * 自己也是吞异常写法）。缺省 [None] = 未接线（老替身零改动，且不假装记过）。
 *
 * **为什么不带时间戳**：时间由去向侧盖（`HostLog` 自己取 `System.currentTimeMillis()`
 * 并与 logcat 同源）—— 交给写侧，同一条事实在两个目的地就会有两个时刻。
 */
fun interface OpLogSink {
    fun log(op: String, projectId: String, success: Boolean, detail: String?)

    /** 缺省：未接线 → 不记（也不抛）。 */
    object None : OpLogSink {
        override fun log(op: String, projectId: String, success: Boolean, detail: String?) {}
    }
}

/**
 * 操作名的已知取值（[OpLogSink.log] 的 `op` 段）。
 *
 * **取值域是开放的**：`opName(args)` 把任何 argv 首词直接当 op 记，本对象只是给
 * 「实际会出现的名字」一个稳定的比较锚（测试与去向侧读起来不必猜字面量）。
 *
 * 2026-10-17 随「去掉审计」自 `:domain` 的 `InstallHistoryOp` 整体搬来本模块：
 * 那时它是为了让 `:ui` 审计页分组而住契约层，页面没了，唯一的消费方就是本模块自己
 * （`OpLogSink` 的实参）。
 *
 * 搬来时**删掉了 `install_script`**：那条的唯一存在理由是「审计页要能读旧账」
 * （落盘文件里可能有这个名字）—— 账本与页面同时没了，留着它就成了没有来处的常量。
 */
object OpName {
    const val INSTALL = "install"
    const val CI = "ci"
    const val UNINSTALL = "uninstall"
    const val PRUNE = "prune"
    const val DEDUPE = "dedupe"
    /** §10.5-2 的 registry 变更（项目级与全局级都归它）。 */
    const val REGISTRY = "registry"
    const val IMPORT = "import"
    const val EXPORT = "export"

    /** 控制台轻操作（`opName` 直取 argv 首词的结果）。 */
    const val LS = "ls"
    const val AUDIT = "audit"

    /** `update`（facade 有实现，今天零生产调用方）。 */
    const val UPDATE = "update"

    /**
     * 缓存回收（`reclaimCache()` 的产物；§10.9 第 5 条那颗 cache clean 按钮）。
     *
     * **不叫 `cache_clean`**：这个动作不是 npm 的 `cache clean`（那个是整目录全清），
     * 名字跟着语义走 —— 两个名字长得一样的话，将来真接了全清就分不出来了。
     */
    const val CACHE_RECLAIM = "cache_reclaim"

    /** T1 脚本执行动作（[ScriptOp.action] 的取值）。 */
    const val RUN_SCRIPT = "run_script"
    const val EXEC = "exec"
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
    /**
     * 返回摘要（人类可读）。
     *
     * [output] 是**脚本会话自己的输出**（2026-10-11 批 94 补，真流式）：T1 会话进程
     * （`npm run` / `npx`）的 stdout/stderr 是脚本说话的地方，而它在跑完之前就该
     * 到控制台 —— 形状与语义逐字同 [HeavyOpExecutor.execute] 的那条缝（不重复注释，
     * 见那边：尽力而为、实现方不抛、与 `ProgressSink` 分开的理由）。
     */
    suspend fun execute(op: ScriptOp, sink: ProgressSink, output: OutputSink): String   // 返回摘要（人类可读）

    /** 默认：spawn 桥未接入 → 如实 ERR_NOT_IMPLEMENTED（门禁已过也不假装跑过）。 */
    object Unavailable : ScriptOpExecutor {
        override suspend fun execute(op: ScriptOp, sink: ProgressSink, output: OutputSink): String {
            throw AutojsException(
                ErrorCode.ERR_NOT_IMPLEMENTED,
                "T1 spawn 桥未接入：${op.what}（${op.action}）未执行。" +
                    "解析与门禁已就位，缺的是 child_process shim → 临时引擎这一段（§10.3 T1）",
            )
        }
    }
}

/**
 * 一次 lifecycle 执行的上下文。
 *
 * [what] 是脚本名或 bin 名（操作事实与错误信息的抓手）；[npmArgs] 是 npm CLI 口径参数；
 * [versionHash] 带上是为了让执行体可复述「我跑的是哪一份」——操作事实只记摘要不够，
 * 用户问「我跑的那份脚本现在还在不在」时要有据可查（脚本一改哈希就变）。
 */
data class ScriptOp(
    val handleId: String,
    val projectId: String,
    /** 操作事实里的 action 段（[OpName] 的常量）。 */
    val action: String,
    /** 归属包名（run 侧是项目自身包名，exec 侧是提供该 bin 的包名）。 */
    val pkg: String,
    /** 脚本名或 bin 名。 */
    val what: String,
    val args: List<String>,
    val projectRoot: java.nio.file.Path,
    val npmArgs: List<String>,
    val versionHash: String,
    val timeoutMillis: Long,
    /**
     * **用户取消的观测缝**（2026-10-11 批 94）：协调器的句柄账执行体看不见 —— 但
     * 「停止」按钮按下去之后**进程树必须真死**，靠的是执行体在 `waitFor` 的切片间隙
     * 查到这一位（TTL 契约「合作式响应取消」在 T1 这条路上的物理载体）。
     * 缺省恒 false：测试替身与无句柄的调用零改动。
     */
    val cancelSignal: OpCancelSignal = OpCancelSignal.NONE,
) {
    /** 主体段（`"<pkg>|<what>"`）—— 操作事实与错误话术同款，不再各拼各的。 */
    val subject: String get() = "$pkg|$what"
}

/**
 * 取消观测缝（2026-10-11 批 94）。
 *
 * 为什么是**读位**而不是回调：调用方（协调器）持有的是 `TrackedOp.cancelled` 那个
 * `@Volatile` 位，执行体要的只是「现在该不该停」—— 一个布尔探针把两边的并发面都压到
 * 最小（回调要在句柄收走后防悬挂；读位没有这个问题：位还在，值只是不再变）。
 */
fun interface OpCancelSignal {
    fun isCancelled(): Boolean

    companion object {
        /** 缺省：永不取消（测试替身 / 无句柄上下文）。 */
        val NONE = OpCancelSignal { false }
    }
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
