package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.nio.file.Files
import java.nio.file.Path

/**
 * npm 会话宿主的**必填环境**（`AUTOSCRIPT_LIBNODE`，§7.8）。
 *
 * **为什么需要这一件**（2026-10-17 设备实测 bug）：脚本引擎那条路（`NodeProcessEngine`）
 * 与 npm 这条路（[HostNodeExecutor] / [NpmScriptExecutor]）spawn 的是**同一个** noden
 * 宿主（`nativeLibraryDir/libnoden.so`），而 `main.cpp` 头一件事就是读 `AUTOSCRIPT_LIBNODE`
 * 去 `dlopen` libnode.so —— 缺了直接 `exit 2`，stderr 只有一行
 * `:nodeN 缺 AUTOSCRIPT_LIBNODE（libnode.so 绝对路径）`。
 * 引擎那条路一直在注（`NodeEngineConfig.libnodePath`），**npm 这条路从来没注过** ——
 * 于是设备上点安装的结局就是那句 exit 2，而它在宿主 JVM 的测试里**复现不出**：
 * 桌面跑的是系统 `node`，它根本不读这个变量。
 *
 * **名字的锚**：`main.cpp` 头注释、`NodeProcessEngine.ENV_LIBNODE`、docs §7.8 三处同名；
 * 本处是第四处（跨模块 —— `:app-service:npm` 看不见 `:engine:node-process`，两者只共享
 * `:domain`）。与 [NpmT1Bridge.ENV_SOCKET] 同一个处境、同一条纪律：**改名要四处同批**，
 * 而字面量由 `NpmHostEnvTest` 钉住（C++ 与 Kotlin 之间没有可共享的常量，
 * 这条只能靠钉字面量）。
 */
internal object NpmHostEnv {
    /** `main.cpp` 必填；缺则 exit 2（§7.8）。 */
    const val ENV_LIBNODE: String = "AUTOSCRIPT_LIBNODE"

    /**
     * 注入宿主键。**必须在用户/基础键之后调用**（§8.1「顺序即契约」：宿主键后写才胜出）。
     *
     * [libnode] 为 null = 这次用的不是需要 libnode 的宿主（桌面系统 `node`，
     * 见 `NodeEngineConfig.libnodePath` 的 KDoc 同款处境），此时不注入 —— 也不报错。
     */
    fun apply(env: MutableMap<String, String>, libnode: Path?) {
        libnode?.let { env[ENV_LIBNODE] = it.toString() }
    }

    /**
     * 必填件预检（与 `NodeProcessEngine.execute` 的同名判据逐字同口径）：
     * **配了却没落位 = 打包缺件**，在这里点名，不让子进程用一句 exit 2 去说 ——
     * 用户看到的「退出码 2」与「libnode.so 没随包」之间隔着一次翻源码。
     *
     * 非绝对路径 = PATH 查找名（桌面测试形态），交给 spawn 自己报 `Cannot run program`。
     */
    fun checkInstalled(libnode: Path?) {
        if (libnode == null || !libnode.isAbsolute) return
        if (!Files.isRegularFile(libnode)) {
            throw AutojsException(
                ErrorCode.ERR_FILE_NOT_FOUND,
                "libnode.so 未随 APK 落位：$libnode（node-runtime-build 产物，§3；main.cpp 缺它 exit 2）",
            )
        }
    }
}
