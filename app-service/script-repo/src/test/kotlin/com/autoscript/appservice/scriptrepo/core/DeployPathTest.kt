package com.autoscript.appservice.scriptrepo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DeployPathTest {

    @Test
    fun `拒绝所有穿越形态的相对路径`() {
        val bad = listOf(
            "", "../evil.js", "a/../../evil.js", "./a.js", "a//b.js",
            "a\\b.js", "/abs.js", "a/./b.js", "..", "a/../",
        )
        for (p in bad) assertFalse(DeployPath.isSafeRelPath(p), "应拒绝: $p")
    }

    @Test
    fun `接受正常相对路径`() {
        for (p in listOf("main.js", "a/b/main.js", "dir/file.txt", "sub/main.min.js", "目录/脚本.js")) {
            assertTrue(DeployPath.isSafeRelPath(p), "应接受: $p")
        }
    }

    @Test
    fun `resolveIn 拒绝逃逸`(@TempDir tmp: Path) {
        assertThrows(IllegalArgumentException::class.java) { DeployPath.resolveIn(tmp, "../outside.js") }
        // 中途段逃逸、规范化后仍越界也必须拒绝
        assertThrows(IllegalArgumentException::class.java) { DeployPath.resolveIn(tmp, "a/../../outside.js") }
    }

    @Test
    fun `resolveIn 正常解析到 root 之下`(@TempDir tmp: Path) {
        val out = DeployPath.resolveIn(tmp, "a/b.js")
        assertEquals(tmp.resolve("a/b.js"), out)
        assertTrue(out.startsWith(tmp.toAbsolutePath().normalize()))
    }
}