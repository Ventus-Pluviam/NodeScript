package com.autoscript.appservice.packager

import com.autoscript.build.ArchGate
import org.junit.jupiter.api.Test

/** 依赖方向守护（docs §6）：packager 只依赖 :domain，零 Android / 零桥 / 零平台 / 零引擎 / 零姐妹服务。 */
class ArchitectureTest {

    @Test
    fun `packager 包零跨层泄漏`() = ArchGate(
        "com.autoscript.appservice.packager",
        rulePackage = "..packager..",
    ).noLeakTo(
        "android..",
        "com.autoscript.bridge..",
        "com.autoscript.platform..",
        "com.autoscript.engine..",
        "com.autoscript.appservice.runtime..",
        "com.autoscript.appservice.scheduler..",
        "com.autoscript.appservice.scriptrepo..",
        "com.autoscript.appservice.permissioncenter..",
        // 同级 npm（自本模块拆出；编译期已无 Gradle 边，字节码再钉一道）
        "com.autoscript.appservice.npm..",
    )
}
