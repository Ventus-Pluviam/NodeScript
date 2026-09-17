package com.autoscript.appservice.runtime

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition
import org.junit.jupiter.api.Test

/** 依赖方向守护（docs §3）：runtime 层只依赖 :domain 契约，零 Android / 零桥 / 零平台 / 零引擎。 */
class ArchitectureTest {

    @Test
    fun `runtime 包零跨层泄漏`() {
        val classes: JavaClasses =
            ClassFileImporter().importPackages("com.autoscript.appservice.runtime")

        ArchRuleDefinition.noClasses()
            .that().resideInAPackage("..runtime..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "android..",
                "com.autoscript.bridge..",
                "com.autoscript.platform..",
                "com.autoscript.engine..",
                "com.autoscript.appservice.scriptrepo..",
            )
            .check(classes)
    }
}