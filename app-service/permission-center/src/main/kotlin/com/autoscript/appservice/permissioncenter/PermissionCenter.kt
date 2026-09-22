package com.autoscript.appservice.permissioncenter

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityLifecycle
import com.autoscript.domain.permission.CapabilityState
import com.autoscript.domain.permission.GrantResult
import com.autoscript.domain.permission.PermissionFacade

/**
 * 系统状态读取（Android 实现由 :app 装配：Settings/AccessibilityManager/MediaProjection
 * 会话态等的只读查询；本模块不直连 Android，见 ArchitectureTest）。
 */
fun interface SystemStateReader {
    suspend fun readSystemState(ability: Capability): CapabilityState
}

/**
 * 授权拉起（Android 实现由 :app 装配：startActivity 拉系统授权页/引导页 + 回调；
 * [openSettings] 跳转应用详情/系统设置页。纯 UI/系统交互，本模块只做编排）。
 */
interface GrantLauncher {
    suspend fun launchGrant(ability: Capability): GrantResult
    fun openSettings(ability: Capability)
}

/**
 * 权限三态门禁实现（docs/framework-design.md §9.5）：
 * 唯一的权限入口——所有模块不得直接查 Settings/ActivityCompat，一律经此门禁（可 Mock）。
 *
 * - [state] 直读系统态；读取异常（ROM 奇异实现/查询崩溃）诚实降级为 DEGRADED
 *   （可用性未知即受限），绝不伪造 GRANTED；
 * - [ensure] DENIED 即抛 ERR_PERMISSION_DENIED，detail 携带能力中心引导文案
 *   （引导页消费）；GRANTED/DEGRADED 如实返回，降级路径由各能力自行判断；
 * - [requestGrant] 已 GRANTED 时直接回 Granted（不再打扰用户拉起系统页）；
 *   否则委托 [GrantLauncher]，结果原样返回（Deferred 由 UI 层等待 Activity 结果后刷新）。
 */
class PermissionCenter(
    private val reader: SystemStateReader,
    private val launcher: GrantLauncher,
) : PermissionFacade {

    override suspend fun state(ability: Capability): CapabilityState {
        return try {
            reader.readSystemState(ability)
        } catch (e: Exception) {
            CapabilityState.DEGRADED
        }
    }

    override suspend fun ensure(ability: Capability): CapabilityState {
        val s = state(ability)
        if (s == CapabilityState.DENIED) {
            throw AutojsException(ErrorCode.ERR_PERMISSION_DENIED, guideText(ability))
        }
        return s
    }

    override suspend fun requestGrant(ability: Capability): GrantResult {
        if (!CapabilityLifecycle.canRequestGrant(state(ability))) return GrantResult.Granted
        return launcher.launchGrant(ability)
    }

    override fun openSystemSettings(ability: Capability) {
        launcher.openSettings(ability)
    }

    companion object {
        /** 能力中心引导文案（P0 引导页消费：告诉用户去哪里开、开了之后是什么态）。 */
        fun guideText(ability: Capability): String = when (ability) {
            Capability.ACCESSIBILITY ->
                "无障碍服务未开启：请前往「设置 → 无障碍 → AutoScript」开启，开启后能力为 GRANTED"
            Capability.SCREEN_CAPTURE ->
                "屏幕采集：无障碍截图通道随无障碍服务可用（无需录屏授权，333ms 节流）；" +
                    "MediaProjection 高清会话接入后，首次会话将弹系统录屏授权，同意后本会话 GRANTED"
            Capability.OVERLAY ->
                "悬浮窗权限未授予：请前往「设置 → 应用 → AutoScript → 悬浮窗/显示在其他应用上层」开启；未开启时对话框走通知回调降级路径"
            Capability.NOTIFICATION ->
                "通知权限未授予：请前往「设置 → 应用 → AutoScript → 通知」开启；未开启时任务提醒不可达"
            Capability.SCHEDULE_EXACT_ALARM ->
                "精确闹钟未允许：请前往「设置 → 应用 → AutoScript → 闹钟和提醒」允许；未允许时定时任务降级为 setWindow（可能偏差，UI 标注）"
            Capability.ROOT ->
                "未检测到 root：root 相关能力（sendevent 输入等）不可用，不降级渲染为禁用"
            Capability.ADB_INPUT ->
                "ADB 输入（Shizuku）未就绪：请确保 Shizuku 运行且已授权 AutoScript；未就绪时输入走无障碍手势"
            Capability.POST_NOTIFICATIONS ->
                "通知发送未允许：请前往系统设置允许通知；拒绝后任务完成提醒静默丢弃并在 UI 明示"
        }
    }
}
