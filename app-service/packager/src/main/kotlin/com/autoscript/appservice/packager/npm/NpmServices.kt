package com.autoscript.appservice.packager.npm

/**
 * [InstallCoordinator] 的协作者束（§10.2 装配面）。
 *
 * 为什么收拢：协调器的构造参数一度膨胀到 15 个——其中**全部**是「外部协作者」
 * （layout/journal/staging/ledger/...）。分组不是为了好看：参数超过 ~6 个之后，
 * 「测试要覆盖某个组合」就得逐个对名字传参，命名参数写的顺序漂一格就静默串位
 * （类型相同的情况下编译器不报）；而真装配层（AppShell/Application）构造时
 * 一眼能看出「这些是同生共死的一组」。
 *
 * 边界（刻意不收进来的）：
 * - **探针/时钟/配置**（freeSpaceProbe/now/config/npmCacheDir）不收：它们不是协作者
 *   而是协调器自己的行为参数。收进来会让「换一份 layout 顺手换掉时钟」变得合法——
 *   那是两件不相干的事，分开才不会被顺手绑走。
 * - **executor**（[InstallCoordinator.HeavyOpExecutor]）不收：它是每次装配都可能不同的
 *   执行体（真引擎/测试假造/E2E 真 npm），与「持久协作者」生命周期不同。
 * - **Kotlin data class 而不 fun interface**：协作者之间无行为关联，收成接口就是硬造抽象。
 *
 * null 语义（与旧构造逐字一致）：可空成员表示「未接线」——**装配缺口**，不等于
 * 「不需要该能力」。协调器对每个 null 都有显式降级路径（history 缺 → 不入史；
 * lockSigner 缺 → ci 不验签直接走；snapshots 缺 → 导入该格式时 ERR_NOT_IMPLEMENTED；
 * registryVerifier 缺 → 校验步骤静默跳过……）。何时补齐由装配层定，协调器不假装。
 */
data class NpmServices(
    val layout: NpmProjectLayout,
    val journal: InstallJournal,
    val staging: InstallStaging,
    val ledger: ApprovalLedger,
    val history: InstallHistory? = null,
    val lockSigner: LockSigner? = null,
    val snapshots: NpmSnapshot? = null,
    val cacheIndex: CacheIndex,
    val bundleImporter: NpmOfflineBundleImporter? = null,
    /** 多镜像交叉校验缝（§10.5-1）；null = 未接线，install 跳过该校验（不假装验过）。 */
    val registryVerifier: RegistryVerifier? = null,
    /**
     * 本次首选注册表解析（读项目 `.npmrc` 的 `registry=`，§10.2）。
     *
     * 可空而非直接给默认实现：缺省实现是**协调器私有知识**（读自己那份 layout），
     * 放在这里会让「测试显式要求不读配置」与「调用方没意见」无法区分——
     * 旧构造参数踩过这个坑（data class 默认值会被测试的 null 覆盖成「永远不知道」）。
     */
    val registryOf: ((String) -> String?)? = null,
)
