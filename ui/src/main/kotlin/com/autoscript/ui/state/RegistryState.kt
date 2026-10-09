package com.autoscript.ui.state

import com.autoscript.domain.npm.NpmRegistryKeys
import com.autoscript.domain.npm.NpmRegistrySnapshot

/**
 * 管理面板 → 镜像源管理页的呈现态（纯数据，Compose 之外可 JVM 测）。
 *
 * 形状照 [ScriptEnvState]：它同样是**宿主配置**（读盘、写盘、有"读到了"这件事），
 * 而不是某次执行的读数。
 *
 * 三条纪律：
 * - **读失败保留已读到的值**（与 [NpmState]/[ScriptEnvState] 同款）：一次瞬时失败
 *   不该把用户设的镜像源显示成"恢复出厂了"；
 * - **校验的"形状层"在这里**（[validate]），但它**不自己判 URL** —— 直接调
 *   `:domain` 的 [NpmRegistryKeys.reject]（与宿主写入侧同一份判据）。界面另判一遍
 *   必然与写入侧漂移，而漂移的方向最坏：界面放行的串在写入侧被拒。
 * - **空输入 = 恢复出厂缺省**，不是"把镜像源设成空串" —— 这两句话在实现上是
 *   「删键」与「写一个空值行」，后者会让 npm 拿到一个空 registry 而每次安装都失败。
 */
data class RegistryState(
    val load: LoadState,
    /** 已读到的读数；null = 还没读到（与"读到了、没设过"是两句不同的话，后者 [configured] 为 null）。 */
    val snapshot: NpmRegistrySnapshot? = null,
    /** 上一次读失败的原文（[load] 已是 [LoadState.Failed]；供"保留旧值"时仍能显示原因）。 */
    val loadError: String? = null,
    /** 上一次写操作的失败原文（≠ 读失败：两条账分开）。 */
    val opError: String? = null,
    /** 上一次写操作的结论回执（现取即清，不缓存）。 */
    val opNotice: String? = null,
    /** 输入框里正在编辑的地址（初值由 [of] 从读到的值灌入）。 */
    val draft: String = "",
) {
    /** 当前生效的那一家（没读到时为 null —— 界面据此显示"尚未读取"，不显示成官方源）。 */
    val effective: String? get() = snapshot?.effective

    /** 用户改过没有（决定「恢复出厂」是否可用；未读到时为 false）。 */
    val customized: Boolean get() = snapshot?.customized == true

    companion object {
        val NOT_LOADED = RegistryState(LoadState.NotLoaded)

        /**
         * 读到了：全量覆盖读数，并把 [draft] 灌成当前生效值。
         *
         * **灌草稿只在草稿为空时做**：用户正在输入时来一次刷新（切页签回来），
         * 把他打的半截地址冲掉是最烦人的那种"帮忙"。
         */
        fun of(snapshot: NpmRegistrySnapshot, previous: RegistryState = NOT_LOADED) = previous.copy(
            load = LoadState.Loaded,
            snapshot = snapshot,
            loadError = null,
            draft = previous.draft.ifEmpty { snapshot.effective },
        )

        /** 读失败：**保留 [previous] 的读数**，只换读态与原因（见类 KDoc）。 */
        fun failed(t: Throwable, previous: RegistryState = NOT_LOADED) = previous.copy(
            load = LoadState.of(t),
            loadError = t.message ?: t.javaClass.simpleName,
        )

        /**
         * 校验输入框。返回 null = 可以提交（含"空输入 = 恢复出厂"）；否则是**拒收原文**。
         *
         * 空输入的放行由 [NpmRegistryKeys.reject] 自己表达（它对空白回 null），
         * 本函数不另判一次 —— 判据只有一处。
         */
        fun validate(draft: String): String? = NpmRegistryKeys.reject(draft)
    }
}
