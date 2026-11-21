package com.autoscript.shell

import com.autoscript.e2e.repoRoot
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Application 在 JVM 上不能真装配；钉住生产读口确实接上了经过行为测试的共享结果。 */
class AppShellAssemblyWiringTest {
    @Test
    fun `依赖壳的界面读口全都等待装配结果而不因启动中立即报失败`() {
        val source = String(
            Files.readAllBytes(repoRoot().resolve("app/src/main/kotlin/com/autoscript/AppShellApplication.kt")),
            Charsets.UTF_8,
        )
        val reads = listOf(
            "taskCenter", "taskLog", "console", "npmSnapshot", "npmRegistry",
            "consoleOutput", "consoleHistory", "npmInstallEvents",
        )
        for (name in reads) {
            val body = source.substringAfter("override suspend fun $name(", "").substringBefore("\n    }")
            assertTrue(body.contains("assemblyResult.await()"), "$name 首读必须等待装配，同一调用完成后自动更新页面")
        }
    }
}
