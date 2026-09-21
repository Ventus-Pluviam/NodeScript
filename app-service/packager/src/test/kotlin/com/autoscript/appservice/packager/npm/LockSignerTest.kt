package com.autoscript.appservice.packager.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * lockfile 带外签名单测（§10.5-1 lock.sig）：签→验闭环 + 篡改拒 +
 * 缺签拒 + 跨项目搬锁拒 + 密钥切换拒。
 */
class LockSignerTest {

    @TempDir
    lateinit var dir: Path

    private val key = LockSigner.KeyProvider { "test-app-key-32bytes-aaaaaaaaaaaa".toByteArray() }
    private val lock get() = dir.resolve("package-lock.json")

    private fun writeLock(text: String = """{"lockfileVersion":3,"packages":{"node_modules/lodash":{"version":"4.17.21"}}}""") {
        Files.write(lock, (text).toByteArray())
    }

    @Test
    fun `签验闭环：签完即可过验`() {
        writeLock()
        val s = LockSigner(dir.resolve(".autojs"), key)
        s.sign("p1", lock)
        assertTrue(Files.exists(dir.resolve(".autojs/lock.sig")), "lock.sig 必须落盘")
        s.verifyOrThrow("p1", lock)   // 不抛即通过
    }

    @Test
    fun `缺签拒绝 ci（不是没签就跳过）`() {
        writeLock()
        val e = assertThrows(AutojsException::class.java) { LockSigner(dir.resolve(".autojs"), key).verifyOrThrow("p1", lock) }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, e.error.code, "TOFU 自签正是被批判形态：缺签必须拒")
    }

    @Test
    fun `lock 被改则验签失败（责任人改不动闭包）`() {
        writeLock()
        LockSigner(dir.resolve(".autojs"), key).sign("p1", lock)
        Files.write(lock, ("""{"lockfileVersion":3,"packages":{"node_modules/evil":{"version":"9.9.9"}}}""").toByteArray())
        val e = assertThrows(AutojsException::class.java) { LockSigner(dir.resolve(".autojs"), key).verifyOrThrow("p1", lock) }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, e.error.code)
    }

    @Test
    fun `跨项目搬锁拒绝（签名绑定 projectId）`() {
        writeLock()
        LockSigner(dir.resolve(".autojs"), key).sign("p1", lock)
        val e = assertThrows(AutojsException::class.java) { LockSigner(dir.resolve(".autojs"), key).verifyOrThrow("p2", lock) }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, e.error.code, "A 项目的签不能给 B 项目的 lock 背书")
    }

    @Test
    fun `密钥切换（重装或恢复出厂）则旧签失效`() {
        writeLock()
        LockSigner(dir.resolve(".autojs"), key).sign("p1", lock)
        val other = LockSigner(dir.resolve(".autojs"), LockSigner.KeyProvider { "other-key".toByteArray() })
        assertThrows(AutojsException::class.java) { other.verifyOrThrow("p1", lock) }
        // 重新签即可恢复（显式动作，不静默）
        other.sign("p1", lock)
        other.verifyOrThrow("p1", lock)
    }

    @Test
    fun `lock 缺失 → ERR_FILE_NOT_FOUND（不拿旧签糊弄）`() {
        writeLock()
        val s = LockSigner(dir.resolve(".autojs"), key)
        s.sign("p1", lock)
        Files.delete(lock)   // lock 消失：要的是显式失败，不是「旧签还在」
        val e = assertThrows(AutojsException::class.java) { s.verifyOrThrow("p1", lock) }
        assertEquals(ErrorCode.ERR_FILE_NOT_FOUND.code, e.error.code)
    }
}
