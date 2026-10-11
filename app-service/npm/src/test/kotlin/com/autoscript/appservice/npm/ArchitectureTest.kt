package com.autoscript.appservice.npm

import com.autoscript.build.ArchGate
import org.junit.jupiter.api.Test

/**
 * 依赖方向守护（docs §6）：npm 只依赖 `:domain` 与 `:platform:process`，零 Android /
 * 零桥 / 零引擎 / 零姐妹服务（含原属同一模块的 `:app-service:packager` —— 2026-09-30
 * 拆分后是同级，编译期已无 Gradle 依赖边，这里再按字节码钉一道）。
 *
 * `:platform:process` 是**逐字点名放行的那一条**（批 105）：进程/`/proc` 面（杀树收尸）
 * 合并进来后，本模块的 T1 杀树不再自带副本。放行粒度到**子包** —— 其余
 * `com.autoscript.platform..`（capabilities/editor/system 三面）仍然零依赖，
 * 「进程面」不得变成「平台面」的入口。
 */
class ArchitectureTest {

    @Test
    fun `npm 包零跨层泄漏`() = ArchGate(
        "com.autoscript.appservice.npm",
        rulePackage = "..npm..",
    ).noLeakTo(
        "android..",
        "com.autoscript.bridge..",
        "com.autoscript.platform.capabilities..",
        "com.autoscript.platform.editor..",
        "com.autoscript.platform.system..",
        "com.autoscript.engine..",
        "com.autoscript.appservice.runtime..",
        "com.autoscript.appservice.scheduler..",
        "com.autoscript.appservice.scriptrepo..",
        "com.autoscript.appservice.permissioncenter..",
        "com.autoscript.appservice.packager..",
    )
}
