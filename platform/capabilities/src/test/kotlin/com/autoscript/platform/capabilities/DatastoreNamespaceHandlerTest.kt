package com.autoscript.platform.capabilities

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.storage.InMemoryDataStore
import com.autoscript.domain.storage.StoredEntry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * `datastore` 桥处理器测试（§9.6；JS 对偶 `bridge/js/src/datastore.ts` +
 * `datastore.test.cjs` mock 宿主逐字复刻本文件的回包）。
 *
 * 判据四件：
 * 1. **found 信封不折叠**：键缺失 `{"found":false}` ≠ 存的 JSON null
 *    `{"found":true,"value":null}` —— 折叠了 JS 侧 undefined/null 就分不开；
 * 2. **值面 JSON 透传**：数字原文（`1.50` 不掉尾零）、对象保持解析序；
 * 3. **字节不过桥**：存储里的 Bytes 如实 ERR_NOT_IMPLEMENTED（不 base64 假装通用）；
 * 4. **参数口径**：缺 key / 空白 key / put 省略 value → ERR_INVALID_PARAM；
 *    未知方法 → ERR_NOT_IMPLEMENTED。
 */
class DatastoreNamespaceHandlerTest {

    private val store = InMemoryDataStore()
    private val handler = CapabilityNamespaces.datastore(store)

    private suspend fun call(method: String, payload: String?): BridgeResponse =
        handler.handle(BridgeRequest(1, "datastore", method, payload, 5_000))

    private fun okText(r: BridgeResponse): String {
        val ok = assertInstanceOf(BridgeResponse.Ok::class.java, r)
        return ok.payload!!
    }

    private fun errCode(r: BridgeResponse): String {
        val err = assertInstanceOf(BridgeResponse.Err::class.java, r)
        return err.errorCode
    }

    @Test
    fun `put get 往返——对象结构与解析序原样回信封`() = runBlocking {
        val put = call("put", """{"key":"cfg","value":{"b":2,"a":[1,null]}}""")
        assertEquals("true", okText(put))

        val got = okText(call("get", """{"key":"cfg"}"""))
        assertEquals("""{"found":true,"value":{"b":2,"a":[1,null]}}""", got, "值子树按解析序编回")
    }

    @Test
    fun `键缺失与 JSON null 不折叠——found 信封两形态`() = runBlocking {
        assertEquals(
            """{"found":false}""",
            okText(call("get", """{"key":"absent"}""")),
            "缺失 = found:false（JS 折 undefined）",
        )

        call("put", """{"key":"explicit","value":null}""")
        assertEquals(
            """{"found":true,"value":null}""",
            okText(call("get", """{"key":"explicit"}""")),
            "存的 JSON null = found:true,value:null（JS 回 null，≠ undefined）",
        )
    }

    @Test
    fun `数字原文透传——尾零不被 Double 化掉`() = runBlocking {
        call("put", """{"key":"num","value":{"n":1.50}}""")
        val got = okText(call("get", """{"key":"num"}"""))
        assertEquals("""{"found":true,"value":{"n":1.50}}""", got, "Value.N.raw 原文进信封")
    }

    @Test
    fun `参数口径——缺 key 空白 key put 省 value 全是 ERR_INVALID_PARAM`() = runBlocking {
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("get", null)))
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("get", """{"key":"  "}""")))
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("put", """{"key":"k"}""")))
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("put", """{"key":123,"value":1}""")),
            "key 非字符串",
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("get", "not-json")), "载荷非法 JSON")
    }

    @Test
    fun `未知方法 ERR_NOT_IMPLEMENTED——transaction 不在桥面`() = runBlocking {
        assertEquals(
            ErrorCode.ERR_NOT_IMPLEMENTED.code,
            errCode(call("transaction", """{"ops":[]}""")),
            "事务 P0 不过桥（facade 也无此方法，这里是兜底）",
        )
    }

    @Test
    fun `remove contains 键存在性语义`() = runBlocking {
        call("put", """{"key":"k","value":1}""")
        assertEquals("true", okText(call("contains", """{"key":"k"}""")))
        assertEquals("true", okText(call("remove", """{"key":"k"}""")), "移除了真值 → true")
        assertEquals("false", okText(call("remove", """{"key":"k"}""")), "再移除 → false（幂等）")
        assertEquals("false", okText(call("contains", """{"key":"k"}""")))
        assertEquals("""{"found":false}""", okText(call("get", """{"key":"k"}""")))
    }

    @Test
    fun `keys 与 clear 全量语义`() = runBlocking {
        call("put", """{"key":"a","value":1}""")
        call("put", """{"key":"b","value":"x"}""")
        assertEquals("""["a","b"]""", okText(call("keys", null)))
        assertEquals("true", okText(call("clear", null)))
        assertEquals("[]", okText(call("keys", null)))
    }

    @Test
    fun `存储里的字节值如实 ERR_NOT_IMPLEMENTED——不 base64 假装通用`() = runBlocking {
        store.put("blob", StoredEntry.Bytes(byteArrayOf(1, 2, 3)))
        val err = assertInstanceOf(
            BridgeResponse.Err::class.java,
            call("get", """{"key":"blob"}"""),
        )
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, err.errorCode)
        assertEquals(true, err.detail?.contains("side-channel"), "detail 说清为什么：side-channel 未接")
    }
}
