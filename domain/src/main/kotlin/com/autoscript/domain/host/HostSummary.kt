package com.autoscript.domain.host

import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityLifecycle
import com.autoscript.domain.permission.CapabilityState

/**
 * 宿主摘要读口（首屏/能力中心「壳就绪没、漏投几条」的状态源）。
 *
 * 为什么接口住 `:domain` 而不是 `:ui` 或 `:app`：装配产物（壳、漏投账本）住在
 * `:app` 的 `AppShellApplication` 里，呈现层住 `:ui` —— 让 `:ui` import `:app`
 * 会成环（app→ui），让 `:app` import `:ui` 只为实现接口又会把 compose 拖回
 * `:app` 源码（与「UI 拆独立模块」决策相悖，且裸 kotlinc 旁路没有 androidx 坐标，
 * jvm-test 的 app 模块会直接编不过）。接口放中间的 `:domain`，两侧各只认它：
 * `:app` 实现（`AppShellApplication : HostSummary`）、`:ui` 消费（`as? HostSummary`）。
 * 装配知识仍归 `:app` —— 本接口只回快照，不暴露壳/调度器本体。
 */
interface HostSummary {
    /** 当前宿主快照（每次调用现取不缓存；与 `permissionCenter().state()` 同口径）。 */
    fun shellSummary(): ShellSummary

    /**
     * 能力中心快照（§9.5 三态门禁的呈现面）。
     *
     * 挂起：三态是**现问系统**的结论（`PermissionFacade.state` 逐项直读，不缓存 ——
     * 缓存 = 撒谎的开始），而系统查询是挂起的（root 探测要切 IO）。
     * 读失败**抛**（不返回"全 DENIED"那种假快照）：`:ui` 据此如实显示「读能力态失败」，
     * 而不是让用户以为自己的授权全丢了。
     */
    suspend fun capabilityCenter(): CapabilityCenterSnapshot

    /**
     * 一键跳转系统授权页（能力中心「去授权」按钮）。**无判断**：去哪一页由
     * `PermissionFacade.openSystemSettings` 的实现决定（`:app` 侧是 `AndroidGrantLauncher`），
     * 本读口只把请求转下去 —— 呈现层因此不必（也不许）碰 `Settings`/`Intent`。
     */
    fun openCapabilitySettings(capability: Capability)

    /**
     * 任务中心快照（§8.6 排期 + §8.5 执行档案/恢复账）。
     *
     * 挂起：[TaskCenterSnapshot] 要读两个持久寄存器（注册表 + 运行档案）与恢复账，
     * 都是 IO/挂起路径（`FileTaskStore.loadAll` / `RunArchive.unfinished`）；
     * 首屏那份同步的 [shellSummary] 里塞不下它。
     *
     * 读失败**抛**（与 [capabilityCenter] 同一条纪律）：`:ui` 据此如实显示「读任务失败」，
     * 而不是渲染成"一条任务都没有" —— 后者会让用户以为自己的定时任务全没了。
     */
    suspend fun taskCenter(): TaskCenterSnapshot

    /**
     * 控制台快照（§7.3 seq 游标拉取 + §8.3 在途执行两端对照）。
     *
     * @param sinceSeq 只回 `seq > sinceSeq` 的行（首读传 0）；快照里的
     *   [ConsoleSnapshot.nextSeq] 是下次该传的值 —— 游标只进不退，读失败也不清零。
     * @param maxLines 本批上限（> 0）；拉满时 [ConsoleSnapshot.pageFull] 为 true。
     *
     * 读失败**抛**（与 [capabilityCenter]/[taskCenter] 同一条纪律）：`:ui` 据此如实
     * 显示「读控制台失败」并**保留已读到的行**，而不是把缓冲清成"尚无日志"
     * —— 一次瞬时失败抹掉用户已经看到的日志，比报错更糟。
     */
    suspend fun console(sinceSeq: Long, maxLines: Int): ConsoleSnapshot

    /**
     * 登记定时任务（任务中心操作面「登记」；§8.6）。
     *
     * 挂起：登记先落盘再动内存/闹钟（`Scheduler.schedule` 的 store-first 纪律）——
     * 落盘失败**抛**，此时内存/闹钟未动，不会出现"界面说登记成功、重启后却没了"。
     * 其余失败同理抛（壳未装配 / 入参校验不过 / cron P1 未落地），`:ui` 如实显示原因。
     *
     * @return 分配到的任务 id（入参 [TaskRegistration.id] 为空时服务端 UUID）。
     */
    suspend fun registerTask(registration: TaskRegistration): String

    /**
     * 取消任务（操作面「取消」）。
     *
     * 幂等（与 `Scheduler.cancel` / 桥侧 `workManager.cancel` 同口径）：从未登记的 id
     * 照样返回 —— 先落 tombstone 再动内存，重复取消无副作用。已投递的 runs 不追回
     * （追回属执行侧，不在本口）。
     * 壳未装配**抛**（静默吞掉 = 用户点了取消却什么都没发生，比报错更难查）。
     */
    suspend fun cancelTask(taskId: String)

    /**
     * 立即执行（操作面「立即执行」；触发源 `USER_CLICK`，不受停用守卫限制 ——
     * 停用任务也能手动跑，见 §8.6 enabled 守卫的豁免名单）。
     *
     * 挂起到**本次执行结算**（与闹钟/广播触发同一条 `onTrigger` 路径；排队上限
     * USER_CLICK 10s + 脚本超时/默认 30s）—— `:ui` 应在挂起期间禁用操作按钮并如实
     * 显示「执行中」，不要另起计时器猜结束。
     *
     * 成败**不由本口回报**（`onTrigger` 恒 Unit；结局在意图日志/控制台）——
     * UI 文案不得把"调用返回了"说成"脚本跑成功了"。Once 任务触发即终态化出册
     * （调度器语义），刷新后从列表消失是事实，不是取消。
     *
     * 失败抛：任务不存在（可能已被取消）/ 调度已收口（宿主正在停止）/ 壳未装配。
     * 查无任务在触发**之前**现查 —— `onTrigger` 对查无任务是静默 return，
     * 不查就会把 no-op 呈现成"已触发"。
     */
    suspend fun runTaskNow(taskId: String)
}

/**
 * 能力中心快照（§9.5）：每一行 = 一个能力 + 当前三态 + 引导文案。
 *
 * @property rows **全量**能力（`Capability.entries` 逐项），不是"有问题的那些" ——
 *   能力中心要能回答"我到底有哪些能力、各自什么状态"，只列异常项会让人以为其余不存在。
 * @property degradedAlarmTaskIds 降级中的定时任务（`AlarmSchedulerProvider.degradedTasks` 的键，
 *   精确闹钟不可用时降 `setWindow` 的那些 —— §8.6 承诺在 UI 标注「可能偏差」）。
 *   非空是如实记账，不是错误。
 */
data class CapabilityCenterSnapshot(
    val rows: List<CapabilityRow>,
    val degradedAlarmTaskIds: List<String>,
)

/**
 * 能力中心的一行。
 *
 * @property guide 引导文案（`PermissionCenter.guideText` 的同一份）—— 被拒/降级时
 *   用户要看到"去哪开、开了之后是什么态"。GRANTED 时也有（文案本身已说明当前态），
 *   呈现层不按三态去猜该不该显示它。
 */
data class CapabilityRow(
    val capability: Capability,
    val state: CapabilityState,
    val guide: String,
) {
    /**
     * 是否该给「去授权」按钮（能力中心按钮显隐）。
     * 判据唯一出处 = [CapabilityLifecycle.canRequestGrant]（DENIED/DEGRADED 可申请，
     * GRANTED 不再打扰用户）—— 呈现层不自己写 `state != GRANTED`，那是第二套判据。
     */
    val canRequestGrant: Boolean get() = CapabilityLifecycle.canRequestGrant(state)
}

/**
 * 壳摘要快照（纯 DTO，两侧共用）。
 *
 * @property shellReady 壳已装配。false = 装配中**或**装配失败 —— UI 只如实显示
 *   「未就绪」，不替 logcat 猜是哪种（失败原因在「壳自装配失败」一行）。
 * @property missedAlarms 漏投账本条数（`AlarmDispatch.missed`；§4.1 装配完成前
 *   响过的闹钟，不静默丢弃）。装配中显示非零不是错误，是如实记账。
 * @property keepAliveActive 保活真生效（§8.7）：**系统事实 ∧ 唤醒锁账本持锁**，
 *   两侧都真才算（见 `ForegroundKeeper.isActive`）。false = 保活没起（后台启动受限/
 *   权限被收回）**或**锁没拿到 —— 此时 `SCREEN_ON` 任务会被屏幕门禁如实拒绝。
 *   **不给默认值**：每个产出方都得显式回答"保活到底生效没有"，
 *   漏填就在编译期炸，而不是在能力中心里默认显示成"已保活"。
 */
data class ShellSummary(
    val shellReady: Boolean,
    val missedAlarms: Int,
    val keepAliveActive: Boolean,
)
