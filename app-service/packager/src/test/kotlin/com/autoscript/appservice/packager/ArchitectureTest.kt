package com.autoscript.appservice.packager

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition
import org.junit.jupiter.api.Test

/** 依赖方向守护（docs §6）：packager 只依赖 :domain，零 Android / 零桥 / 零平台 / 零引擎 / 零姐妹服务。 */
class ArchitectureTest {

    @Test
    fun `packager 包零跨层泄漏`() {
        val classes: JavaClasses =
            ClassFileImporter().importPackages("com.autoscript.appservice.packager")

        ArchRuleDefinition.noClasses()
            .that().resideInAPackage("..packager..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "android..",
                "com.autoscript.bridge..",
                "com.autoscript.platform..",
                "com.autoscript.engine..",
                "com.autoscript.appservice.runtime..",
                "com.autoscript.appservice.scheduler..",
                "com.autoscript.appservice.scriptrepo..",
                "com.autoscript.appservice.permissioncenter..",
            )
            .check(classes)
    }
}
