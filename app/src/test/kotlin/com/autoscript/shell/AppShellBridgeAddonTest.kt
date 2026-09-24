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
 * bridge addon 装配期落位（§19 交付轨）在 [AppShellKit.assemble] 上的接线：
 * 没货如实空报告、有货落进 `ScriptPaths.bridgeAddonFile`（与引擎 `addonPath` 同源）。
 */
class AppShellBridgeAddonTest {

    @TempDir
    lateinit var dir: Path

    private val files: Path get() = dir.resolve("files")
    private val cache: Path get() = dir.resolve("cache")

    private class NoopProvider : SchedulerProvider {
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
            TriggerHandle { }

        override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
    }

    private fun kit(bridgeAddon: ByteArray?): AppShellKit.AssembledShell =
        AppShellKit.assemble(
            filesDir = files,
            cacheDir = cache,
            schedulerProvider = NoopProvider(),
            screenGate = ScreenGate.AllowAll,
            bridgeAddon = bridgeAddon,
        )

    @Test
    fun `没货如实空——不粉饰成 addon 已就位`() {
        kit(null).use { assembled ->
            assertFalse(assembled.bridgeAddonReport.changed)
            assertEquals(null, assembled.bridgeAddonFailure())
            assertTrue(!Files.exists(ScriptPaths.bridgeAddonFile(files)), "没货连 lib 目录都不建")
        }
    }

    @Test
    fun `有货落位——落位根就是引擎 addonPath 的拼点`() {
        kit("addon-v1".toByteArray()).use { assembled ->
            assertTrue(assembled.bridgeAddonReport.changed)
            assertEquals(null, assembled.bridgeAddonFailure())
            val dest = ScriptPaths.bridgeAddonFile(files)
            assertEquals("addon-v1", String(Files.readAllBytes(dest)))
        }
    }

    @Test
    fun `重装换新版——字节异则替换(应用自有资产)`() {
        val dest = ScriptPaths.bridgeAddonFile(files)
        Files.createDirectories(dest.parent)
        Files.write(dest, "old".toByteArray())

        kit("new".toByteArray()).use { assembled ->
            assertTrue(assembled.bridgeAddonReport.changed)
            assertEquals("new", String(Files.readAllBytes(dest)), "字节即版本：升版即换")
        }
    }
}
