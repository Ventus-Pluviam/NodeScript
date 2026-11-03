package com.autoscript.appservice.permissioncenter

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityLifecycle
import com.autoscript.domain.permission.CapabilityState
import com.autoscript.domain.permission.GrantResult
import com.autoscript.domain.permission.PermissionFacade
import kotlinx.coroutines.CancellationException

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
 * 权限三态门禁实现（docs §9.5）：
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
        } catch (e: CancellationException) {
            throw e        // 取消不是"读不到状态"：折成 DEGRADED 会把取消谎报成三态之一
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
        /**
         * 能力引导文案（`CapabilityRow.guide` 与 [ensure] 的拒绝 detail 共用**同一份**）。
         *
         * **与三态无关**（backlog A8 的裁定）：每条只回答「这项能力是干什么的 +
         * 怎么让它可用」，**不按拒绝态起句**。于是同一份文案在 GRANTED/DEGRADED/DENIED
         * 三态下都成立，呈现层不需要按态猜该不该显示它 —— 这正是
         * `CapabilityRow.guide` 那句「呈现层不按三态去猜」要的数据形态。
         *
         * 反面教材是 A8 的现场：文案写死「精确闹钟未允许：请前往…」，而同一行的三态读数
         * 是「可用」；`CapabilityRow.guide` 的 KDoc 描述的是**意图**（文案自带有当前态那句），
         * 而八条里六条是按拒绝态写的 —— 意图与数据对不上。改数据这一头（显示逻辑不动）。
         *
         * 另一条纪律：文案里承诺的行为必须与代码一致。（§9.3 三通道三选一）之后
         * **不存在降级链** —— 指定 adb/root 而该通道不可用就是 `ERR_PERMISSION_DENIED`，
         * 绝不改用别的通道，所以 `ADB_INPUT` 那条不能再写「未就绪时输入走无障碍手势」。
         */
        fun guideText(ability: Capability): String = when (ability) {
            Capability.ACCESSIBILITY ->
                "无障碍服务：读界面树、执行点击与滑动的通道。" +
                    "在「设置 → 无障碍 → AutoScript」开启；未开启时 a11y.* 如实回 " +
                    "ERR_SERVICE_DISABLED —— 这条通道没有替代路径"
            Capability.SCREEN_CAPTURE ->
                "屏幕采集：截屏帧源。无障碍截图通道随无障碍服务可用（无需录屏授权，333ms 节流）；" +
                    "MediaProjection 高清会话接入后，首次会话由系统弹录屏授权，同意后本会话可用"
            Capability.OVERLAY ->
                "悬浮窗：脚本对话框浮在其他应用之上。" +
                    "在「设置 → 应用 → AutoScript → 悬浮窗/显示在其他应用上层」开启；" +
                    "未开启时对话框走通知回调降级路径，a11y 服务在跑时也经可信窗口显示"
            Capability.NOTIFICATION ->
                "通知（任务提醒）：任务状态与提醒的送达通道。" +
                    "在「设置 → 应用 → AutoScript → 通知」开启；" +
                    "未开启时任务提醒不可达，其余功能不受影响"
            Capability.SCHEDULE_EXACT_ALARM ->
                "精确闹钟：定时任务按点触发。" +
                    "在「设置 → 应用 → AutoScript → 闹钟和提醒」允许；" +
                    "未允许时定时任务降级为 setWindow（可能偏差，任务中心逐条标注）"
            Capability.ROOT ->
                "root：a11y 的 root 输入通道（`su -c` 注入与 shell）。" +
                    "由用户在本应用之外准备（设备上 `su` 可用即可）；探测不到时该通道如实回 " +
                    "ERR_PERMISSION_DENIED —— 不会改用别的通道"
            Capability.ADB_INPUT ->
                "ADB 输入（Shizuku）：a11y 的 adb 输入通道，注入事件的进程身份是 shell。" +
                    "需先安装 Shizuku、启动它，并在其中授权本应用；" +
                    "未就绪时该通道如实回 ERR_PERMISSION_DENIED —— 不会改用别的通道"
            Capability.POST_NOTIFICATIONS ->
                "通知发送权限：脚本主动发通知（`auto.notification.post`）。" +
                    "在系统设置里允许通知；未允许时 post 抛 ERR_PERMISSION_DENIED，" +
                    "任务完成提醒静默丢弃并在 UI 明示"
            Capability.USAGE_ACCESS ->
                "使用情况访问权限：`auto.app.currentPackage` 读当前前台应用。" +
                    "在「设置 → 应用 → 特殊访问权限 → 使用情况访问权限」允许 AutoScript；" +
                    "未允许时如实返回 null"
        }
    }
}
