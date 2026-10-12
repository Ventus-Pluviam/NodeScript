package com.autoscript.domain.scripts

/**
 * 脚本资源限额（批 107；docs §8.1「脚本资源限额」+ §7 桥侧三闸）。
 *
 * **它限的是两件不同的事**，这一点是这个类型存在的全部理由，所以先说清楚：
 *
 * | 档 | 字段 | 限的是谁 | 生效手段 |
 * |---|---|---|---|
 * | **单脚本** | [maxHeapMb] / [uvThreadPoolSize] | **这一个脚本进程**自己 | Node 的 `--max-old-space-size` / `UV_THREADPOOL_SIZE` |
 * | **桥** | [bridgeInFlightPerConnection] / [bridgeBytesPerConnectionMb] / [bridgeReadThreadsPerConnection] | **所有脚本合计**，按连接切 | `:bridge:java` 的每连接闸 + `limitedParallelism` |
 *
 * 混在一起是因为用户问的是同一个问题（「这个脚本最多能吃多少」），而它们**不是同一种资源**：
 * 把堆调到 64 MB 与把桥的在途字节调到 8 MiB 拒的是完全不同的请求。把两组分开做成两个页面
 * 会让用户以为「线程」和「内存」是同层的两个数 —— 它们是两层。
 *
 * **全局一份，不按项目分级**（与 [ScriptEnvStore] 同一条裁定）：今天没有项目级元数据面，
 * 而「某个项目多给点内存」不是本仓能验证的诉求（用户说不清_scripts_ 要多少堆，
 * 只能估 —— 估出来的数写进配置比不写更坏）。
 *
 * **缺省即 Node 自己的默认**（[uvThreadPoolSize] = 4）：这是刻意的 —— 一份没配过的配置
 * 不该改变任何行为。改动一律「用户显式改过才生效」，与 `ScriptEnvStore` 那条「每次 spawn
 * 现读」配套：现读是为了改完下次执行即生效，缺省是为了没改时与不装这个功能一样。
 */
data class ScriptRuntimeLimits(
    /** 单脚本 JS 堆上限（MiB），落 `--max-old-space-size`。 */
    val maxHeapMb: Int,
    /** 单脚本 libuv 后台线程池大小，落 `UV_THREADPOOL_SIZE`（Node 自己的缺省是 4）。 */
    val uvThreadPoolSize: Int,
    /** 每条桥连接的**在途请求条数**上限（外审 M2，全局闸仍另有 256）。 */
    val bridgeInFlightPerConnection: Int,
    /** 每条桥连接的**在途请求字节**上限（MiB，外审 M2；全局闸仍另有 16 MiB）。 */
    val bridgeBytesPerConnectionMb: Int,
    /** 每条桥连接的**并发处理请求数**上限（外审 M2 的 `limitedParallelism`）。 */
    val bridgeReadThreadsPerConnection: Int,
) {
    companion object {

        /**
         * 出厂值。
         *
         * 逐项的依据都写在决策记录里，但**选值纪律只有一条：缺省不得改变既有行为**
         * —— 唯一与现状不同的是桥侧三闸（现状是「无限制」，故填一个保守但够用的数，
         * 而不是 0 或 1 那种「装上就等于禁用」的值）。
         *
         * [uvThreadPoolSize] = 4 是 **Node 自己的默认**：那一项缺省与现状逐位相同。
         */
        val DEFAULTS: ScriptRuntimeLimits = ScriptRuntimeLimits(
            maxHeapMb = 192,
            uvThreadPoolSize = 4,
            bridgeInFlightPerConnection = 64,
            bridgeBytesPerConnectionMb = 32,
            bridgeReadThreadsPerConnection = 8,
        )

        /**
         * 逐项上限 —— **这一组数比缺省重要**，它们是「用户填什么都不会把设备撑死」的边界。
         *
         * 每一项都有一句为什么是这个数，而不是照着缺省乘个整数。
         */
        const val MAX_HEAP_MB: Int = 1024
        const val MAX_UV_THREADPOOL: Int = 64
        const val MAX_BRIDGE_INFLIGHT: Int = 256
        const val MAX_BRIDGE_BYTES_MB: Int = 256
        const val MAX_BRIDGE_READ_THREADS: Int = 64

        /**
         * 校验的**唯一一份**（`:ui` 的即时校验与 `:app` 的写入闸门共用，不抄两份 ——
         * 抄两份必漂，而漂的后果是「界面放行、写入拒绝」或更糟的「界面拒绝、写入放行」，
         * 与 [ScriptEnvKeys] 记的同一条理由）。
         *
         * @return null = 合法；否则是**拒收原文**（点名哪个字段、为什么、合法范围多少）——
         *   调用方原样交给用户，不加工成「参数非法」那种读不出所以然的措辞。
         */
        fun reject(limits: ScriptRuntimeLimits): String? {
            fun range(name: String, value: Int, max: Int): String? = when {
                value < 1 -> "$name 不得小于 1（0 会把脚本/请求直接掐死，不是「不限」）：$value"
                value > max -> "$name 不得超过 $max（再大就是拿设备内存当缓冲，设备直接 OOM 而现场看不出是哪条面干的）：$value"
                else -> null
            }
            return range("单脚本堆上限（MB）", limits.maxHeapMb, MAX_HEAP_MB)
                ?: range("单脚本 IO 线程数", limits.uvThreadPoolSize, MAX_UV_THREADPOOL)
                ?: range("每连接在途请求数", limits.bridgeInFlightPerConnection, MAX_BRIDGE_INFLIGHT)
                ?: range("每连接在途字节（MB）", limits.bridgeBytesPerConnectionMb, MAX_BRIDGE_BYTES_MB)
                ?: range("每连接并发处理数", limits.bridgeReadThreadsPerConnection, MAX_BRIDGE_READ_THREADS)
        }
    }
}