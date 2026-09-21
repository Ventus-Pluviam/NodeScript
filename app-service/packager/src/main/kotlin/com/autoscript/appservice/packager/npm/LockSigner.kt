package com.autoscript.appservice.packager.npm

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * lockfile 带外签名（docs/framework-design.md §10.5-1 · `files/.autojs/lock.sig`）。
 *
 * 防的是什么：npm 的 lock integrity 只锁**注册表内容**（tarball sha512），锁不住
 * 「这份 lock 是不是本机认可的」——第三方/市场项目塞一份把包名指到别处的 lock，
 * integrity 照样成立。故 App 侧对 lock 做 HMAC 签名，`npm ci` 前验签。
 *
 * 私钥来源是接缝（[KeyProvider]）：生产注入 Android Keystore 包装的应用密钥
 * （密钥丢失 = 显式「安全降级」**失败**，不静默放行）；本类纯 JVM 可单测。
 * 算法 HMAC-SHA256（§10.5 允许 HMAC/ECDSA 二选一，P0 取可实现且可离线验证的）。
 *
 * 验签失败语义（诚实优先）：抛 [AutojsException] 且错误码 ERR_PERMISSION_DENIED ——
 * 调用方（InstallCoordinator.ci）据此拒绝「按这份 lock 重建」。缺失签名文件
 * 同样视为失败（TOFU 自签正是被批判的形态），首次签名由 [sign] 显式完成。
 */
class LockSigner(
    private val dir: Path,
    private val key: KeyProvider,
) {

    /** lock.sig 的字节布局：`v1 <hex>`（算法版本前缀，便于将来换 ECDSA 而不破坏旧文件）。 */
    private val file: Path = dir.resolve("lock.sig")

    /** 应用密钥接缝（Android Keystore / 测试用固定字节）。 */
    fun interface KeyProvider {
        fun keyBytes(): ByteArray
    }

    /** 对项目 lockfile 签名（覆盖内容 + projectId，防跨项目搬锁）。 */
    fun sign(projectId: String, lockfile: Path) {
        val body = bodyOf(projectId, lockfile)
        val sig = hmac(body)
        Files.createDirectories(dir)
        Files.write(file, ("v1 " + sig + "\n").toByteArray(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE).use { it.force(true) }
    }

    /**
     * 验签：通过则静默返回（调用方继续）；失败抛 AutojsException(ERR_PERMISSION_DENIED)。
     * 文件缺失/前缀不识/签名不符一律拒——**不是**「没签就跳过」。
     */
    fun verifyOrThrow(projectId: String, lockfile: Path) {
        if (!Files.exists(lockfile)) {
            throw com.autoscript.domain.core.AutojsException(
                com.autoscript.domain.core.ErrorCode.ERR_FILE_NOT_FOUND, "lockfile 不存在，无法验签重建: $lockfile",
            )
        }
        if (!Files.exists(file)) {
            throw com.autoscript.domain.core.AutojsException(
                com.autoscript.domain.core.ErrorCode.ERR_PERMISSION_DENIED,
                "lock.sig 缺失：该项目的 lockfile 未经本机签名，npm ci 拒绝执行（§10.5-1 带外信任锚）",
            )
        }
        val stored = String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim()
        val hex = stored.removePrefix("v1").trim()
        val expect = hmac(bodyOf(projectId, lockfile))
        if (hex.isEmpty() || !constantTimeEquals(hex, expect)) {
            throw com.autoscript.domain.core.AutojsException(
                com.autoscript.domain.core.ErrorCode.ERR_PERMISSION_DENIED,
                "lock.sig 验签失败：lockfile 与签名不匹配（被改过/换过/跨项目搬运）",
            )
        }
    }

    /** 待签体 = 算法标签 + projectId + lock 内容（顺序固定，防拼接歧义）。 */
    private fun bodyOf(projectId: String, lockfile: Path): ByteArray {
        val lock = Files.readAllBytes(lockfile)
        val pre = ("autojs-lock-v1|" + projectId + "|").toByteArray(StandardCharsets.UTF_8)
        return pre + MessageDigest.getInstance("SHA-256").let { md ->
            md.update(lock); md.digest()
        }
    }

    private fun hmac(body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.keyBytes(), "HmacSHA256"))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
