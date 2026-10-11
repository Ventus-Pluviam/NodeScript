package com.autoscript.platform.system

import com.autoscript.build.ArchGate
import org.junit.jupiter.api.Test

/**
 * 依赖方向守护（docs §6 模块表）：`:platform:system` 只实现 `:domain` 的 SPI，不反向；
 * 禁服务逻辑（`:app-service:*`）、禁桥/引擎直连、禁 UI。
 *
 * `com.autoscript.bridge..` 整体在黑名单里：本模块实现 `NamespaceHandler`（住 `:domain`
 * 的桥面形状，审查步骤 6 起 handler 与实现同模块 —— 原「handler 在 capabilities」
 * 口径已反转），但**挂 Router 归装配层**（`:app` `PlatformWiring`/`AppShell`）——
 * 本模块不出桥实现层（§12.2「分两层」）。
 * `HandleRef` 等挂载缝类型住 `:domain`，依赖它不等于依赖桥实现层。
 *
 * `:platform:process`（进程/`/proc` 收尸面，批 105）**逐字点名放行**：shell 收尸那份
 * `ProcOps` 副本合并进去了。其余 `:platform:system..` 同级模块仍零依赖。
 */
class ArchitectureTest {

    @Test
    fun `platform system 包零跨层泄漏`() = ArchGate(
        "com.autoscript.platform.system",
        rulePackage = "..platform.system..",
    ).noLeakTo(
        "androidx..",
        "com.autoscript.bridge..",
        "com.autoscript.engine..",
        "com.autoscript.appservice..",
        "com.autoscript.platform.capabilities..",
        "com.autoscript.platform.editor..",
        "java.awt..",
        "javax.swing..",
    )
}
