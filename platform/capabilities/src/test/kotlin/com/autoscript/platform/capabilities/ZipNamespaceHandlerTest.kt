package com.autoscript.platform.capabilities

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.storage.ZipArchiver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * `zip` 桥处理器测试（§9.6；JS 对偶 `bridge/js/src/zip.ts` + `zip.test.cjs`）。
 *
 * 判据三件：
 * 1. **wire 形状**：compress 发 `{source,archive}`、extract 发 `{archive,targetDir}`，
 *    两路径原样到 SPI（与 facade 逐字段对齐，改一侧另一侧就 undefined）；
 * 2. **错误不折叠**：SPI 的 `AutojsException` 原码透传（ERR_FILE_NOT_FOUND / ERR_IO
 *    不被洗成 INVALID_PARAM）—— 归档失败的现场诊断全靠码和 detail；
 * 3. **参数口径**：缺参/空白/NUL 路径 → ERR_INVALID_PARAM；未知方法 →
 *    ERR_NOT_IMPLEMENTED（`unzip` 别名不猜）。
 *
 * 归档语义（zip-slip 等）归 `:platform:system` 的 `JdkZipArchiverTest` ——
 * 这里是假归档器，只验桥面转接。
 */
class ZipNamespaceHandlerTest {

    private class FakeArchiver : ZipArchiver {
        val calls = mutableListOf<Pair<String, Pair<Path, Path>>>()
        var failWith: AutojsException? = null

        override suspend fun compress(source: Path, archive: Path) {
            failWith?.let { throw it }
            calls += "compress" to (source to archive)
        }

        override suspend fun extract(archive: Path, targetDir: Path) {
            failWith?.let { throw it }
            calls += "extract" to (archive to targetDir)
        }
    }

    private val fake = FakeArchiver()
    private val handler = CapabilityNamespaces.zip(fake)

    private suspend fun call(method: String, payload: String?): BridgeResponse =
        handler.handle(BridgeRequest(1, "zip", method, payload, 5_000))

    private fun ok(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Ok::class.java, r).payload!!

    private fun errCode(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Err::class.java, r).errorCode

    @Test
    fun `compress 两路径原样到 SPI，回 true`() = runBlocking {
        val resp = call("compress", """{"source":"/a/dir","archive":"/b/out.zip"}""")
        assertEquals("true", ok(resp))
        val (op, paths) = fake.calls.single()
        assertEquals("compress", op)
        assertEquals(Path.of("/a/dir"), paths.first)
        assertEquals(Path.of("/b/out.zip"), paths.second)
    }

    @Test
    fun `extract 两路径原样到 SPI`() = runBlocking {
        val resp = call("extract", """{"archive":"/b/out.zip","targetDir":"/c/x"}""")
        assertEquals("true", ok(resp))
        val (op, paths) = fake.calls.single()
        assertEquals("extract", op)
        assertEquals(Path.of("/b/out.zip"), paths.first)
        assertEquals(Path.of("/c/x"), paths.second)
    }

    @Test
    fun `参数口径——缺参空白 NUL 路径全是 ERR_INVALID_PARAM`() = runBlocking {
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("compress", null)))
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("compress", """{"source":"/a"}""")),
            "缺 archive",
        )
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("extract", """{"archive":"  ","targetDir":"/c"}""")),
            "空白路径",
        )
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("extract", """{"archive":"/b/z.zip","targetDir":"bad\u0000path"}""")),
            "NUL 字符：Path.of 抛 InvalidPathException → 折叠",
        )
        assertEquals(true, fake.calls.isEmpty(), "被拒的调用不碰归档器")
    }

    @Test
    fun `SPI 错误原码透传——不折叠成 INVALID_PARAM`() = runBlocking {
        fake.failWith = AutojsException(ErrorCode.ERR_FILE_NOT_FOUND, "压缩源不存在: /a/nope", null)
        val nf = assertInstanceOf(BridgeResponse.Err::class.java, call("compress", """{"source":"/a/nope","archive":"/b/z.zip"}"""))
        assertEquals(ErrorCode.ERR_FILE_NOT_FOUND.code, nf.errorCode)
        assertEquals(true, nf.detail!!.contains("压缩源不存在"), "detail 原样带现场")

        fake.failWith = AutojsException(ErrorCode.ERR_IO, "不是合法 zip: xx", null)
        val io = assertInstanceOf(BridgeResponse.Err::class.java, call("extract", """{"archive":"/b/bad.zip","targetDir":"/c"}"""))
        assertEquals(ErrorCode.ERR_IO.code, io.errorCode)
        fake.failWith = null
    }

    @Test
    fun `未知方法与 unzip 别名都如实 ERR_NOT_IMPLEMENTED`() = runBlocking {
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, errCode(call("unzip", """{"archive":"/b/z.zip","targetDir":"/c"}""")))
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, errCode(call("list", null)))
    }
}
