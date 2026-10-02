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
 * `:app` 源码（与「UI 拆独立模块」决策相悖：`:app` 的价值就是零 compose 的装配层）。
 * 接口放中间的 `:domain`，两侧各只认它：
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
     * 其余失败同理抛（壳未装配 / 入参校验不过 / 非法 cron 表达式），`:ui` 如实显示原因。
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

    /**
     * 停止一次**在途**执行（控制台「停止」按钮 / engines.exec 的 cancel 回调入口；
     * §12.3 + §8.2 池四步 quiesce）。
     *
     * 按 runId 精确停止（`RuntimeController.stop(runId)` → 池四步 quiesce，
     * `TimedOut` 已由池 kill 兜底）：已结算/从未存在 → **false**（`AlreadyGone` 的
     * 诚实投影，不抛 —— 在途表本来就没有它，不是失败），真停走 → **true**
     * （`StoppedClean`/`StoppedTimeout` 都算"停过了"，后者在池侧已兜底）。
     * 壳未装配**抛**（静默 false = 用户点了停止却什么都没发生，比报错更难查）。
     *
     * 与 `Scheduler.stopLastRun` 的分工：那是"最近一次投递"的单槽位快捷口
     * （调度侧持有，恢复重投会覆盖）；本口是"指定 runId"的精确口（在途表持有，
     * 不受调度单槽覆盖影响）。控制台在途块按行给停止按钮，走本口不走单槽口。
     */
    suspend fun stopRun(runId: Long): Boolean

    /**
     * 脚本文件清单快照（项目页文件列表读口；`files/scripts/` 一棵树的平铺）。
     *
     * 挂起：要遍历项目根目录（IO）；读失败**抛**（与 [taskCenter]/[console] 同一条纪律），
     * 项目根不存在回**空清单** —— "还没部署过任何项目"是真实事实，不是失败。
     * 实现方排除 `node_modules` 与点开头条目（依赖缓存/版本控制噪声，不是用户资产）。
     */
    suspend fun scriptFiles(): ScriptFilesSnapshot
}

/**
 * 能力中心快照（§9.5）：每一行 = 一个能力 + 当前三态 + 引导文案。
 *
 * @property rows **全量**能力（`Capability.entries` 逐项），不是"有问题的那些" ——
 *   能力中心要能回答"我到底有哪些能力、各自什么状态"，只列异常项会让人以为其余不存在。
 * @property degradedAlarmTaskIds 降级中的定时任务（`AlarmSchedulerProvider.degradedTasks` 的键，
 *   精确闹钟不可用时降 `setWindow` 的那些 —— §8.6 承诺在 UI 标注「可能偏差」）。
 *   非空是如实记账，不是错误。
 * @property installSize 安装体积（§15 的 E1 处置「接受超支并在能力中心明示」；**不给默认值** ——
 *   见 [InstallSize] 的 KDoc）。填 null 的快照是"没量到"，UI 据此显示「未量到」而不是 0。
 */
data class CapabilityCenterSnapshot(
    val rows: List<CapabilityRow>,
    val degradedAlarmTaskIds: List<String>,
    val installSize: InstallSize? = null,
)

/**
 * 安装体积的实测值（§15：预算 ≤40MB release 已被实测推翻，2026-10-02 拍板选「接受 + 明示」）。
 *
 * **为什么是实测而不是文档里的 92MB**：92MB 是 node-slice 产物的**未压缩**三件套合计，
 * 而用户装的是**压缩后**的 APK，两者不是同一个数。把预算数字抄进 UI 就是"呈现层说谎"——
 * 与能力中心同一条诚实纪律（不读到的就不显示）。所以这里给的是宿主现场量的字节数。
 *
 * @property totalBytes 已安装 APK 文件总长（`applicationInfo.sourceDir` 的文件长度）。
 * @property engineBytes 其中引擎两条 native 轨（libnode/libnoden/libc++_shared + libopencv）
 *   的合计。分出来是因为**超支全在这一段**（图像面只占 6.6%），给用户一个可比较的数。
 * @property engineFilesPresent 引擎 .so 真的在不在。**false 仍照报体积**：那正是
 *   "装了个跑不了脚本的壳"的事实，隐去比显示更糟（真机可执行包由 node-slice 补，见 backlog B5）。
 */
data class InstallSize(
    val totalBytes: Long,
    val engineBytes: Long,
    val engineFilesPresent: Boolean,
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

/**
 * 脚本文件清单快照（项目页文件列表）。
 *
 * @property rows 排序由实现方定（`:app` 侧按修改时间倒序 —— TG 会话列表"最近在前"
 *   的同一读法）；本层不做二次排序。
 */
data class ScriptFilesSnapshot(
    val rows: List<ScriptFileRow>,
)

/**
 * 一个脚本文件行（项目页文件列表的一行 = TG 会话列表的一行会话）。
 *
 * @property projectId 所属项目（`files/scripts/` 下第一级目录名）。
 * @property relPath 相对项目根的路径 —— 列表显示名（同名的 `main.js` 靠它区分）。
 * @property name 文件名（不含目录）。
 * @property ext 小写扩展名（无扩展名 = ""；文件类型图标用它取色/取字）。
 * @property sizeBytes 文件长度（字节数；格式化在呈现层）。
 * @property modifiedMillis 最后修改时刻（epoch ms；排序与"时间日期"列都出自它）。
 */
data class ScriptFileRow(
    val projectId: String,
    val relPath: String,
    val name: String,
    val ext: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
)
