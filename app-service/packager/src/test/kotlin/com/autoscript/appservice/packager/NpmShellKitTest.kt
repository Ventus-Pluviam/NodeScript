package com.autoscript.appservice.packager

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * npm 生产装配验证（`NpmShellKit.assembleHandler`）：目录约定收拢后，
 * 产出的 handler 仍是可达的真 `NpmBridgeHandler`（§12.2「已可挂」不断言挂载本身，
 * 只断言装配产物可用 —— 挂载缝的测试在 `:app` 的 AppShellCapabilityMountTest）。
 *
 * 诚实口径：缺省 executor = Unavailable → `list` 这类轻操作可用（直读 lockfile），
 * 重操作（install 需要真 npm/引擎）如实 `ERR_NOT_IMPLEMENTED`（§10.2 P0 边界）。
 */
class NpmShellKitTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `装配产物轻操作可用：无项目时 list 如实回错`() = runBlocking {
        val h = NpmShellKit.assembleHandler(
            filesDir = dir.resolve("files"),
            cacheDir = dir.resolve("cache"),
        )
        val r = h.handle(BridgeRequest(1, "npm", "list", """{"projectId":"main"}""", 10_000))
        // main 项目尚不存在（无 package-lock.json）：如实报错而非空列表假装
        assertInstanceOf(BridgeResponse.Err::class.java, r)
    }

    @Test
    fun `装配产物重操作诚实失败：缺省执行体不伪造安装`() = runBlocking {
        val h = NpmShellKit.assembleHandler(
            filesDir = dir.resolve("files"),
            cacheDir = dir.resolve("cache"),
        )
        val r = h.handle(
            BridgeRequest(2, "npm", "install", """{"spec":"lodash@4.17.21"}""", 10_000),
        )
        val err = assertInstanceOf(BridgeResponse.Err::class.java, r)
        assertEquals("ERR_NOT_IMPLEMENTED", err.errorCode)
    }
}
