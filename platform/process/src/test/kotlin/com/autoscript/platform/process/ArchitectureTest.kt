package com.autoscript.platform.process

import com.autoscript.build.ArchGate
import org.junit.jupiter.api.Test

/**
 * 依赖方向守护（docs §6 模块表 `:platform:process` 行）：进程/`/proc` 面是**叶子模块**，
 * 连 `:domain` 都不依赖（只用 std），因此除 Android/桌面 UI 外的一切 `com.autoscript..`
 * 包都是泄漏 —— 任何一条依赖边都会把「进程面」与某个上层绑死，那正是本模块存在的理由
 * 被绕开的那条路（批 105 的两个前身各自被依赖方向逼着留一份副本）。
 *
 * `com.autoscript.build`（[ArchGate] 自身）不在黑名单里：各模块的 [ArchGate] 都依赖它。
 */
class ArchitectureTest {

    @Test
    fun `process 包零跨层泄漏——叶子模块不得依赖任何 autoscript 包`() = ArchGate(
        "com.autoscript.platform.process",
    ).noLeakTo(
        "android..",
        "androidx..",
        "java.awt..",
        "javax.swing..",
        "com.autoscript.appservice..",
        "com.autoscript.bridge..",
        "com.autoscript.domain..",
        "com.autoscript.engine..",
        "com.autoscript.platform.capabilities..",
        "com.autoscript.platform.editor..",
        "com.autoscript.platform.system..",
        "com.autoscript.shell..",
        "com.autoscript.ui..",
    )
}
