package com.autoscript.ui.state

import com.autoscript.domain.host.CapabilityCenterSnapshot
import com.autoscript.domain.host.CapabilityRow
import com.autoscript.domain.host.InstallSize
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityState

/**
 * 能力中心呈现态（纯数据，Compose 之外可 JVM 测）。
 *
 * 三态各自**怎么说给用户听**是呈现层的事（`:domain` 只给 `GRANTED/DEGRADED/DENIED`
 * 三个字面量），但映射本身必须在可测面上 —— 把 DENIED 渲染成"未开启"会让用户以为
 * 「还没开」，而实际是「被拒了，要去系统里改」，两者该做的事不一样。
 *
 * 四条诚实规则（与 [HomeState] 同一条纪律）：
 * - [load] = [LoadState.NotLoaded] 时 [rows] 为空 —— **不冒充**「一个能力都没有」
 *   （那是 [LoadState.Loaded] 且清单为空；而 `Capability.entries` 恒非空，
 *   所以"loaded 且空"在现实中只会是装配异常，渲染上仍按"真的没有"处理）；
 * - 引导文案（`PermissionCenter.guideText` 的同一份）仍在 [CapabilityRowState.guide]
 *   **原样透传**、不截断不加工；批 47 起设置页**不再渲染**它（用户口径「去除各个权限的
 *   描述」）—— 撤下的是渲染，字段与透传照旧；
 * - 「去授权」按钮的显隐取 [CapabilityRow.canRequestGrant]（`:domain`
 *   `CapabilityLifecycle` 的判据）—— 呈现层不自己写 `state != GRANTED`，那是第二套判据；
 * - 降级中的定时任务**单列一段**（§8.6 承诺「可能偏差」要在 UI 上标注）：它们不是
 *   权限问题，塞进能力行里会让人找不到；
 * - 安装体积的字段与换算照旧（§15 E1 的记账口径不变，`text()` 仍由测试钉着，
 *   没量到 = null 而不是 0）；批 47 起设置页**不再渲染**那句「安装体积 xxx」
 *   （用户口径「安装体积xxx那个文字去掉」）—— UI 披露面的撤下见
 *   `design-decisions.md` 的「已推翻 / 已改口径」表，预算记账不受影响。
 */
data class CapabilityCenterState(
    val load: LoadState,
    val rows: List<CapabilityRowState>,
    val degradedAlarmTaskIds: List<String>,
    val installSize: InstallSizeState?,
) {
    companion object {
        /** 首帧哨兵：没读到过（**不是**"读成功但为空"，见类 KDoc）。 */
        val NOT_LOADED = CapabilityCenterState(
            load = LoadState.NotLoaded,
            rows = emptyList(),
            degradedAlarmTaskIds = emptyList(),
            installSize = null,
        )

        /** 读取成功。 */
        fun of(snapshot: CapabilityCenterSnapshot): CapabilityCenterState = CapabilityCenterState(
            load = LoadState.Loaded,
            rows = snapshot.rows.map { CapabilityRowState.of(it) },
            degradedAlarmTaskIds = snapshot.degradedAlarmTaskIds,
            // null（没量到）原样传下去：UI 那一条「未量到」与「量到了 0」必须长得不一样。
            installSize = snapshot.installSize?.let { InstallSizeState.of(it) },
        )

        /** 读取失败（**保留原异常文案**，见 [LoadState.of]）。 */
        fun failed(t: Throwable): CapabilityCenterState = CapabilityCenterState(
            load = LoadState.of(t),
            rows = emptyList(),
            degradedAlarmTaskIds = emptyList(),
            installSize = null,
        )
    }
}

/**
 * 一行能力的呈现态。
 *
 * @property tone 三态该用哪一档色（判读在 [of]，色值在主题）—— 旧版把这个判读写成
 *   `CapabilityScreen` 里一个私有 `stateColor()`，既不可测也不可复用。
 * @property stateLabel 三态的中文说法，逐态不同，因为"用户该做什么"逐态不同：
 *   GRANTED 什么都不用做、DEGRADED 能用但受限、DENIED 必须去系统里改。
 * @property guide 引导文案（原样透传，不截断不加工）。批 47 起设置页不渲染它。
 * @property canRequestGrant 「去授权」按钮显隐（`:domain` 判据的投影，见 [CapabilityRow]）。
 */
data class CapabilityRowState(
    val capability: Capability,
    val title: String,
    val state: CapabilityState,
    val stateLabel: String,
    val tone: StatusTone,
    val guide: String,
    val canRequestGrant: Boolean,
) {
    companion object {
        fun of(row: CapabilityRow): CapabilityRowState = CapabilityRowState(
            capability = row.capability,
            title = label(row.capability),
            state = row.state,
            stateLabel = stateLabel(row.state),
            tone = tone(row.state),
            guide = row.guide,
            canRequestGrant = row.canRequestGrant,
        )

        /** 能力名的中文说法。`else` 分支回枚举名 —— 新增能力忘配文案时显示英文名而不是空白。 */
        fun label(capability: Capability): String = when (capability) {
            Capability.ACCESSIBILITY -> "无障碍服务"
            Capability.SCREEN_CAPTURE -> "屏幕采集"
            Capability.OVERLAY -> "悬浮窗"
            Capability.NOTIFICATION -> "通知（任务提醒）"
            Capability.SCHEDULE_EXACT_ALARM -> "精确闹钟"
            Capability.ROOT -> "root"
            Capability.ADB_INPUT -> "ADB 输入（Shizuku）"
            Capability.POST_NOTIFICATIONS -> "通知发送权限"
            Capability.USAGE_ACCESS -> "使用情况访问权限"
        }

        /**
         * 三态的中文说法。逐态对应 `PermissionCenter.guideText` 的承诺：
         * DEGRADED 是"能用但受限"（不是"不能用"），DENIED 是"被拒，要去系统里改"。
         */
        fun stateLabel(state: CapabilityState): String = when (state) {
            CapabilityState.GRANTED -> "可用"
            CapabilityState.DEGRADED -> "降级可用"
            CapabilityState.DENIED -> "被拒绝"
        }

        /**
         * 三态 → 着色档。DEGRADED 走 [StatusTone.ATTENTION]（能用但受限，不是错），
         * DENIED 才走 [StatusTone.PROBLEM] —— 让"GRANTED 不是唯一好看的那一态"这件事
         * 在可测的面里可见。
         */
        fun tone(state: CapabilityState): StatusTone = when (state) {
            CapabilityState.GRANTED -> StatusTone.OK
            CapabilityState.DEGRADED -> StatusTone.ATTENTION
            CapabilityState.DENIED -> StatusTone.PROBLEM
        }
    }
}

/**
 * 安装体积的呈现态（§15 的 E1 处置；文案与换算都在这里，让「说成多少 MB」也可测）。
 *
 * **为什么自己算 MiB 而不在装配层算好**：换算口径（MiB vs MB、按什么除）是个会被漂移的
 * 决定，放进纯 JVM 呈现态才能被 `CapabilityCenterStateTest` 钉住。
 *
 * @property engineFilesPresent 引擎 .so 真的在不在。false 时 [text] **如实说「引擎未随包」**
 *   —— 那正是「装了个跑不了脚本的壳」的事实。批 47 起设置页不渲染这句话
 *   （用户口径「安装体积xxx那个文字去掉」），文案与换算保留给记账与测试。
 */
data class InstallSizeState(
    val totalBytes: Long,
    val engineBytes: Long,
    val engineFilesPresent: Boolean,
) {
    /**
     * 那句「安装体积」的标准措辞：总数、其中引擎多少、为什么这么大，三件事都要在。
     * 批 47 起设置页不渲染它（见类 KDoc），文案仍由测试钉住 —— 换算口径要有一个
     * 可测的落点，将来要再披露时从这里同源取。
     */
    fun text(): String {
        val total = "${mib(totalBytes)} MiB"
        if (!engineFilesPresent) {
            return "安装体积 $total（引擎未随包，脚本暂时跑不了；完整说明见 docs/design/13-roadmap-budget.md）"
        }
        val rest = (totalBytes - engineBytes).coerceAtLeast(0L)
        return "安装体积 $total（其中引擎 ${mib(engineBytes)} MiB，其余 ${mib(rest)} MiB）"
    }

    companion object {
        fun of(size: InstallSize) = InstallSizeState(
            totalBytes = size.totalBytes,
            engineBytes = size.engineBytes,
            engineFilesPresent = size.engineFilesPresent,
        )

        /** MiB（1 MiB = 1024² B），一位小数。**不是** MB —— 两套口径混用正是"体积对不上"的来源。 */
        fun mib(bytes: Long): String = String.format("%.1f", bytes / (1024.0 * 1024.0))
    }
}
