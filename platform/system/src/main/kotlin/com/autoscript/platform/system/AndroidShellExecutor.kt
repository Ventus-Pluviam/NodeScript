package com.autoscript.platform.system

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.ShellExecutor
import com.autoscript.domain.system.ShellMode
import com.autoscript.domain.system.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * `shell` 命名空间的 Android 实现（docs §9.6；SPI 见 `:domain` 的 [ShellExecutor]，
 * 语义层 handler 住 `:platform:capabilities` 的 `ShellNamespaceHandler`）。
 *
 * 三条契约在这里兑现，每条都有对应单测：
 *
 * 1. **超时是实现者义务**（铁律 3）：[ShellExecutor] 的 KDoc 明说「调用方只传建议值，
 *    实现不得无限等待」。所以超时到点就 `destroyForcibly()` 并抛
 *    [ErrorCode.ERR_TIMEOUT] —— 不是"返回已读到的半截输出"，更不是继续挂着。
 * 2. **任何退出路径都要收尸**：成功、超时、被上层取消（桥 TTL 到点会取消 handler 协程）
 *    三条路都走 `finally` 里的 `destroyForcibly()`。少这一条，脚本被杀之后
 *    `su`/`sh` 会变成孤儿进程留在设备上，而这在真机上只表现为"越跑越卡"，查不到源头。
 * 3. **双流必须并发读干**：管道缓冲区（Linux 默认 64KB）写满即阻塞子进程，
 *    先读干 stdout 再读 stderr 会在输出超过一屏时死锁 —— 所以两个 `async` 在
 *    `waitFor` **之前**就起来了。
 *
 * 参数用 argv 数组而非拼串（`su -c "$command"` 那种）：命令里的引号/空格/`$` 一旦
 * 参与拼接就要靠转义猜，argv 形态让内核直接收参数，不经过第二层 shell 解析。
 *
 * **本类不做能力门禁**：ROOT 模式能不能用由装配层的 `PermissionFacade` 先判
 * （§9.5），本类只负责"已经决定要执行之后"的执行与收尸。`su` 不存在时
 * [launch] 抛 [IOException]，这里如实折成 [ErrorCode.ERR_SERVICE_DISABLED]。
 */
class AndroidShellExecutor(
    private val launcher: ProcessLauncher = ProcessLauncher { Runtime.getRuntime().exec(it) },
) : ShellExecutor {

    /** 进程启动缝：真机走 [Runtime.exec]；单测注入以记录 argv / 供可控进程。 */
    fun interface ProcessLauncher {
        fun launch(argv: Array<String>): Process
    }

    override suspend fun exec(
        command: String,
        mode: ShellMode,
        timeoutMillis: Long,
    ): ShellResult {
        require(timeoutMillis > 0) { "shell 超时必须是正数（铁律 3：不允许无限等待），实际 $timeoutMillis" }
        val argv = argvFor(command, mode)
        return withContext(Dispatchers.IO) {
            val process = try {
                launcher.launch(argv)
            } catch (e: IOException) {
                throw AutojsException(
                    ErrorCode.ERR_SERVICE_DISABLED,
                    "shell 通道不可用（mode=$mode）：${e.message}",
                    e,
                )
            }
            try {
                coroutineScope {
                    val out = async { process.inputStream.use { it.readBytes() } }
                    val err = async { process.errorStream.use { it.readBytes() } }
                    // runInterruptible：桥 TTL 到点取消本协程时，阻塞中的 waitFor 才收得到中断
                    //（否则取消只能等进程自己结束，"超时"就成了一句空话）。
                    val finished = runInterruptible {
                        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                    }
                    if (!finished) {
                        throw AutojsException(
                            ErrorCode.ERR_TIMEOUT,
                            "shell 超时 ${timeoutMillis}ms（进程已强杀）：$command",
                        )
                    }
                    ShellResult(
                        code = process.exitValue(),
                        stdout = out.await().toTextOrNull(),
                        stderr = err.await().toTextOrNull(),
                    )
                }
            } finally {
                // 收尸兜底：超时/取消/异常路径都不留孤儿进程（见 KDoc 第 2 条）。
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private companion object {
        /**
         * 模式 → argv（§9.6 三通道）：
         * - [ShellMode.DEFAULT] / [ShellMode.ADB]：`sh -c <cmd>`（adb 通道下设备已身处 adb shell 内，
         *   不需要再套一层 adb 客户端）；
         * - [ShellMode.ROOT]：`su -c <cmd>` —— 命令作为**单个** argv 元素交给 su，由它转交自己的 sh。
         */
        fun argvFor(command: String, mode: ShellMode): Array<String> = when (mode) {
            ShellMode.DEFAULT, ShellMode.ADB -> arrayOf("sh", "-c", command)
            ShellMode.ROOT -> arrayOf("su", "-c", command)
        }

        /**
         * 字节 → 文本：**零字节即 null**（"该流没产出"，与 `ShellResult` 的可空语义对齐），
         * 非空则原样解码、**不裁剪**（尾换行是有信息的字节，裁掉是有损变换；
         * 要 `trim()` 是调用方的事）。非法 UTF-8 用替换字符而非抛异常 ——
         * shell 输出混二进制是常态，为它丢掉整条命令的结果不划算。
         */
        fun ByteArray.toTextOrNull(): String? =
            if (isEmpty()) null else toString(Charsets.UTF_8)
    }
}
