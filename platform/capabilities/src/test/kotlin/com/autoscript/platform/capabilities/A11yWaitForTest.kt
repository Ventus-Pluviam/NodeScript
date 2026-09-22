package com.autoscript.platform.capabilities

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `waitFor` 的返回形状（§9.1 / §12.3）：与 `findOne` 共用选择器解析与树读路径，
 * 但回 **boolean** 而不是 `{ref}` —— 因为 JS facade `a11y.ts` 的 `waitFor` 是
 * `Promise<boolean>`（`result === true`），回 `{ref}` 会让它恒 `false`。
 *
 * 为什么单开一个文件：这条契约漂移曾真实存在（Kotlin 复用 `findOne` 的回包路径，
 * JS 只认 `=== true`），而两侧各自的测试都没抓到 —— JS mock 自己回 `'true'`，
 * Kotlin 的 `waitFor` 又没有专属断言。把它钉在这里，任一侧再漂都会红。
 */
class A11yWaitForTest {

    private lateinit var tree: InMemoryUiTree
    private lateinit var handler: A11yNamespaceHandler

    @BeforeEach
    fun setup() {
        tree = InMemoryUiTree()
        handler = A11yNamespaceHandler(tree, tree, InMemoryInputProvider())
    }

    private suspend fun seedButton() {
        tree.add(
            InMemoryUiTree.Attrs(text = "启动", className = "Button", clickable = true),
        )
    }

    @Test
    fun `命中回 Ok true 而非 ref 对象`() = runBlocking {
        seedButton()
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(
                A11yNamespaceHandler.Request(1, "waitFor", """{"conditions":{"text":"启动"}}"""),
            ),
        )
        assertEquals("true", resp.payload, "JS facade 只认 === true：回 {ref} 会让它恒 false")
        Unit
    }

    @Test
    fun `无匹配回 Ok false 而不是 Err NOT_FOUND`() = runBlocking {
        seedButton()
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(
                A11yNamespaceHandler.Request(2, "waitFor", """{"conditions":{"text":"不存在"}}"""),
            ),
        )
        // 调用方拿它做分支判断（if (await waitFor(...))），不是当异常处理。
        assertEquals("false", resp.payload)
        Unit
    }

    @Test
    fun `未知条件键仍是 Err 不折成 false`() = runBlocking {
        seedButton()
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Err::class.java,
            handler.handle(
                A11yNamespaceHandler.Request(3, "waitFor", """{"conditions":{"txt":"启动"}}"""),
            ),
        )
        assertEquals("ERR_INVALID_PARAM", resp.code, "参数错误照旧上抛，不与「没等到」混为一谈")
        Unit
    }

    @Test
    fun `缺 payload 仍是 Err 不折成 false`() = runBlocking {
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Err::class.java,
            handler.handle(A11yNamespaceHandler.Request(4, "waitFor", null)),
        )
        assertEquals("ERR_INVALID_PARAM", resp.code)
        Unit
    }

    @Test
    fun `findOne 的返回形状未被带偏 仍是 ref 对象`() = runBlocking {
        seedButton()
        val findOne = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(5, "findOne", """{"conditions":{"text":"启动"}}""")),
        )
        val o = A11yBridgeJson.decodeObject(findOne.payload!!)
        assertInstanceOf(A11yBridgeJson.Value.Obj::class.java, o["ref"], "findOne 命中仍回 {ref}")
        val none = assertInstanceOf(
            A11yNamespaceHandler.Response.Err::class.java,
            handler.handle(A11yNamespaceHandler.Request(6, "findOne", """{"conditions":{"text":"不存在"}}""")),
        )
        assertEquals("ERR_NOT_FOUND", none.code, "findOne 无匹配仍是 NOT_FOUND（JS 折成 NotFoundError/null）")
        Unit
    }
}
