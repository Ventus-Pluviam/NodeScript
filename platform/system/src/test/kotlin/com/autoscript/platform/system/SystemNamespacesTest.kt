package com.autoscript.platform.system

import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.platform.system.app.AppLauncher
import com.autoscript.platform.system.device.DeviceInfoProvider
import com.autoscript.platform.system.device.DeviceProfile
import com.autoscript.platform.system.floatingWindow.FloatingWindowHost
import com.autoscript.platform.system.floatingWindow.FloatingWindowSpec
import com.autoscript.platform.system.shell.ShellExecutor
import com.autoscript.platform.system.shell.ShellMode
import com.autoscript.platform.system.shell.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `shell`/`device`/`app`/`floatingWindow` 四个命名空间的桥处理器测试
 * （docs §9.4/§9.6/§12.2，JS 对偶 `extras.ts`；`dialogs` 半拆去
 * capabilities 的 DialogsNamespaceHandlerTest）。
 *
 * 判据与 [CapabilityNamespacesTest] 同一套口径，另加三件本组特有的事：
 * 1. **载荷形状与 JS facade 逐字对齐**：`{code,stdout,stderr,truncated}`、`{value,confirmed}`、
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

    private class FakeFloating(
        private val next: Long = 7,
        private val failOnCreate: AutojsException? = null,
        private val failOnClose: AutojsException? = null,
    ) : FloatingWindowHost {
        val closed = mutableListOf<HandleRef>()

        /** 真送到平台层的规格（判据：被拒的越界值一次都不该到这里）。 */
        val created = mutableListOf<FloatingWindowSpec>()

        override suspend fun create(spec: FloatingWindowSpec): HandleRef {
            failOnCreate?.let { throw it }
            created += spec
            return HandleRef(next, 1)
        }
        override suspend fun close(ref: HandleRef) {
            failOnClose?.let { throw it }
            closed += ref
        }
    }

    // ── shell ───────────────────────────────────────────────────────

    @Test
    fun `shell exec 四字段载荷与超时透传`() = runBlocking {
        // 第四个字段 truncated 是后补的（backlog A2b 的拍板口径）：
        // 形状与 `extras.ts` 的 `ShellResult` 逐字对齐，这里同时钉住"缺省 false"
        // 与"实现报 true 时原样透出"—— 桥只透传，不自己判截断。
        val fake = FakeShell(result = { ShellResult(0, "out", "err") })
        val h = SystemNamespaces.shell(fake, defaultTimeoutMillis = 12_345)
        val resp = h.handle(BridgeRequest(1, "shell", "exec", """{"cmd":"id","timeout":9000}""", 5_000))
        val ok = assertInstanceOf(BridgeResponse.Ok::class.java, resp)
        val o = DomainJson.decodeObject(ok.payload!!)
        assertEquals("0", (o["code"] as DomainJson.Value.N).raw)
        assertEquals("out", (o["stdout"] as DomainJson.Value.S).v)
        assertEquals("err", (o["stderr"] as DomainJson.Value.S).v)
        assertEquals(false, (o["truncated"] as DomainJson.Value.B).v)
        assertEquals(9_000L, fake.timeout)
        // 缺 timeout → 走 handler 默认（不是 0、不是无限）
        h.handle(BridgeRequest(2, "shell", "exec", """{"cmd":"id"}""", 5_000))
        assertEquals(12_345L, fake.timeout)
        Unit
    }

    @Test
    fun `shell 方法别名与 mode 字面量`() = runBlocking {
        val fake = FakeShell()
        val h = SystemNamespaces.shell(fake)
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
    fun `shell 的 ROOT 档不经三态门禁——mode 直接落到执行体`() = runBlocking {
        // 钉的是**实际行为**（订正后的口径）：`AndroidShellExecutor` 与 `SystemNamespaces`
        // 的 KDoc 原写「ROOT 模式能不能用由装配层的 PermissionFacade 先判（§9.5）」，而那条
        // 路上**零判据** —— `PermissionFacade.ensure`/`requireGrantedOrThrow` 在生产代码里
        // 没有调用点，`PlatformWiring.inject` 是纯转接。mode 是 payload 字段，桥面掩码目录
        // 也只有 `shell` → DEVICE_CONTROL 一档（`:domain` 的 CapabilityMaskTest 钉那条），
        // 于是 ROOT 档一路走到执行体，中间没有任何一道三态查询。
        //
        // 真在判的只有设备自己怎么说（`su` 不在 → ERR_SERVICE_DISABLED；`su` 在但被拒 →
        // 非零退出码原样进 ShellResult），与 §9.3「root 通道恒登记，真无 root 时命令自己失败」
        // 同一条口径。**要把 ROOT 档收窄成"先判后用"，改这条用例之前先改 §11 的矩阵** ——
        // 掩码不是沙箱（同 UID 同权，脚本绕开桥直接 spawn `su` 效果一样）。
        val fake = FakeShell()
        val h = SystemNamespaces.shell(fake)
        assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(1, "shell", "exec", """{"cmd":"id","mode":"root"}""", 5_000)),
        )
        assertEquals(ShellMode.ROOT, fake.mode, "ROOT 档原样落到执行体（不经三态、不经任何预检）")
        Unit
    }

    @Test
    fun `shell 截断标志原样透出：截断不改退出码也不折成错误`() = runBlocking {
        val fake = FakeShell(result = { ShellResult(0, "big", null, truncated = true) })
        val resp = SystemNamespaces.shell(fake)
            .handle(BridgeRequest(1, "shell", "exec", """{"cmd":"cat big"}""", 5_000))
        val ok = assertInstanceOf(BridgeResponse.Ok::class.java, resp)
        val o = DomainJson.decodeObject(ok.payload!!)
        // 仍走 Ok（不是 Err）：截断是捕获策略，命令本身跑成功了。
        assertEquals("0", (o["code"] as DomainJson.Value.N).raw)
        assertEquals(true, (o["truncated"] as DomainJson.Value.B).v)
        Unit
    }

    @Test
    fun `shell 分类错误原码透传，零超时与缺 cmd 是参数错`() = runBlocking {
        val denied = FakeShell(fail = { AutojsException(ErrorCode.ERR_PERMISSION_DENIED, "root 未授权") })
        val h = SystemNamespaces.shell(denied)
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
        val h = SystemNamespaces.device(FakeDevice())
        val model = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(1, "device", "model", null, 5_000)),
        )
        assertEquals("Pixel 8", DomainJson.decode(model.payload!!).let { (it as DomainJson.Value.S).v })
        val sdk = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(2, "device", "sdkInt", null, 5_000)),
        )
        assertEquals("34", (DomainJson.decode(sdk.payload!!) as DomainJson.Value.N).raw)
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
        val h = SystemNamespaces.app(FakeApp(launched = false, current = null))
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
        assertInstanceOf(DomainJson.Value.Null::class.java, DomainJson.decode(cur.payload!!))
        Unit
    }

    @Test
    fun `app launch 成功回 true，缺 packageName 是参数错`() = runBlocking {
        val h = SystemNamespaces.app(FakeApp())
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

    // ── floatingWindow ──────────────────────────────────────────────

    @Test
    fun `floatingWindow create 回句柄两字段，close 幂等透传`() = runBlocking {
        val fake = FakeFloating(next = 42)
        val h = SystemNamespaces.floatingWindow(fake)
        val created = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(1, "floatingWindow", "create", """{"title":"面板","width":300,"height":200}""", 5_000)),
        )
        val o = DomainJson.decodeObject(created.payload!!)
        assertEquals("42", (o["refId"] as DomainJson.Value.N).raw)
        assertEquals("1", (o["generation"] as DomainJson.Value.N).raw)

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
        val h = SystemNamespaces.floatingWindow(fake)
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
        val stale = SystemNamespaces.floatingWindow(
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

    @Test
    fun `floatingWindow 尺寸超 Int 域即拒——不得截断成 1 像素的窗`() = runBlocking {
        // 老路 `optLong(...).toInt()`：4294967297 截成 1 —— **一个合法的宽**（> 0），
        // 于是一条 `width=4294967297` 的 create 静默造出一个 1 像素宽的窗，而
        // `FloatingWindowSpec` 的 `require(width > 0)` 对此毫无意见。2147483648 截成
        // Int.MIN_VALUE，那条被 require 挡下 —— 挡的是「非正」这条错，不是「越界」这条。
        val fake = FakeFloating()
        val h = SystemNamespaces.floatingWindow(fake)
        for (raw in listOf(4294967297L, 4294967296L, 2147483648L, -2147483649L)) {
            for (key in listOf("width", "height")) {
                val payload = "{\"title\":\"面板\",\"$key\":$raw}"
                val r = assertInstanceOf(
                    BridgeResponse.Err::class.java,
                    h.handle(BridgeRequest(1, "floatingWindow", "create", payload, 5_000)),
                    "$key=$raw 必须是分类错误（不是截断后的另一个尺寸）",
                )
                assertEquals(ErrorCode.ERR_INVALID_PARAM.code, r.errorCode, "$key=$raw")
            }
        }
        assertTrue(fake.created.isEmpty(), "被拒的 create 不碰平台层（实际 ${fake.created}）")
        Unit
    }

    // ── 挂载缝整体口径 ──────────────────────────────────────────────

    @Test
    fun `shell 的 mode 缺省是 default（JS 不传 mode 不抛错）`() = runBlocking {
        val shellFake = FakeShell()
        SystemNamespaces.shell(shellFake)
            .handle(BridgeRequest(1, "shell", "exec", """{"cmd":"id"}""", 5_000))
        assertEquals(ShellMode.DEFAULT, shellFake.mode)
        Unit
    }
}
