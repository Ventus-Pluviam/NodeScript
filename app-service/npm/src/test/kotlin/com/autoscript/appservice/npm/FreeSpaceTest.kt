package com.autoscript.appservice.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 磁盘探针的取数口（2026-10-17 设备实测：`Files.getFileStore` 在 Android 上抛
 * `UnsupportedOperationException("getFileStore")`，装依赖整个走不通 —— 见 [FreeSpace] 的类 KDoc）。
 *
 * **本机测不到"Android 那格没实现"这件事**（宿主 JVM 的 `getFileStore` 是好的），所以这里
 * 钉的是**能钉的那一半**：探针对"目录还不存在"这个常态的处置。两条断言各自有前提价值 ——
 * 第二条正是"为什么非退到 fallback 不可"的实测依据（不存在的路径 `usableSpace` 回 0）。
 */
class FreeSpaceTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `存在的目录读出所在分区的可用字节`() {
        assertTrue(
            FreeSpace.usableBytes(dir) > 0,
            "存在的目录必须读得出真数（/tmp 真被写满时这条会红 —— 那是环境，不是回归）",
        )
    }

    @Test
    fun `不存在的路径回 0（这就是非退 fallback 不可的理由）`() {
        val missing = dir.resolve("还没建的项目")
        assertFalse(Files.exists(missing))
        assertEquals(
            0L,
            FreeSpace.usableBytes(missing),
            "`statvfs` 对不存在的路径失败，两边实现都把失败折成 0 —— 直接拿它比预检下限，" +
                "新项目第一次装依赖就会撞一句假的 ERR_DISK_FULL",
        )
    }

    @Test
    fun `目录不存在时退到 fallback 读真数，不拿 0 冒充磁盘满`() {
        val missing = dir.resolve("还没建的项目")
        assertTrue(
            FreeSpace.usableBytesFor(missing, dir) > 0,
            "退到 fallback（生产给的是 filesDir，同一块分区）后必须读到真数",
        )
    }

    @Test
    fun `目录存在时就地读，不绕 fallback`() {
        // 就地读与 fallback 读是同一块分区，数字应当一致；这里钉的是"存在时不绕路"这个选择
        // 本身 —— 造一个 fallback 指向**不存在**的路径：真绕过去了就会读出 0。
        val alsoMissing = dir.resolve("fallback 也不存在")
        assertTrue(
            FreeSpace.usableBytesFor(dir, alsoMissing) > 0,
            "目录存在时不该看 fallback —— 看了就会读出 0（fallback 自己不存在）",
        )
    }
}
