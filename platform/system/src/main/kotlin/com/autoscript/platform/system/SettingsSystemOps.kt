package com.autoscript.platform.system

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.autoscript.domain.storage.SystemSettings

/**
 * [AndroidSystemSettings.Ops] 的真机实现（docs §9.6）：唯一碰
 * `android.provider.Settings` 的地方。门禁判断（canWrite false 时抛什么）
 * 在宿主 [AndroidSystemSettings] 里 —— 这里只有「问/写 + 把系统异常折成
 * 契约形状」：
 *
 * - [SystemSettings.getString]/[getInt]：`SettingNotFoundException` = 键不存在
 *   → null（缺键是常态；异常折叠点在此，本机 JVM 测试因此碰不到它）；
 * - [getInt] 不给默认值：0 是合法亮度，拿 0 冒充缺失就分不开了；
 * - [canWrite]：API 23+ `Settings.System.canWrite`；更低版本系统还没有
 *   WRITE_SETTINGS 这道门 → 视为可写（minSdk 26 之下永不发生，留着是防回退）。
 *
 * 本机 JVM **只编译不执行**（静态调用 + Context，stub 运行期抛异常）。
 */
class SettingsSystemOps(private val context: Context) : AndroidSystemSettings.Ops {

    override fun canWrite(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }

    override fun getString(key: String): String? =
        Settings.System.getString(context.contentResolver, key)

    override fun getInt(key: String): Int? = try {
        Settings.System.getInt(context.contentResolver, key)
    } catch (_: Settings.SettingNotFoundException) {
        null
    }

    override fun putString(key: String, value: String): Boolean =
        Settings.System.putString(context.contentResolver, key, value)

    override fun putInt(key: String, value: Int): Boolean =
        Settings.System.putInt(context.contentResolver, key, value)
}
