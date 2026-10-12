package com.autoscript

import com.autoscript.bridge.NewlineFrameServer
import com.autoscript.bridge.PerConnectionLimits
import com.autoscript.domain.scripts.ScriptRuntimeLimits

/**
 * 用户设的**每连接配额** → 桥侧闸的推送（批 107；§8.1 脚本资源限额 + §7 桥面三闸）。
 *
 * **它为什么是 `:app` 根包里一个独立小类**，而不是 `AppShellApplication` 的一个私有方法
 * （这是本类存在的全部理由，不是分层洁癖）：
 *
 * `:app` 的 `ArchitectureTest.桥只许装配包碰` 规定「除 `com.autoscript.shell` 装配包外，
 * 根包不许碰 `com.autoscript.bridge..`」。批 107 要在**保存限额**与**装填期**各推一次闸，
 * 那两个调用点都在 `AppShellApplication`（根包）。三条出路：
 *
 * 1. 把那条门整个对 `:app` 打开 —— 省事，但把一条边界一笔勾销，**不做**；
 * 2. 把两个调用点挪进 `com.autoscript.shell` —— 它们是「装配完之后把设置推给运行时」，
 *    不属于装配本身，而 `AppShellApplication` 正是那个「装配完」的角色，硬挪会让它依赖
 *    一个只为了绕过门而存在的类；
 * 3. **取这一条**：桥接触点收敛到一个**逐字点名**的小类，门上只多一条白名单。
 *
 * 选 3 的代价是本类只能做「翻译」这一件事（领域值 → 桥的闸），不能顺手干别的 ——
 * 那正是它的 KDoc 能写这么窄的原因。
 */
object BridgeQuotaWiring {

    /**
     * 把领域侧的限额翻译成桥的每连接闸并推给它（**立即生效**）。
     *
     * **不推会怎样**：闸是宿主进程里一份 `@Volatile` 当前值，而设置页会显示「已保存」——
     * 那正是本仓反复点名的「保存了却没生效」。故这里没有「下次重启自然好」这条路。
     */
    fun apply(server: NewlineFrameServer, limits: ScriptRuntimeLimits) {
        server.updatePerConnectionLimits(
            PerConnectionLimits(
                inFlight = limits.bridgeInFlightPerConnection,
                bytes = limits.bridgeBytesPerConnectionMb.toLong() * 1024 * 1024,
                concurrent = limits.bridgeReadThreadsPerConnection,
            ),
        )
    }
}
