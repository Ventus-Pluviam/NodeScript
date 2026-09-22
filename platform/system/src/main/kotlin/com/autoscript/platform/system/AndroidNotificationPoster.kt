package com.autoscript.platform.system

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.NotificationPoster
import com.autoscript.domain.system.NotificationSpec

/**
 * `notification` 的 Android 实现（docs §12.2；SPI 见 `:domain` 的 [NotificationPoster]，
 * 语义层 handler 在 `:platform:capabilities`）。分层照 README ops 表：Android 接触面
 * 只有 [Ops] 缝（真机 [NotificationOps] 碰 `android.app.NotificationManager`），
 * 本类留**本机 JVM 可测**的语义：
 *
 * 1. **发前 canPost 门**：通知未授权 → `ERR_PERMISSION_DENIED`（§9.5：授权问题是分类
 *    错误，不是回 false）。这一条不是多余的预检 —— Android 在被拒时**不抛异常、直接
 *    丢弃**通知，不检查就等于让调用方拿到一个假成功；
 * 2. 空白正文拒绝（require 在触 ops 之前，垃圾进不了 NotificationManager）；
 * 3. cancel 幂等无回执：系统不报告撤销结果，契约回 Unit 不编 Boolean。
 *
 * **与 `PermissionFacade` 的关系（不是重复造门禁）**：§9.5 的唯一权限入口仍归
 * `PermissionFacade`（`:app-service:permission-center`），它把同一个系统事实映射成
 * 三态给**能力中心 UI**；本类只把同一事实折成**脚本侧的分类错误**。两处都是问系统、
 * 都不各自发明策略（策略＝ DENIED→抛 / UI 显示哪条文案，分别只有一处），因此不构成
 * 判据漂移 —— 与 `AndroidSystemSettings.ensureWritable` 同构（那条 WRITE_SETTINGS 亦然）。
 * 系统事实的读取点在 [Ops.canPost]，真机实现见 [NotificationOps]。
 */
class AndroidNotificationPoster(
    private val ops: Ops,
) : NotificationPoster {

    override fun canPost(): Boolean = ops.canPost()

    override fun post(spec: NotificationSpec) {
        require(spec.text.isNotBlank()) { "notification text 不得为空白，实际 \"${spec.text}\"" }
        if (!ops.canPost()) {
            throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "通知未授权发送（POST_NOTIFICATIONS / 应用通知未开）: id=${spec.id}",
                null,
            )
        }
        ops.post(spec)
    }

    override fun cancel(id: Int) = ops.cancel(id)

    /**
     * 通知接触面（README ops 表的本行）：真机 [NotificationOps]；单测注入内存替身 ——
     * `NotificationManager` 是系统服务，stub 运行期抛异常。
     *
     * [post]/[cancel] 都回 `Unit` 是**如实**：`NotificationManager.notify` 与 `.cancel`
     * 都没有失败返回值、被拒绝时也不抛（见类 KDoc 第 1 条 —— 那正是我们把门禁放在它
     * 前面、而不是等它报错的原因）。这里不发明一个假的 Boolean 成功位。
     */
    interface Ops {
        fun canPost(): Boolean
        fun post(spec: NotificationSpec)
        fun cancel(id: Int)
    }
}
