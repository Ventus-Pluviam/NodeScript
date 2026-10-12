package com.autoscript.appservice.npm

import javax.crypto.spec.SecretKeySpec
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

    private val key = LockSigner.KeyProvider { SecretKeySpec("test-app-key-32bytes-aaaaaaaaaaaa".toByteArray(), "HmacSHA256") }
    private val lock get() = dir.resolve("package-lock.json")

    private fun writeLock(text: String = """{"lockfileVersion":3,"packages":{"node_modules/lodash":{"version":"4.17.21"}}}""") {
        Files.write(lock, (text).toByteArray())
    }

    @Test
    fun `签验闭环：签完即可过验`() {
        writeLock()
        val s = LockSigner(dir.resolve(".ns"), key)
        s.sign("p1", lock)
        assertTrue(Files.exists(dir.resolve(".ns/lock.sig")), "lock.sig 必须落盘")
        s.verifyOrThrow("p1", lock)   // 不抛即通过
    }

    @Test
    fun `缺签拒绝 ci（不是没签就跳过）`() {
        writeLock()
        val e = assertThrows(AutojsException::class.java) { LockSigner(dir.resolve(".ns"), key).verifyOrThrow("p1", lock) }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, e.error.code, "TOFU 自签正是被批判形态：缺签必须拒")
    }

    @Test
    fun `lock 被改则验签失败（责任人改不动闭包）`() {
        writeLock()
        LockSigner(dir.resolve(".ns"), key).sign("p1", lock)
        Files.write(lock, ("""{"lockfileVersion":3,"packages":{"node_modules/evil":{"version":"9.9.9"}}}""").toByteArray())
        val e = assertThrows(AutojsException::class.java) { LockSigner(dir.resolve(".ns"), key).verifyOrThrow("p1", lock) }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, e.error.code)
    }

    @Test
    fun `跨项目搬锁拒绝（签名绑定 projectId）`() {
        writeLock()
        LockSigner(dir.resolve(".ns"), key).sign("p1", lock)
        val e = assertThrows(AutojsException::class.java) { LockSigner(dir.resolve(".ns"), key).verifyOrThrow("p2", lock) }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, e.error.code, "A 项目的签不能给 B 项目的 lock 背书")
    }

    @Test
    fun `密钥切换（重装或恢复出厂）则旧签失效`() {
        writeLock()
        LockSigner(dir.resolve(".ns"), key).sign("p1", lock)
        val other = LockSigner(dir.resolve(".ns"), LockSigner.KeyProvider { SecretKeySpec("other-key".toByteArray(), "HmacSHA256") })
        assertThrows(AutojsException::class.java) { other.verifyOrThrow("p1", lock) }
        // 重新签即可恢复（显式动作，不静默）
        other.sign("p1", lock)
        other.verifyOrThrow("p1", lock)
    }

    // ── 落盘格式严格性 + 原子落位（2026-10-01，backlog A4）─────────────
    // 病灶：KDoc 写「前缀不识一律拒」，实现却是 removePrefix("v1") —— 裸 hex 照样接受，
    // 于是「版本前缀」这个换算法的兼容开关等于不存在。

    @Test
    fun `裸 hex（丢了版本前缀）被拒 —— 即便它就是正确的 HMAC`() {
        writeLock()
        val s = LockSigner(dir.resolve(".ns"), key)
        s.sign("p1", lock)
        val sig = dir.resolve(".ns/lock.sig")
        val hex = String(Files.readAllBytes(sig)).trim().removePrefix("v1").trim()
        Files.write(sig, hex.toByteArray())   // 内容一字不差，就是没有版本标签
        val e = assertThrows(AutojsException::class.java) { s.verifyOrThrow("p1", lock) }
        assertEquals(
            ErrorCode.ERR_PERMISSION_DENIED.code, e.error.code,
            "前缀是将来换 ECDSA 的兼容开关，宽松解析等于把它废掉",
        )
    }

    @Test
    fun `未知前缀、大写 hex、半截签名一律拒`() {
        writeLock()
        val s = LockSigner(dir.resolve(".ns"), key)
        s.sign("p1", lock)
        val sig = dir.resolve(".ns/lock.sig")
        val hex = String(Files.readAllBytes(sig)).trim().removePrefix("v1").trim()
        val bad = listOf("v2 $hex", "v1 ${hex.uppercase()}", "v1 ${hex.dropLast(1)}", "v1", "")
        for (text in bad) {
            Files.write(sig, text.toByteArray())
            val e = assertThrows(AutojsException::class.java) { s.verifyOrThrow("p1", lock) }
            assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, e.error.code, "写法「$text」必须被拒")
        }
    }

    @Test
    fun `sign 原子落位：不留临时残渣，旧签被整体替换`() {
        writeLock()
        val nsDir = dir.resolve(".ns")
        val s = LockSigner(nsDir, key)
        s.sign("p1", lock)
        writeLock("""{"lockfileVersion":3,"packages":{"node_modules/lodash":{"version":"4.17.22"}}}""")
        s.sign("p1", lock)   // 换内容再签一次：覆盖路径与首次同一条
        s.verifyOrThrow("p1", lock)
        val listing = Files.list(nsDir).use { st -> st.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("lock.sig"), listing.sorted(), "临时文件必须被 rename 掉，不留 lock.sig.tmp")
    }

    @Test
    fun `lock 缺失 → ERR_FILE_NOT_FOUND（不拿旧签糊弄）`() {
        writeLock()
        val s = LockSigner(dir.resolve(".ns"), key)
        s.sign("p1", lock)
        Files.delete(lock)   // lock 消失：要的是显式失败，不是「旧签还在」
        val e = assertThrows(AutojsException::class.java) { s.verifyOrThrow("p1", lock) }
        assertEquals(ErrorCode.ERR_FILE_NOT_FOUND.code, e.error.code)
    }
}
