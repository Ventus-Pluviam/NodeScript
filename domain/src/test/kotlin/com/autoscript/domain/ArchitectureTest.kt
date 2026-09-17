package com.autoscript.domain

import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * 领域层架构门（docs/framework-design.md §4.1/§6 依赖规则）：
 * :domain 零 Android / 零桌面 UI 依赖；纯 Kotlin，JVM 可测。
 */
@AnalyzeClasses(packages = ["com.autoscript.domain"])
class ArchitectureTest {

    @ArchTest
    val noAndroidDependency = noClasses()
        .that().resideInAPackage("com.autoscript.domain..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("android..", "org.jetbrains.compose..", "java.awt..", "javax.swing..")

    @ArchTest
    val domainDoesNotLeakBridgeImpl = noClasses()
        .that().resideInAPackage("com.autoscript.domain..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("com.autoscript.bridge..", "com.autoscript.platform..", "com.autoscript.engine..")
}