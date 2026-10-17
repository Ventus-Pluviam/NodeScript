package com.autoscript.domain.permission

/**
 * 桥面命名空间的能力申报（A5，§11）：路由层按这张表把「命名空间 + 方法」翻译成所需
 * [CapabilityMask]，再要求本次执行的掩码覆盖它（deny-by-default）。
 *
 * **为什么住在 `:domain`**：桥路由（`:bridge:java`）与来源授权策略（`:domain`）都要读它，
 * 而 `:bridge:java` 只许依赖 `:domain`（§4.1）—— 表放这里两端都看得见，且它是**策略数据**，
 * 不是某个 handler 的实现细节。
 *
 * **用的是 [BridgeCapability] 而不是设备能力 [Capability]**：本表只回答「桥面命名空间要哪张
 * 票」，与「系统给不给这个 App」是两张正交的目录（见 [BridgeCapability] 的 KDoc）。
 * 各 handler 的既有设备门禁**照旧执行**，本表不接管、不替代它们。
 *
 * **与 wire 方法表（`WireMethods.BY_NS`）的关系**：那是「有哪些命名空间/方法」的事实来源
 * （由 `bridge/schema/wire.schema.json` 生成，`wire-schema.test.cjs` 四向对账）；
 * 本表是「每个面要哪张能力票」的**策略**。两者都不许手抄对方的清单：本表只申报
 * 命名空间级的必需能力 + 少数方法级的覆盖要求，未申报的命名空间按
 * [UNKNOWN_NAMESPACE_REQUIRED]（默认拒）处理 —— 新挂一个命名空间忘了申报，
 * 结果是「拒绝调用」而不是「悄悄全通」。
 *
 * **命名空间清单以实际 handler 为准**（`register("…")` ↔ schema 双向相等由
 * `wire-schema.test.cjs` 钉）：本表覆盖 schema 里现有的 19 个命名空间。
 * 不发明 schema 里没有的命名空间（如 `files`）。
 */
object BridgeCapabilityCatalog {

    /**
     * 未申报命名空间的必需能力：**全量**（= 未申报即拒）。
     *
     * 为什么不是 `NONE`（谁都能调）：`NONE` 会让「新挂的命名空间忘登记」静默变成
     * 一个新的全通面 —— 那正是 A5 要堵的方向。反过来，`ALL` 让遗漏表现为
     * `ERR_PERMISSION_DENIED`，是响亮且可诊断的。
     */
    val UNKNOWN_NAMESPACE_REQUIRED: CapabilityMask = CapabilityMask.ALL

    private val FILESYSTEM: CapabilityMask =
        CapabilityMask.of(BridgeCapability.FILESYSTEM_ACCESS, BridgeCapability.DEVICE_DATA_READ)

    /**
     * 命名空间 → 必需能力。只列**策略上非同质**的那些；其余见 [NAMESPACE_REQUIRED] 的兜底表。
     *
     * 分档口径（与 §11「来源分级」对应）：
     * - 本地数据面（`datastore`）：只要 `LOCAL_STORAGE`；
     * - 文件面（`zip`/`images` 的读图）：`FILESYSTEM_ACCESS` + 各自的设备面；
     * - 自动化面（`a11y`/`screen`/`dialogs`/`floatingWindow`/`shell`/`device`/`app`/
     *   `notification`/`clipboard`/`sensors`/`settings`/`power_manager`）：对应的设备能力；
     * - 执行面（`engines`）：见 [METHOD_REQUIRED] —— `poolStats` 只读、`exec` 最重；
     * - `npm`：供应链面（写文件 + 跑安装），需 `PACKAGE_INSTALL`；
     * - `console`：日志归属面，任何已认证执行都有（它本身不触达设备）。
     */
    private val NAMESPACE_REQUIRED: Map<String, CapabilityMask> = mapOf(
        // 本地数据 / 归档 / 图像文件面
        "datastore" to CapabilityMask.of(BridgeCapability.LOCAL_STORAGE),
        "zip" to FILESYSTEM,
        "images" to FILESYSTEM,

        // 自动化设备面
        "a11y" to CapabilityMask.of(BridgeCapability.ACCESSIBILITY, BridgeCapability.INPUT_INJECTION),
        "screen" to CapabilityMask.of(BridgeCapability.SCREEN_CAPTURE),
        "dialogs" to CapabilityMask.of(BridgeCapability.OVERLAY),
        "floatingWindow" to CapabilityMask.of(BridgeCapability.OVERLAY),
        "shell" to CapabilityMask.of(BridgeCapability.DEVICE_CONTROL),
        "device" to CapabilityMask.of(BridgeCapability.DEVICE_DATA_READ),
        "app" to CapabilityMask.of(BridgeCapability.DEVICE_CONTROL),
        "notification" to CapabilityMask.of(BridgeCapability.NOTIFICATION),
        "clipboard" to CapabilityMask.of(BridgeCapability.CLIPBOARD),
        "sensors" to CapabilityMask.of(BridgeCapability.SENSORS),
        "settings" to CapabilityMask.of(BridgeCapability.DEVICE_CONTROL),
        "power_manager" to CapabilityMask.of(BridgeCapability.DEVICE_CONTROL),

        // 跨脚本执行面：命名空间级取最重档（未列出的方法落这里 = 拒，见 [METHOD_REQUIRED]）
        "engines" to CapabilityMask.of(BridgeCapability.CROSS_SCRIPT_CONTROL),

        // 供应链面
        "npm" to CapabilityMask.of(BridgeCapability.PACKAGE_INSTALL, BridgeCapability.FILESYSTEM_ACCESS),

        // 调度面（写任务注册表 = 改宿主状态）
        "workManager" to CapabilityMask.of(BridgeCapability.SCHEDULER_WRITE),

        // 日志面：归属由连接身份决定，不需要设备能力（缺身份仍单独拒，见 ConsoleCollector）
        "console" to CapabilityMask.NONE,
    )

    /**
     * 方法级要求：**整体覆盖**该方法的命名空间级要求（不是并集）。
     *
     * 为什么必须是覆盖而不是并集：`engines` 的命名空间级要求是「控制」这一最重档，
     * 并集会让最轻的 `poolStats` 也被要求控制权 —— 分档就白分了。覆盖的代价是
     * 「方法级漏写」会落到命名空间级（= 重档），失败方向仍然是**拒**，不是放行。
     * 只列策略上与其他方法不同档的那些；未列出的方法取命名空间级要求。
     *
     * **`engines.stop` / `engines.status` 是本表最微妙的两条**（A5 整改第 1 条）：
     * 它们既要能**触达别的执行**（那时才需要跨脚本位），又要**任何掩码都能停/看自己**
     * （`EngineSessionImpl.cancel`/`onExit` 就靠这条）。所以本表把它们的**目录级**要求
     * 降到 `NONE`，把「是不是别的执行」这个判据**下沉到 handler**：handler 拿真实
     * caller（`AuthenticatedRunContext.engineRunId`）与目标 `runId` 比，只在对**别的**
     * 执行时才经 `CrossScriptAuthorizer` 要 `CROSS_SCRIPT_CONTROL`/`CROSS_SCRIPT_OBSERVE`。
     *
     * 为什么不能留在目录里：路由层的判据只有「调用方掩码 vs 目录要求」，看不到 runId
     * （那是 payload），所以目录一旦写 `CROSS_SCRIPT_CONTROL`，窄掩码脚本连**自己**都停不掉
     * —— 目录会把自我操作也一并拒掉。这属于「同一方法对不同目标是不同档」，
     * 只有 handler 有足够信息分档。**代价如实说**：`engines` 命名空间级的重档现在
     * 只由 handler 兜（不再有路由层的那道闸），所以 `exec`/未知方法仍**显式**列在本表里，
     * 未列出的方法落命名空间级 `CROSS_SCRIPT_CONTROL`（仍是拒）。
     *
     * `engines` 其余分档：
     * - `poolStats` 只读**池容量**（全局视图：`{capacity,free,busy}`，不分调用方 ——
     *   故要观察位；它暴露的是宿主有多少槽位在跑，不是"你自己的池视图"）；
     * - `channel*` 是脚本**自有**命名通道（按发起执行私有，见 `EnginesNamespaceHandler`
     *   的「命名通道」段），不触达其他执行、不改宿主状态 → `NONE`；
     * - `heartbeat` 由连接身份单独约束（只能替自身打点），掩码上不再加码；
     * - `stdinIdle` 同款：只关**自己**那条执行的 stdin 写端（runId 来自连接身份，
     *   payload 里根本没有这个字段）→ `NONE`；
     * - `exec` 拉起**新执行**（新进程 + 新桥连接 + 新掩码）→ 最重，加 `SCHEDULER_WRITE`。
     */
    private val METHOD_REQUIRED: Map<Pair<String, String>, CapabilityMask> = mapOf(
        ("engines" to "exec") to
            CapabilityMask.of(BridgeCapability.CROSS_SCRIPT_CONTROL, BridgeCapability.SCHEDULER_WRITE),
        // 自我放行在 handler：目录要 NONE，跨脚本位只在对**别的**执行时才要（见上）。
        ("engines" to "stop") to CapabilityMask.NONE,
        ("engines" to "status") to CapabilityMask.NONE,
        ("engines" to "poolStats") to CapabilityMask.of(BridgeCapability.CROSS_SCRIPT_OBSERVE),
        ("engines" to "heartbeat") to CapabilityMask.NONE,
        ("engines" to "channel") to CapabilityMask.NONE,
        ("engines" to "channelEmit") to CapabilityMask.NONE,
        ("engines" to "channelDrain") to CapabilityMask.NONE,
        ("engines" to "channelClose") to CapabilityMask.NONE,
        // 关的是**调用方自己**那条执行的 stdin 写端（目标 runId 取自认证连接身份，
        // 不是 payload）—— 触达不到别的执行、也不改宿主状态，与 stop/status 同一条
        // "自我操作不需要跨脚本位"口径（归属判据在 handler：根本没给 runId 这个入口）。
        ("engines" to "stdinIdle") to CapabilityMask.NONE,
    )

    /** 命名空间级要求；未申报 → [UNKNOWN_NAMESPACE_REQUIRED]（默认拒）。 */
    fun namespaceRequired(namespace: String): CapabilityMask =
        NAMESPACE_REQUIRED[namespace] ?: UNKNOWN_NAMESPACE_REQUIRED

    /**
     * 「命名空间 + 方法」的必需能力：方法级条目**整体覆盖**命名空间级，未列出则取命名空间级。
     * 两个方向都偏「拒」——未申报命名空间拒、方法级漏写落重档。
     */
    fun required(namespace: String, method: String): CapabilityMask =
        METHOD_REQUIRED[namespace to method] ?: namespaceRequired(namespace)

    /** 已申报的命名空间集合（诊断/对账用；**不含**兜底拒绝的那些）。 */
    fun declaredNamespaces(): Set<String> = NAMESPACE_REQUIRED.keys.toSet()
}
