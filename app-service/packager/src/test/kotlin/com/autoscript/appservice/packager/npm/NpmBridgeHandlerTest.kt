package com.autoscript.appservice.packager.npm

import com.autoscript.domain.scripts.ScriptPaths
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.npm.PackageSpec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * npm 命名空间桥处理器单测（§10.8）：方法路由 + 参数校验 + 错误分类。
 * 用真 InstallCoordinator + FakeExecutor（门禁/journal/轻操作全走真路径），
 * 只把重操作执行体替换为内存假造——桥行为验证不依赖网络。
 */
class NpmBridgeHandlerTest {

    @TempDir
    lateinit var dir: Path

    private val layout get() = NpmProjectLayout(ScriptPaths.projectsRoot(dir))
    private val installed = mutableListOf<Pair<String, List<PackageSpec>>>()

    private class RecordingExecutor(val sink: (String, List<PackageSpec>) -> Unit) : InstallCoordinator.HeavyOpExecutor {
        override suspend fun execute(op: InstallCoordinator.HeavyOp, sink2: InstallCoordinator.ProgressSink): String {
            sink(op.projectId, op.args.drop(1).map { s -> parseLikeHandler(s) })
            return "ok"
        }

        /** 与 NpmBridgeHandler.parseSpec 同规则的 spec 反拆（双断言防漂移）。 */
        private fun parseLikeHandler(spec: String): PackageSpec {
            val at = if (spec.startsWith("@")) spec.indexOf('@', 1) else spec.indexOf('@')
            return if (at <= 0 || at == spec.length - 1) PackageSpec(spec, null)
            else PackageSpec(spec.substring(0, at), spec.substring(at + 1))
        }
    }

    private fun handler(executor: InstallCoordinator.HeavyOpExecutor = RecordingExecutor { p, s -> installed += p to s }) = NpmBridgeHandler(
        InstallCoordinator(
            services = NpmServices(
                layout = layout,
                journal = InstallJournal(dir.resolve(".autojs")),
                staging = InstallStaging(layout),
                ledger = ApprovalLedger(),
                cacheIndex = CacheIndex { false },
            ),
            executor = executor,
            freeSpaceProbe = { 10L * 1024 * 1024 * 1024 },
        ),
    )

    private fun req(method: String, payload: String?) =
        BridgeRequest(id = 1, namespace = "npm", method = method, payload = payload, ttlMillis = 10_000)

    private fun json(vararg kv: Pair<String, Any?>): String = NpmBridgeJson.encode(mapOf(*kv))

    @Test
    fun `install 路由到 install 并透传 spec 拆分`() = runBlocking {
        val h = handler()
        val r = h.handle(req("install", json("spec" to "lodash@4.17.21")))
        assertTrue(r is com.autoscript.domain.bridge.BridgeResponse.Ok, "install 必须 Ok（实为 $r）")
        // 回包 = :domain InstallHandle 原样上桥（handleId/projectId/enqueuedAtMillis）。
        // 刻意**不是**包体：门面此刻还不知道会装出什么版本，回一个猜的版本号就是伪造
        // （§10.8 文档里 install → {name,version,integrity} 的示例在门面形状下产不出来）。
        // JS facade 侧断言同一份形状（npm-contract.test.cjs 的 mock 逐字复刻这里）。
        val payload = (r as com.autoscript.domain.bridge.BridgeResponse.Ok).payload!!
        assertTrue(payload.contains("\"handleId\":\"inst-1\""), "回包须带句柄：$payload")
        assertTrue(payload.contains("\"projectId\":\"main\""), payload)
        assertTrue(payload.contains("\"enqueuedAtMillis\":"), payload)
        assertTrue(!payload.contains("integrity"), "不得在回包里伪造 integrity：$payload")
        assertTrue(!payload.contains("\"version\""), "不得在回包里伪造 version：$payload")
        assertEquals(1, installed.size)
        assertEquals("main", installed[0].first, "缺省项目须为 main（诚实默认）")
        assertEquals(PackageSpec("lodash", "4.17.21"), installed[0].second.single())
    }

    @Test
    fun `install 显式 projectId 覆盖缺省`() = runBlocking {
        val h = handler()
        h.handle(req("install", json("spec" to "axios", "projectId" to "p2")))
        assertEquals("p2", installed.single().first)
    }

    @Test
    fun `install 空 projectId 拒绝（不静默套默认）`() = runBlocking {
        val h = handler()
        val r = h.handle(req("install", json("spec" to "axios", "projectId" to "")))
        assertTrue(r is com.autoscript.domain.bridge.BridgeResponse.Err)
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, (r as com.autoscript.domain.bridge.BridgeResponse.Err).errorCode)
        assertTrue(installed.isEmpty(), "空 projectId 不得落到安装链")
    }

    @Test
    fun `install 缺 spec 报 ERR_INVALID_PARAM`() = runBlocking {
        val r = handler().handle(req("install", json("projectId" to "p1")))
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, (r as com.autoscript.domain.bridge.BridgeResponse.Err).errorCode)
    }

    @Test
    fun `install 无 payload 报 ERR_INVALID_PARAM（不伪造）`() = runBlocking {
        val r = handler().handle(req("install", null))
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, (r as com.autoscript.domain.bridge.BridgeResponse.Err).errorCode)
    }

    @Test
    fun `scoped 包名不带版本不被误拆`() = runBlocking {
        val h = handler()
        h.handle(req("install", json("spec" to "@babel/core")))
        assertEquals(PackageSpec("@babel/core", null), installed.single().second.single(), "@scope 名的 @ 不是版本分隔")
    }

    @Test
    fun `remove 走 uninstall 轻语义`() = runBlocking {
        val r = handler().handle(req("remove", json("spec" to "dayjs", "projectId" to "p1")))
        assertTrue(r is com.autoscript.domain.bridge.BridgeResponse.Ok)
        assertNull((r as com.autoscript.domain.bridge.BridgeResponse.Ok).payload)
    }

    @Test
    fun `list 空项目返回空数组（零 Node 直读）`() = runBlocking {
        val r = handler().handle(req("list", json("projectId" to "empty-proj")))
        assertEquals("[]", (r as com.autoscript.domain.bridge.BridgeResponse.Ok).payload)
    }

    @Test
    fun `offlineGap 全量缺口（空缓存索引）`() = runBlocking {
        writeLock("p1", """{"lockfileVersion":3,"packages":{"":{},"node_modules/a":{"version":"1.0.0","integrity":"sha512-x"}}}""")
        val r = handler().handle(req("offlineGap", json("projectId" to "p1")))
        val payload = (r as com.autoscript.domain.bridge.BridgeResponse.Ok).payload!!
        assertTrue(payload.contains("\"a\""), "缺口须含 a：$payload")
        // version 是 JS MissingPkg 的字段（宿主一直发、facade 曾漏声明）：脚本要能
        // 提示「缺 a@1.0.0」而不是只拿到一个名字。
        assertTrue(payload.contains("\"version\":\"1.0.0\""), "缺口须带版本：$payload")
    }

    @Test
    fun `audit P0 诚实空报告标 offline`() = runBlocking {
        val r = handler().handle(req("audit", json("projectId" to "p1", "offline" to true)))
        val payload = (r as com.autoscript.domain.bridge.BridgeResponse.Ok).payload!!
        assertTrue(payload.contains("\"offline\":true"), payload)
        assertTrue(payload.contains("\"level\":\"none\""), payload)
        // 键名是 vulns（§10.8 文档 + JS AuditReport.vulns 都读它）。曾真实漂移成
        // vulnerabilities：两侧各自的测试都没抓到，因为 JS mock 自己回的 vulns。
        assertTrue(payload.contains("\"vulns\":[]"), "键名须为 vulns：$payload")
        assertTrue(!payload.contains("vulnerabilities"), "不得再发旧键名：$payload")
    }

    @Test
    fun `setRegistry 写项目 npmrc`() = runBlocking {
        val h = handler()
        h.handle(req("setRegistry", json("projectId" to "p1", "registry" to "https://registry.npmjs.org")))
        assertEquals(
            listOf("registry=https://registry.npmjs.org"),
            Files.readAllLines(layout.npmrc("p1")),
            "npmrc 单行就位（无尾行/空行）",
        )
    }

    @Test
    fun `setRegistry scope 落地为 npmrc 作用域键（不静默丢弃）`() = runBlocking {
        val h = handler()
        // bridge/js 的 setRegistry(registry, {scope}) 会带 scope；宿主不认就是静默丢用户声明
        h.handle(req("setRegistry", json("projectId" to "p1", "registry" to "https://registry.npmmirror.com", "scope" to "@my")))
        assertEquals(
            listOf("@my:registry=https://registry.npmmirror.com"),
            Files.readAllLines(layout.npmrc("p1")),
            "scope 须映射 npm 官方 `<scope>:registry` 键形",
        )
    }

    @Test
    fun `setRegistry 空 scope 当全局（不产出孤立的冒号键）`() = runBlocking {
        val h = handler()
        h.handle(req("setRegistry", json("projectId" to "p1", "registry" to "https://registry.npmjs.org", "scope" to "")))
        assertEquals(
            listOf("registry=https://registry.npmjs.org"),
            Files.readAllLines(layout.npmrc("p1")),
            "空串 scope 退化为全局键（与 JS 侧 opts.scope ?? null 同义）",
        )
    }

    @Test
    fun `requestApprove 只入队（脚本无 resolve 权）`() = runBlocking {
        val h = handler()
        val r = h.handle(req("requestApprove", json("projectId" to "p1", "pkg" to "esbuild", "versionHash" to "sha512-a")))
        val payload = (r as com.autoscript.domain.bridge.BridgeResponse.Ok).payload!!
        assertTrue(payload.contains("\"status\":\"pending\""), payload)
        // scripts 回显：调用方没声明 → 空表（不是 null/缺键，JS 侧 scripts: readonly string[]）
        assertTrue(payload.contains("\"scripts\":[]"), "未声明 scripts 须回显空表：$payload")
        // 方法表里根本没有 resolve：未知方法 → ERR_NOT_IMPLEMENTED（人机分离铁律）
        val resolveTry = h.handle(req("resolveApproval", json("requestId" to "x")))
        assertEquals(
            ErrorCode.ERR_NOT_IMPLEMENTED.code,
            (resolveTry as com.autoscript.domain.bridge.BridgeResponse.Err).errorCode,
            "resolveApproval 不得从桥面可达",
        )
    }

    @Test
    fun `未知方法 ERR_NOT_IMPLEMENTED`() = runBlocking {
        val r = handler().handle(req("explode", json()))
        assertEquals(
            ErrorCode.ERR_NOT_IMPLEMENTED.code,
            (r as com.autoscript.domain.bridge.BridgeResponse.Err).errorCode,
        )
    }

    @Test
    fun `底层 AutojsException 错误码透传（如 git 依赖 NOT_SUPPORTED）`() = runBlocking {
        val r = handler().handle(req("install", json("spec" to "foo@git+https://x.git", "projectId" to "p1")))
        assertEquals(
            ErrorCode.ERR_NOT_SUPPORTED.code,
            (r as com.autoscript.domain.bridge.BridgeResponse.Err).errorCode,
        )
    }

    @Test
    fun `requestApprove 回显 scripts 并校验形态（不静默丢弃用户声明）`() = runBlocking {
        val h = handler()
        val r = h.handle(
            req(
                "requestApprove",
                json("projectId" to "p1", "pkg" to "esbuild", "versionHash" to "sha512-a", "scripts" to listOf("postinstall")),
            ),
        )
        val payload = (r as com.autoscript.domain.bridge.BridgeResponse.Ok).payload!!
        assertTrue(payload.contains("\"requestId\":\"apr-1\""), payload)
        assertTrue(payload.contains("\"scripts\":[\"postinstall\"]"), "声明过的脚本须原样回显：$payload")

        // 形态不对即 ERR_INVALID_PARAM —— 与 setRegistry 的 scope 同一条纪律：
        // 宿主不认的字段被静默丢弃，比报错更糟。
        val bad = h.handle(req("requestApprove", json("projectId" to "p1", "pkg" to "esbuild", "scripts" to "postinstall")))
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            (bad as com.autoscript.domain.bridge.BridgeResponse.Err).errorCode,
            "scripts 必须是数组（字符串不是）",
        )
    }

    @Test
    fun `list 不发 sizeBytes（lockfile 量不到，恒 0 等于声称该包 0 字节）`() = runBlocking {
        writeLock(
            "p1",
            """{"lockfileVersion":3,"packages":{"":{},"node_modules/a":{"version":"1.0.0","integrity":"sha512-x"}}}""",
        )
        val r = handler().handle(req("list", json("projectId" to "p1")))
        val payload = (r as com.autoscript.domain.bridge.BridgeResponse.Ok).payload!!
        assertTrue(payload.contains("\"name\":\"a\""), payload)
        assertTrue(payload.contains("\"version\":\"1.0.0\""), payload)
        assertTrue(!payload.contains("sizeBytes"), "尺寸不在 list 面（JS PkgNode 无此字段）：$payload")
        // 尺寸的两条真来源仍在：offlineGap（缺失清单，带 size）与 storage（目录实测）
        val gap = handler().handle(req("offlineGap", json("projectId" to "p1")))
        assertTrue(
            (gap as com.autoscript.domain.bridge.BridgeResponse.Ok).payload!!.contains("\"size\":"),
            "offlineGap 仍带 size（尺寸的真来源）",
        )
    }

    private fun writeLock(projectId: String, content: String) {
        Files.createDirectories(layout.projectRoot(projectId))
        Files.write(layout.lockfile(projectId), (content).toByteArray())
    }
}

