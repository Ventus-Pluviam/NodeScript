package com.autoscript.shell

import com.autoscript.domain.host.InstallSize
import java.io.File

/**
 * 安装体积的实测（§15 的 E1 处置：拍板「接受超支并在能力中心明示」；
 * 预算重定为 `≤ 150MB release`，见 `design-decisions.md` 第 41 项 —— 本类的量法与之无关，
 * 它量的是宿主现场的**真实字节数**，不是拿预算数字去比）。
 *
 * **为什么量而不是抄**：§15 记的 ≈92MB 是 node-slice 产物的**未压缩** jniLibs 三件套合计，
 * 文档链接那行还写「APK 压缩安装后另计」——而用户装的是**压缩后**的 APK。两者不是同一个数，
 * 也不是同一个口径。抄文档数字进 UI 就是呈现层说谎。
 *
 * **为什么量出来的还要分「引擎那一段」**：超支**全在**引擎三件套（OpenCV 只占 6.6%，
 * 见 §15 的实测记账），所以只给一个总数没法回答用户真正会问的那句
 * 「为什么这么大 / 能不能小一点」。分段给才答得上。
 *
 * 与 `CapabilityCenterRead` 同款分法：抽成纯 JVM 面，让"怎么算"这段判断可 JVM 测，
 * `AppShellApplication` 只留一句转接（Application 在 JVM 单测里构造不出来）。
 */
object InstallSizeRead {

    /**
     * 引擎两条 native 轨的文件名。取自 `autoscript.engine-natives` 的三件套 + 图像那条：
     * `libnoden` / `libnode` / `libc++_shared` 是引擎三件，`libopencv` 是 §9.2 图像管线。
     *
     * **口径**：量的是宿主**运行时的真实文件**（`nativeLibraryDir` 下 extract 出来的 .so），
     * 不是 APK 内的压缩条目 —— 前者才是装完占多少存储、后者还受压缩率影响。
     * 库名带 ABI 前缀的实际落位是 `lib/arm64-v8a/`，故按子目录找而非拼全名。
     */
    val ENGINE_SO_NAMES: List<String> = listOf(
        "libnoden.so",
        "libnode.so",
        "libc++_shared.so",
        "libopencv.so",
    )

    /**
     * 引擎字节数：按 [ENGINE_SO_NAMES] 在 [nativeLibDir] 下**逐个 ABI 子目录**找齐再求和。
     *
     * 找不到（CI 出的无引擎 APK、装配期「缺位只 warn」那条路）→ 求和得 0，
     * 而 [InstallSize.engineFilesPresent] 如实 false。**0 不隐藏**：它和"量到了 0 字节"
     * 是两回事，故两者分两个字段。
     */
    fun engineBytes(nativeLibDir: File): Long {
        if (!nativeLibDir.isDirectory) return 0L
        return ENGINE_SO_NAMES.sumOf { name ->
            // ABI 子目录（lib/<abi>/libX.so）：libcpp 直接在 lib/ 下也一并算上。
            val inAbi = nativeLibDir.listFiles()
                ?.filter { it.isDirectory }
                ?.sumOf { abi -> File(abi, name).takeIf { f -> f.isFile }?.length() ?: 0L }
                ?: 0L
            inAbi + (File(nativeLibDir, name).takeIf { it.isFile }?.length() ?: 0L)
        }
    }

    /**
     * 量一份安装体积。
     *
     * @param apkFile 已安装的 APK 文件（生产 = `applicationInfo.sourceDir`）。
     * @param nativeLibDir extract 出来的 native 库目录（生产 = `applicationInfo.nativeLibraryDir`）。
     *
     * 量不到（文件不在/读不到）→ **整体回 null**（"未量到"），不给 0：
     * 0 会被 UI 渲染成「安装体积 0 B」，那是比不显示更糟的谎。
     */
    fun measure(apkFile: File, nativeLibDir: File): InstallSize? {
        val total = apkFile.takeIf { it.isFile }?.length() ?: return null
        val engine = engineBytes(nativeLibDir)
        return InstallSize(
            totalBytes = total,
            engineBytes = engine,
            engineFilesPresent = engine > 0L,
        )
    }
}
