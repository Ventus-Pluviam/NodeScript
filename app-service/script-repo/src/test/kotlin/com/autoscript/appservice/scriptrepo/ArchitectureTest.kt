package com.autoscript.appservice.scriptrepo

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition
import org.junit.jupiter.api.Test

/** 依赖方向守护（docs/framework-design.md §3）：core 层保持纯 JVM，零 Android / 零桥 / 零引擎泄漏。 */
class ArchitectureTest {

    @Test
    fun `core 包保持平台无关`() {
        val classes: JavaClasses =
            ClassFileImporter().importPackages("com.autoscript.appservice.scriptrepo.core")

        ArchRuleDefinition.noClasses()
            .that().resideInAPackage("..core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "android..",
                "com.autoscript.bridge..",
                "com.autoscript.platform..",
                "com.autoscript.engine..",
                "org.jetbrains.kotlinx.coroutines..",
            )
            .check(classes)
    }
}