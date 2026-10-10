package com.autoscript.appservice.npm

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * T1 会话里**一个被批准的子进程**（[NpmT1Bridge] 的执行侧）。
 *
 * 职责只有三件：起进程、把两条输出流泵回 socket（base64 的 `out` 帧）、等退出发 `exit` 帧。
 * 判据（能不能跑、跑什么）全在协调器与审批账本那侧，本类不判 —— 它拿到的就是一条
 * 已经放行的命令。
 *
 * **回收顺序**（§10.3 T1「TERM→超时→SIGKILL 且 `kill -- -<pgid>` + `/proc` 二次收割」）：
 * 本版用 `ProcessHandle.descendants()` 收后代，不用 `kill -- -pgid`。理由是**可靠性**而不是
 * 省事：负 pgid 那条路要求子进程**没有**自己的进程组，而 `sh -c` 之后的东西是否另起
 * 进程组由 shell 与 ROM 决定（`setsid`/job control 在部分 ROM 上默认开）—— 一条"大多数
 * 情况下对"的杀树，漏掉的那次留下的正是最难查的孤儿。`descendants()` 走 `/proc` 的
 * 父子链，与"谁把谁生出来"这个事实同源，且 Java 9+ 的平台实现里本来就有。
 * **它也有自己的洞**（如实记账）：孙子进程若被 `reparent` 给 init（父先死），链就断了 ——
 * 那正是 §10.3 里 `kill -- -<pgid>` 想兜的那一类，**本版没兜住**。
 */
class T1Child(
    private val id: Long,
    private val cmd: String,
    private val args: List<String>,
    private val cwd: String?,
    private val env: Map<String, String>?,
    private val detached: Boolean,
    private val shell: Boolean,
    private val runner: T1ProcessRunner,
    /** 往会话 socket 写一帧（由 [T1Session] 提供，已加锁）。 */
    private val emit: (Map<String, Any?>) -> Boolean,
) {

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdin: OutputStream? = null

    /** 起进程 → 泵输出 → 等退出。在会话给这条子进程开的线程上跑。 */
    fun run() {
        // detached 在 shim 那侧已经拒过一次，这里再拒一次：**两侧各拒一次是刻意的** ——
        // shim 是"送信的"，它被绕过（`delete require.cache`）时这条判据不能跟着消失。
        if (detached) {
            fail("ERR_PERMISSION_DENIED", "detached:true 被拒（§10.3 T1）：脱离进程组的子进程回收不到")
            return
        }
        val proc = try {
            runner.start(T1SpawnSpec(cmd, args, cwd, env, shell))
        } catch (e: IOException) {
            // 起不来是**结果**不是崩溃：如实回一条 err 帧，让脚本侧收到带码的错误
            // （§10.3 T1：可操作话术优于静默失败）。
            fail("ERR_NPM_SPAWN_BLOCKED", "T1 子进程启动失败（$cmd）：${e.message}")
            return
        }
        process = proc
        stdin = proc.outputStream
        val errPump = Thread({ pump(proc.errorStream, fd = 2) }, "t1-child-$id-err").apply {
            isDaemon = true
            start()
        }
        pump(proc.inputStream, fd = 1)
        proc.waitFor()
        errPump.join(ERR_PUMP_JOIN_MILLIS)
        emit(
            mapOf(
                "t" to "exit",
                "id" to id,
                "code" to proc.exitValue(),
                // signal 本版恒 null：Java 的 Process 不给信号号（`exitValue()` 对信号终止
                // 给的是 128+n 那个约定值，不是信号名）。**不编一个**：脚本侧拿到的
                // `signalCode` 为 null，而 `exitCode` 是 128+n —— 与真 child_process 在
                // "被信号杀死"这一档上不同，如实记账。
                "signal" to null,
            ),
        )
    }

    /** 读一条流到 EOF，逐块 base64 报回（分块边界即 socket 帧边界，语义上是字节流）。 */
    private fun pump(stream: InputStream, fd: Int) {
        val buf = ByteArray(PUMP_CHUNK_BYTES)
        try {
            while (true) {
                val n = stream.read(buf)
                if (n < 0) return
                if (n == 0) continue
                val b64 = Base64.getEncoder().encodeToString(if (n == buf.size) buf else buf.copyOf(n))
                if (!emit(mapOf("t" to "out", "id" to id, "fd" to fd, "data" to b64))) return
            }
        } catch (_: IOException) {
            // 流断了（进程被杀/管道关闭）：那是结束方式，不是病因 —— 退出码由 waitFor 那侧给。
        }
    }

    /** 脚本往子进程 stdin 写（`in` 帧）。写失败静默：子进程可能已经退了。 */
    fun writeStdin(b64: String) {
        val os = stdin ?: return
        try {
            os.write(Base64.getDecoder().decode(b64))
            os.flush()
        } catch (_: IOException) {
            // 子进程已退：写不进去是正常结果（真 child_process 在这种情况下给 EPIPE）。
        }
    }

    /**
     * 收这条进程树：TERM → 宽限 → KILL（含后代）。幂等。
     *
     * 后代那条链包在 `runCatching` 里：`Process.toHandle()` 对**非本机进程**（单测替身、
     * 任何自定义 `Process` 实现）会抛 `UnsupportedOperationException`，而"收不到后代"
     * 不该让"收掉主进程"这件事一起失败 —— 那是收尸，做一半比不做更糟。
     */
    fun reap() {
        val proc = process ?: return
        if (!proc.isAlive) return
        val tree = runCatching { proc.toHandle().descendants().toList() }.getOrDefault(emptyList())
        proc.destroy()
        tree.forEach { runCatching { it.destroy() } }
        if (!proc.waitFor(REAP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            tree.forEach { runCatching { it.destroyForcibly() } }
        }
    }

    /** 一条失败帧（`err`）：脚本侧那个 ChildProcess 会把它发成 `error` 事件。 */
    private fun fail(code: String, detail: String) {
        emit(mapOf("t" to "err", "id" to id, "code" to code, "detail" to detail))
    }

    private companion object {
        /** 单块上限：一条 64KiB 的输出块 base64 后约 87KiB，仍远在帧上限（1MiB）之下。 */
        const val PUMP_CHUNK_BYTES: Int = 64 * 1024

        /** 收尸宽限（TERM 之后给多久才 KILL）。 */
        const val REAP_GRACE_MILLIS: Long = 500L

        /** stderr 泵的 join 上限：进程已退，管道必然关闭，正常是微秒级。 */
        const val ERR_PUMP_JOIN_MILLIS: Long = 2_000L
    }
}

/** 一次已放行的 spawn 请求（会话 → 执行体）。 */
data class T1SpawnSpec(
    val cmd: String,
    val args: List<String>,
    val cwd: String?,
    val env: Map<String, String>?,
    val shell: Boolean,
)

/**
 * 起进程的缝（单测注入替身；生产 = [ProcessBuilderT1Runner]）。
 *
 * 为什么留缝而不是直接 `ProcessBuilder`：这条路的判据大多在"起了之后"（泵输出、等退出、
 * 杀树），那些用真进程验又慢又脆（要等真 sleep），用替身能把三条都跑成确定性用例；
 * 而"真能起真进程"由 E2E 那侧单独钉。
 */
fun interface T1ProcessRunner {
    /** 起进程；起不来抛 [IOException]（调用方折成 `err` 帧）。 */
    fun start(spec: T1SpawnSpec): Process
}

/**
 * 生产执行体：`ProcessBuilder`。
 *
 * **`shell = true` 时与 Node 的语义对齐**（`cmd + ' ' + args.join(' ')` 交给 `/bin/sh -c`）：
 * npm 自己发的是显式的 `spawn("sh", ["-c", body])`，走不到这条分支；但脚本侧若写
 * `spawn('echo hi', {shell:true})`，语义必须与 Node 一致 —— 否则同一个写法在两处跑出不同的东西。
 *
 * `cwd` / `env` 按 Node 的口径：`env` 给的是**完整替换**（Node 的 `env` 选项就是替换，
 * 不是合并），故给了就整份用，没给才继承。这一条容易记反，写在这里免得下一版"顺手合并"。
 */
class ProcessBuilderT1Runner : T1ProcessRunner {

    override fun start(spec: T1SpawnSpec): Process {
        val argv = if (spec.shell) {
            listOf(shellPath(), "-c", (listOf(spec.cmd) + spec.args).joinToString(" "))
        } else {
            listOf(spec.cmd) + spec.args
        }
        val pb = ProcessBuilder(argv)
        spec.cwd?.let { dir -> pb.directory(java.io.File(dir)) }
        spec.env?.let { env ->
            pb.environment().clear()
            pb.environment().putAll(env)
        }
        return pb.start()
    }

    /**
     * `/bin/sh` 的选法：Android 在 `/system/bin/sh`，桌面在 `/bin/sh`。
     *
     * 不读环境变量 `SHELL`：那是**用户的交互 shell**（可能是 bash/zsh），而 Node 的
     * `shell:true` 契约写的是 `/bin/sh`。拿用户 shell 去跑脚本会让"同一段脚本在两台机器上
     * 语法不同"—— 那正是 sh 存在的意义所在。
     */
    private fun shellPath(): String =
        if (java.io.File("/system/bin/sh").canExecute()) "/system/bin/sh" else "/bin/sh"
}
