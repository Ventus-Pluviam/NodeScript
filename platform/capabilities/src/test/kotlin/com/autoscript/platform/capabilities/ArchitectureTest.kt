package com.autoscript.platform.capabilities

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition
import org.junit.jupiter.api.Test

/**
 * 依赖方向守护（docs §6 模块表）：capabilities 实现 :domain SPI，不反向；
 * 禁服务逻辑（app-service）、禁桥/引擎直连、禁 UI。
 *
 * `a11y`/`screen`/`dialogs` 命名空间挂 Router 所需的接缝类型（`com.autoscript.domain.
 * bridge.NamespaceHandler`）住 `:domain`，本模块只见 `:domain`（见 [CapabilityNamespaces]）；
 * `com.autoscript.bridge..` 仍整体在黑名单里 —— 依赖 `:domain` 的挂载缝不等于依赖桥实现层。
 *
 * `android..` 按**包**豁免：设备面全部住 `com.autoscript.platform.capabilities.device`
 * 子包（无障碍服务 + 截图回调 + 对话框设备面），其余（语义层 [AndroidUiTree]/
 * [AndroidDialogHost]/[A11yBridge] 接缝/handler）保持纯 JVM —— 它们的可测性
 * （假桥/假 ops 注入、本机无 SDK 跑测）全押在这条线上。按包不按名：SAM/匿名合成类
 * （`...$dialog$1`）跟着源文件走，名单不用人肉续（按名豁免已经错过两轮）。
 */
class ArchitectureTest {

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
    fun `android 只许设备包子包碰`() {
        val classes: JavaClasses =
            ClassFileImporter().importPackages("com.autoscript.platform.capabilities")

        ArchRuleDefinition.noClasses()
            .that().resideInAPackage("..capabilities..")
            .and().resideOutsideOfPackage("..capabilities.device")
            .should().dependOnClassesThat().resideInAnyPackage("android..")
            .check(classes)
    }
}
