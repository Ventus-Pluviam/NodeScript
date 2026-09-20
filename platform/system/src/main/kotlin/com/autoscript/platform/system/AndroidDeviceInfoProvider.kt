package com.autoscript.platform.system

import com.autoscript.domain.system.DeviceInfoProvider
import com.autoscript.domain.system.DeviceProfile

/**
 * `device` 命名空间的 Android 实现（docs §9.6；SPI 见 `:domain` 的 [DeviceInfoProvider]）。
 *
 * P0 只兑现 `auto.device.model` / `auto.device.sdkInt` 两项（§12.3 最小集），
 * 其余字段（品牌/分辨率/内存/存储）是 P2，落在这里会变成"契约没有、实现先有"，
 * 所以不提前加。
 *
 * [DeviceProfile] 的构造器自带守卫（型号非空、SDK ≥ 1）：`Build.MODEL` 在极少数
 * 定制 ROM 上确实是空串，那种设备上**如实抛** [IllegalArgumentException] 比回一个
 * 空型号更有用 —— 脚本侧能立刻看出"这台机器读不到型号"，而不是拿到 `""` 去拼日志。
 */
class AndroidDeviceInfoProvider(
    private val model: () -> String = { android.os.Build.MODEL },
    private val sdkInt: () -> Int = { android.os.Build.VERSION.SDK_INT },
) : DeviceInfoProvider {

    override fun profile(): DeviceProfile = DeviceProfile(model = model(), sdkInt = sdkInt())
}
