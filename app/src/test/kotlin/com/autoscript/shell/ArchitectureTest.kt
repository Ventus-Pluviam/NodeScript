package com.autoscript.shell

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition
import org.junit.jupiter.api.Test

/**
 * 依赖方向守护（docs §6 模块表 + Composition Root **包级例外两则**）：
 * - `:app` 唯一允许碰 `:bridge:java` / `:platform:*` 的地方是 `com.autoscript.shell`
 *   装配包 —— 前者把 handler 薄转接挂到 `BridgeRouter`，后者是
 *   `SystemSpis` + `CapabilityNamespaces` 生产装配（[PlatformWiring]），均无业务逻辑；
 * - 装配包之外的 :app 类直连平台/桥永远禁止；引擎/UI 包不得泄漏进装配层。
 */
class ArchitectureTest {

    @Test
    fun `shell 装配包零跨层泄漏`() {
        val classes: JavaClasses =
            ClassFileImporter().importPackages("com.autoscript.shell")

        // `com.autoscript.platform..` 不在此黑名单：§6 包级例外二允许装配包做
        // 生产装配（PlatformWiring）。装配包**之外**碰 platform 由下面第三条看住。
        ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.autoscript.shell..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.autoscript.engine..",
                // UI 实现包（放行 androidx.compose.runtime.internal.StabilityInferred：
                // compose 编译器给本模块全部类加的稳定性注解，非真实 UI 依赖）。
                "androidx.compose.ui..",
                "androidx.compose.material3..",
                "androidx.compose.foundation..",
                // :ui 模块（2026-09-23 拆出）：装配包不得依赖呈现层 ——
                // 首屏读口走 :domain 的 HostSummary，不是 shell 反向 import MainActivity。
                "com.autoscript.ui..",
            )
            .check(classes)
    }

    @Test
    fun `桥只许装配包碰`() {
        // :app 根包（AppShellApplication + 未来 UI）不得直连 bridge；
        // 唯一的桥接线是 com.autoscript.shell 装配包（Composition Root 例外）。
        val classes: JavaClasses =
            ClassFileImporter().importPackages("com.autoscript")

        ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.autoscript")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.autoscript.bridge..",
            )
            .check(classes)
    }

    @Test
    fun `平台实现只许装配包碰`() {
        // Gradle 边放开后（§6 包级例外二），包级例外必须在这里量化：shell **之外**
        // 的 :app 类（根包 / 未来 UI 包）碰 com.autoscript.platform.. 即红。
        // 排除依赖包：importPackages 会把 classpath 上同前缀的依赖类一并导进来，
        // platform/bridge/domain/appservice 自己的类互依不是 :app 的越界。
        val classes: JavaClasses =
            ClassFileImporter().importPackages("com.autoscript")

        ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.autoscript..")
            .and().resideOutsideOfPackages(
                "com.autoscript.shell..",
                "com.autoscript.platform..",
                "com.autoscript.bridge..",
                "com.autoscript.domain..",
                "com.autoscript.appservice..",
            )
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.autoscript.platform..",
            )
            .check(classes)
    }
}
