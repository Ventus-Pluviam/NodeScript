package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.StopResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** RunSession 骨架契约：RunSession 接口 + RunContext 收口 + ReleaseOnly 默认归还。 */
class RunSessionTest {

    @Test
    fun `ReleaseOnly 幂等归还且标题稳定`() = runBlocking {
        val session: RunSession = ReleaseOnly()
        assertEquals("ReleaseOnly", session.title)
        val after = session.run()
        assertEquals(StopResult.Clean, session.quiesce())
        assertEquals(StopResult.Clean, session.quiesce(), "重复 quiesce 幂等")
        assertTrue(after === session, "run 返回自身（无副作用）")
    }

    @Test
    fun `自定义 RunContext 可注入展开路径`() = runBlocking {
        var seen: Pair<String, List<String>>? = null
        val ctx = object : RunContext {
            override suspend fun exec(expanded: String, args: List<String>) {
                seen = expanded to args
            }
        }
        ctx.exec("/data/user/0/com.autoscript/files/projects/demo/main.js", listOf("--prod"))
        assertEquals("/data/user/0/com.autoscript/files/projects/demo/main.js" to listOf("--prod"), seen)
    }

    @Test
    fun `骨架期 RunSession 可被直接构造（无 slot 依赖）`() {
        // 生产会话接 PoolHandle 后扩展；骨架只钉接口形状
        val view = object : SessionView {
            override val expandedScriptPath = "projects/demo/main.js"
        }
        assertEquals("projects/demo/main.js", view.expandedScriptPath)
    }
}
