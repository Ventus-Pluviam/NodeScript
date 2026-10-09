package com.autoscript.domain.host

import com.autoscript.domain.npm.ShellConsoleMode
import com.autoscript.domain.editor.SyntaxHighlighter
import com.autoscript.domain.npm.ApprovalTicket
import com.autoscript.domain.npm.InstallEventBatch
import com.autoscript.domain.npm.NpmConsoleHandle
import com.autoscript.domain.npm.NpmConsoleSnapshot
import com.autoscript.domain.npm.NpmPanelSnapshot
import com.autoscript.domain.npm.InstallHistoryEntry
import com.autoscript.domain.npm.NpmCacheReclaimReport
import com.autoscript.domain.npm.NpmRegistryKeys
import com.autoscript.domain.npm.NpmRegistrySnapshot
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityLifecycle
import com.autoscript.domain.permission.CapabilityState
import com.autoscript.domain.scripts.ScriptEnvEntry

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

    /**
     * 脚本环境变量（§8.1；管理面板 → 环境变量）。**全局一份**，不是按项目的配置。
     *
     * 挂起：读盘（jsonl replay 已在构造时做过，这里是内存投影 —— 但仍声明挂起，
     * 与其余读口同一形状，免得将来换成现读实现时改签名）。
     *
     * 空列表 = **真的没设过任何变量**，不是"读不到"。读不到由实现**抛**（与
     * [taskCenter]/[npmSnapshot] 同一条纪律）：`:ui` 据此如实说"读取失败"，
     * 而不是画成"你设的变量都没了"。
     */
    suspend fun scriptEnv(): List<ScriptEnvEntry>

    /**
     * 写入/覆盖一条脚本环境变量（后写胜）。改动**下次脚本执行起**生效 ——
     * 引擎每次 spawn 现读，不需要重启宿主。
     *
     * 键名不合法**抛** [IllegalArgumentException]，原文点名哪个键、为什么
     * （判据的唯一出处是 `:domain` 的 `ScriptEnvKeys.reject`，本口不另判一遍）。
     * **不静默丢弃**：用户敲了一个被保留前缀占用的名字，必须当场知道。
     */
    suspend fun putScriptEnv(key: String, value: String)

    /**
     * 删除一条脚本环境变量。**幂等**（与 [cancelTask] 同口径）：从未设过的 key 照样返回。
     */
    suspend fun removeScriptEnv(key: String)

    /**
     * 全局镜像源读数（§10.9 第 8 条；管理面板 → 镜像源管理）。
     *
     * 与依赖面板同一条分工：**宿主自己的界面读口，不经桥**（桥面是脚本侧的面）。
     * 缺省实现回「没设过 + 出厂缺省」—— 未接线的替身零改动即可编译，
     * 且不假装读过盘。
     */
    suspend fun npmRegistry(): NpmRegistrySnapshot =
        NpmRegistrySnapshot(null, NpmRegistryKeys.OFFICIAL, NpmRegistryKeys.MIRROR)

    /**
     * 设 / 清全局镜像源（§10.9 第 8 条）。`null` 或全空白 = **恢复出厂缺省**。
     *
     * 校验不过**抛** [IllegalArgumentException]（原文点名用户输入的那个串）；
     * 判据的唯一一份在 `:domain` 的 [NpmRegistryKeys.reject]，本口与 `:ui` 共用。
     *
     * 缺省实现是空操作（未接线时不落账也不假装成功）。
     */
    suspend fun setNpmRegistry(raw: String?) {}

    /**
     * 在控制台执行一行 npm 命令（§10.9 第 3 条「npm 终端视图」；管理面板 → 控制台）。
     *
     * 与依赖面板/审批卡/镜像源同一条分工：**宿主自己的界面入口，不经桥** —— 桥面是
     * 脚本侧的面，而控制台是人在宿主界面上敲命令的地方。
     *
     * 契约（判据的唯一一份在 `:domain` 的 `NpmConsoleKeys`，本口不另判一遍）：
     * - 命令行不合法**抛** [IllegalArgumentException]，原文点名用户敲的那个串；
     * - 项目号不合法同样抛（与落盘侧的 `require` 同源）；
     * - 重操作**入队即返回**（与 `install` 同语义，含磁盘预检/配额/项目锁/全局会话），
     *   输出走 [consoleOutput] 拉；
     * - `npm run` / `npx` 走 T1 门禁：未获批 → `ERR_PERMISSION_DENIED`（且请求已入队），
     *   获批但 spawn 桥未接 → `ERR_NOT_IMPLEMENTED`。两条都**如实**，不假装跑过。
     *
     * 缺省实现抛 `ERR_NOT_IMPLEMENTED`：未接线的替身零改动即可编译，但**不静默**
     * —— 让「没接线」与「跑了但没输出」在界面上长得不一样。
     */
    suspend fun runNpmCommand(projectId: String, line: String): NpmConsoleHandle =
        throw com.autoscript.domain.core.AutojsException(
            com.autoscript.domain.core.ErrorCode.ERR_NOT_IMPLEMENTED,
            "控制台命令面未接线：本宿主没有接上 npm 命令执行入口",
        )

    /**
     * 在控制台执行一条 **shell 命令**（2026-10-09 用户口径：控制台要能执行 shell）。
     *
     * 与 [runNpmCommand] 并列的第二条执行面，**不走 npm 那条链**：shell 命令与
     * 依赖树无关（不建事务、不占安装会话、不碰项目锁），它就是「跑一条命令、把输出拿回来」。
     *
     * [projectId] 只用于**把输出落进那个项目的控制台环**（shell 命令与依赖树无关，
     * 不碰项目锁、不建事务）—— 与控制台其余读口同一个作用域。
     *
     * [mode] 的语义见 [ShellConsoleMode]：`DEFAULT` 一律拒（如实说需要 root 或 Shizuku），
     * `ROOT` 走 `su -c`，`ADB` 走 Shizuku。**mode 由调用方显式给**，宿主不替它挑 ——
     * 静默降级会让「我以为在用 root」与「实际用的是 shell」不可分辨（§9.3 同一条纪律）。
     *
     * 返回的是**命令自己的结果**（退出码 + 双流 + 截断标志），不是事件流：shell 命令
     * 是**同步现取**的（与轻操作 `ls`/`audit` 同形），跑完才返回。控制台把它渲染成
     * OUTPUT + RESULT 两行。**没有流式**（与 npm 输出尾部同一条边界）。
     *
     * 缺省实现抛 `ERR_NOT_IMPLEMENTED`：未接线的替身**不假装跑过**。
     */
    suspend fun runShellCommand(
        projectId: String,
        command: String,
        mode: ShellConsoleMode,
        timeoutMillis: Long = 30_000L,
    ): ShellConsoleResult =
        throw com.autoscript.domain.core.AutojsException(
            com.autoscript.domain.core.ErrorCode.ERR_NOT_IMPLEMENTED,
            "控制台 shell 面未接线：本宿主没有接上 shell 执行入口",
        )

    /**
     * 安装审计史读数（§10.5-2；管理面板 → 依赖管理 → 审计页）。
     *
     * 与依赖面板/审批卡/镜像源/控制台同一条分工：**宿主自己的界面读口，不经桥**。
     *
     * **无参**（与 [npmSnapshot] 里的待审队列同一取舍）：全量 + 呈现层筛 —— 按项目筛会让
     * 「全局变更」（registry 改动，`projectId` 是空串）从任何一次筛选里掉出去。
     *
     * 读失败**抛**（与 [npmSnapshot]/[console] 同）：空表是「读成功且真的一条都没有」的样子，
     * 会把「宿主读不到审计」画成「你没做过任何操作」—— 而审计页上那句话是安全相关的。
     *
     * 缺省实现抛 `ERR_NOT_IMPLEMENTED`：未接线的替身零改动即可编译，但不静默。
     */
    suspend fun npmHistory(): List<InstallHistoryEntry> =
        throw com.autoscript.domain.core.AutojsException(
            com.autoscript.domain.core.ErrorCode.ERR_NOT_IMPLEMENTED,
            "审计史读口未接线：本宿主没有接上 npm 审计入口",
        )

    /**
     * 按 lock 闭包回收 npm 缓存（§10.9 第 5 条「包大小管理页」的 cache clean 按钮）。
     *
     * 与依赖面板/审批卡/镜像源/审计同一条分工：**宿主自己的界面入口，不经桥** ——
     * 桥面是脚本侧的面，而这个按钮改的是**磁盘占用**，只有人在界面上按得下去。
     *
     * 语义不是 `npm cache clean`：删的是「没有任何项目 lock 需要的那些」，见
     * [com.autoscript.domain.npm.PackageManagerFacade.reclaimCache]。
     *
     * 失败**抛**（不回一份「删了 0 条」的报告）：这个动作的产物是**磁盘上少了东西**，
     * 静默失败会让用户以为清了、其实没清 —— 他下次点开才发现还是满的，而中间那段时间
     * 他一直以为问题解决了。
     *
     * 缺省实现抛 `ERR_NOT_IMPLEMENTED`：未接线的替身零改动即可编译，但不静默。
     */
    suspend fun reclaimNpmCache(): NpmCacheReclaimReport =
        throw com.autoscript.domain.core.AutojsException(
            com.autoscript.domain.core.ErrorCode.ERR_NOT_IMPLEMENTED,
            "缓存回收未接线：本宿主没有接上 npm 缓存目录",
        )

    /**
     * 依赖维护动作（§10.9 第 5 条：`prune` / `dedupe` / `ci` 三颗按钮）。
     *
     * 与 [runNpmCommand] 的分工：那条路收的是**用户敲的一行字**（要解析、要白名单、
     * 要在控制台回显），这条路收的是**界面上一颗按钮**（动作在编译期就定死了，
     * 没有可解析的东西）。合成一条会让按钮走一遍「把动作名拼成命令行再解析回来」的
     * 往返 —— 那条路上任何一次白名单调整都会**静默**改掉按钮的行为。
     *
     * 返回 [com.autoscript.domain.npm.InstallHandle]（与 [runNpmCommand] 同）：
     * 重操作是入队即返回，产物要走 `snapshot()`/`consoleOutput` 看。
     *
     * 失败**抛**原文（`ci` 的验签拒绝、磁盘/配额预检都是这样上来的）：界面不按错误码
     * 另编一句话 —— `lock.sig` 缺失那句里已经写清了为什么拒，界面再译一遍就是第二份判据。
     *
     * 缺省实现抛 `ERR_NOT_IMPLEMENTED`：未接线的替身零改动即可编译，但不静默。
     */
    suspend fun runNpmMaintenance(
        projectId: String,
        action: com.autoscript.domain.npm.NpmMaintenanceAction,
    ): com.autoscript.domain.npm.InstallHandle =
        throw com.autoscript.domain.core.AutojsException(
            com.autoscript.domain.core.ErrorCode.ERR_NOT_IMPLEMENTED,
            "依赖维护动作未接线：本宿主没有接上 npm 安装会话入口",
        )

    /**
     * 控制台输出读数（seq 游标拉取，与 [console] 同口径）。
     *
     * [NpmConsoleSnapshot.running] 由实现按**句柄账**判定（不是「有没有新行」）：
     * 呈现层据此禁用输入行，而「排队中」正是用户最需要看到「它还没结束」的那一段。
     *
     * 缺省实现回空快照（未接线 = 没有输出，如实）。
     */
    suspend fun consoleOutput(projectId: String, sinceSeq: Long, maxLines: Int = 256): NpmConsoleSnapshot =
        NpmConsoleSnapshot(firstSeq = sinceSeq, lastSeq = sinceSeq, lines = emptyList(), running = false)

    /**
     * 依赖面板的**变更半边**入口：跑一行安装/卸载命令（§10.9 第 1 条，2026-10-09 批 87）。
     *
     * **为什么是「一行命令」而不是「一组 `PackageSpec`」**：门禁强度不该取决于用户从哪个
     * 界面按下去 —— 依赖面板的输入行与控制台敲的是同一种东西，故两者走**同一条**执行入口
     * （`InstallCoordinator.runConsoleCommand`）：同一份判据（`NpmConsoleKeys.parse`）、
     * 同一套装前多镜像交叉校验、同一道磁盘/配额预检、同一把项目锁与全局安装会话。
     * 新开一条「按 spec 装」的宿主口就是第二份安装入口，而两份入口的差别只在
     * 「谁先忘了加某道门」上体现出来。
     *
     * 参数是**拼好的那一行原文**（`"npm install axios --save-dev"`），由 `:ui` 的
     * `NpmInstallOps` 从输入框与旗标开关拼出来 —— 拼装规则住在呈现层（那里看得见开关），
     * 而**判据**在宿主侧与界面侧读同一份。
     *
     * 与 [runNpmCommand] 是**同一个口**：后者是控制台的调用名，本方法只是让依赖面板
     * 不必假装自己是控制台。实现方应把两者指向同一个函数体。
     */
    suspend fun runNpmPanelCommand(projectId: String, line: String): NpmConsoleHandle =
        runNpmCommand(projectId, line)

    /**
     * 安装会话的**进度读数**（§10.9 第 1 条的阶段进度条，2026-10-09 批 87）。
     *
     * 与 [consoleOutput] 同形同纪律（seq 游标拉取、有界、空洞可见），但**问的是另一件事**：
     * 控制台答「命令行发生了什么」（ECHO/OUTPUT/WARNING/RESULT 混在一条流里），
     * 本口答「这次安装走到哪一步了」—— 故读的是**事件环**（`InstallEvent` 原样，
     * 不经过控制台那层投影），呈现层据此画阶段条。
     *
     * **为什么不让呈现层从控制台输出里认阶段**：控制台那条流是给人看的文本，
     * 从 `"下载"` 这样的中文串里认阶段就是**按文本猜**——宿主哪天改了措辞，
     * 阶段条会静默停在第一格（与 `NpmConsoleLine.ok` 那条「成败不在呈现层猜」同一条纪律）。
     *
     * 缺省实现回空批次（未接线 = 没有事件，如实；游标原样带回，调用方以游标为准）。
     */
    suspend fun npmInstallEvents(projectId: String, sinceSeq: Long, maxBatch: Int = 64): InstallEventBatch =
        InstallEventBatch(firstSeq = sinceSeq, lastSeq = sinceSeq, events = emptyList())
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
 * 一条 shell 命令的执行结果（[HostSummary.runShellCommand] 的返回）。
 *
 * **为什么不直接用 `:platform:system` 的 `ShellResult`**：`:domain` 看不到 `:platform:*`
 * （依赖方向铁律），而且那个类型带 `truncated` 的内部口径 —— 控制台要的是「退出码 +
 * 两条流 + 有没有被截断」这四件事，与它逐字同形但**必须住在 `:domain`**。
 * 两处字段若漂了，编译期不会红（各是各的类型）—— 故 `:app` 的装配侧有一条
 * 逐字段转接的测试钉住（`PlatformWiringTest` 的「控制台 shell 面接线」）。
 */
data class ShellConsoleResult(
    val code: Int,
    val stdout: String?,
    val stderr: String?,
    val truncated: Boolean = false,
) {
    val isSuccess: Boolean get() = code == 0
}

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
