package com.autoscript.domain.scripts

/**
 * 项目号与项目内相对路径的值类（2026-10-12，批 109；外审「typed ids」那条建议）。
 *
 * ## 这一项要解决的是什么
 *
 * 此前这两类值在本仓是**裸 `String`**：`ScriptPaths.projectRoot(filesDir, projectId)` 与
 * `projectRoot(filesDir, scriptPath)` 长得一模一样，编译器分不出「项目号」与「项目内相对路径」
 * —— 而后者能带 `/`、前者不能。于是「把 A 传到 B 的参数上」这种错**编译得过**，只有当那份
 * 越界的值真的被拿去拼路径时才可能被 `require` 挡下；更常见的情形是**它根本没被挡下**，
 * 因为拼出来的路径恰好还在项目根之下（`a/b` 既是合法的多段相对路径，也是「项目 `a` 的子目录
 * `b`」）。值类把这条区分搬到**编译期**：构造出来的 [ProjectId] 按定义就不含 `/`。
 *
 * ## 边界收口，不铺开（维护者 2026-10-12 裁断）
 *
 * 值类只用在**两端**：不可信输入**进**来的那一步、拼路径**出**去的那一步。中间的 DTO 字段
 * （`InstallEvent.projectId`、`RunRecord.scriptPath`、`ScheduledTask.projectId` …）**仍是
 * `String`**。理由：把 `projectId` 铺开到全部 105 个 main 文件，得到的收益只是「更多地方
 * 在编译期多一次检查」，代价是**几乎全部是签名搬运 + 每处读点显式写 `.value`** ——
 * 回归面远大于收益，而两端收口已经把**真正会被外部输入驱动**的路径全覆盖了。
 *
 * **代价照实记**：DTO 边界上仍然可能把「项目号」的值塞进「相对路径」的字段，编译器不拦。
 * 那条缝要等 DTO 本身也类型化才关得上 —— 本批不假装已经关上了。
 *
 * ## 判据只有一份（这是本批的一半价值）
 *
 * 引入本文件之前，本仓有**三份**同一套的项目内相对路径判据、**三份**项目号判据：
 *
 * - 项目内相对路径：[com.autoscript.appservice.scriptrepo.core.DeployPath.isSafeRelPath]、
 *   `ScriptPaths.scriptFile` 内联的那份、以及 `ScriptDeployRecovery` 的**私有副本**
 *   （它当初刻意重写，是因为架构门禁禁止 scheduler 依赖同级 app-service 模块）；
 * - 项目号：`ScriptPaths.PROJECT_ID` 与 `FsProjectStore.idPattern`
 *   （后者收 `.` 且限长 64，与前者**不同** —— 批 109 按维护者裁断统一到前者）。
 *
 * 三份判据抄在一起，漂的方向最坏：放行的一侧比拒收的一侧宽，于是「界面能敲出来的项目号
 * 落盘时被 `require` 拒」，用户看到的是「执行失败」而不是「这个项目号不合法」。
 * 本文件是这两条判据的**唯一定义处**，其余引用点一律转调，**不再有副本**。
 *
 * ## 为什么不放公开的「不校验」工厂
 *
 * 从盘上读回的值（SQLite 的 `project_id` 列、`FileTaskStore` 的 `"project"` 字段、
 * `FsProjectStore.listIds()` 的目录名）**没有过判据**。本文件**刻意不提供**一个跳过
 * `init` 的后门来收编它们：那类后门一旦存在，`require` 就退化成「可选项」，而本项的全部价值
 * 就是那个 `require` 在。读回侧要构造 [ProjectId] 就得先过判据 —— 判不过就响亮地抛，
 * 这是本仓一贯的纪律（见 `PackSpec` 的「响亮失败」），不是本批新立的规矩。
 *
 * ## 空串 = 根层哨兵（2026-10-16）
 *
 * [ROOT] 是本类**唯一的例外**：空串不是项目号（[ScriptPaths.isValidProjectId] 对它回
 * false），它是"**没有项目**"的编码 —— `files/scripts/` 本层那些条目（`files/scripts/x.js`）。
 *
 * 为什么要这个哨兵而不是另造一套寻址：`(projectId, relPath)` 是本仓唯一的寻址形状
 * （文件清单行、执行口、意图日志、归档、在途表全用它）。而 `Path.resolve("")` 恒等自身，
 * 于是 `ScriptPaths.projectRoot(filesDir, ROOT)` 天然等于 `files/scripts/`、
 * `scriptFile(filesDir, ROOT, RelPath("x.js"))` 天然等于 `files/scripts/x.js` ——
 * **引擎侧一行都不用改**（`NodeProcessEngine` 里 `ProjectId(run.projectId)` 与 `cwd` 那两处
 * 照旧）。空串也不可能逃逸：resolve 一个空串不改变路径。
 *
 * **随之而来的危险面由调用方把闸**（哨兵一旦可构造，凡"以 id 拼路径"的地方都要问一句
 * "空串在这里是什么意思"）：已知且已堵的是 `FsProjectStore.create/delete` ——
 * `rootFor(ROOT)` 就是 `files/scripts` 本身，不堵的话 `delete` 会把整个库删掉。
 * 外部输入**进不来**这个哨兵：[createOrNull] 对空串回 null（界面/桥侧照旧拒空）。
 */
@JvmInline
value class ProjectId(val value: String) {

    /**
     * 判据收 [ScriptPaths.PROJECT_ID]（唯一一份，见本文件 KDoc），**外加空串这一个哨兵**。
     *
     * 注意判据里**没有 `.`**：原正则 `[A-Za-z0-9._-]+` 收 `.` 与 `..`，而
     * `projectsRoot.resolve("..")` 正好跳出项目根。批 84 已把它收掉，2026-10-16 放宽
     * 字符集（允许中文等）时**仍然保留** —— 放宽的从来不是防逃逸那一条。
     *
     * 空串放行**只为本类自己的 [ROOT]**：写法上仍推荐用具名常量而不是 `ProjectId("")`
     * 字面量（读代码的人不该靠"猜空串什么意思"）。
     */
    init {
        require(value.isEmpty() || ScriptPaths.isValidProjectId(value)) { "非法项目号: $value" }
    }

    /** 展示与日志用的原文（拼接路径、拼消息时才解包；判据不放行的是 [value] 本身）。 */
    override fun toString(): String = value

    companion object {

        /**
         * 根层哨兵：`files/scripts/` 本层（不是项目，是"没有项目"）。
         *
         * 见类 KDoc「空串 = 根层哨兵」。**这不是一个项目号** —— [ScriptPaths.isValidProjectId]
         * 与 [createOrNull] 都对它回否；它是 [value] 为空串的那个 [ProjectId]，给
         * `ScriptFileRow.projectId` 这类"必须有值"的位置一个诚实的编码。
         */
        val ROOT: ProjectId = ProjectId("")

        /**
         * 收外部输入的入口：非法回 null（**不抛**），由调用方决定折成哪个错误码。
         *
         * 为什么不让调用方直接 `ProjectId(raw)` 捕获 IAE：桥侧两个 ingress
         * （`NpmBridgeHandler` 的 `npm.*` 与 `EnginesNamespaceHandler` 的 `engines.exec`）
         * 拿到的都是脚本给的值，它们的错误语义是 `ERR_INVALID_PARAM` + **话术**，
         * 与 `ProjectId` 的 `init` 话术（「非法项目号」）不是同一条。后者说的是「这串东西
         * 不是项目号」，前者要说「这个参数你填错了」—— 两个身份，别混。
         */
        fun createOrNull(raw: String): ProjectId? =
            if (ScriptPaths.isValidProjectId(raw)) ProjectId(raw) else null
    }
}

/**
 * 项目内相对路径的值类（§9.6 口径）：相对、非空、段内无 `..`/`.`/空段/反斜杠。
 *
 * **判据搬进 [init] 的意义不止于类型**：`DeployPath.resolveIn` 与 `ScriptPaths.scriptFile`
 * 此前各自**再判一遍**同一套规则，于是「拼路径的那一步」实际上判了两次。现在构造过一次
 * 之后，拿到 [RelPath] 的任何代码都不需要再判 —— 也**不该**再判。
 *
 * **纯词法，不防符号链接**：见 `DeployPath.resolveIn` 的 KDoc，那里已如实分开记
 * 「判据不够强」与「当下可利用」。本类不打算改这条 —— 改它要 `toRealPath()`，
 * 而那要求目标已存在，会否掉「部署晚于调度」的脚本路径。
 */
@JvmInline
value class RelPath(val value: String) {

    init {
        require(isSafe(value)) { "非法项目内相对路径: $value" }
    }

    override fun toString(): String = value

    companion object {

        /**
         * 本仓对「项目内相对路径」**唯一一份**判据（原先散在三处，见本文件 KDoc）。
         *
         * 为什么把「.`」也拒掉：`projectsRoot.resolve(".")` 就是项目根本身，于是
         * 「项目 `.npmrc`」会落在共享目录上；而多写一个 `.` 的相对路径在文件系统上
         * 与同名路径等价，收掉它只是让同一件事有唯一的写法。
         */
        fun isSafe(relPath: String): Boolean {
            if (relPath.isBlank()) return false
            if (relPath.startsWith("/") || relPath.startsWith("\\")) return false
            return relPath.split('/').all { it != ".." && it != "." && it.isNotEmpty() && !it.contains('\\') }
        }

        /** 收外部输入的入口：非法回 null（与 [ProjectId.createOrNull] 同款分工）。 */
        fun createOrNull(raw: String): RelPath? = if (isSafe(raw)) RelPath(raw) else null
    }
}
