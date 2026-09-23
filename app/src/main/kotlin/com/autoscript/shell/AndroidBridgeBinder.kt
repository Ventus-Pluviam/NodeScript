package com.autoscript.shell

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.InputStream
import java.io.OutputStream

/**
 * [BridgeSocketBinder] 的 Android 生产实现：`LocalServerSocket(String)` 绑 **abstract**
 * （§7.8：minSdk 26 没有 `ServerSocketChannel` 的 unix API，设备面只能走 `android.net`；
 * Java 侧的 String 名与 main.cpp 客户端 `sun_path[0]=0 + 名` 对齐，无需 `/` 前缀）。
 * 对端 uid 取 `LocalSocket.peerCredentials`（内核 `SO_PEERCRED`，与客户端侧对称）。
 *
 * **薄到没有逻辑**：绑定失败回 null、accept 期异常上抛 —— 门禁/循环/生命周期全在
 * [BridgeSocketListener]（纯 JVM 可测面）。本对象**不在单测里碰**：`:app` 单测运行期
 * classpath 不带 android.jar，`android.*` 方法体是会抛异常的桩。
 */
object AndroidBridgeBinder : BridgeSocketBinder {

    override fun bind(socketName: String): BoundBridgeSocket? = try {
        val server = LocalServerSocket(socketName)
        object : BoundBridgeSocket {
            override fun accept(): AcceptedBridgeConnection {
                val socket: LocalSocket = server.accept()
                // 流在 accept 当场取齐（取失败 = IOException 上抛 → 循环按故障收，
                // 不留到 serve 中途才炸连接）
                val input: InputStream = socket.getInputStream()
                val output: OutputStream = socket.getOutputStream()
                return object : AcceptedBridgeConnection {
                    // 读不到凭据会抛 → 门禁 fail-closed（见 AcceptedBridgeConnection KDoc）
                    override val peerUid: Int
                        get() = socket.peerCredentials.uid

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
                    // 同上：关断幂等
                }
            }
        }
    } catch (_: Exception) {
        null    // 名字被抢/权限/平台差异：离线降级由调用方记账，不冒泡炸装配
    }
}
