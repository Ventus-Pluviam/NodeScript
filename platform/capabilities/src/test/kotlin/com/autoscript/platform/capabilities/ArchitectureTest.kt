package com.autoscript.platform.capabilities

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition
import org.junit.jupiter.api.Test

/**
 * 依赖方向守护（docs §6 模块表）：capabilities 实现 :domain SPI，不反向；
 * 禁服务逻辑（app-service）、禁桥/引擎直连、禁 UI。
 *
 * `a11y`/`screen` 命名空间挂 Router 所需的接缝类型（`com.autoscript.domain.bridge.
 * NamespaceHandler`）住 `:domain`，本模块只见 `:domain`（见 [CapabilityNamespaces]）；
 * `com.autoscript.bridge..` 仍整体在黑名单里 —— 依赖 `:domain` 的挂载缝不等于依赖桥实现层。
 */
class ArchitectureTest {

    @Test
    fun `capabilities 包零跨层泄漏`() {
        val classes: JavaClasses =
            ClassFileImporter().importPackages("com.autoscript.platform.capabilities")

        ArchRuleDefinition.noClasses()
            .that().resideInAPackage("..capabilities..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "android..",
                "androidx..",
                "com.autoscript.bridge..",
                "com.autoscript.engine..",
                "com.autoscript.appservice..",
                "androidx.compose..",
                "java.awt..",
                "javax.swing..",
            )
            .check(classes)
    }
}
