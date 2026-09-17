package com.autoscript.domain.permission

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/** 能力清单（草案）。P0 覆盖：无障碍、悬浮窗、通知、精确闹钟、存储；其余随模块扩展。 */
enum class Capability {
    ACCESSIBILITY,
    SCREEN_CAPTURE,
    OVERLAY,
    NOTIFICATION,
    SCHEDULE_EXACT_ALARM,
    ROOT,
    ADB_INPUT,
    POST_NOTIFICATIONS,
}

/**
 * 三态门禁（docs/framework-design.md §9.5）。
 * - GRANTED：系统授予且当前可用（含会话型 MediaProjection 已激活）；
 * - DEGRADED：可降级但受限（a11y 节流、无 root、BAL、电池未豁免…）；
 * - DENIED：被用户/系统拒绝 → 调用抛 ERR_PERMISSION_DENIED。
 */
enum class CapabilityState { GRANTED, DEGRADED, DENIED }

/** 授权申请结果：UI 引导页驱动，返回后能力中心刷新三态。 */
sealed interface GrantResult {
    data object Granted : GrantResult
    data object Denied : GrantResult
    data object Deferred : GrantResult          // 用户切走/系统页未返回，等待 Activity 结果
}

/**
 * 唯一权限入口（§9.5）：所有模块**不得**直接查 Settings/ActivityCompat，
 * 一律经本门禁（可 Mock）。实现位于 :app-service:permission-center。
 */
interface PermissionFacade {
    suspend fun state(ability: Capability): CapabilityState

    /** 需要即保证可用：DENIED 且可引导 → 抛 ERR_PERMISSION_DENIED（Detail 含引导文案）；GRANTED/DEGRADED 如实返回。 */
    suspend fun ensure(ability: Capability): CapabilityState

    /** 拉起系统授权页/能力中心引导。UI 线程不得直调，经协调器。 */
    suspend fun requestGrant(ability: Capability): GrantResult

    /** 一键跳转系统设置页（能力中心 UI 用）。 */
    fun openSystemSettings(ability: Capability)
}

// —— 便利守卫：能力未达标时按契约抛错 ——
suspend fun PermissionFacade.requireGrantedOrThrow(
    ability: Capability,
    action: String,
): CapabilityState {
    val s = ensure(ability)
    if (s == CapabilityState.DENIED) {
        throw AutojsException(ErrorCode.ERR_PERMISSION_DENIED, "能力 $ability 不可用，无法 $action")
    }
    return s
}