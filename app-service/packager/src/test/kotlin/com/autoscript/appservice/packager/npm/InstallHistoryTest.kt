package com.autoscript.appservice.packager.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 安装审计史单测（§10.2 install-history / §10.5-2「审计日志落 App 且可导出」）：
 * 成败都入史 + fsync 落盘 + op 名归类 + 半行容忍 + 特殊字符 roundtrip。
 */
class InstallHistoryTest {

    @TempDir
    lateinit var dir: Path

    private fun history() = InstallHistory(dir.resolve(".autojs"))

    @Test
    fun `成功与失败都入史（审计不能只记成功）`() {
        val h = history()
        h.record(InstallHistory.Op.INSTALL, "p1", true, "npm install 完成")
        h.record(InstallHistory.Op.INSTALL, "p1", false, "npm 退出码 1")
        val all = h.all()
        assertEquals(2, all.size)
        assertTrue(all[0].success)
        assertFalse(all[1].success)
        assertEquals("npm install 完成", all[0].detail)
        assertEquals("npm 退出码 1", all[1].detail)
        assertEquals(2, h.forProject("p1").size)
    }

    @Test
    fun `registry 变更独立 op（登记审计键）`() {
        val h = history()
        h.record(InstallHistory.Op.REGISTRY, "p1", true, "registry=https://registry.npmmirror.com")
        assertEquals(InstallHistory.Op.REGISTRY, h.all().single().op)
    }

    @Test
    fun `半行容忍：截断尾行不抛、完整行仍可用`() {
        val h = history()
        h.record(InstallHistory.Op.PRUNE, "p1", true, null)
        val file = dir.resolve(".autojs/install-history.jsonl")
        assertTrue(Files.exists(file), "record 后文件必须已落盘")
        Files.write(file, ("{\"projectId\":\"p1\"").toByteArray(), java.nio.file.StandardOpenOption.APPEND)
        assertEquals(1, h.all().size)
    }

    @Test
    fun `特殊字符 roundtrip（detail 含引号反斜杠换行不变）`() {
        val h = history()
        val detail = "npm 退出码 1: \"EINTEGRITY\" path\\to\\pkg\nline2"
        h.record(InstallHistory.Op.CI, "p1", false, detail)
        assertEquals(detail, h.all().single().detail)
    }

    @Test
    fun `未知 op 如实收下（不为枚举整齐丢事件）`() {
        val h = history()
        h.record("audit-signatures", "p1", true, null)
        assertEquals("audit-signatures", h.all().single().op)
    }

    @Test
    fun `空目录返回空`() {
        assertTrue(history().all().isEmpty())
        assertTrue(history().forProject("p1").isEmpty())
    }
}
