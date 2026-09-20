package com.autoscript.platform.system

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition
import org.junit.jupiter.api.Test

/**
 * 依赖方向守护（docs §6 模块表）：`:platform:system` 只实现 `:domain` 的 SPI，不反向；
 * 禁服务逻辑（`:app-service:*`）、禁桥/引擎直连、禁 UI。
 *
 * `com.autoscript.bridge..` 整体在黑名单里：本模块**没有**挂 Router 的职责 ——
 * 它只出 `com.autoscript.domain.system` 各 SPI 的 Android 实现，由装配层（`:app`）
 * 经 `:platform:capabilities` 的 handler 挂上桥（§12.2「分两层」）。
 * `HandleRef` 等挂载缝类型住 `:domain`，依赖它不等于依赖桥实现层。
 */
class ArchitectureTest {

    @Test
    fun `platform system 包零跨层泄漏`() {
        val classes: JavaClasses =
            ClassFileImporter().importPackages("com.autoscript.platform.system")

        ArchRuleDefinition.noClasses()
            .that().resideInAPackage("..platform.system..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "androidx..",
                "com.autoscript.bridge..",
                "com.autoscript.engine..",
                "com.autoscript.appservice..",
                "com.autoscript.platform.capabilities..",
                "java.awt..",
                "javax.swing..",
            )
            .check(classes)
    }
}
