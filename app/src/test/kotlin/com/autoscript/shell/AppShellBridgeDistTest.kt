package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.domain.scripts.ScriptPaths
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * facade dist 装配期落位（§12.4 资产交付轨）在 [AppShellKit.assemble] 上的接线：
 * 空来源如实空报告、有货落进 `ScriptPaths.autoModuleRoot`、引擎拿到的
 * `bridgeDistPath` 与落位根同源（Application 侧同一函数拼，这里钉住契约）。
 */
class AppShellBridgeDistTest {

    @TempDir
    lateinit var dir: Path

    private val files: Path get() = dir.resolve("files")
    private val cache: Path get() = dir.resolve("cache")

    private class NoopProvider : SchedulerProvider {
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
            TriggerHandle { }

        override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
    }

    private fun kit(bridgeDist: Map<String, ByteArray>): AppShellKit.AssembledShell =
        AppShellKit.assemble(
            filesDir = files,
            cacheDir = cache,
            schedulerProvider = NoopProvider(),
            screenGate = ScreenGate.AllowAll,
            bridgeDist = bridgeDist,
        )

    @Test
    fun `有货全落位——落位根就是 require 解析点`() {
        kit(
            mapOf(
                "index.js" to "idx".toByteArray(),
                "bootstrap.js" to "boot".toByteArray(),
            ),
        ).use { assembled ->
            val report = assembled.bridgeDistReport
            assertTrue(report.changed, "首落必须 changed")
            assertTrue(report.failures.isEmpty(), "failures=$report.failures")
            val root = ScriptPaths.autoModuleRoot(files)
            assertEquals("idx", String(Files.readAllBytes(root.resolve("index.js"))))
            assertEquals("boot", String(Files.readAllBytes(root.resolve("bootstrap.js"))))
        }
    }

    @Test
    fun `空来源如实空——不粉饰成 facade 已就位`() {
        kit(emptyMap()).use { assembled ->
            assertFalse(assembled.bridgeDistReport.changed)
            assertTrue(assembled.bridgeDistFailures().isEmpty())
            assertTrue(!Files.exists(ScriptPaths.autoModuleRoot(files)), "没货连目录都不建")
        }
    }

    @Test
    fun `重装换新版——字节异则替换(与脚本补部署的只补缺相反)`() {
        val root = ScriptPaths.autoModuleRoot(files)
        Files.createDirectories(root)
        Files.write(root.resolve("index.js"), "v1".toByteArray())

        kit(mapOf("index.js" to "v2".toByteArray())).use { assembled ->
            assertTrue(assembled.bridgeDistReport.changed)
            assertEquals("v2", String(Files.readAllBytes(root.resolve("index.js"))), "应用自有资产：升版即换")
        }
    }
}
