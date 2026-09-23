package com.autoscript.engine.nodeprocess

import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * spawn 缝（对齐 `:app-service:packager` 的 `ProcessLauncher` 惯例，签名按引擎语义重画）：
 * [NodeProcessEngine] 与真实 OS 进程之间唯一的接触面 —— JVM 单测注入假实现即可断言
 * argv/env/cwd 与 stop/kill 语义，不必真起进程；真起进程的集成测试再换 [ProcessBuilderLauncher]。
 */
fun interface ProcessLauncher {
    /**
     * 拉起子进程。[env] 是**增量覆盖**（实现负责与父环境合并后写入子进程）；
     * [workingDir] 是子进程 cwd（实现不得忽略 —— 脚本的相对路径解析依赖它）。
     * 启动失败抛 [IOException]（调用方原样上抛进池的 Failed 路径，消息即真原因）。
     */
    fun spawn(command: List<String>, env: Map<String, String>, workingDir: Path): SpawnedProcess
}

/** 已拉起的子进程最小视图：pid/存活/退出码/两级终止 —— 语义面（何时用哪一级）在 [NodeProcessEngine]。 */
interface SpawnedProcess {
    /**
     * 子进程 pid；宿主运行时不可得（旧 API 无 `Process.pid()`）为 null ——
     * 与 `ScriptEngine.pid` 同一诚实口径（§8.4：绝不给 0/自身 pid），看门狗据此走 noPid 清单。
     */
    val pid: Int?

    /** 仍在运行（未退出、未被收尸）。 */
    val isAlive: Boolean

    /** 退出码；仍存活为 null（对齐 JVM `exitValue()` 语义，调用方先查 [isAlive]）。 */
    fun exitValue(): Int?

    /** 礼貌终止（SIGTERM）—— 四步 quiesce 的「请求退出」。 */
    fun destroy()

    /** 强制终止（SIGKILL）—— 仅 kill 权威（§4.1）经 quiesce 超时兜底触达。 */
    fun destroyForcibly()

    /** 等待退出，[timeoutMillis] 内退出回 true，超时回 false（不抛）。 */
    fun waitFor(timeoutMillis: Long): Boolean
}

/**
 * 真起进程（`ProcessBuilder`）。stdout/stderr 合流进 PIPE 并由守护排水线程读到 EOF ——
 * 不排水会把管道写满、把子进程卡死在 write 上（经典 pipe 反压死锁），丢弃内容不丢进程。
 *
 * `Process.pid()` 是 Java 9 / 旧 Android 运行时可能缺失的方法：编译期 compileSdk 35 可见，
 * 运行期缺方法抛 [NoSuchMethodError] → 如实回 null（§8.4 noPid 路径，不是崩溃）。
 */
class ProcessBuilderLauncher : ProcessLauncher {

    override fun spawn(command: List<String>, env: Map<String, String>, workingDir: Path): SpawnedProcess {
        val process = ProcessBuilder(command)
            .directory(workingDir.toFile())
            .apply {
                environment().putAll(env)
                redirectErrorStream(true)   // 合流成一条，一条排水线程即可防写满
            }
            .start()
        val drain = Thread {
            try {
                process.inputStream.use { input: InputStream ->
                    val buf = ByteArray(8192)
                    while (input.read(buf) != -1) { /* 读即弃：诊断面尚未接 SPI，先保不卡死 */ }
                }
            } catch (_: Exception) {
                // 进程退出后管道关闭引发的读异常：排水线程的正常终点
            }
        }.apply {
            isDaemon = true
            name = "node-engine-drain-${process.hashCode()}"
            start()
        }
        return JdkSpawnedProcess(process, drain)
    }

    private class JdkSpawnedProcess(
        private val process: Process,
        @Suppress("unused") private val drain: Thread,   // 持引用防 GC 提前回收排水线程句柄
    ) : SpawnedProcess {
        override val pid: Int? = try {
            process.pid().toInt()
        } catch (_: NoSuchMethodError) {
            null                      // 旧运行时无 Process.pid()：诚实 noPid（§8.4），不是 0/自身
        } catch (_: SecurityException) {
            null
        }

        override val isAlive: Boolean get() = process.isAlive

        override fun exitValue(): Int? = if (process.isAlive) null else process.exitValue()

        override fun destroy() = process.destroy()

        override fun destroyForcibly() {
            process.destroyForcibly()
        }

        override fun waitFor(timeoutMillis: Long): Boolean =
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    }
}
