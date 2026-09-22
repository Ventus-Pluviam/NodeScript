package com.autoscript.platform.system

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.storage.SystemSettings

/**
 * `settings` 的 Android 实现（docs §9.6；SPI 见 `:domain` 的 [SystemSettings]，
 * 语义层 handler 待建 `:platform:capabilities`）。分层照 README ops 表：
 * Android 接触面只有 [Ops] 缝（真机 [SettingsSystemOps] 碰 `android.provider.Settings`），
 * 本类留**本机 JVM 可测**的语义：
 *
 * 1. **空白键拒写**（require 在触 ops 之前，垃圾进不了 ContentResolver）；
 * 2. **写前 canWrite 门**：未授 `WRITE_SETTINGS` → `ERR_PERMISSION_DENIED`
 *    （§9.5：授权问题是分类错误，不是 false）；
 * 3. **已授权仍被拒**（`put*` 回 false：键被保护/ROM 裁剪）→ `ERR_IO`；
 * 4. 读侧缺失如实 null（ops 把 `SettingNotFoundException` 折成 null ——
 *    折叠点在 Android ops 文件里，本类只透传）。
 */
class AndroidSystemSettings(
    private val ops: Ops,
) : SystemSettings {

    override fun canWrite(): Boolean = ops.canWrite()

    override fun getString(key: String): String? {
        requireNonBlank(key)
        return ops.getString(key)
    }

    override fun getInt(key: String): Int? {
        requireNonBlank(key)
        return ops.getInt(key)
    }

    override fun putString(key: String, value: String) {
        requireNonBlank(key)
        ensureWritable(key)
        if (!ops.putString(key, value)) {
            throw AutojsException(ErrorCode.ERR_IO, "系统拒绝写入设置: $key（已授权仍被拒，键可能受保护）", null)
        }
    }

    override fun putInt(key: String, value: Int) {
        requireNonBlank(key)
        ensureWritable(key)
        if (!ops.putInt(key, value)) {
            throw AutojsException(ErrorCode.ERR_IO, "系统拒绝写入设置: $key（已授权仍被拒，键可能受保护）", null)
        }
    }

    private fun ensureWritable(key: String) {
        if (!ops.canWrite()) {
            throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "系统设置未授权写入（WRITE_SETTINGS 未授予）: $key",
                null,
            )
        }
    }

    private fun requireNonBlank(key: String) {
        require(key.isNotBlank()) { "settings key 不得为空白，实际 \"$key\"" }
    }

    /**
     * 设置读写接触面（README ops 表的本行）：真机 [SettingsSystemOps]；
     * 单测注入内存替身 —— `android.provider.Settings` 是静态调用，stub 运行期抛异常。
     * [getInt] 回 null = 键不存在（ops 负责把 `SettingNotFoundException` 折成 null）。
     */
    interface Ops {
        fun canWrite(): Boolean
        fun getString(key: String): String?
        fun getInt(key: String): Int?
        fun putString(key: String, value: String): Boolean
        fun putInt(key: String, value: Int): Boolean
    }
}
