package com.autoscript.shell

import kotlinx.coroutines.CompletableDeferred

/**
 * 后台装配的共享结果：读者等同一次装配，不把「还在装」当成读取失败。
 *
 * 结果不挂在某个页面的 Job 下：切页只取消该次 await，不取消装配或其他读者。
 * 成功/失败都结束等待；再次装配才换一轮，不能把已完成的旧壳永远缓存住。
 * [begin] 保留尚未完成的首轮，所以先进入页面的读者不会被换到一份无人完成的结果上。
 */
internal class AssemblyResult<T> {
    @Volatile
    private var result = CompletableDeferred<T>()

    @Synchronized
    fun begin(): CompletableDeferred<T> {
        if (result.isCompleted) result = CompletableDeferred()
        return result
    }

    suspend fun await(): T = result.await()
}
