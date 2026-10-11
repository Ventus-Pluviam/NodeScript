package com.autoscript.appservice.npm

/**
 * POSIX 信号名表（1–31，Linux/Android 同一份；32+ 是实时信号，编号各家不同**不猜**）。
 *
 * 用途见 [T1Child.run] 的 exit 帧：`exitValue >= 128` 时按 shell 惯例 `n = exit - 128`
 * 反查名字。**已知的误报档**：进程自己 `exit(143)` 会被报成 SIGTERM —— POSIX shell
 * （包括 npm 自己跑的 `sh`）对同一个数字也是这么报的，脚本生态里正常退出码 < 128，
 * 这个约定就是为「被信号杀死」这一档存在的。
 *
 * **它为什么留在本模块而不跟着 [com.autoscript.platform.process.TreeOps] 一起走**
 * （批 105 合并两份 `ProcOps` 时刻意分开的）：信号名表是 **T1 的 exit 帧契约**（这一层
 * 要把退出码翻成信号名给脚本看），而 `/proc` + `kill` 是**收尸的执行面**。前者没有第二个
 * 调用方 —— 通用进程模块不该替调用方决定退出码怎么呈现。
 */
internal fun signalName(num: Int): String? = POSIX_SIGNAL_NAMES[num]

/** 表住这里而不是 when：三十一条分支直接把圈复杂度顶过 detekt 阈值（表没有分支语义）。 */
private val POSIX_SIGNAL_NAMES: Map<Int, String> = mapOf(
    1 to "SIGHUP", 2 to "SIGINT", 3 to "SIGQUIT", 4 to "SIGILL", 5 to "SIGTRAP",
    6 to "SIGABRT", 7 to "SIGBUS", 8 to "SIGFPE", 9 to "SIGKILL", 10 to "SIGUSR1",
    11 to "SIGSEGV", 12 to "SIGUSR2", 13 to "SIGPIPE", 14 to "SIGALRM", 15 to "SIGTERM",
    16 to "SIGSTKFLT", 17 to "SIGCHLD", 18 to "SIGCONT", 19 to "SIGSTOP", 20 to "SIGTSTP",
    21 to "SIGTTIN", 22 to "SIGTTOU", 23 to "SIGURG", 24 to "SIGXCPU", 25 to "SIGXFSZ",
    26 to "SIGVTALRM", 27 to "SIGPROF", 28 to "SIGWINCH", 29 to "SIGIO", 30 to "SIGPWR",
    31 to "SIGSYS",
)
