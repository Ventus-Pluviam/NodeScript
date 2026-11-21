package com.autoscript.appservice.npm

import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 摘要编码优化不能改动 marker、cacache 或已有签名所依赖的十六进制文本。 */
class DirSizerDigestTest {
    @Test
    fun `SHA256 金样保持小写固定长度与前导零`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            DirSizer.sha256(byteArrayOf()),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DirSizer.sha256("abc".toByteArray()),
        )
    }

    @Test
    fun `SHA512 金样保持小写固定长度与前导零`() {
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47" +
                "d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            DirSizer.sha512(byteArrayOf()),
        )
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            DirSizer.sha512("abc".toByteArray()),
        )
    }

    @Test
    fun `各种字节输入的两种摘要均与旧格式化编码逐字一致`() {
        val allBytes = ByteArray(256) { it.toByte() }
        for (size in 0..allBytes.size) {
            val bytes = allBytes.copyOf(size)
            val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val sha512 = MessageDigest.getInstance("SHA-512").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(sha256, DirSizer.sha256(bytes), "SHA-256 输入长度 $size")
            assertEquals(sha512, DirSizer.sha512(bytes), "SHA-512 输入长度 $size")
        }
    }
}
