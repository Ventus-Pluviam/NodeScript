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
 *
 * `android..` 有**一条包内例外**：无障碍服务三件（[AutoScriptAccessibilityService] +
 * 设备面私有类 ServiceBridge/ServiceNode —— §9.1 设备面唯一触点）。语义层
 * （[AndroidUiTree]/[AndroidGestureInput]/A11yBridge 接缝/handler）必须保持纯 JVM：
 * 它们的可测性（假桥注入、本机无 SDK 跑测）全押在这条线上，Android 只许活在服务文件里。
 */
class ArchitectureTest {

    /** 设备面唯一触点（同文件私有类独立 class 文件，按名排除；截图回调同属服务面）。 */
    private val androidExempt = arrayOf(
        "AutoScriptAccessibilityService",
        "ServiceBridge",
        "ServiceNode",
        "ScreenshotCallback",
    )

    @Test
    fun `capabilities 包零跨层泄漏`() {
        val classes: JavaClasses =
            ClassFileImporter().importPackages("com.autoscript.platform.capabilities")

        ArchRuleDefinition.noClasses()
            .that().resideInAPackage("..capabilities..")
            .should().dependOnClassesThat().resideInAnyPackage(
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

    @Test
    fun `android 只许服务面名单碰`() {
        val classes: JavaClasses =
            ClassFileImporter().importPackages("com.autoscript.platform.capabilities")

        ArchRuleDefinition.noClasses()
            .that().resideInAPackage("..capabilities..")
            .and().doNotHaveSimpleName("AutoScriptAccessibilityService")
            .and().doNotHaveSimpleName("ServiceBridge")
            .and().doNotHaveSimpleName("ServiceNode")
            .and().doNotHaveSimpleName("ScreenshotCallback")
            .should().dependOnClassesThat().resideInAnyPackage("android..")
            .check(classes)
    }
}
