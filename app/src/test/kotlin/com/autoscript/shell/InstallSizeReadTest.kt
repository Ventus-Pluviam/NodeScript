package com.autoscript.shell

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 安装体积的实测（§15 的 E1 处置：拍板「接受超支并在能力中心明示」）。
 *
 * 钉住三件会撒谎的事：
 * - **量不到回 null，不回 0**（0 会被 UI 读成「安装包是空的」）；
 * - **引擎那一份要真按 ABI 子目录找**（`lib/<abi>/libX.so` 是真实落位，拼全名会找不到）；
 * - **引擎不在时体积照样报**，只是 `engineFilesPresent = false` —— 隐去比显示更糟。
 */
class InstallSizeReadTest {

    private fun so(dir: File, abi: String, name: String, bytes: Int): File =
        File(File(dir, abi), name).also {
            it.parentFile.mkdirs()
            Files.write(it.toPath(), ByteArray(bytes))
        }

    @Test
    fun `按 ABI 子目录找齐引擎四件并求和`(@TempDir tmp: File) {
        val libs = File(tmp, "lib")
        so(libs, "arm64-v8a", "libnoden.so", 100)
        so(libs, "arm64-v8a", "libnode.so", 200)
        so(libs, "arm64-v8a", "libc++_shared.so", 300)
        so(libs, "arm64-v8a", "libopencv.so", 400)
        val apk = File(tmp, "app.apk").also { Files.write(it.toPath(), ByteArray(5_000)) }

        val size = InstallSizeRead.measure(apk, libs)!!
        assertEquals(1_000L, size.engineBytes, "四个 .so 都要算进去（缺一个就少报一段）")
        assertEquals(5_000L, size.totalBytes)
        assertTrue(size.engineFilesPresent)

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `libcpp 直接落在 lib 根下也算 不只找 ABI 子目录`(@TempDir tmp: File) {
        val libs = File(tmp, "lib")
        so(libs, "arm64-v8a", "libnode.so", 200)
        Files.write(File(libs, "libc++_shared.so").toPath(), ByteArray(300))
        assertEquals(500L, InstallSizeRead.engineBytes(libs), "只查 ABI 子目录会漏掉裸放 lib 根下的那一份")

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `APK 读不到时整体回 null 而不是 0`(@TempDir tmp: File) {
        assertNull(InstallSizeRead.measure(File(tmp, "不存在.apk"), File(tmp, "lib")))
        assertEquals(0L, InstallSizeRead.engineBytes(File(tmp, "根本没有这个目录")))
        assertFalse(
            InstallSizeRead.measure(File(tmp, "不存在.apk"), File(tmp, "lib"))?.engineFilesPresent ?: false,
            "量不到时不存在「引擎在不在」这个事实",
        )

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `引擎未随包时体积照报并标记缺失`(@TempDir tmp: File) {
        val apk = File(tmp, "app.apk").also { Files.write(it.toPath(), ByteArray(8 * 1024 * 1024)) }
        val size = InstallSizeRead.measure(apk, File(tmp, "lib"))!!
        assertEquals(8L * 1024 * 1024, size.totalBytes)
        assertEquals(0L, size.engineBytes)
        assertFalse(size.engineFilesPresent, "CI 出的无引擎 APK 就是这个形状：体积如实报，缺失如实标")

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }
}
