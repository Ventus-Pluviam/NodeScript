package com.autoscript.platform.system.shell

/**
 * shell 命名空间的契约 DTO/SPI（§9.3/§9.6/§12.2；`extras.ts` 的 Kotlin 对偶）。
 *
 * 沿革（原 `SystemHostContracts.kt` 四面合一份 → 按面拆开，契约随「仅 handler+impl 消费」
 * 判据迁自 `:domain`）见 `SystemNamespaces` 的类注释，此处不重复。
 *
 * **非法即拒**：构造期 `require`（空命令/负超时由 handler 挡，本面只管结果形状），handler 据此折叠
 * ERR_INVALID_PARAM，绝不把垃圾发往平台层。
 */

// ── shell（§9.6）────────────────────────────────────────────────────

/** 执行通道（§9.6）：DEFAULT 普通 shell / ROOT 经 `su -c` / ADB（设备侧已在 adb shell 内）。 */
enum class ShellMode { DEFAULT, ROOT, ADB }

/**
 * shell 执行结果（与 JS `extras.ts` 的 `ShellResult` 逐字对齐：code/stdout/stderr/truncated）。
 * [stdout]/[stderr] 可空 = 该流没产出；绝不拿空串冒充「有输出但为空」。
 *
 * [truncated]：至少一条流被 [ShellCaptureLimit.MAX_CAPTURE_BYTES] 截断（口径，
 * 见 `docs/design-decisions.md` 第 21 项）。**默认 false**，只有真发生截断才置位 ——
 * 因此旧调用方/旧 facade 拿到的形状不变（多一个字段，语义是纯增量）。
 *
 * **截断不进 [isSuccess] 的判据**：`code` 是子进程的真实退出码，截断是**宿主侧的捕获策略**，
 * 两者正交 —— 拿截断去改 `isSuccess` 会让"命令跑成功了"变成"失败"，那是撒谎。
 * 判断要不要分页/落盘的是脚本自己，依据就是这个标志（[stdout] 末尾另有
 * [ShellCaptureLimit.TRUNCATION_MARK] 给人看，机器判定一律用本字段）。
 */
data class ShellResult(
    val code: Int,
    val stdout: String?,
    val stderr: String?,
    val truncated: Boolean = false,
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
