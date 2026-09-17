package com.autoscript.bridge

import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * 桥基础层架构门（docs/framework-design.md §4.1/§6）：
 * :bridge:java 依赖 :domain，禁 Android / 禁 UI；实现不得落入平台/引擎包。
 */
@AnalyzeClasses(packages = ["com.autoscript.bridge"])
class ArchitectureTest {

    @ArchTest
    val noAndroidDependency = noClasses()
        .that().resideInAPackage("com.autoscript.bridge..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(
            "android..",
            "androidx.compose..",
            "androidx.appcompat..",
            "androidx.activity..",
            "java.awt..",
            "javax.swing..",
        )

    @ArchTest
    val bridgeDoesNotReachPlatformOrEngine = noClasses()
        .that().resideInAPackage("com.autoscript.bridge..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("com.autoscript.platform..", "com.autoscript.engine..")
}