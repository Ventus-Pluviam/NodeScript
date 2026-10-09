package com.autoscript.platform.system.shell

import com.autoscript.domain.automation.GestureInput
import com.autoscript.domain.automation.InputChannel
import com.autoscript.domain.automation.InputProvider
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/**
 * 经 shell 的坐标输入（§9.3 的 `root` 与 `adb` 两条通道）。
 *
 * **为什么住 `:platform:system`**：它不碰 `android.*`，只调 [ShellExecutor] 的
 * `exec(cmd, mode, timeout)` —— 那条 SPI 与 [ShellMode] 就住本模块。`:platform:system`
 * 的 archUnit 黑名单不含 `com.autoscript.platform.capabilities..`，且 `:platform:*`
 * 互相依赖不违反「`:platform:*` → `:domain`」那条铁律。装配层（`:app` 的 `PlatformWiring`）
 * 把实例塞进 `CapabilityNamespaces.a11y` 的 `channels` 表。
 *
 * **通道 → shell 模式是 1:1 的**（`ADB` → `ShellMode.ADB`、`ROOT` → `ShellMode.ROOT`）：
 * [ShellMode.ADB] 的契约是「设备侧已在 adb shell 内」（见 `AndroidShellExecutor.argvFor`），
 * 所以本类不自己调 `adb` 客户端，只把命令交给那条通道。**ADB 通道真身是 Shizuku**
 * （用户装 Shizuku 并用 adb 启动它的服务）—— 没有 Shizuku 时本通道的 provider 根本不接线，
 * 调用方拿到的是 handler 的 `ERR_PERMISSION_DENIED`，而不是「命令跑不起来」。
 *
 * **`input` 命令为什么够**：`input tap x y` / `input swipe x1 y1 x2 y2 [ms]` 是
 * `cmd input` 的稳定面（AOSP `InputShellCommand`），换一个特权身份（shell uid 或 root）即可注入 ——
 * 这正是「adb/root 通道」与无障碍通道的**语义差异**：注入者的进程身份不同。
 *
 * **本类未经真机验证**：设备道 2026-10-06 已裁（backlog B3/E3）。JVM 侧用假
 * [ShellExecutor] 钉住「命令拼装 + 通道映射 + 退出码判定」，真机上的第一次使用
 * 应当与 auto 通道对一遍行为。
 */
class ShellInputProvider(
    /** 本 provider 走哪条通道（只用于**如实报错**：错误消息里要说清是哪一条挂了）。 */
    private val channel: InputChannel,
    /**
     * 命令执行缝：**为什么不是直接收 [ShellExecutor]** —— 两条通道的**执行机制不同**。
     * ROOT 是 `su -c`（[ShellExecutor.exec] 的 `ShellMode.ROOT`，本仓已有）；
     * ADB 经 Shizuku 时是 `Shizuku.newProcess(...)` 拿到一个远端 shell **进程**，
     * 不是本进程 fork 出来的 `sh` —— 应用自己 fork 的 `sh` 身份仍是应用 uid，
     * 根本注不进事件。把执行机制做成缝，本类就只剩「命令怎么拼 + 退出码怎么判」，
     * 两套机制各自可测（假执行器注入）。
     */
    private val run: suspend (command: String) -> ShellResult,
) : InputProvider {

    init {
        require(channel == InputChannel.ADB || channel == InputChannel.ROOT) {
            "ShellInputProvider 只服务 adb/root 两条通道（auto 有无障碍原生实现），实际 $channel"
        }
    }

    /**
     * **恒 true** —— 这是本类最容易写错的一处。
     *
     * 无障碍通道的 `canPerformGestures` 问的是「服务能力位开没开」（那是系统的一个开关）。
     * shell 通道**没有对应的开关**：`input` 能不能用取决于**进程身份**，而那正是本
     * provider 被接线的前提（ROOT 通道接线 = `su` 探测已过；ADB 通道接线 = Shizuku 已授权）。
     * 所以到得了这里的调用，能力位就是「已授予」。
     *
     * 真正的失败（`su` 突然没了、Shizuku 服务死了）会以**命令退出码非 0** 的形式出现在
     * [dispatchGesture]/[tap] 里 —— 那如实回 false 或抛，不在这里假装。
     */
    override val canPerformGestures: Boolean = true

    override suspend fun dispatchGesture(gesture: GestureInput): Boolean {
        // 多笔画手势在 shell 面只能逐笔画串行注入（`input` 一次一条命令，没有多指原语）。
        // 顺序与 startDelay 由调用方给的点序表达；这里不重排、不合并。
        for (stroke in gesture.strokes) {
            if (!inject(strokeCommand(stroke.points, stroke.durationMillis))) return false
        }
        return true
    }

    /**
     * 坐标点击 → `input swipe x y x y <ms>`（**同点滑**是 shell 面表达「按住时长」的
     * 唯一写法；`input tap` 没有时长参数）。`durationMillis = 0`（普通点击）用 1ms。
     */
    override suspend fun tap(x: Int, y: Int, durationMillis: Long): Boolean =
        inject("input swipe $x $y $x $y ${durationMillis.coerceAtLeast(1)}")

    /**
     * 一条笔画 → 一条命令：首尾两点 → `input swipe`；单点 → `input tap`。
     *
     * **中间点被丢掉**，这是 shell 面的**真实上限**，不是偷懒：`input` 只有
     * tap/swipe 两个原语，没有轨迹。要真轨迹得走 `sendevent`（§9.3 提过），那是另一件
     * 事（要按设备的事件节点写，未落地）。所以经 adb/root 的「手势」实际是**直线**——
     * 这一点必须让调用方知道，故写在类 KDoc 与 §9.3 契约里。
     */
    private fun strokeCommand(
        points: List<com.autoscript.domain.automation.GesturePoint>,
        durationMillis: Long,
    ): String {
        val first = points.first()
        val last = points.last()
        val ms = durationMillis.coerceAtLeast(1)
        return if (first == last) "input tap ${first.x} ${first.y}"
        else "input swipe ${first.x} ${first.y} ${last.x} ${last.y} $ms"
    }

    private suspend fun inject(command: String): Boolean {
        // 执行缝抛异常 = 通道本身不可用（`su` 不在、Shizuku 服务死了）—— 原码上抛，
        // 由 handler 回桥。**绝不折成 false**：false 的语义是「系统拒绝执行这次注入」，
        // 与「这条通道现在没了」是两回事，后者调用方该去能力中心而不是重试。
        val result = run(command)
        if (!result.isSuccess) {
            throw AutojsException(
                ErrorCode.ERR_SERVICE_DISABLED,
                "坐标注入失败（通道 ${channel.name.lowercase()}，exit=${result.code}）：" +
                    "${result.stderr ?: "无 stderr"}",
                null,
            )
        }
        return true
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L

        /** 通道 → shell 模式（1:1，见类 KDoc）；`auto` 不走 shell。 */
        fun shellModeOf(channel: InputChannel): ShellMode = when (channel) {
            InputChannel.ADB -> ShellMode.ADB
            InputChannel.ROOT -> ShellMode.ROOT
            InputChannel.AUTO -> throw IllegalArgumentException("auto 通道不走 shell")
        }

        /**
         * ROOT 通道的执行缝：本仓既有的 `su -c`（[ShellExecutor] 的 [ShellMode.ROOT]）。
         * 生产装配一行就能造（`ShellInputProvider.root(executor)`）。
         */
        fun root(executor: ShellExecutor, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): ShellInputProvider =
            ShellInputProvider(InputChannel.ROOT) { cmd ->
                executor.exec(cmd, ShellMode.ROOT, timeoutMillis)
            }

        /**
         * ADB 通道的执行缝：**由调用方给**（生产是 Shizuku 的远端进程，见类 KDoc）。
         * 本模块不引 Shizuku 依赖 —— 那条依赖住实现侧，本类只认「跑一条命令、回结果」。
         */
        fun adb(run: suspend (String) -> ShellResult): ShellInputProvider =
            ShellInputProvider(InputChannel.ADB, run)
    }
}
