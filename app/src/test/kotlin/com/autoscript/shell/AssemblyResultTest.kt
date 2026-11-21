package com.autoscript.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 读口等待的是进程级装配结果，而不是页面 Job 或一个定时重试次数。 */
class AssemblyResultTest {
    @Test
    fun `页面先开始读取也能在装配完成后由同一次调用拿到结果`() = runBlocking {
        val result = AssemblyResult<Any>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { result.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { result.await() }
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        val built = Any()
        result.begin().complete(built)
        withTimeout(5_000) {
            assertSame(built, first.await())
            assertSame(built, second.await())
            assertSame(built, result.await(), "就绪后的读取立即返回同一个产物，不重跑装配")
        }
        Unit
    }

    @Test
    fun `取消一个页面只撤该次等待不取消装配或其他页面`() = runBlocking {
        val result = AssemblyResult<Any>()
        val leaving = async(start = CoroutineStart.UNDISPATCHED) { result.await() }
        val staying = async(start = CoroutineStart.UNDISPATCHED) { result.await() }
        leaving.cancelAndJoin()
        assertFalse(staying.isCompleted)

        val built = Any()
        result.begin().complete(built)
        assertSame(built, withTimeout(5_000) { staying.await() })
        Unit
    }

    @Test
    fun `装配失败结束所有等待且保留原异常`() = runBlocking {
        val result = AssemblyResult<Any>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { result.await() }.exceptionOrNull() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { result.await() }.exceptionOrNull() }
        val cause = IllegalStateException("意图日志数据库打不开", java.io.IOException("database locked"))
        result.begin().completeExceptionally(cause)
        withTimeout(5_000) {
            for (failure in listOf(first.await(), second.await(), runCatching { result.await() }.exceptionOrNull())) {
                // 协程调试的 stacktrace recovery 可能复制异常外壳；追到原异常而不是断言外壳引用。
                assertEquals(cause.message, failure?.message)
                val chain = generateSequence(failure) { it.cause }.toList()
                assertTrue(chain.any { it === cause }, "共享结果必须保留装配时的原异常和 cause：$chain")
                assertTrue(chain.any { it === cause.cause }, "底层 IO 病因不能丢失")
            }
        }
        Unit
    }

    @Test
    fun `失败后可开启新一轮并且不再返回上一轮的产物`() = runBlocking {
        val result = AssemblyResult<Any>()
        result.begin().completeExceptionally(IllegalStateException("首次失败"))
        val retry = result.begin()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { result.await() }
        assertFalse(waiting.isCompleted)
        val built = Any()
        retry.complete(built)
        assertSame(built, withTimeout(5_000) { waiting.await() })

        val replacement = result.begin()
        val nextRead = async(start = CoroutineStart.UNDISPATCHED) { result.await() }
        assertFalse(nextRead.isCompleted, "重装配不能立即返回上一轮的壳")
        val newer = Any()
        replacement.complete(newer)
        assertSame(newer, withTimeout(5_000) { nextRead.await() })
        Unit
    }

    @Test
    fun `装配域在协程体执行前已取消也会结束等待读口`() = runBlocking {
        val result = AssemblyResult<Any>()
        val reader = async(start = CoroutineStart.UNDISPATCHED) { runCatching { result.await() }.exceptionOrNull() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.cancel()
        var entered = false
        val startup = result.begin()
        scope.launch { entered = true }.invokeOnCompletion { cause ->
            if (cause != null) startup.completeExceptionally(cause)
        }
        assertTrue(withTimeout(5_000) { reader.await() } is CancellationException)
        assertFalse(entered)
        Unit
    }
}
