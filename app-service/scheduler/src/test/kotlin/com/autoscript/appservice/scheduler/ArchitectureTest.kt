package com.autoscript.appservice.scheduler

import com.autoscript.build.ArchGate
import org.junit.jupiter.api.Test

/**
 * 依赖方向守护（docs §3/§4.1 + 模块表 §6）：
 * scheduler 只能依赖 :domain 的脚本/模型 + 桥的**转接面**，禁止其他 app-service、引擎 SPI、平台、Android。
 *
 * 黑名单必须覆盖：本模块之外的全部 app-service 同级模块 + 引擎 SPI + 平台 + Android。
 * 注意 ArchUnit 的 com.autoscript.engine.. 并不匹配 com.autoscript.domain.engine（引擎 SPI），
 * 故 domain.engine 必须显式列出（设计 §6：scheduler 只许碰 RunRecord 等脚本模型）。
 *
 * **`domain.bridge..` 已撤（审查步骤 6）**：`WorkManagerNamespaceHandler`
 * 自 `:app` 迁入本模块后，桥转接面成为本模块的正常消费 —— 量化：仅
 * BridgeRequest / BridgeResponse / RpcNamespaceHandler 三型（handler 折信封），
 * 不进 `domain.engine`/`domain.automation`/平台/Android（那些仍在黑名单）。
 * 规则形状（import + noClasses + resideIn + dependOnAny）见共享 [ArchGate]。
 */
class ArchitectureTest {

    @Test
    fun `scheduler 包零跨层泄漏`() = ArchGate("com.autoscript.appservice.scheduler").noLeakTo(
        "android..",
        "com.autoscript.bridge..",
        "com.autoscript.platform..",
        "com.autoscript.engine..",
        // 引擎 SPI 所在的领域包（仅 :domain 脚本模型可用）；
        // domain.bridge.. 已撤：WorkManagerNamespaceHandler 迁入后转接面属正常消费（见类 KDoc 量化）
        "com.autoscript.domain.engine..",
        "com.autoscript.domain.permission..",
        "com.autoscript.domain.automation..",
        // 同级 app-service 模块
        "com.autoscript.appservice.scriptrepo..",
        "com.autoscript.appservice.runtime..",
        "com.autoscript.appservice.permissioncenter..",
        "com.autoscript.appservice.packager..",
    )
}
