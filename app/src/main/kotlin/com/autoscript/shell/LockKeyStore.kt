package com.autoscript.shell

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.autoscript.appservice.npm.LockSigner
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 应用密钥的 **get-or-create 判定**（§10.5-1 · §11.3 第 3/8 条）。
 *
 * 这一层是纯 JVM 的：Keystore 的调用被收在 [HmacKeys] 那条缝后面，故
 * 「已存在就取、拿不到就报错、绝不静默重建」这三条判定可单测 ——
 * 而它们正是这道防线最容易写错的地方（见 [resolve] 的 KDoc）。
 *
 * 与 [LockSigner.KeyProvider] 的关系：那个是**签名时刻**的接缝（给句柄不交字节，
 * 因为 Keystore 里 `getEncoded()` 拿不到材料）；本类是**装配时刻**的取钥判定。
 */
object LockKeyStore {

    /**
     * 别名带 `v1`：落盘签名格式已是 `v1 <hex>`（[LockSigner] 的版本前缀是将来换 ECDSA
     * 的兼容开关）。密钥别名与它同版本号，换算法时**换一把新密钥**而不是让旧密钥
     * 去签新格式 —— 否则「v1 签名验不过」与「算法换代」两件事在诊断上分不开。
     */
    const val ALIAS = "autoscript.lock.hmac.v1"

    /** 256 位 = HMAC-SHA256 的输出长度；低于它就削掉了签名强度，不做。 */
    private const val KEY_BITS = 256

    /**
     * 取应用密钥，没有就建一把。
     *
     * **「取不到」一律不静默重建**：三种取法各有各的失败方向，
     * 混成「建一把新的」会把它们全变成同一种灾难 ——
     * - **锁被换过 / 库失效**（`KeyPermanentlyInvalidatedException`）：重建 = 新签名盖住
     *   旧签名，用户看不出「这份 lock 曾被换过」；
     * - **Keystore 整体不可用**（锁屏态、厂商 ROM 抽风、`loadStore` 抛）：重建会把
     *   所有项目已有签名变成一律验不过，用户只看到一堆 [LockSigner] 的 `ERR_PERMISSION_DENIED`。
     * 两条方向都是**显式失败**（抛出去由装配层记账）比「换把钥匙继续」诚实。
     *
     * 只有**真的没有这把钥匙**（首次运行）才建。判据是 [HmacKeys.load] 返回 null ——
     * 「不存在」与「取不动」在缝上就是两种返回，不需要在调用方猜。
     */
    fun resolve(keys: HmacKeys, alias: String = ALIAS): LockSigner.KeyProvider =
        LockSigner.KeyProvider {
            val existing = keys.load(alias)
            if (existing != null) existing else keys.create(alias)
        }

    /**
     * Keystore 的最小面（`load` / `create`）。**只有它知道 `android.`** ——
     * [resolve] 与它的 JVM 单测都不碰 Android（`:app` 无 Robolectric）。
     */
    interface HmacKeys {
        /** 取已存在的 HMAC 密钥；**null = 别名下没有这把钥匙**（不是「取不动」）。 */
        fun load(alias: String): SecretKey?

        /** 新建一把并落进 Keystore；失败照抛（不返回 null 假装没有）。 */
        fun create(alias: String): SecretKey
    }

    /**
     * 真实现（Android Keystore，`AndroidKeyStore` 提供者）。
     *
     * 密钥材料**永不出库**：`getEncoded()` 在 Keystore 密钥上返回 null，故签名只能在
     * 库内完成 —— 这正是 [LockSigner.KeyProvider] 给句柄而非字节的原因（§11.3 第 3 条）。
     *
     * 生成参数两处要点：
     * - `PURPOSE_SIGN or PURPOSE_VERIFY`：HMAC 密钥在 Keystore 里**没有加密语义**，
     *   `ENCRYPT_MODE` 对它是错的（HMAC 密钥不支持）；`Mac.init` 验的就是这两条用途。
     * - `BLOCK_MODE_GCM` + `PADDING_NONE`：Keystore 的通用模板要求（HMAC 不用它们，
     *   但参数要写全，否则部分 ROM 上 `generateKey` 直接抛）。
     *
     * 关键条 `setUserAuthenticationRequired(false)` 写在**明面上**而不是靠默认：
     * 这道签名要能在无手势的息屏态下跑（守时安装任务），但它不是用户可见的信任决策 ——
     * 真正的门是 §10.5-1 的「lock 必须由本机签过」，不是签的时候有没有人解锁。
     */
    object AndroidKeystore : HmacKeys {
        private const val PROVIDER = "AndroidKeyStore"

        override fun load(alias: String): SecretKey? {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            val entry = store.getEntry(alias, null) as? KeyStore.SecretKeyEntry ?: return null
            return entry.secretKey
        }

        override fun create(alias: String): SecretKey =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setKeySize(KEY_BITS)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
            }.generateKey()
    }
}
