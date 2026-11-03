package com.autoscript.platform.system.shell

import java.util.logging.Logger

/**
 * shell 捕获输出上限（§9.6）。**拍板：静默截断 + 显式日志 Warning + 返回截断标志**
 *（口径见 `docs/design-decisions.md` 第 21 项）。
 *
 * **病灶**（backlog A2b）：`AndroidShellExecutor.PipeReader`
 * 把 stdout/stderr 全量读进内存，`cat` 一个大文件就能把宿主撑爆。加 cap 是**契约口径**
 * 变更 —— 「超限报错」与「截断」两条路都合法、代价不同：前者让合法的大输出直接失败，
 * 后者有损但可用。拍板取后者。
 *
 * **「静默」指的是调用方不因此失败**（`code`/`isSuccess` 照旧，不抛 `ERR_*`），
 * 不是「瞒着」。截断一旦发生，三处同时留痕，缺一处就是悄悄丢字节：
 * 1. [ShellResult.truncated] 标志 —— **程序读的那个**（脚本据此判断要不要改用分页/落盘）；
 * 2. 本类 [LogSink] 的 Warning —— **运维读的那个**（logcat）；
 * 3. 被截断那条流的末尾追加 [TRUNCATION_MARK] —— **人读的那个**（否则半截输出
 *    看起来就是一条正常结束的输出）。
 *
 * **为什么是 1 MiB**（不是随手取的整数字）：
 * 1. 正常 shell 命令的输出量级是 KB 级（`pm list packages` 几百行 ≈ 数十 KB），
 *    1 MiB 对合法用途是"够不着"的余量，截断只会发生在 `cat` 大文件这类误用上；
 * 2. 1 MiB 的普通文本经 JSON 编码后仍 ≈1 MiB，远在单帧上限 8 MiB（§7.5）之下；
 * 3. **最坏情况也要算**：`DomainJson.appendQuoted` 把 `c.code < 0x20` 转义成 `\uXXXX`，
 *    即控制字符（如 `yes | head -c 1M` 全是 `\n`）有 6 倍膨胀 —— 1 MiB × 6 ≈ 6 MiB，
 *    仍不触顶。**这条余量是必须的**：单帧超限在桥上是 `FrameTooLargeException` →
 *    读循环 `break` → **关连接**（`NewlineFrameServer`），比截断重得多，
 *    所以上限必须在实现侧先兜住，不能让超限的载荷走到编码那一步。
 *    [MAX_CAPTURE_BYTES] × [JSON_WORST_CASE_EXPANSION] 对单帧上限的守卫在
 *    `ShellCaptureLimitTest` 里钉住（改了任一个数就得重新算这笔账）。
 *
 * **两条流各自独立计数**：stdout 截了不影响 stderr 的额度。否则一条啰嗦的 stdout
 * 会把错误信息挤掉，而错误信息恰恰是短、且最该留住的那部分。
 */
object ShellCaptureLimit {

    /** 单条流（stdout 或 stderr）的捕获上限。 */
    const val MAX_CAPTURE_BYTES: Int = 1 * 1024 * 1024

    /**
     * JSON 编码的最坏膨胀系数：`\uXXXX` 转义 = 6 字节出 1 字节（见 [DomainJson] 的
     * `appendQuoted`，`c.code < 0x20` 分支）。取 6 不是保守估计，是精确上界。
     */
    const val JSON_WORST_CASE_EXPANSION: Int = 6

    /** 单帧上限（§7.5，`NewlineFrameServer.DEFAULT_MAX_FRAME_BYTES`）—— 只用于对账，不复制那份契约。 */
    const val FRAME_BUDGET_BYTES: Int = 8 * 1024 * 1024

    /** 上限的人类可读形态（日志与截断标记共用，别在别处手抄这两个数）。 */
    val MAX_CAPTURE_LABEL: String = "${MAX_CAPTURE_BYTES / (1024 * 1024)} MiB（$MAX_CAPTURE_BYTES 字节）"

    /** 截断时追加到该流末尾的说明行（人读的；程序判定一律用 [ShellResult.truncated]）。 */
    val TRUNCATION_MARK: String =
        "\n[autoscript] 输出超过 $MAX_CAPTURE_LABEL，已截断（后续字节被丢弃）"

    /**
     * 日志缝：**缺省走 `java.util.logging`，刻意不碰 `android.util.Log`**。
     *
     * 两个理由都不是洁癖：① `:platform:system` 的 JVM 单测**没有**
     * `isReturnDefaultValues = true`（见 `build-logic` 的 android-library 约定插件），
     * 单测里调 `android.util.Log` 会当场抛 "not mocked" —— 一条"输出超限"的告警
     * 不该把测试判红；② JUL 的缺省 `ConsoleHandler` 写 `System.err`，而 Android 把
     * `System.err` 重定向进 logcat（tag `System.err`）—— 真机上照样落 logcat，
     * 只是 tag 不叫包名。**不假装它和 `android.util.Log` 等价**：要精确的 tag/优先级
     * 就得注入自己的 [LogSink]，这也是把它做成缝的原因之一。
     *
     * 做成函数类型而非对象：单测注入一个记录器就能断言"Warning 真的发了"，
     * 不必去 mock 静态方法。
     */
    fun interface LogSink {
        fun warn(message: String)
    }

    private val JUL: Logger = Logger.getLogger("AutoScript.shell")

    /** 生产缺省：JUL →（Android 上）logcat。 */
    val defaultLogSink: LogSink = LogSink { JUL.warning(it) }
}
