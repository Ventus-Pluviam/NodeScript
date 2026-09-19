package com.autoscript.shell

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition
import org.junit.jupiter.api.Test

/**
 * 依赖方向守护（docs §6 模块表 + Composition Root 例外）：
 * - `:app` 唯一允许碰 `:bridge:java` 的地方是 `com.autoscript.shell` 装配包
 *   （把 handler 薄转接挂到 BridgeRouter，无业务逻辑）；
 * - 平台直连永远禁止；引擎/UI 包不得泄漏进装配层。
 */
class ArchitectureTest {

    @Test
    fun `shell 装配包零跨层泄漏`() {
        val classes: JavaClasses =
            ClassFileImporter().importPackages("com.autoscript.shell")

        ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.autoscript.shell..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.autoscript.platform..",
                "com.autoscript.engine..",
                // UI 实现包（放行 androidx.compose.runtime.internal.StabilityInferred：
                // compose 编译器给本模块全部类加的稳定性注解，非真实 UI 依赖）。
                "androidx.compose.ui..",
                "androidx.compose.material3..",
                "androidx.compose.foundation..",
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
}
