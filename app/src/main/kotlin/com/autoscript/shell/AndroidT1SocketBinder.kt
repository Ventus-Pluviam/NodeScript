package com.autoscript.shell

import android.net.LocalServerSocket
import android.net.LocalSocket
import com.autoscript.appservice.npm.NpmT1Bridge
import java.io.InputStream
import java.io.OutputStream

/**
 * T1 桥 socket 的 **Android 绑定实现**（`android.net.LocalServerSocket`，abstract 名）。
 *
 * 为什么设备上不能用 [NpmT1Bridge.fileSystemBinder]：JDK 17 的
 * `UnixDomainSocketAddress.of(Path)` **拒 NUL 前缀**（`InvalidPathException: Nul character
 * not allowed`，本机实测），故它只能绑文件系统路径；而 app 数据目录下的**路径型** socket
 * 在本平台上不可靠 —— 路径长度受 `sun_path` 108 字节限制、要处理残留文件、还要考虑
 * 厂商 ROM 对应用私有目录的挂载方式。abstract 名（内核命名空间，无文件、无权限位、
 * 随进程消失）才是设备侧该用的形态。`:app` 的脚本桥（[AndroidBridgeBinder]）早就这么做，
 * 本件是同一个决定的第二处落点。
 *
 * **对端该连的串是 `"\0" + name`**：Node 的 `net.connect({path})` 见到 NUL 前缀即走
 * abstract namespace（与 `main.cpp` 的 `sun_path[0]=0` 同一手法，§7.8）。
 *
 * **但这个串**不能**经 env 给对端**（2026-10-11 实测缺陷）：JVM 的
 * `ProcessBuilder.start()` 在环境变量值里见 NUL 就抛 `Invalid environment variable value`
 * （execve 的 envp 以 NUL 结尾，值里带 NUL 无意义），而 `connectTarget` 正是经
 * [com.autoscript.appservice.npm.NpmT1Bridge.applyEnv] 注入 `AUTOSCRIPT_T1_SOCKET` 的
 * 值 —— 设备侧 `npm run` 因此**必炸在起 npm 进程那一步**，报错是一串查不到出处的
 * `IllegalArgumentException`。桌面单测全绿（[NpmT1Bridge.fileSystemBinder] 的
 * `connectTarget` 是纯路径，无 NUL），同「pure-JVM 门看不见 API 级差异」一类。
 *
 * 修法（两边各让一步）：env 里给的是**裸名字**（NUL-free），NUL 前缀的职责搬到
 * shim 侧 —— `npm-t1-bridge.cjs` 的 `ensureConn` 见 env 值是"裸 abstract 名"就自己
 * 拼 `'\0' + path`（判别式：env 里不可能有 NUL，`includes('\0')` 为真只可能是
 * 文件系统路径里的字面 NUL，而那在本平台没有合法用例）。桌面侧路径型 `connectTarget`
 * 依旧原样直连，两条路各自对端不变。
 *
 * **薄到没有逻辑**（与 [AndroidBridgeBinder] 同一条口径）：绑定失败回 null、accept 期
 * 异常上抛，门禁/生命周期全在 [com.autoscript.appservice.npm.T1Session]（纯 JVM 可测面）。
 * 本对象**不在单测里碰** —— `:app` 单测的 android.jar 是桩，方法体一调就抛。
 */
object AndroidT1SocketBinder : NpmT1Bridge.SocketBinder {

    override fun bind(socketName: String): NpmT1Bridge.BoundSocket? = try {
        val server = LocalServerSocket(socketName)
        object : NpmT1Bridge.BoundSocket {
            // 裸名经 env 交给 shim（env 不能带 NUL，见类 KDoc 的缺陷注记）；
            // shim 见"裸 abstract 名"自己拼 NUL 前缀连 abstract namespace。
            override val connectTarget: String = socketName

            override fun accept(): NpmT1Bridge.AcceptedT1Connection? {
                val socket: LocalSocket = server.accept() ?: return null
                // 两条流在 accept 当场取齐（与 AndroidBridgeBinder 同款：取失败即上抛，
                // 不留到会话中途才炸连接）。
                val input: InputStream = socket.inputStream
                val output: OutputStream = socket.outputStream
                return object : NpmT1Bridge.AcceptedT1Connection {
                    override val input: InputStream = input
                    override val output: OutputStream = output

                    override fun close() {
                        try {
                            socket.close()
                        } catch (_: Exception) {
                            // 释放路径幂等：已关 = 正是目标状态
                        }
                    }
                }
            }

            override fun close() {
                try {
                    server.close()
                } catch (_: Exception) {
                    // 关断幂等：同上
                }
            }
        }
    } catch (_: Exception) {
        null // 名字被抢/权限/平台差异：调用方保持 Unavailable，不冒泡炸装配
    }
}
