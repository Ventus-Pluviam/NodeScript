package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * lockfile 带外签名（docs §10.5-1 · `files/.ns/lock.sig`）。
 *
 * 防的是什么：npm 的 lock integrity 只锁**注册表内容**（tarball sha512），锁不住
 * 「这份 lock 是不是本机认可的」——第三方/市场项目塞一份把包名指到别处的 lock，
 * integrity 照样成立。故 App 侧对 lock 做 HMAC 签名，`npm ci` 前验签。
 *
 * 私钥来源是接缝（[KeyProvider]）：设计口径是 Android Keystore 包装的应用密钥
 * （密钥丢失 = 显式「安全降级」**失败**，不静默放行）；本类纯 JVM 可单测。
 * **注意：本仓当前没有任何 [KeyProvider] 实现，生产装配传 `null`（`NpmShellKit`），
 * 即这套签名在生产路径上还没生效** —— 见 `docs/design/11-security.md` §11.3 第 8 条。
 * 算法 HMAC-SHA256（§10.5 允许 HMAC/ECDSA 二选一，P0 取可实现且可离线验证的）。
 *
 * 验签失败语义（诚实优先）：抛 [AutojsException] 且错误码 ERR_PERMISSION_DENIED ——
 * 调用方（InstallCoordinator.ci）据此拒绝「按这份 lock 重建」。缺失签名文件
 * 同样视为失败（TOFU 自签正是被批判的形态），首次签名由 [sign] 显式完成。
 *
 * **落盘格式是严格的**：只认 `v1 <64 位小写 hex>` 这一种写法（[FILE_FORMAT]）。
 * 前缀不识、大小写混写、裸 hex（没有版本标签）一律拒 —— 「版本前缀」是将来换
 * ECDSA 的兼容开关，宽松解析会把这个开关悄悄废掉（裸 hex 恰好等于正确 HMAC 时，
 * 宽松写法会放行一份没有版本语义的文件）。写侧同理：先写 `lock.sig.tmp` 再原子
 * rename，避免「写到一半崩溃留下半截签名」这种既非旧态也非新态的文件。
 */
class LockSigner(
    private val dir: Path,
    private val key: KeyProvider,
) {

    /** lock.sig 的字节布局：`v1 <hex>`（算法版本前缀，便于将来换 ECDSA 而不破坏旧文件）。 */
    private val file: Path = dir.resolve("lock.sig")

    /**
     * 应用密钥接缝（Android Keystore / 测试用固定字节）。
     *
     * **形状给的是 [SecretKey] 句柄，不是 `ByteArray`**（2026-10-01 拍板，backlog A1c）：
     * Keystore 里的密钥材料**不出库**（`SecretKey.getEncoded()` 拿不到字节），签名只能在库内完成 ——
     * 原先那种「交出密钥字节」的形状**接不上** Keystore。给句柄则两边都成立：
     * Keystore 的 HMAC 密钥（`KeyProperties.KEY_ALGORITHM_HMAC_SHA256`，API 23+；minSdk 26 ✓）
     * 与测试用的固定字节密钥都能直接喂 `Mac.init(SecretKey)`。
     * 语义一字不变：仍是 HMAC-SHA256，`v1 <hex>` 落盘格式与原子写都不动。
     *
     * 实现落点口径（同批拍板）：Keystore 版住 `:app` 装配层（Composition Root 已依赖本模块，
     * 零契约变更）；本模块仍零 `android.*`（archUnit 守护）。
     */
    fun interface KeyProvider {
        fun secretKey(): SecretKey
    }

    /**
     * 对项目 lockfile 签名（待签体含 projectId 与 lock 内容摘要，**能**验出跨项目搬锁）。
     *
     * **「防跨项目搬锁」这个说法此前写在这里，它只对了一半**（2026-10-12 订正，外审 M9；
     * 本条经维护者裁定**不做**，这里只订正事实、不改行为）：待签体 `bodyOf` 确实带了
     * `projectId`，所以「拿 A 的 lock 去 B 项目验签」会**验不过** —— 这一点成立。
     * 但落盘只有**一个** `files/.ns/lock.sig`（路径里没有项目维度，见 [file]），
     * 于是「签 B 废 A」：给任一项目签名都覆盖同一份文件，之后对**其他**项目验签必然失败。
     * 可用性代价真实存在，且随项目数增长（多项目共存时只能签一个、其余全拒）。
     * 彻底解是「每项目一份 `lock.sig`」，属行为变更、需重新拍板。
     *
     * 落位是**原子**的：写临时文件 → `force(true)` → `ATOMIC_MOVE` 覆盖。崩溃只可能落在
     * 「新内容还没 rename」或「已 rename」两态之一，不会读到半截签名（半截签名验不过，
     * 会让用户面对一个无法解释的 `ERR_PERMISSION_DENIED`）。
     */
    fun sign(projectId: String, lockfile: Path) {
        val body = bodyOf(projectId, lockfile)
        val sig = hmac(body)
        Files.createDirectories(dir)
        val tmp = dir.resolve(TMP_NAME)
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { ch ->
            ch.write(ByteBuffer.wrap((PREFIX + " " + sig + "\n").toByteArray(StandardCharsets.UTF_8)))
            // rename 之前先把内容钉到盘上：否则崩溃后可能 rename 成功而内容还在页缓存里。
            ch.force(true)
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        fsyncDir()
    }

    /**
     * 验签：通过则静默返回（调用方继续）；失败抛 AutojsException(ERR_PERMISSION_DENIED)。
     * 文件缺失/前缀不识/签名不符一律拒——**不是**「没签就跳过」。
     */
    fun verifyOrThrow(projectId: String, lockfile: Path) {
        if (!Files.exists(lockfile)) {
            throw AutojsException(
                ErrorCode.ERR_FILE_NOT_FOUND, "lockfile 不存在，无法验签重建: $lockfile",
            )
        }
        if (!Files.exists(file)) {
            throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "lock.sig 缺失：该项目的 lockfile 未经本机签名，npm ci 拒绝执行（§10.5-1 带外信任锚）",
            )
        }
        val stored = String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim()
        val hex = FILE_FORMAT.matchEntire(stored)?.groupValues?.get(1)
            ?: throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "lock.sig 格式不认识（只认 `$PREFIX <64 位小写 hex>`）：拒绝按这份 lock 重建",
            )
        val expect = hmac(bodyOf(projectId, lockfile))
        if (!constantTimeEquals(hex, expect)) {
            throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "lock.sig 验签失败：lockfile 与签名不匹配（被改过/换过/跨项目搬运）",
            )
        }
    }

    /** 待签体 = 算法标签 + projectId + lock 内容（顺序固定，防拼接歧义）。 */
    private fun bodyOf(projectId: String, lockfile: Path): ByteArray {
        val lock = Files.readAllBytes(lockfile)
        val pre = ("autoscript-lock-v1|" + projectId + "|").toByteArray(StandardCharsets.UTF_8)
        return pre + MessageDigest.getInstance("SHA-256").let { md ->
            md.update(lock); md.digest()
        }
    }

    private fun hmac(body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key.secretKey())
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /**
     * 目录 fsync：把「目录项已指向新文件」这件事也钉到盘上（rename 本身的持久化边界）。
     * 打不开目录 fd 时（非 POSIX 文件系统）不致命：最坏情形是崩溃后仍看到**旧的**签名，
     * 而旧签名对不上新 lock → 验签失败 → fail-closed，不会静默放行。
     */
    private fun fsyncDir() {
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // 见 KDoc：失败方向是 fail-closed，不静默放行。
        }
    }

    private companion object {
        const val PREFIX = "v1"
        const val TMP_NAME = "lock.sig.tmp"

        /** 唯一认可的落盘形态：`v1 ` + 64 位小写 hex（HMAC-SHA256 的 hex 长度固定）。 */
        val FILE_FORMAT = Regex("""v1 ([0-9a-f]{64})""")
    }
}
