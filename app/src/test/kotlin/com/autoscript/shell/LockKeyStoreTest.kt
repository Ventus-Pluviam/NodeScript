package com.autoscript.shell

import com.autoscript.appservice.npm.LockSigner
import com.autoscript.domain.core.AutojsException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * 应用密钥的 get-or-create 判定（§10.5-1 T2 / §11.3 第 3 条）。
 *
 * 验的是**判定**不是 Keystore：`AndroidKeystore` 里的 `android.` 调用在本机跑不动
 * （`:app` 无 Robolectric），故真实现只有一份薄壳，而「取不动 ≠ 没有」这条最容易写错的
 * 分界用 [LockKeyStore.HmacKeys] 的假件钉住。
 */
class LockKeyStoreTest {

    @TempDir
    lateinit var dir: Path

    private fun key(tag: String): SecretKey =
        SecretKeySpec("test-app-key-$tag-aaaaaaaaaaaaaaaa".toByteArray(), "HmacSHA256")

    /** 假 Keystore：`present` 控「库里有没有」，`onCreate` 控新建会不会炸。 */
    private class FakeKeys(
        private val present: SecretKey?,
        private val onCreate: (String) -> SecretKey = { error("本用例不该新建") },
    ) : LockKeyStore.HmacKeys {
        val loads = mutableListOf<String>()
        val creates = mutableListOf<String>()
        override fun load(alias: String): SecretKey? {
            loads += alias
            return present
        }

        override fun create(alias: String): SecretKey {
            creates += alias
            return onCreate(alias)
        }
    }

    @Test
    fun `库里已有：直接取那把，绝不新建`() {
        val existing = key("existing")
        val keys = FakeKeys(present = existing)
        assertSame(existing, LockKeyStore.resolve(keys).secretKey())
        assertEquals(listOf(LockKeyStore.ALIAS), keys.loads)
        assertTrue(keys.creates.isEmpty(), "已有钥匙时重建 = 换钥盖掉旧签名，正是 [resolve] KDoc 点名的灾难")
    }

    @Test
    fun `库里没有：建一把（首次运行那条路）`() {
        val fresh = key("fresh")
        val keys = FakeKeys(present = null, onCreate = { fresh })
        assertSame(fresh, LockKeyStore.resolve(keys).secretKey())
        assertEquals(listOf(LockKeyStore.ALIAS), keys.creates)
    }

    @Test
    fun `取不动（建钥抛）：异常照传，不静默换一把继续`() {
        val keys = FakeKeys(present = null, onCreate = { throw IllegalStateException("Keystore 锁屏不可用") })
        val provider = LockKeyStore.resolve(keys)
        val e = assertThrows(IllegalStateException::class.java) { provider.secretKey() }
        assertTrue(e.message!!.contains("锁屏"), "原因原文要透出来：${e.message}")
    }

    @Test
    fun `签名时刻的失败是响亮失败：验签拒绝且错误码是 ERR_PERMISSION_DENIED`() {
        // 这一条把「取钥失败」与「lock 被换过」在**诊断上**分开：前者抛原始异常（不是
        // AutojsException），后者是 LockSigner 的权限拒 —— 混成一条用户就分不清了。
        val signer = LockSigner(dir.resolve(".autojs"), LockKeyStore.resolve(FakeKeys(present = key("a"))))
        val lock = dir.resolve("package-lock.json")
        Files.write(lock, ("""{"lockfileVersion":3}""").toByteArray())
        signer.sign("main", lock)
        Files.write(lock, """{"lockfileVersion":3,"packages":{}}""".toByteArray())   // 被改过
        val e = assertThrows(AutojsException::class.java) { signer.verifyOrThrow("main", lock) }
        assertEquals("ERR_PERMISSION_DENIED", e.error.code)
        assertTrue(e.message!!.contains("验签失败"), "拒签必须带可读原因：${e.message}")
    }
}
