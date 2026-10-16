package com.autoscript.domain.host

import com.autoscript.domain.npm.ShellConsoleMode
import com.autoscript.domain.editor.SyntaxHighlighter
import com.autoscript.domain.scripts.ScriptRuntimeLimits
import com.autoscript.domain.npm.InstallEventBatch
import com.autoscript.domain.npm.NpmConsoleHandle
import com.autoscript.domain.npm.NpmConsoleSnapshot
import com.autoscript.domain.npm.NpmPanelSnapshot
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
     * 脚本输出日志的文件列表（日志管理页「脚本输出」tab；2026-10-13 用户令）。
     *
     * 读的是落盘目录（`filesDir/.ns/script-logs/`），**壳没装好也不抛** —— 与
     * [scriptFiles] 同一条"不依赖壳寄存器"口径：目录照样可读，列出来是事实
     * （此时"有日志但跑不了"恰恰是用户该看到的全貌）。[ScriptLogRow.running]
     * 那一列需要壳里的在途表：壳未就绪时一律 false（"没在跑"是事实，不是失败）。
     * 只有遍历/解析本身抛（IO 异常）才向上传。
     */
    suspend fun scriptLogIndex(): ScriptLogSnapshot

    /**
     * 一次执行的日志全文（点日志列表那一行进详情；2026-10-13 用户令）。
     *
     * 读 `filesDir/.ns/script-logs/&lt;runId&gt;.log`（JSONL，截尾 [maxLines] 行 ——
     * 长跑脚本的输出能到几十万行，详情页全读会把内存撑爆；截尾只截**显示**，
     * 文件里那份全文不动）。只复用 [ConsoleLineRow] 的形状（seq/level/text/at），
     * runId 已隐含在调用参数里，不再重复携带。
     *
     * 文件缺失/解析失败**抛**（与 [readScriptFile] 同一条纪律）：读不到就是读不到，
     * 不返回空表 —— 空表是"这次执行真的没输出"，两者在详情页是两句话。
     */
    suspend fun scriptLogContent(runId: Long, maxLines: Int = 2000): List<ConsoleLineRow>

    /**
     * 暂停一次**在途**执行（悬浮窗「暂停执行」按钮；2026-10-13 用户令）。
     *
     * OS 级冻结（SIGSTOP，见 `RuntimeController.pauseRun`）—— 不是优雅停：
     * 进程原地冻住、心跳停、输出停，恢复（[resumeRun]）后从冻住那一行继续。
     * @return true = 已冻住；false = 它已结算/从未存在，或本设备冻不住
     *   （pid 拿不到 / `kill` 二进制缺席 —— 原文见异常，不在这里编）。
     * 壳未装配**抛**（与 [stopRun] 同：静默 false = 点了暂停却什么都没发生）。
     */
    suspend fun pauseRun(runId: Long): Boolean

    /** 恢复一次被 [pauseRun] 冻住的执行（看门狗宽限与期限线随之顺延）。语义与 [pauseRun] 对偶。 */
    suspend fun resumeRun(runId: Long): Boolean

    /**
     * 往一次**在途**执行的脚本进程 stdin 送一行（悬浮窗底部输入框那颗蓝色箭头；
     * 2026-10-16 用户令）。
     *
     * 走的是 OS 管道（引擎 spawn 时本来就 `Redirect.PIPE`），脚本侧用 `process.stdin`
     * 照常读 —— **不是桥调用**，脚本零改动。行尾由实现补（调用方给的是"用户敲的那一行"）。
     *
     * @return true = 已写进管道；false = 它不在途（已结算/从未存在）或管道已随进程关闭。
     * 缺省实现回 false：**未接线的替身零改动即可编译**，且不静默 —— UI 按 false 如实报
     * （与 [pauseRun] 的 false 同一条"不假装"纪律）。
     */
    suspend fun sendInput(runId: Long, line: String): Boolean = false

    /**
     * 本机有没有弹**系统级**悬浮窗的资格（执行日志悬浮窗的宿主二选一；2026-10-13 用户令）。
     *
     * 同步、便宜：就是 `Settings.canDrawOverlays` 那一句（`:ui` 不碰 `Settings`，
     * 实现在 `:app`）。false = 用应用内兜底窗（与 ToastHost 同型）+ toast 引导去能力中心授权。
     */
    fun overlayAllowed(): Boolean

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
     * 新建文件/文件夹（项目页 FAB 展开的两个动作；落盘 =
     * `files/scripts/<projectId>/<dirRelPath>/<name>`，[dirRelPath] 为空时就是项目根）。
     *
     * 挂起：写盘（IO）。失败**抛**（名字非法/撞名/项目或文件夹不存在 —— 原文给 UI）；
     * **不覆盖已存在**（静默覆盖会把用户脚本换成空文件）。
     *
     * @param projectId 目标项目（`files/scripts/` 下第一级目录名）。
     * @param name 单段名字（用户输入的是名字不是路径；含 `/`/`..` 拒绝 ——
     *   落进哪一层由 [dirRelPath] 说，不由名字里的斜杠说）。
     * @param isFolder true = 建文件夹，false = 建空文件。
     * @param dirRelPath **目标文件夹**的 `files/scripts/` 之下相对路径（与 [readScriptFile]
     *   的 relPath 同一个形态：[ScriptFileRow.relPath]，首段就是 [projectId]，尾随 `/` 可有可无）；
     *   `null`/空 = 项目根。**2026-10-16 用户口径**：项目页 FAB 落点从"项目根"改成"**当前进入的
     *   那一层**"（用户在哪一层按的新建，就建在哪一层）—— 呈现层把当前目录原样传下来，
     *   不再由它 `substringBefore('/')` 只取项目名。
     *   实现方必须**防越界**（`..` 之类）并校验首段与 [projectId] 一致，且目标目录**必须已存在**
     *   （不会顺路把中间层建出来：那是"建了个名字打错的层级"，不是新建条目）。
     */
    suspend fun createEntry(
        projectId: String,
        name: String,
        isFolder: Boolean,
        dirRelPath: String? = null,
    )

    /**
     * 删一个条目（文件列表的菜单「删除」／多选批量删除；2026-10-17 用户口径）。
     *
     * **文件夹递归整棵**：非空文件夹删不掉的话，菜单里那格在"能看见却删不动"的形态上
     * 等于没做（TG 文件页删文件夹也是连内容一起）。
     *
     * 挂起：写盘（IO）。失败**抛**（原文给 UI）：
     * - 条目不存在（**抛而不是静默成功**：静默成功会把"删错了对象"和"删掉了"画成同一件事）；
     * - [relPath] 越界 / 首段与 [projectId] 对不上（判据与 [readScriptFile] 同源）；
     * - 落盘失败（权限、占用）。
     *
     * **根层的文件夹就是项目本身**（`files/scripts/<名字>/`，见 [ProjectId.ROOT]）——
     * 删它就是连同其中全部文件删掉一整个项目。用户 2026-10-17 裁定**允许**（不是禁止），
     * 故本口不为此设闸；实现方仍必须挡住"删 `files/scripts/` 本身"（那是整个项目库）。
     *
     * **不检查在途执行**：引擎已把脚本读进内存，删文件不会打断本次执行，下一次投递才会
     * 得到 `ERR_FILE_NOT_FOUND`（如实记账，不在这里补一层"正在运行就不许删"）。
     *
     * @param projectId 所属项目（[ProjectId.ROOT] 的空串 = 根层那一条）。
     * @param relPath 同 [readScriptFile]（`files/scripts/` 之下的相对路径，首段 = 项目名）；
     *   目录行以 `/` 结尾（[ScriptFileRow.relPath] 的形态），实现方按"尾随斜杠可有可无"收。
     */
    suspend fun deleteEntry(projectId: String, relPath: String)

    /**
     * 条目（**文件或文件夹**）的绝对路径 —— 文件列表菜单的「复制绝对路径」（2026-10-17 用户口径）。
     *
     * 为什么这条要经宿主：绝对路径 = `files/scripts/` 的落点 + relPath，而**只有宿主知道
     * `filesDir`**（`:ui` 拿不到，也不该拿到 —— 它连 `files/scripts` 这个约定都不该知道）。
     *
     * **不查存在性**：与 [com.autoscript.domain.scripts.ScriptPaths.scriptFile] 同一条分工
     * —— 约定只回答"应该在哪"，查存在性是调用方的事。于是刚被删掉的行照样复制得出它曾有的
     * 路径（用户要的往往正是"我删之前那个文件在哪"）。
     *
     * 失败**抛**：越界 / 首段与 [projectId] 对不上（判据与 [readScriptFile] 同源）。
     *
     * @param relPath 同 [readScriptFile]。
     */
    suspend fun absolutePathOf(projectId: String, relPath: String): String

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
     * 跑一个脚本文件（项目页文件行尾那颗 ▶；2026-10-13 用户口径）。
     *
     * 与 [runTaskNow] 是**两条入口、一条执行链**：那边按 `taskId` 触发已登记的任务，
     * 这边按 `(projectId, relPath)` 触发**没登记过**的那个文件 —— 用户想跑一下手里这个
     * 脚本，不该先被迫登记一条定时任务。宿主侧两条都走同一条投递链（意图日志 → 引擎池 →
     * 档案），所以跑过的文件在控制台与任务日志里都查得到，触发源是 `USER_CLICK`。
     *
     * **挂起到本次执行结算**（与 [runTaskNow] 逐字同一条口径：排队上限 USER_CLICK 10s +
     * 脚本超时/默认 30s）—— 调用方在挂起期间应禁用那颗 ▶ 并如实显示「执行中」，不要另起
     * 计时器猜结束。
     *
     * **成败不由本口回报**（结局在意图日志/控制台，与 [runTaskNow] 同）—— 调用方文案
     * 不得把"调用返回了"说成"脚本跑成功了"。
     *
     * 失败抛：
     * - 该扩展名**不可执行**（判据的唯一一份在 `:domain` 的
     *   [com.autoscript.domain.scripts.ScriptRunnability.isRunnable]，本口不另判一遍）——
     *   引擎侧起的是 `node <路径>`，`.sh` 这类只会得到一句 SyntaxError，当场拒比假装跑过好；
     * - 文件不存在 / 项目不存在（`ERR_FILE_NOT_FOUND`，与 [readScriptFile] 同源）；
     * - 调度已收口（宿主正在停止）；
     * - 壳未装配（与 [runTaskNow] 同）。
     *
     * @param relPath 同 [readScriptFile]（`files/scripts/` 之下的相对路径，首段 = 项目名）。
     *
     * 缺省实现抛 `ERR_NOT_IMPLEMENTED`：未接线的替身零改动即可编译，但**不静默**
     * —— 让「没接线」与「跑了但没输出」在界面上长得不一样（与 [runNpmCommand] 同一条纪律）。
     */
    suspend fun runScriptFile(projectId: String, relPath: String): Unit =
        throw com.autoscript.domain.core.AutojsException(
            com.autoscript.domain.core.ErrorCode.ERR_NOT_IMPLEMENTED,
            "脚本执行口未接线：本宿主没有接上「跑这个文件」的入口（projectId=$projectId，relPath=$relPath）",
        )

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
     * 脚本资源限额读数（§8.1「脚本资源限额」，批 107；设置页 → 脚本设置）。
     *
     * 与 [scriptEnv] **同一套纪律**：读不到**抛**不返回出厂值 —— 出厂值长得像「用户填的」，
     * 回它会把「盘读不出来」画成「你的设置重置了」（另见 `AppShellApplication` 的
     * 「壳未装配」那条）。未设过一份 = 出厂值是**实现**的合法回答（文件不存在），
     * 不是这条口在撒谎。
     */
    suspend fun scriptLimits(): ScriptRuntimeLimits

    /**
     * 保存脚本资源限额（§8.1，批 107）。
     *
     * 不过 [ScriptRuntimeLimits.reject] 时**抛** [IllegalArgumentException]，原文点名
     * 是哪个字段越界 —— 判据唯一一份在 `:domain`，本口只转发，`:ui` 与落盘实现都不另判一遍。
     *
     * **立即生效**（与 [putScriptEnv] 同款：改完下次执行即用新值，不重启宿主）：
     * 单脚本那半在每次 spawn 现读，桥那半在每次请求现读。
     */
    suspend fun saveScriptLimits(limits: ScriptRuntimeLimits)

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
     * 与依赖面板/镜像源同一条分工：**宿主自己的界面入口，不经桥** —— 桥面是
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
     * 停掉该项目此刻还在跑的控制台命令（2026-10-11 批 94：控制台「停止」按钮）。
     *
     * 与 [runNpmCommand] 同一条分工：交给**装配产物里那个 facade**（句柄账在协调器手里，
     * 换一个实例就找不到要停的那个句柄）；未接线**抛**，不假装停过。
     *
     * 回 `true` = 确实取消到了东西；`false` = 没有在跑的（多按一次是 no-op）。
     * 覆盖面（T1 真杀 / 重操作弃结果）见 [com.autoscript.domain.npm.PackageManagerFacade.cancelConsoleCommand]。
     */
    suspend fun cancelConsoleCommand(projectId: String): Boolean =
        throw com.autoscript.domain.core.AutojsException(
            com.autoscript.domain.core.ErrorCode.ERR_NOT_IMPLEMENTED,
            "控制台命令面未接线：本宿主没有接上 npm 命令执行入口（停止请求未执行）",
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
     * 按 lock 闭包回收 npm 缓存（§10.9 第 5 条「包大小管理页」的 cache clean 按钮）。
     *
     * 与依赖面板/镜像源同一条分工：**宿主自己的界面入口，不经桥** ——
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
     * 控制台**命令历史**读数（§10.9 第 3 条，2026-10-10 批 90）。
     *
     * 与 [consoleOutput] 的分工是**环 vs 盘**：输出环随进程消失，而"关掉控制台再进来
     * 还能翻回上次敲的那条"要靠落盘。历史由宿主在命令派发时记下
     * （`InstallCoordinator.runConsoleCommand`），本口只读。
     *
     * **按项目分开**：控制台是"命令跑在某个项目上"的面，历史跟着同一个作用域走
     * —— 在 A 项目敲的 `npm install axios` 翻到 B 项目去点，落的是 B 的
     * `node_modules`，而按钮上那行字一模一样。
     *
     * 缺省实现回空表（未接线 = 没有历史可补，如实）。
     */
    suspend fun consoleHistory(projectId: String): List<String> = emptyList()

    /**
     * 记一条控制台命令历史（写口，2026-10-10 批 90）。
     *
     * 由 `:ui` 在**派发点**调用（那里手上有用户敲的原文）；执行入口拿不到原文
     * （shell 面那条只收得到剥掉入口词的正文）。理由见门面同口的 KDoc。
     *
     * 缺省实现是空操作（未接线 = 不记，也不抛 —— 历史是便利面，为它让一条本来
     * 能跑的命令失败是本末倒置）。
     */
    suspend fun recordConsoleHistory(projectId: String, line: String) {}

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
