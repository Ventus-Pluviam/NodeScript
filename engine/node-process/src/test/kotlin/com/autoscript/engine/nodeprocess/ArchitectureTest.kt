package com.autoscript.engine.nodeprocess

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition
import org.junit.jupiter.api.Test

/**
 * 依赖方向守护（docs §6 `:engine:node-process` 行）：Kotlin 侧实现 `:domain` 的 `ScriptEngine`
 * SPI，不反向、不碰编排/平台/shell/UI —— 本模块保持**纯 JVM**（`tools/jvm-test-all.sh` 以
 * 无 android.jar 模式编译它，任何 `android.*` import 直接编译失败，是本测试的前一道闸）。
 *
 * `:bridge:native` 的依赖是 C++/运行期 `.so` 装载（main.cpp dlopen），没有 Kotlin 类可依，
 * 故黑名单里的 `com.autoscript.bridge..`（`:bridge:java` 的 Kotlin 面）对本模块是全禁。
 */
class ArchitectureTest {

    @Test
    fun `engine 模块零跨层泄漏`() {
        val classes: JavaClasses =
            ClassFileImporter().importPackages("com.autoscript.engine.nodeprocess")

        ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.autoscript.engine.nodeprocess..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.autoscript.bridge..",      // 桥 Kotlin 面归 :main 装配（§6：引擎只见 domain SPI）
                "com.autoscript.appservice..",  // 编排层（池/调度）反向依赖引擎 = 环
                "com.autoscript.platform..",    // 平台实现 SPI 不反向
                "com.autoscript.shell..",       // 装配包（shell 反过来禁碰 engine，双向不交）
                "androidx..",
                "android.app..",                // 禁 UI/系统服务面；纯 java.* 进程面
                "android.widget..",
            )
            .check(classes)
    }
}
