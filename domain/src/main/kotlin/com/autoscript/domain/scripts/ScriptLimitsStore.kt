package com.autoscript.domain.scripts

/**
 * 脚本资源限额的领域契约（批 107；docs §8.1「脚本资源限额」）。
 *
 * 这是设置页「脚本设置」那一行底下的面：用户编一组**当前值**（不是账本），
 * 单脚本那半在每次 spawn 现读，桥那半由 `:bridge:java` 每次请求现读。
 *
 * **为什么住 `:domain`**：读方三处都要拿到同一份类型与**同一份校验判据** ——
 * `:engine:node-process`（spawn 前不校验就等于没有闸）、`:bridge:java`（闸的值）、
 * `:ui`（即时校验）。放任何一个实现模块都会让另两处反向依赖它。形状与
 * [ScriptEnvStore] 逐字同款，**刻意不做第三个存储接口**：限额是「当前值」不是账本，
 * 它不需要 append、不需要墓碑、不需要幂等锚点 ——
 * `[load]` 回一份不可变快照、`[save]` 覆盖一次，够了的那些就是够了的。
 *
 * **零 Android、零 IO**：`:domain` 不碰 `java.nio` 也没有平台面，故接口在此、
 * 落盘实现在 `:app-service:script-repo`（与 [ScriptEnvStore] 同模块同纪律）。
 */
interface ScriptLimitsStore {

    /**
     * 读当前限额。
     *
     * **读不到抛，不回缺省**：缺省值长得很像「用户填的」，回它会把「盘读不出来」画成
     * 「你的设置重置成出厂了」（与 `HostSummary.scriptEnv` 同一条纪律）。文件不存在
     * **不是**读不到 —— 那是合法的「从没改过」，实现自己回 [ScriptRuntimeLimits.DEFAULTS]。
     */
    fun load(): ScriptRuntimeLimits

    /**
     * 保存（**覆盖**整份，不是逐项合并）。
     *
     * [limits] 不过 [ScriptRuntimeLimits.reject] 时**抛** [IllegalArgumentException]，
     * 原文点名是哪个字段越界 —— 判据的唯一一份在 `:domain`，实现方与 `:ui` 都不另判一遍。
     *
     * **覆盖而不是合并**是刻意的：合并会让「界面上没显示的那些字段」悄悄保持旧值，
     * 而本页的五项是全部可改的，没有「隐藏字段」这回事。
     */
    fun save(limits: ScriptRuntimeLimits)
}