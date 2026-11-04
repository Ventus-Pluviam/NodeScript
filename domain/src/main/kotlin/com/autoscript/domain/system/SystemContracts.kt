package com.autoscript.domain.system

import com.autoscript.domain.bridge.HandleRef

/**
 * `dialogs` 命名空间的契约 DTO/SPI（§9.4；`extras.ts` 的 Kotlin 对偶）——
 * shell/device/app/floatingWindow 的契约随 handler 迁去 `:platform:system`
 * （D3 起按命名空间拆为 `shell/ShellContracts.kt`、`device/DeviceContracts.kt`、`app/AppContracts.kt`、`floatingWindow/FloatingWindowContracts.kt`；此前是同名的 `SystemHostContracts.kt`），DialogHost 留 `:domain` —— 装配层（`:app`
 * `PlatformWiring`）生产读面引用它（这正是它留在 `:domain` 的判据）。
 *
 * **非法即拒**：构造期 `require`（空 title/空选项），handler 据此折叠 ERR_INVALID_PARAM。
 */

// ── dialogs（§9.4）──────────────────────────────────────────────────

/**
 * 对话框模式（§9.4 BAL 安全路径）：AUTO 按 overlay 可见性自选；
 * OVERLAY 强制弹窗（不可用即失败，不静默降级）；NOTIFICATION 强制走通知回调。
 */
enum class DialogMode { AUTO, OVERLAY, NOTIFICATION }

/** 输入框请求（§9.4 / §12.3 `auto.dialogs.prompt`）。 */
data class DialogPromptRequest(
    val title: String,
    val placeholder: String?,
    val mode: DialogMode,
) {
    init {
        require(title.isNotBlank()) { "dialogs.prompt 的 title 不得为空" }
    }
}

/** 选择框请求（§9.4 / §12.3 `auto.dialogs.choose`；同步选项列表）。 */
data class DialogChooseRequest(
    val title: String,
    val options: List<String>,
    val mode: DialogMode,
) {
    init {
        require(title.isNotBlank()) { "dialogs.choose 的 title 不得为空" }
        require(options.isNotEmpty()) { "dialogs.choose 的 options 不得为空" }
    }
}

/** 输入框结果：取消 = [value] 为 null 且 [confirmed] 为 false（JS 契约逐字对齐）。 */
data class DialogOutcome(
    val value: String?,
    val confirmed: Boolean,
) {
    companion object {
        /** 用户取消（JS facade 折叠为 `{value:null,confirmed:false}`）。 */
        val CANCELLED = DialogOutcome(value = null, confirmed = false)
    }
}

/** 选择框结果：[index] 为选中下标，[CANCELLED][.CANCELLED_INDEX] 表示用户取消。 */
data class DialogChoice(val index: Int) {
    val isCancelled: Boolean get() = index == CANCELLED_INDEX

    companion object {
        const val CANCELLED_INDEX = -1
        val CANCELLED = DialogChoice(CANCELLED_INDEX)
    }
}

/**
 * 对话框宿主 SPI（§9.4 BAL：overlay 可见时弹窗，否则通知回调）。
 * 实现住 `:platform:capabilities`（overlay 悬浮窗 + 通知降级两条路径的 UI 编排）；
 * 领域层只冻结「问一次、拿一个结果」的契约，路径选择是平台细节。
 *
 * 回调只提交请求、人工在 UI 确认（§12.2：脚本永远不直接弹系统 Dialog），
 * 因此本 SPI 是挂起函数：等到人操作完或超时由实现方收尾。
 */
interface DialogHost {
    suspend fun prompt(request: DialogPromptRequest): DialogOutcome
    suspend fun choose(request: DialogChooseRequest): DialogChoice
}

