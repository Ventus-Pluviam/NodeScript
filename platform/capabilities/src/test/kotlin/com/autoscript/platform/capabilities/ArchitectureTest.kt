package com.autoscript.platform.capabilities

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition
import org.junit.jupiter.api.Test

/**
 * 依赖方向守护（docs §6 模块表）：capabilities 实现 :domain SPI，不反向；
 * 禁服务逻辑（app-service）、禁桥/引擎直连、禁 UI。
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
