package com.autoscript.ui.state

import com.autoscript.domain.scripts.ScriptRuntimeLimits

/**
 * 设置页 → 脚本设置子页的呈现态（纯数据，Compose 之外可 JVM 测）。
 *
 * 与 [ScriptEnvState] 同款的形状（同一个界面的两页，故同一种读/写/草稿/回执四件套），
 * 但**多一件事**：五个草稿是**数字文本**而不是任意字符串，所以本页多一层「草稿 → 值」的
 * 解析（[draftToLimits]）与「值 → 草稿」的反向填充（[of]）。
 *
 * 两条纪律：
 * - **草稿是文本、值是 int，中间那道解析不许吞错**：[ScriptRuntimeLimits.reject] 只判
 *   「越界」，「这不是数字」是本层独有的形态（`:domain` 收不到非 int）。所以解析在这里，
 *   而**越界判据仍委托 `:domain`**（不抄第二份，见 [draftToLimits] 的 KDoc）。
 * - **保存前不清草稿**（与 [ScriptEnvState] 同款）：宿主拒绝时先清表会出现「界面上空了、
 *   盘上还是旧值」。
 */
data class ScriptLimitsState(
    val load: LoadState,
    /** 已读到的限额（读成功时非空；读失败时是**上次读到的**）。 */
    val limits: ScriptRuntimeLimits? = null,
    val loadError: String? = null,
    val opError: String? = null,
    val opNotice: String? = null,
    /** 五个草稿（文本；空串 = 用户没填这一格，保存时直接报错而不是当 0）。 */
    val draftHeapMb: String = "",
    val draftUvThreads: String = "",
    val draftConnInflight: String = "",
    val draftConnBytesMb: String = "",
    val draftConnThreads: String = "",
) {
    /**
     * 改**一个**草稿（界面每次按键走这里；五个字段各有一次 `copy` 会写五遍 —— 那正是
     * 「加一项要改六处」的形状）。
     *
     * 顺带清 [opError]：[opError] 说的是**上一次保存**被拒，而用户已经改了一个格子，
     * 那句报错已经不指向当前输入了（留着会让用户以为改了还得重按保存）。
     */
    fun setDraft(field: com.autoscript.ui.screens.ScriptLimitsDraft, value: String): ScriptLimitsState =
        copy(
            draftHeapMb = if (field == com.autoscript.ui.screens.ScriptLimitsDraft.HEAP) value else draftHeapMb,
            draftUvThreads = if (field == com.autoscript.ui.screens.ScriptLimitsDraft.UV) value else draftUvThreads,
            draftConnInflight = if (field == com.autoscript.ui.screens.ScriptLimitsDraft.INFLIGHT) value else draftConnInflight,
            draftConnBytesMb = if (field == com.autoscript.ui.screens.ScriptLimitsDraft.BYTES) value else draftConnBytesMb,
            draftConnThreads = if (field == com.autoscript.ui.screens.ScriptLimitsDraft.THREADS) value else draftConnThreads,
            opError = null,
        )

    companion object {
        val NOT_LOADED = ScriptLimitsState(LoadState.NotLoaded)

        /** 读到了：全量覆盖并**把草稿填成本次读到的值**（本页不是「新增行」，没有「空草稿」这个状态）。 */
        fun of(limits: ScriptRuntimeLimits, previous: ScriptLimitsState = NOT_LOADED) = previous.copy(
            load = LoadState.Loaded,
            limits = limits,
            loadError = null,
            draftHeapMb = limits.maxHeapMb.toString(),
            draftUvThreads = limits.uvThreadPoolSize.toString(),
            draftConnInflight = limits.bridgeInFlightPerConnection.toString(),
            draftConnBytesMb = limits.bridgeBytesPerConnectionMb.toString(),
            draftConnThreads = limits.bridgeReadThreadsPerConnection.toString(),
        )

        /** 读失败：保留 [previous] 的限额与草稿（一次瞬时失败不该把用户编好的值显示成空）。 */
        fun failed(t: Throwable, previous: ScriptLimitsState = NOT_LOADED) = previous.copy(
            load = LoadState.of(t),
            loadError = t.message ?: t.javaClass.simpleName,
        )

        /** 把五行草稿**填回出厂值**（界面上的「恢复出厂」按钮；不落盘，落盘由 ops 走宿主口）。 */
        fun resetDrafts(previous: ScriptLimitsState) = of(ScriptRuntimeLimits.DEFAULTS, previous)

        /**
         * 草稿 → 值。返回 null = 可提交；否则是**拒收原文**（直接显示给用户）。
         *
         * **判据分两半且各归其位**：「这不是数字」是本层独有的形态（`:domain` 收不到非 int），
         * 「这个数越界」由 [ScriptRuntimeLimits.reject] 说（它知道每项的上限是多少、为什么）。
         * 界面另判一遍上限必然与写入侧漂 —— 那是 [ScriptEnvKeys] KDoc 里点名的同款坑。
         *
         * **空草稿单独报错**而不是当 0：`0` 在这里是合法形状但**非法值**（脚本/连接会被直接
         * 掐死），报「不能填 0」会让人以为是范围问题；报「没填」更接近事实。
         */
        fun draftToLimits(state: ScriptLimitsState): Result<ScriptRuntimeLimits> {
            fun field(name: String, raw: String): Result<Int> {
                val text = raw.trim()
                if (text.isEmpty()) return Result.failure(IllegalArgumentException("$name 没填"))
                val n = text.toIntOrNull()
                    ?: return Result.failure(IllegalArgumentException("$name 不是整数：$text"))
                return Result.success(n)
            }
            val heap = field("单脚本堆上限（MB）", state.draftHeapMb)
            val pool = field("单脚本 IO 线程数", state.draftUvThreads)
            val inflight = field("每连接在途请求数", state.draftConnInflight)
            val bytes = field("每连接在途字节（MB）", state.draftConnBytesMb)
            val threads = field("每连接并发处理数", state.draftConnThreads)
            listOf(heap, pool, inflight, bytes, threads).firstOrNull { it.isFailure }
                ?.let { return Result.failure(it.exceptionOrNull()!!) }
            val limits = ScriptRuntimeLimits(
                maxHeapMb = heap.getOrThrow(),
                uvThreadPoolSize = pool.getOrThrow(),
                bridgeInFlightPerConnection = inflight.getOrThrow(),
                bridgeBytesPerConnectionMb = bytes.getOrThrow(),
                bridgeReadThreadsPerConnection = threads.getOrThrow(),
            )
            // 越界判据委托 :domain 的唯一一份 —— 本层不抄第二份上限表。
            ScriptRuntimeLimits.reject(limits)?.let { return Result.failure(IllegalArgumentException(it)) }
            return Result.success(limits)
        }
    }
}
