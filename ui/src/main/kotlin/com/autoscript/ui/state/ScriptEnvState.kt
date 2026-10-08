package com.autoscript.ui.state

import com.autoscript.domain.scripts.ScriptEnvEntry
import com.autoscript.domain.scripts.ScriptEnvKeys

/**
 * 管理面板 → 环境变量页的呈现态（纯数据，Compose 之外可 JVM 测）。
 *
 * 这一屏是**宿主配置**，不是某次执行的读数 —— 与 [NpmState]/[ConsoleState] 的区别在
 * 「有没有'读到了'这件事」：它确实要读盘（读失败得如实说），故仍带 [LoadState]。
 *
 * 三条纪律：
 * - **读失败保留已读到的表**（与 [NpmState] 同款）：一次瞬时 IO 失败不该把用户编好的
 *   变量显示成空表 —— 那会让人以为"我设的全没了"；
 * - **校验的"形状层"在这里**（[validate]）：`@Composable` 里的判断进不了 `:ui` 单测门
 *   （那台门跑的是 JVM，compose 不在其上），而"哪个键名不合法"正是最该被测的一句
 *   —— 与 `RegistrationForm` 把解析搬出 Compose 是同一条理由；
 * - **空串是合法值**：`ScriptEnvEntry.value` 为 `""` 与"这个键没设"是两回事，
 *   列表里画成 `KEY=`（不是"未设置"），与 `:domain` `StoredEntry.Json` 的口径一致。
 *
 * 语义层（保留前缀等**真正的**合法性判据）不在这里抄一份：[validate] 直接调
 * [ScriptEnvKeys.reject]（`:domain` 的唯一一份），故界面放行的写入侧必然也放行。
 */
data class ScriptEnvState(
    val load: LoadState,
    /** 已读到的表（按 key 升序，排序归读口）。 */
    val entries: List<ScriptEnvEntry> = emptyList(),
    /** 上一次读失败的原文（[load] 已是 [LoadState.Failed]；此字段供"保留旧表"时仍能显示原因）。 */
    val loadError: String? = null,
    /** 上一次写/删操作的失败原文（≠ 读失败：两条账分开，与 [NpmState] 同款）。 */
    val opError: String? = null,
    /** 上一次写/删操作的结论回执（现取即清，不缓存）。 */
    val opNotice: String? = null,
    /** 新增行里正在编辑的两个格子。 */
    val draftKey: String = "",
    val draftValue: String = "",
) {
    companion object {
        val NOT_LOADED = ScriptEnvState(LoadState.NotLoaded)

        /** 读到了：全量覆盖（宿主表是权威，不累积）。**不清**草稿与操作回执（与本次读无关）。 */
        fun of(entries: List<ScriptEnvEntry>, previous: ScriptEnvState = NOT_LOADED) = previous.copy(
            load = LoadState.Loaded,
            entries = entries,
            loadError = null,
        )

        /**
         * 读失败：**保留 [previous] 的 [entries]**（见类 KDoc），只换读态与原因。
         * 失败态下的表是"上次读到的"，界面必须照实说（顶部一行），不能当成本次读数。
         */
        fun failed(t: Throwable, previous: ScriptEnvState = NOT_LOADED) = previous.copy(
            load = LoadState.of(t),
            loadError = t.message ?: t.javaClass.simpleName,
        )

        /**
         * 校验新增行（形状层）。返回 null = 可以提交；否则是**拒收原文**（直接显示给用户）。
         *
         * 两句话各自点名：键不合法由 [ScriptEnvKeys.reject] 说（它知道为什么），
         * 空键在这里补一句（`reject` 也拦空串，但"变量名不得为空"对空输入来说太绕，
         * 界面直接说"请填变量名"更好读 —— 判据不重复，措辞分家）。
         */
        fun validate(draftKey: String): String? {
            val k = draftKey.trim()
            if (k.isEmpty()) return "请填变量名"
            return ScriptEnvKeys.reject(k)
        }
    }
}
