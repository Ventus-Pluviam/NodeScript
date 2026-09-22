package com.autoscript.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.autoscript.appservice.permissioncenter.GrantLauncher
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.GrantResult

/**
 * 授权页去向（[AndroidGrantLauncher] 的映射目标）。抽出这一层是因为
 * `Intent` 在 JVM 上不可构造 —— 把「能力 → 去哪个系统页」这条**判断**放在可测的枚举上，
 * 把「枚举 → Intent → startActivity」这段没有判断的搬运留在 [GrantPageOpener] 后面。
 */
enum class GrantPage {
    /** 设置 → 无障碍（用户在这里启用 AutoScript 的无障碍服务）。 */
    ACCESSIBILITY,
    /** 应用 → 显示在其他应用上层。 */
    OVERLAY,
    /** 应用 → 通知（含 API 33+ 的通知运行时权限总开关）。 */
    NOTIFICATIONS,
    /** 应用 → 闹钟和提醒（API 31+ 才有此页，低版本落到 [APP_DETAILS]）。 */
    EXACT_ALARM,
    /** 应用详情页：没有专门授权页的能力（截屏/root/Shizuku）统一送到这里，不假装有快捷入口。 */
    APP_DETAILS,
}

/**
 * 系统页跳转规格（纯数据，可 JVM 单测）。
 *
 * 为什么除了 [GrantPage] 还要这一层：页枚举只回答"去哪一页"，而真机跳转真正会配错的是
 * action 字面量与 extra 配列 —— 配错了系统直接忽略 extra，表现为"跳过去了但没定位到
 * 本应用"，现场极难查。这一层把"页 → action + extra 规格"钉成数据，由单测锁字面量；
 * [AndroidSettingsPageOpener] 只做"规格 + 包名 → Intent"搬运，不再有判断可错。
 *
 * `Settings.ACTION_*` 全是编译期字符串常量，JVM 单测引用不碰框架
 * （与 `BootEventsTest` 引 `Intent.ACTION_BOOT_COMPLETED` 同理）。
 */
data class SettingsTarget(
    val action: String,
    /** 需要 `Settings.EXTRA_APP_PACKAGE`（通知设置）。 */
    val withAppPackageExtra: Boolean = false,
    /** 需要 `data = package:<包名>`（overlay/精确闹钟/应用详情）。 */
    val withPackageData: Boolean = false,
)

/** 拉起系统页的系统接触面（唯一的 Android 依赖点，也是单测缝）。 */
fun interface GrantPageOpener {
    /** 打开一个系统设置页；回 false = 系统里没有能处理它的页面（**不抛**，由调用方如实上报）。 */
    fun open(page: GrantPage): Boolean
}

/**
 * 授权拉起（docs §9.5）：把 `PermissionCenter.requestGrant` 的请求翻译成一次系统页跳转。
 *
 * **为什么全是系统设置页、没有运行时权限弹窗**：引导文案（`PermissionCenter.guideText`）
 * 对每个能力承诺的都是"请前往设置 → …"，而 P0 的 `:app` 还没有 Activity
 * （UI 尚未落地），`ActivityCompat.requestPermissions` 那条路需要 Activity 才能走。
 * 送到系统设置页是**同一件事**（用户在那里完成授权），且不需要伪造一个 Activity。
 * 真正需要运行时弹窗的场景（API 33+ 通知权限）在设置页里有同一个开关，不重复造路径。
 *
 * **返回值口径（照 GrantResult 的定义，不加戏）**：
 * - 成功把用户送到系统页 = `Deferred` —— "用户切走/系统页未返回，等待回执"，
 *   回前台后由能力中心重查三态（本类不猜用户点了什么）；
 * - 系统里没有可处理的页面（`Intent` 拉不起）= `Denied` —— 这次申请**没能把用户送到授权页**。
 *   如实说明：它不是"用户拒绝"，KDoc 特意点出这一点，免得调用方把两种 DENIED 混为一谈；
 *   能力本身的三态仍由系统查询决定（`AndroidSystemStateReader`），不因这次失败而改变。
 *
 * **本类不做三态判断**（§9.5：`PermissionFacade` 是唯一入口）：什么时候该拉、
 * 拉了之后算不算成功，都在 [com.autoscript.appservice.permissioncenter.PermissionCenter] 那边。
 */
class AndroidGrantLauncher(
    private val opener: GrantPageOpener,
) : GrantLauncher {

    override suspend fun launchGrant(ability: Capability): GrantResult =
        if (opener.open(pageFor(ability))) GrantResult.Deferred else GrantResult.Denied

    override fun openSettings(ability: Capability) {
        opener.open(pageFor(ability))
    }

    companion object {
        /**
         * 能力 → 系统页（§9.5 引导文案逐条对应；`PermissionCenter.guideText` 里写的
         * 「设置 → 应用 → AutoScript → X」就是这里的落点）。
         *
         * `SCREEN_CAPTURE`/`ROOT`/`ADB_INPUT` 没有专门的授权页：截屏授权由首次截图时的
         * MediaProjection 弹窗给出（§9.2），root/Shizuku 由用户在本应用之外准备。
         * 三者一律送应用详情页 —— 不编造一个不存在的快捷入口。
         */
        fun pageFor(ability: Capability): GrantPage = when (ability) {
            Capability.ACCESSIBILITY -> GrantPage.ACCESSIBILITY
            Capability.OVERLAY -> GrantPage.OVERLAY
            Capability.NOTIFICATION, Capability.POST_NOTIFICATIONS -> GrantPage.NOTIFICATIONS
            Capability.SCHEDULE_EXACT_ALARM -> GrantPage.EXACT_ALARM
            Capability.SCREEN_CAPTURE, Capability.ROOT, Capability.ADB_INPUT -> GrantPage.APP_DETAILS
        }

        /**
         * 页 → 跳转规格（action 字面量 + extra 配列，单测锁死）。
         *
         * `sdkInt` 显式参数是为了 JVM 可测：`ACTION_REQUEST_SCHEDULE_EXACT_ALARM` 是 API 31
         * 引入的，低版本系统里没有这个页面 —— 回退到应用详情页，而不是抛异常（老设备上精确闹钟
         * 本来就一直可用，见 `AndroidCapabilityProbes.exactAlarmAllowed` 的同一口径）。
         * 缺省读真 SDK；单测传参钉住高低两分支（无参调在 JVM 上走 S-以下分支）。
         */
        fun specFor(page: GrantPage, sdkInt: Int = Build.VERSION.SDK_INT): SettingsTarget = when (page) {
            GrantPage.ACCESSIBILITY -> SettingsTarget(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            GrantPage.OVERLAY ->
                SettingsTarget(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackageData = true)
            GrantPage.NOTIFICATIONS ->
                SettingsTarget(Settings.ACTION_APP_NOTIFICATION_SETTINGS, withAppPackageExtra = true)
            GrantPage.EXACT_ALARM ->
                if (sdkInt >= Build.VERSION_CODES.S) {
                    SettingsTarget(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, withPackageData = true)
                } else {
                    appDetailsSpec()
                }
            GrantPage.APP_DETAILS -> appDetailsSpec()
        }

        private fun appDetailsSpec(): SettingsTarget =
            SettingsTarget(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackageData = true)
    }
}

/**
 * [GrantPageOpener] 的真机实现：唯一碰 `Intent`/`Settings` 的地方。
 *
 * 全部带 `FLAG_ACTIVITY_NEW_TASK`：调用方是 `applicationContext`（广播/后台路径也会拉起
 * 引导页），没有 Activity 栈可依附；不加这个 flag 会直接 `AndroidRuntimeException`。
 *
 * 本类无判断：去哪一页是 [AndroidGrantLauncher.pageFor] 的事，用什么 action/extra 是
 * [AndroidGrantLauncher.specFor] 的事 —— 这里只做"规格 + 包名 → Intent"搬运。
 */
class AndroidSettingsPageOpener(context: Context) : GrantPageOpener {

    private val appContext = context.applicationContext

    override fun open(page: GrantPage): Boolean {
        return try {
            appContext.startActivity(
                intentFor(AndroidGrantLauncher.specFor(page), appContext.packageName),
            )
            true
        } catch (e: Exception) {
            // 系统里没有能处理该 action 的页面（ROM 裁剪/低版本）。这是真实失败，
            // 如实回 false 让调用方上报 Denied，不静默当成功。
            false
        }
    }

    private fun intentFor(spec: SettingsTarget, packageName: String): Intent {
        return Intent(spec.action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (spec.withAppPackageExtra) {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            if (spec.withPackageData) {
                data = Uri.parse("package:$packageName")
            }
        }
    }
}
