package com.autoscript.domain.permission

/**
 * 脚本来源分级（A5，§11 来源分级表）：宿主按**来源**决定一次执行拿到的 [CapabilityMask]。
 *
 * | 来源 | §11 信任级别 | 本枚举 | 本批能否产生 |
 * |---|---|---|---|
 * | 内置/作者签名模板 | 高 | [BUILT_IN] | ❌ 没有签名校验结果 |
 * | 打包分发脚本 | 中高 | [PACKAGED] | ❌ 打包器不写来源 |
 * | 用户自写脚本 | 中 | [USER_AUTHORED] | ❌ 没有"这是我写的"标记 |
 * | 第三方/市场脚本 | 低 | [THIRD_PARTY] | ❌ 没有市场导入记录 |
 * | （无证据） | — | [UNKNOWN] | ✅ **本批唯一可达的档** |
 *
 * **现在没有可用的来源元数据**：`ScriptProject` 只有 `id/name/version/mainScript/createdAtMillis`，
 * 仓库里没有签名校验结果、没有市场来源标记、没有「用户自写 vs 内置」的落盘字段。
 * 因此**生产缺省**不猜来源：一律 [UNKNOWN]（见 [TrustTierMasks.UNKNOWN_DEFAULT]）。
 * 装配层可以注入一个接了真元数据的 [TrustTierResolver]（见 [ScriptAuthorizationPolicy] 的
 * 「可注入」一条）来让某个项目落到别的档 —— 那时本枚举才可能有别的取值，且**只能来自
 * 宿主持有的元数据**。
 *
 * ⚠️ **[UNKNOWN] 与「已验证的低来源」是两回事**，不许合并：
 * - [UNKNOWN] = **没有证据**，只能靠保守缺省兜底；
 * - [THIRD_PARTY] = **有证据表明来自低信任来源**（市场导入记录、无签名…）。
 * 本批只有前者。将来接入 [TrustTierResolver] 时，实现方**必须**能区分这两者 ——
 * 把"查不到"当成"第三方"会让缺元数据的脚本被当成"已知的低信任"处理（方向不坏），
 * 但反过来把"第三方"当成"未知"就会丢掉那条证据。**任何情况下都不许从
 * payload / projectId / 项目名推断来源**（那是脚本自报，不是证据）。
 *
 * ⚠️ **等级只决定掩码，不构成沙箱**：低信任档拿到窄掩码，仍然与高信任档同 UID 同权，
 * Node 内建 `fs`/`http` 一样可用（§11.3 第 1 条）。别把本枚举读成「隔离级别」。
 */
enum class TrustTier {
    /** **本批唯一可达档**：没有任何来源证据（既不是"已验证高信任"，也不是"已验证低信任"）。 */
    UNKNOWN,

    /** 内置 / 作者签名模板：§11 表为「全量（含 root）」。**本批无来源可产生它。** */
    BUILT_IN,

    /**
     * 打包分发脚本：§11 表为「打包时配置」。
     *
     * ⚠️ **本批没有验证配置**：打包器不写来源标记、也没有"产物如何被验证"的定义。
     * 因此本档在 [TrustTierMasks.maskFor] 里 **fail closed** —— 落
     * [TrustTierMasks.UNKNOWN_DEFAULT]（保守档），**不是**全量。理由：§11 给这一档的
     * 能力是「**打包时配置**」，而"配置"此刻不存在；没有配置就没有依据，没有依据就不给
     * 高信任。真接入时先定义验证方式与配置载体，再把这一档改成按配置取掩码。
     */
    PACKAGED,

    /** 用户自写脚本：§11 表为「全量」+ 提示风险。**本批无来源可产生它。** */
    USER_AUTHORED,

    /** 第三方 / 市场脚本：**有证据的低信任来源**（市场导入记录等）。本批无来源可产生它。 */
    THIRD_PARTY,
}

/**
 * 来源分级 → 掩码的默认矩阵（A5，§11）。
 *
 * ## 已裁定：无来源证据档的缺省掩码（2026-10-08）
 *
 * 维护者 2026-10-08 拍板：**[TrustTier.UNKNOWN] 的缺省掩码 = 保守档 A**，即
 * [CapabilityMask.ALL] 减去 `CROSS_SCRIPT_CONTROL`、`CROSS_SCRIPT_OBSERVE` 两位，
 * 其余命名空间保持全量。这就是 [UNKNOWN_DEFAULT]。
 *
 * **2026-10-08 批 78 修订：`SCHEDULER_WRITE` 从保守档里放回去了**（原三位减成两位）。
 * 理由：批 76 已裁定**来源分级不做**（`design-decisions.md` 第 45 项），而这一位当初
 * 收走的理由与第 45 项自己写的「同 UID 脚本绕开桥直接往注册表追加一行即可绕过」**逐字
 * 相同** —— 它挡不住恶意脚本，只挡老实脚本。留一个「待来源元数据接入后恢复」的口子，
 * 而恢复它的那条路已被关掉，等于挂一句永远兑现不了的承诺。跨脚本那两位**不动**：
 * 它们的判据（低信任不得控制高信任执行）不依赖来源分级，且 [CrossScriptAuthorizer]
 * 的目标侧比较有独立的防护价值。
 *
 * ## 本批真正发生的行为变化（准确口径，别读成"保持现行行为"）
 *
 * 本批**没有**来源元数据，所以生产装配落 [TrustTier.UNKNOWN] 这一档。
 * 相对批 74 的现行行为，变化是：
 *
 * | 面 | 批 74（全量） | 本批缺省档 |
 * |---|---|---|
 * | a11y / screen / dialogs / floatingWindow / shell / device / app | 可用 | **仍可用**（不动） |
 * | notification / clipboard / sensors / settings / power_manager | 可用 | **仍可用**（不动） |
 * | datastore / zip / images / npm | 可用 | **仍可用**（不动） |
 * | `engines.status` / `engines.poolStats`（观察其他执行） | 可用 | **拒** `ERR_PERMISSION_DENIED` |
 * | `engines.stop` / `engines.exec`（控制其他执行、拉起新执行） | 可用 | **拒** |
 * | `workManager.create/cancel/list`（脚本建/删定时任务） | 可用 | **仍可用**（批 78 起） |
 * | `console.*` / `engines.heartbeat` / `engines.channel*` | 可用 | **仍可用**（`NONE` 要求） |
 *
 * 第四行**曾经**是功能回退（批 75 收走、批 78 放回）：缺来源元数据时脚本**可以**自建
 * 定时任务，与批 74 的现行行为一致。若将来真接了来源元数据、要按来源收窄这一位，
 * 由**装配层注入**一个带 [ScriptAuthorizationPolicy] 的策略（按项目给档），或者显式给
 * `capabilityMask` 覆盖 —— 不要用全局开关把整批授权绕过去。
 *
 * ## 其余档位：§11 冻结矩阵的原样，不是安全性论断
 *
 * - [TrustTier.BUILT_IN] / [TrustTier.USER_AUTHORED] / [TrustTier.THIRD_PARTY] →
 *   [CapabilityMask.ALL]：这是 §11 表格（内置=全量含 root、用户自写=全量、第三方=全量，
 *   沙箱已裁）的**逐字照搬**。**它不代表这些来源被验证过安全** —— 特别是
 *   [TrustTier.THIRD_PARTY]：§11 现行口径就是"第三方 = 全量 + 安装时告知"，掩码收窄
 *   第三方是**产品决定**，须与 `docs/design/11-security.md` 同批改，不在本批自行发明。
 * - [TrustTier.PACKAGED] → [UNKNOWN_DEFAULT]（**fail closed**，见该枚举项的 KDoc）。
 *
 * 改这里 = 改产品语义，须与 `docs/design/11-security.md` 同批。
 */
object TrustTierMasks {

    /**
     * 无来源元数据（[TrustTier.UNKNOWN]）的缺省档 = 已裁定的「保守档 A」：**排除跨脚本面**，
     * 其余全量（含排期写入 —— 批 78 放回 `SCHEDULER_WRITE`）。
     *
     * 收走的两位各有理由：
     * - `CROSS_SCRIPT_CONTROL` / `CROSS_SCRIPT_OBSERVE`：§11 与 §16 风险表点名
     *   「RuntimeChannel 按来源分级过滤；低信任不得控制高信任引擎」—— 这正是 A5 的目标。
     *
     * **`SCHEDULER_WRITE` 不在其中（批 78 放回）**：批 75 曾把它一并收走，理由是「改宿主
     * 任务注册表是持久副作用」；但那条理由与批 76 裁定来源分级不做时写下的
     * 「同 UID 脚本直接往注册表追加一行即可绕过」是同一条事实 —— 掩码收窄挡不住它。
     * 留着这一位 = 拿一个挡不住恶意的判据去罚老实脚本（脚本再也建不了定时任务，
     * 而 `workManager` 是 §14 P0 用户故事明写的闭环之一）。
     */
    val UNKNOWN_DEFAULT: CapabilityMask = CapabilityMask.ALL
        .minus(BridgeCapability.CROSS_SCRIPT_CONTROL)
        .minus(BridgeCapability.CROSS_SCRIPT_OBSERVE)

    /**
     * 矩阵本体（来源 → 掩码）。
     *
     * 逐档依据见 [TrustTierMasks] 的 KDoc：只有 [UNKNOWN_DEFAULT] 是 2026-10-08 裁定的
     * 缺省；其余三档照搬 §11 冻结矩阵（不是安全性论断），[TrustTier.PACKAGED] fail closed。
     */
    fun maskFor(tier: TrustTier): CapabilityMask = when (tier) {
        TrustTier.UNKNOWN -> UNKNOWN_DEFAULT
        // §11 冻结矩阵原样（全量）；**不是**"验证过安全"的论断 —— 见类 KDoc。
        TrustTier.BUILT_IN, TrustTier.USER_AUTHORED, TrustTier.THIRD_PARTY -> CapabilityMask.ALL
        // §11 写的是「打包时配置」，而本批没有配置载体/验证链 → 没有依据就不给高信任。
        TrustTier.PACKAGED -> UNKNOWN_DEFAULT
    }
}
