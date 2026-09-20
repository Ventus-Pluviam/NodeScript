package com.autoscript.domain.system

import com.autoscript.domain.bridge.HandleRef

/**
 * `dialogs` / `floatingWindow` / `shell` / `device` / `app` 五个命名空间的领域契约
 * （docs/framework-design.md §9.4 / §9.6，JS 对偶见 `bridge/js/src/extras.ts`）。
 *
 * 为什么这些 SPI 住 `:domain`：五个 handler 的真实现都要碰 Android 系统服务
 * （WindowManager / Runtime.exec / PackageManager / Build），而 §6 依赖规则要求
 * `:platform:*` 只依赖 `:domain`、`:app` 禁止直连 `:platform`。把「要问什么」
 * （本文件的接口 + DTO）与「怎么问」（平台实现）切开，handler 才是纯 JVM 可测的
 * —— 与 [com.autoscript.domain.automation.FrameSource] /
 * [com.autoscript.domain.automation.InputProvider] 同一模式。
 *
 * 校验一律内建在 DTO 构造器（非法即抛 IllegalArgumentException，handler 折叠为
 * ERR_INVALID_PARAM）：绝不把空 title / 空选项列表 / 负尺寸发往平台层。
 */

// ── shell（§9.6）────────────────────────────────────────────────────

/** 执行通道（§9.6）：DEFAULT 普通 shell / ROOT 经 `su -c` / ADB（设备侧已在 adb shell 内）。 */
enum class ShellMode { DEFAULT, ROOT, ADB }

/**
 * shell 执行结果（与 JS `extras.ts` 的 `ShellResult` 逐字对齐：code/stdout/stderr）。
 * [stdout]/[stderr] 可空 = 该流没产出；绝不拿空串冒充「有输出但为空」。
 */
data class ShellResult(
    val code: Int,
    val stdout: String?,
    val stderr: String?,
) {
    val isSuccess: Boolean get() = code == 0
}

/**
 * shell 执行 SPI（§9.6）。实现住 `:platform:system`（`Runtime.exec("sh","-c",…)`
 * + 双流读干 + `waitFor(timeout)`）。
 *
 * 超时是**实现方契约**而非可选项：`child_process` 缺失的副作用（§10：Node 侧无 spawn）
 * 由本 SPI 在宿主侧兑现，所以超时必须由实现强制 —— 调用方只传建议值，
 * 实现不得无限等待（铁律 3：每次跨进程操作必有 TTL）。
 */
interface ShellExecutor {
    suspend fun exec(command: String, mode: ShellMode, timeoutMillis: Long): ShellResult
}

// ── device（§9.6）───────────────────────────────────────────────────

/** 设备信息 P0 最小集（§12.3 `auto.device`）：型号 + SDK 版本，其余字段 P2。 */
data class DeviceProfile(
    val model: String,
    val sdkInt: Int,
) {
    init {
        require(model.isNotBlank()) { "device.model 不得为空" }
        require(sdkInt >= 1) { "device.sdkInt 必须 ≥ 1，实际 $sdkInt" }
    }
}

/** 设备信息 SPI（§9.6）。实现住 `:platform:system`（`Build.MODEL` / `Build.VERSION.SDK_INT`）。 */
interface DeviceInfoProvider {
    fun profile(): DeviceProfile
}

// ── app（§9.3/§12.2）────────────────────────────────────────────────

/**
 * 应用开关 SPI。实现住 `:platform:system`（PackageManager）。
 * - [launch] 回 false = 找不到/起不来（**不是异常**：JS facade 用 `=== true` 判成败，
 *   抛错会让 try/catch 策略退化成「只有崩了才算失败」）；
 * - [currentPackage] 回 null = 取不到前台包（无权限/无前台窗口），如实给 null 不给空串。
 */
interface AppLauncher {
    suspend fun launch(packageName: String): Boolean
    suspend fun currentPackage(): String?
}

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

// ── floatingWindow（§9.4）───────────────────────────────────────────

/**
 * 悬浮窗创建规格（§9.4）：标题 + 逻辑像素尺寸。
 * [width]/[height] 为 null = wrap content（宿主按内容测量）；非 null 时必须 > 0。
 */
data class FloatingWindowSpec(
    val title: String?,
    val width: Int?,
    val height: Int?,
) {
    init {
        require(width == null || width > 0) { "悬浮窗宽度必须 > 0 或 null（wrap content），实际 $width" }
        require(height == null || height > 0) { "悬浮窗高度必须 > 0 或 null（wrap content），实际 $height" }
    }

    companion object {
        /** 缺省规格：无标题 + 双向 wrap content（JS facade 不传 width/height 时的形态）。 */
        val DEFAULT = FloatingWindowSpec(title = null, width = null, height = null)
    }
}

/**
 * 悬浮窗宿主 SPI（§9.4）：`TYPE_ACCESSIBILITY_OVERLAY`（可信窗口易保持）+
 * `SYSTEM_ALERT_WINDOW` 回退由实现决定；窗口类型选择是平台细节，不进领域契约。
 *
 * 返回 [HandleRef]（§7.4）：句柄带 generation，跨代/已关闭 → ERR_STALE_HANDLE。
 * [close] 幂等（未知句柄由实现抛分类错误，handler 折叠，不静默成功）。
 */
interface FloatingWindowHost {
    suspend fun create(spec: FloatingWindowSpec): HandleRef
    suspend fun close(ref: HandleRef)
}
