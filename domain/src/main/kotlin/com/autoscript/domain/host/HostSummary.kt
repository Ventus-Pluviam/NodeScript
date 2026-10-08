package com.autoscript.domain.host

import com.autoscript.domain.editor.SyntaxHighlighter
import com.autoscript.domain.npm.ApprovalTicket
import com.autoscript.domain.npm.NpmPanelSnapshot
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
     * 任务日志：全部项目的终态执行历史（§8.5；不混入任务中心的未结算记录）。
     *
     * 每次现读壳持有的 RunArchive，失败抛、空列表才表示没有已归档的终态。
     * 启动失败没有 engineRunId，不能从本口读到，也不伪造一条引擎记录。
     */
    suspend fun taskLog(): TaskLogSnapshot

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
     * 脚本文件清单快照（项目页文件列表读口；`files/scripts/` 一棵树，文件与
     * **文件夹**都进清单 —— TG 会话列表里群与用户混排的同一读法）。
     *
     * 挂起：要遍历项目根目录（IO）；读失败**抛**（与 [taskCenter]/[console] 同一条纪律），
     * 项目根不存在回**空清单** —— "还没部署过任何项目"是真实事实，不是失败。
     * 实现方排除 `node_modules` 与点开头条目（依赖缓存/版本控制噪声，不是用户资产）；
     * `node_modules` 之下的内容整个不进清单（不是"只藏目录本身"）。
     */
    suspend fun scriptFiles(): ScriptFilesSnapshot

    /**
     * 新建文件/文件夹（项目页 FAB 展开的两个动作；落盘 = `files/scripts/<projectId>/<name>`）。
     *
     * 挂起：写盘（IO）。失败**抛**（名字非法/撞名/项目不存在 —— 原文给 UI）；
     * **不覆盖已存在**（静默覆盖会把用户脚本换成空文件）。
     *
     * @param projectId 目标项目（`files/scripts/` 下第一级目录名）。
     * @param name 单段名字（用户输入的是名字不是路径；含 `/`/`..` 拒绝 ——
     *   要进子文件夹先建子文件夹）。
     * @param isFolder true = 建文件夹，false = 建空文件。
     */
    suspend fun createEntry(projectId: String, name: String, isFolder: Boolean)

    /**
     * 读一个脚本文件的文本内容（项目页**点文件进编辑**；TG 文件页点开文档的对应位）。
     *
     * 挂起：读盘（IO）。失败**抛**（与 [scriptFiles] 同一条纪律）—— 读不到就是读不到，
     * 不返回空串：空串是"这个文件本来就是空的"，两者在编辑器里是两句话。
     *
     * 非 UTF-8 文本、超过实现方上限的文件**抛**（原文给 UI）：把二进制当文本打开再存回去
     * 等于损坏用户文件，宁可如实拒绝。上限由实现方定，本契约只要求"拒绝时抛"。
     *
     * @param projectId 所属项目（`files/scripts/` 下第一级目录名）。
     * @param relPath **`files/scripts/` 之下的相对路径**（= [ScriptFileRow.relPath]，首段就是
     *   [projectId]；**不是**单段名字，也不是"项目根之下"——子文件夹里的文件靠它定位）。
     *   实现方必须防越界，并**校验首段与 [projectId] 一致**：两者对不上就是调用方写错了，
     *   当场抛比静默读到另一个文件强（2026-10-06 实测：把"项目内相对路径"按"项目根之下"
     *   拼，编辑器报"不是文件：demo/main.js"——被拼成了 `files/scripts/demo/demo/main.js`）。
     */
    suspend fun readScriptFile(projectId: String, relPath: String): String

    /**
     * 覆盖写一个脚本文件的文本内容（编辑器「保存」）。
     *
     * 挂起：写盘（IO）。失败**抛**；实现方必须**原子替换**（先写临时文件再 rename）——
     * 半截写入会把用户的脚本毁成语法错误，而用户看不到"写到一半"这件事。
     *
     * 只覆盖**已存在**的文件：不新建（新建走 [createEntry]，撞名语义在那里裁决）。
     *
     * @param relPath 同 [readScriptFile]。
     */
    suspend fun saveScriptFile(projectId: String, relPath: String, content: String)

    /**
     * 为指定路径文件创建语法高亮会话（编辑器前端呼叫）。
     * 默认 NONE：原生解析器缺席时，编辑器保留纯文本能力。
     */
    fun createSyntaxHighlighter(relPath: String): SyntaxHighlighter = SyntaxHighlighter.NONE

    /**
     * 依赖面板读数（§10.9.1；管理面板 → 依赖管理）。挂起：要读 lockfile 与遍历目录（IO）。
     *
     * **全量**（全部项目 + 全局待审队列），不按项目问 —— 理由见 [NpmPanelSnapshot] 的 KDoc。
     *
     * 读失败**抛**（与 [taskCenter]/[console] 同一条纪律）：`:ui` 据此如实显示「读依赖失败」，
     * 而不是画成「一个项目都没有」—— 后者会让用户以为自己的项目丢了。
     *
     * 读口**未接线**（宿主没装配 npm）同样**抛**，不返回空快照：空快照是「读成功且真的
     * 一条都没有」的样子。装配期的失败原文在 `AssembledShell.npmCliFailure`，
     * 呈现层应显示它而不是显示一个空面板。
     */
    suspend fun npmSnapshot(): NpmPanelSnapshot

    /**
     * 人工审批决定（§10.5-2 **人机分离**的唯一落点）。
     *
     * 为什么在 [HostSummary] 而不是桥面：桥面是**脚本侧**的面，而审批的全部意义就是
     * 「人的动作」—— 脚本只能发请求（`auto.npm.requestApprove` 只入队），决定必须由
     * UI 回调带进来。本口就是那个回调面；`PackageManagerFacade.resolveApproval` 在
     * 全仓**只该有这一个生产调用方**。
     *
     * 幂等：已决票再调返回原票（不翻案）。票不存在**抛**（原文给 UI）。
     */
    suspend fun resolveNpmApproval(requestId: String, approve: Boolean): ApprovalTicket
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
 * 安装体积的实测值（§15：预算 `≤ 40MB release` 已被实测推翻，**2026-10-07 用户裁定重定为 `≤ 150MB release`**
 * —— 见 `design-decisions.md` 第 41 项；本类与 `InstallSizeRead` 的实测链出自第 20 项，不受重定影响）。
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
 * @property guide 引导文案（`PermissionCenter.guideText` 的同一份）—— 三态通用：
 *   每条只答"这项能力是干什么的 + 怎么让它可用"，**不按拒绝态起句**（backlog A8 的
 *   裁定，2026-10-07），所以 GRANTED/DEGRADED/DENIED 下都成立，呈现层不按三态去猜
 *   该不该显示它。当前态由 [state] 表达，文案不重复说它。
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
 * @property rows 顺序 = 目录遍历序（**未排序**）—— 排序在呈现层做（TG 文件页
 *   `sortFileItems` 的同一条分工：读数与排序分家），因为排序档/逆向是用户在界面里
 *   现选的呈现偏好（"仅应用于此文件夹"），不进读口契约。
 */
data class ScriptFilesSnapshot(
    val rows: List<ScriptFileRow>,
)

/**
 * 一个脚本文件行（项目页文件列表的一行 = TG 会话列表的一行会话）。
 *
 * @property projectId 所属项目（`files/scripts/` 下第一级目录名）。
 * @property relPath **`files/scripts/` 之下的**相对路径（首段 = [projectId]）—— 列表显示名
 *   （同名的 `main.js` 靠它区分）；目录以 `/` 结尾（与文件行区分，呈现层不用再另判）。
 *   **不是**"相对项目根"：`ScriptFilesRead` 从 `files/scripts/` 起算（`root.relativize`），
 *   这条路径直接喂给 [HostSummary.readScriptFile]/[HostSummary.saveScriptFile]。
 * @property name 文件名（不含目录；目录 = 最后一段目录名）。
 * @property ext 小写扩展名（无扩展名 = ""；文件类型图标用它取色/取字。目录恒 ""）。
 * @property isDirectory 是文件夹还是文件（TG 文件页"目录排最前"的判据源）。
 * @property childCount 文件夹直接子项数（文件的此值无意义，恒 0；UI 用它显示
 *   "N 项"，TG 会话行"成员数"的对应位）。
 * @property sizeBytes 文件长度（字节数；格式化在呈现层。目录恒 0 —— 文件夹
 *   不显示大小，显示 [childCount]）。
 * @property modifiedMillis 最后修改时刻（epoch ms；排序与"时间日期"列都出自它）。
 */
data class ScriptFileRow(
    val projectId: String,
    val relPath: String,
    val name: String,
    val ext: String,
    val isDirectory: Boolean,
    val childCount: Int,
    val sizeBytes: Long,
    val modifiedMillis: Long,
)
