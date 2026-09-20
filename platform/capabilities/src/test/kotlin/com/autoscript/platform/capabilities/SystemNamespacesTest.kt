package com.autoscript.platform.capabilities

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.AppLauncher
import com.autoscript.domain.system.DeviceInfoProvider
import com.autoscript.domain.system.DeviceProfile
import com.autoscript.domain.system.DialogHost
import com.autoscript.domain.system.DialogMode
import com.autoscript.domain.system.DialogOutcome
import com.autoscript.domain.system.DialogPromptRequest
import com.autoscript.domain.system.DialogChooseRequest
import com.autoscript.domain.system.DialogChoice
import com.autoscript.domain.system.FloatingWindowHost
import com.autoscript.domain.system.FloatingWindowSpec
import com.autoscript.domain.system.ShellExecutor
import com.autoscript.domain.system.ShellMode
import com.autoscript.domain.system.ShellResult
import com.autoscript.domain.bridge.HandleRef
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 五个系统侧命名空间的桥处理器测试（docs §9.4/§9.6/§12.2，JS 对偶 `extras.ts`）。
 *
 * 判据与 [CapabilityNamespacesTest] 同一套口径，另加三件本组特有的事：
 * 1. **载荷形状与 JS facade 逐字对齐**：`{code,stdout,stderr}`、`{value,confirmed}`、
 *    裸下标（取消 -1）、`{refId,generation}` —— 桥只透传，改一侧另一侧就收 undefined；
 * 2. **错误分类不被抹平**：SPI 抛的 `AutojsException` 原码透传（ERR_PERMISSION_DENIED /
 *    ERR_STALE_HANDLE），参数非法一律 ERR_INVALID_PARAM，未知方法 ERR_NOT_IMPLEMENTED；
 * 3. **不伪造可用**：`app.launch` 起不来回 false 而非抛错；`device` 无 P0 之外的方法即
 *    如实 ERR_NOT_IMPLEMENTED（JS 侧按 Err NOT_IMPLEMENTED 抛 AutojsError）。
 */
class SystemNamespacesTest {

    // ── 替身（内存实现，与 InMemory* 同一模式） ──────────────────────

    private class FakeShell(
        var mode: ShellMode? = null,
        var timeout: Long? = null,
        private val result: (String) -> ShellResult = { ShellResult(0, "", "") },
        private val fail: ((String) -> AutojsException)? = null,
    ) : ShellExecutor {
        override suspend fun exec(command: String, mode: ShellMode, timeoutMillis: Long): ShellResult {
            this.mode = mode
            this.timeout = timeoutMillis
            fail?.let { throw it(command) }
            return result(command)
        }
    }

    private class FakeDevice(private val p: DeviceProfile = DeviceProfile("Pixel 8", 34)) : DeviceInfoProvider {
        override fun profile(): DeviceProfile = p
    }

    private class FakeApp(
        private val launched: Boolean = true,
        private val current: String? = "com.autoscript",
    ) : AppLauncher {
        override suspend fun launch(packageName: String): Boolean = launched
        override suspend fun currentPackage(): String? = current
    }

    private class FakeDialogs(
        private val outcome: DialogOutcome = DialogOutcome("abc", true),
        private val choice: DialogChoice = DialogChoice(1),
    ) : DialogHost {
        override suspend fun prompt(request: DialogPromptRequest): DialogOutcome = outcome
        override suspend fun choose(request: DialogChooseRequest): DialogChoice = choice
    }

    private class FakeFloating(
        private val next: Long = 7,
        private val failOnCreate: AutojsException? = null,
        private val failOnClose: AutojsException? = null,
    ) : FloatingWindowHost {
        val closed = mutableListOf<HandleRef>()
        override suspend fun create(spec: FloatingWindowSpec): HandleRef {
            failOnCreate?.let { throw it }
            return HandleRef(next, 1)
        }
        override suspend fun close(ref: HandleRef) {
            failOnClose?.let { throw it }
            closed += ref
        }
    }

    // ── shell ───────────────────────────────────────────────────────

    @Test
    fun `shell exec 三字段载荷与超时透传`() = runBlocking {
        val fake = FakeShell(result = { ShellResult(0, "out", "err") })
        val h = CapabilityNamespaces.shell(fake, defaultTimeoutMillis = 12_345)
        val resp = h.handle(BridgeRequest(1, "shell", "exec", """{"cmd":"id","timeout":9000}""", 5_000))
        val ok = assertInstanceOf(BridgeResponse.Ok::class.java, resp)
        val o = A11yBridgeJson.decodeObject(ok.payload!!)
        assertEquals("0", (o["code"] as A11yBridgeJson.Value.N).raw)
        assertEquals("out", (o["stdout"] as A11yBridgeJson.Value.S).v)
        assertEquals("err", (o["stderr"] as A11yBridgeJson.Value.S).v)
        assertEquals(9_000L, fake.timeout)
        // 缺 timeout → 走 handler 默认（不是 0、不是无限）
        h.handle(BridgeRequest(2, "shell", "exec", """{"cmd":"id"}""", 5_000))
        assertEquals(12_345L, fake.timeout)
        Unit
    }

    @Test
    fun `shell 方法别名与 mode 字面量`() = runBlocking {
        val fake = FakeShell()
        val h = CapabilityNamespaces.shell(fake)
        // extras.ts 的 shell.shell() 是 exec() 的别名：两个方法名都可达
        h.handle(BridgeRequest(1, "shell", "shell", """{"cmd":"id"}""", 5_000))
        h.handle(BridgeRequest(2, "shell", "exec", """{"cmd":"id","mode":"root"}""", 5_000))
        assertEquals(ShellMode.ROOT, fake.mode)
        // 未知 mode 拒绝（拼错即报错，不静默套 DEFAULT）
        val bad = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(3, "shell", "exec", """{"cmd":"id","mode":"Rootx"}""", 5_000)),
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, bad.errorCode)
        Unit
    }

    @Test
    fun `shell 分类错误原码透传，零超时与缺 cmd 是参数错`() = runBlocking {
        val denied = FakeShell(fail = { AutojsException(ErrorCode.ERR_PERMISSION_DENIED, "root 未授权") })
        val h = CapabilityNamespaces.shell(denied)
        val err = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(1, "shell", "exec", """{"cmd":"su"}""", 5_000)),
        )
        assertEquals("ERR_PERMISSION_DENIED", err.errorCode)
        assertTrue(err.detail!!.contains("root 未授权"))

        val zero = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(2, "shell", "exec", """{"cmd":"id","timeout":0}""", 5_000)),
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, zero.errorCode)

        val noCmd = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(3, "shell", "exec", "{}", 5_000)),
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, noCmd.errorCode)

        val noPayload = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(4, "shell", "exec", null, 5_000)),
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, noPayload.errorCode)

        val unknown = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(5, "shell", "fly", "{}", 5_000)),
        )
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, unknown.errorCode)
        Unit
    }

    // ── device ──────────────────────────────────────────────────────

    @Test
    fun `device model 与 sdkInt 直出字符串与数字`() = runBlocking {
        val h = CapabilityNamespaces.device(FakeDevice())
        val model = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(1, "device", "model", null, 5_000)),
        )
        assertEquals("Pixel 8", A11yBridgeJson.decode(model.payload!!).let { (it as A11yBridgeJson.Value.S).v })
        val sdk = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(2, "device", "sdkInt", null, 5_000)),
        )
        assertEquals("34", (A11yBridgeJson.decode(sdk.payload!!) as A11yBridgeJson.Value.N).raw)
        // P0 只两个方法，其余如实未实现
        val unknown = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(3, "device", "brand", null, 5_000)),
        )
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, unknown.errorCode)
        Unit
    }

    // ── app ─────────────────────────────────────────────────────────

    @Test
    fun `app launch 起不来回 false 而非抛错`() = runBlocking {
        val h = CapabilityNamespaces.app(FakeApp(launched = false, current = null))
        val launch = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(1, "app", "launch", """{"packageName":"com.x"}""", 5_000)),
        )
        assertEquals("false", launch.payload)
        // 取不到前台包 → 载荷就是 JSON null（不给空串冒充「有包名为空」）
        val cur = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(2, "app", "currentPackage", null, 5_000)),
        )
        assertEquals("null", cur.payload)
        assertInstanceOf(A11yBridgeJson.Value.Null::class.java, A11yBridgeJson.decode(cur.payload!!))
        Unit
    }

    @Test
    fun `app launch 成功回 true，缺 packageName 是参数错`() = runBlocking {
        val h = CapabilityNamespaces.app(FakeApp())
        val ok = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(1, "app", "launch", """{"packageName":"com.autoscript"}""", 5_000)),
        )
        assertEquals("true", ok.payload)
        val bad = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(2, "app", "launch", "{}", 5_000)),
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, bad.errorCode)
        Unit
    }

    // ── dialogs ─────────────────────────────────────────────────────

    @Test
    fun `dialogs prompt 回 value confirmed 两字段`() = runBlocking {
        val h = CapabilityNamespaces.dialogs(FakeDialogs(outcome = DialogOutcome("张三", true)))
        val ok = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(1, "dialogs", "prompt", """{"title":"名字","placeholder":"请输入"}""", 5_000)),
        )
        val o = A11yBridgeJson.decodeObject(ok.payload!!)
        assertEquals("张三", (o["value"] as A11yBridgeJson.Value.S).v)
        assertTrue((o["confirmed"] as A11yBridgeJson.Value.B).v)
        Unit
    }

    @Test
    fun `dialogs prompt 取消折叠为 value null confirmed false`() = runBlocking {
        val h = CapabilityNamespaces.dialogs(FakeDialogs(outcome = DialogOutcome.CANCELLED))
        val ok = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(1, "dialogs", "prompt", """{"title":"名字"}""", 5_000)),
        )
        val o = A11yBridgeJson.decodeObject(ok.payload!!)
        assertTrue(o["value"] is A11yBridgeJson.Value.Null)
        assertFalse((o["confirmed"] as A11yBridgeJson.Value.B).v)
        Unit
    }

    @Test
    fun `dialogs choose 直出下标，取消即 -1`() = runBlocking {
        val h = CapabilityNamespaces.dialogs(FakeDialogs(choice = DialogChoice(2)))
        val ok = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(1, "dialogs", "choose", """{"title":"选","options":["a","b","c"]}""", 5_000)),
        )
        assertEquals("2", (A11yBridgeJson.decode(ok.payload!!) as A11yBridgeJson.Value.N).raw)

        val cancelled = CapabilityNamespaces.dialogs(FakeDialogs(choice = DialogChoice.CANCELLED))
        val c = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            cancelled.handle(BridgeRequest(2, "dialogs", "choose", """{"title":"选","options":["a"]}""", 5_000)),
        )
        assertEquals("-1", (A11yBridgeJson.decode(c.payload!!) as A11yBridgeJson.Value.N).raw)
        Unit
    }

    @Test
    fun `dialogs 空标题与空选项在构造期即拒`() = runBlocking {
        val h = CapabilityNamespaces.dialogs(FakeDialogs())
        val noTitle = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(1, "dialogs", "prompt", """{"title":""}""", 5_000)),
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, noTitle.errorCode)
        val noOptions = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(2, "dialogs", "choose", """{"title":"选","options":[]}""", 5_000)),
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, noOptions.errorCode)
        val notArray = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(3, "dialogs", "choose", """{"title":"选","options":"a"}""", 5_000)),
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, notArray.errorCode)
        Unit
    }

    @Test
    fun `dialogs BAL 降级路径失败回分类错误`() = runBlocking {
        // overlay 未授权且通知不可达 → 宿主如实抛 ERR_PERMISSION_DENIED，handler 原码透传
        val h = CapabilityNamespaces.dialogs(
            object : DialogHost {
                override suspend fun prompt(request: DialogPromptRequest): DialogOutcome =
                    throw AutojsException(ErrorCode.ERR_PERMISSION_DENIED, "overlay 未授权且通知降级不可达")
                override suspend fun choose(request: DialogChooseRequest): DialogChoice =
                    throw AutojsException(ErrorCode.ERR_SERVICE_DISABLED, "无对话框宿主")
            },
        )
        val err = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(1, "dialogs", "prompt", """{"title":"名字","mode":"overlay"}""", 5_000)),
        )
        assertEquals("ERR_PERMISSION_DENIED", err.errorCode)
        val err2 = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(2, "dialogs", "choose", """{"title":"选","options":["a"]}""", 5_000)),
        )
        assertEquals("ERR_SERVICE_DISABLED", err2.errorCode)
        Unit
    }

    // ── floatingWindow ──────────────────────────────────────────────

    @Test
    fun `floatingWindow create 回句柄两字段，close 幂等透传`() = runBlocking {
        val fake = FakeFloating(next = 42)
        val h = CapabilityNamespaces.floatingWindow(fake)
        val created = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(1, "floatingWindow", "create", """{"title":"面板","width":300,"height":200}""", 5_000)),
        )
        val o = A11yBridgeJson.decodeObject(created.payload!!)
        assertEquals("42", (o["refId"] as A11yBridgeJson.Value.N).raw)
        assertEquals("1", (o["generation"] as A11yBridgeJson.Value.N).raw)

        val closed = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(2, "floatingWindow", "close", """{"ref":{"refId":42,"generation":1}}""", 5_000)),
        )
        assertEquals("true", closed.payload)
        assertEquals(listOf(HandleRef(42, 1)), fake.closed)
        Unit
    }

    @Test
    fun `floatingWindow 尺寸非法与跨代句柄都是分类错误`() = runBlocking {
        val fake = FakeFloating(failOnCreate = AutojsException(ErrorCode.ERR_PERMISSION_DENIED, "overlay 未授予"))
        val h = CapabilityNamespaces.floatingWindow(fake)
        val zero = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(1, "floatingWindow", "create", """{"width":0,"height":200}""", 5_000)),
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, zero.errorCode)

        val denied = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(2, "floatingWindow", "create", """{"width":300}""", 5_000)),
        )
        assertEquals("ERR_PERMISSION_DENIED", denied.errorCode)

        // 未知句柄 / 跨代 → ERR_STALE_HANDLE 原码透传（§7.4）
        val stale = CapabilityNamespaces.floatingWindow(
            FakeFloating(failOnClose = AutojsException(ErrorCode.ERR_STALE_HANDLE, "窗口已关闭")),
        )
        val err = assertInstanceOf(
            BridgeResponse.Err::class.java,
            stale.handle(BridgeRequest(3, "floatingWindow", "close", """{"ref":{"refId":9,"generation":1}}""", 5_000)),
        )
        assertEquals("ERR_STALE_HANDLE", err.errorCode)

        val unknown = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(4, "floatingWindow", "resize", "{}", 5_000)),
        )
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, unknown.errorCode)
        Unit
    }

    // ── 挂载缝整体口径 ──────────────────────────────────────────────

    @Test
    fun `五个命名空间的 mode 缺省都是 auto 与 default`() = runBlocking {
        // 确认 mode 缺省不抛错：JS facade 不传 mode 时走 AUTO（dialogs）/ DEFAULT（shell）
        val shellFake = FakeShell()
        CapabilityNamespaces.shell(shellFake)
            .handle(BridgeRequest(1, "shell", "exec", """{"cmd":"id"}""", 5_000))
        assertEquals(ShellMode.DEFAULT, shellFake.mode)

        var seenMode: DialogMode? = null
        val h = CapabilityNamespaces.dialogs(
            object : DialogHost {
                override suspend fun prompt(request: DialogPromptRequest): DialogOutcome {
                    seenMode = request.mode
                    return DialogOutcome.CANCELLED
                }
                override suspend fun choose(request: DialogChooseRequest): DialogChoice = DialogChoice.CANCELLED
            },
        )
        h.handle(BridgeRequest(2, "dialogs", "prompt", """{"title":"t"}""", 5_000))
        assertEquals(DialogMode.AUTO, seenMode)
        Unit
    }
}
