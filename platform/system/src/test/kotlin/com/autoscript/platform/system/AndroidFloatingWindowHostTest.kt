package com.autoscript.platform.system

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.FloatingWindowSpec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `floatingWindow` Android 实现的契约测试（docs §9.4/§12.2）：发号、幂等、错误分类。
 *
 * §12.2 把"有状态的判断"划给实现层，本测试就是钉住那份判断的：
 * 未知句柄 / 跨代句柄 / 已关闭句柄三种情形必须给出**不同**的结论。
 *
 * [AndroidFloatingWindowHost.FloatingWindowOps] 缝让本测试零 Android 依赖
 * （真机实现 `WindowManagerOps` 是唯一碰 `WindowManager` 的地方）。
 */
class AndroidFloatingWindowHostTest {

    @Test
    fun `create 发单调递增句柄，generation 恒 1`() = runBlocking {
        val ops = FakeOps()
        val host = AndroidFloatingWindowHost(ops)
        val a = host.create(FloatingWindowSpec.DEFAULT)
        val b = host.create(FloatingWindowSpec("面板", 300, 200))
        assertEquals(1L, a.refId)
        assertEquals(2L, b.refId)
        assertEquals(1L, a.generation)
        assertEquals(2, ops.added.size)
        // spec 原样交给实现层（width/height 的 wrap content 语义不在宿主这里解释）。
        assertEquals(FloatingWindowSpec("面板", 300, 200), ops.added[1].first)
    }

    @Test
    fun `overlay 可用性由缝决定，原样传给 ops`() = runBlocking {
        val ops = FakeOps()
        AndroidFloatingWindowHost(ops, overlayTypeAvailable = { false }).create(FloatingWindowSpec.DEFAULT)
        AndroidFloatingWindowHost(ops, overlayTypeAvailable = { true }).create(FloatingWindowSpec.DEFAULT)
        assertEquals(listOf(false, true), ops.added.map { it.second })
    }

    @Test
    fun `close 幂等：同一句柄关两次都成功，且只撤一次窗`() = runBlocking {
        val ops = FakeOps()
        val host = AndroidFloatingWindowHost(ops)
        val ref = host.create(FloatingWindowSpec.DEFAULT)
        host.close(ref)
        host.close(ref)   // 脚本 finally 里补一刀是常态：不该把清理变成新的失败源
        assertEquals(1, ops.removed.size)
    }

    @Test
    fun `未知句柄抛 ERR_STALE_HANDLE（与"已关闭"可分辨）`() = runBlocking {
        val host = AndroidFloatingWindowHost(FakeOps())
        host.create(FloatingWindowSpec.DEFAULT)
        val e = assertThrows(AutojsException::class.java) {
            runBlocking { host.close(HandleRef(999, 1)) }
        }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, e.error)
    }

    @Test
    fun `跨代句柄抛 ERR_STALE_HANDLE，且不动窗口`() = runBlocking {
        val ops = FakeOps()
        val host = AndroidFloatingWindowHost(ops)
        val ref = host.create(FloatingWindowSpec.DEFAULT)
        val e = assertThrows(AutojsException::class.java) {
            runBlocking { host.close(HandleRef(ref.refId, 2)) }
        }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, e.error)
        assertTrue(ops.removed.isEmpty(), "代次不匹配不得误关活着的窗口")
    }

    @Test
    fun `加窗被系统拒绝折成 ERR_PERMISSION_DENIED，不发假句柄`() = runBlocking {
        val host = AndroidFloatingWindowHost(FakeOps(failAdd = SecurityException("permission denied")))
        val e = assertThrows(AutojsException::class.java) {
            runBlocking { host.create(FloatingWindowSpec.DEFAULT) }
        }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, e.error)
        // 失败不占号：下一次成功 create 仍是 1（发号器只在真正加窗成功后才前进）。
        val ok = AndroidFloatingWindowHost(FakeOps()).create(FloatingWindowSpec.DEFAULT)
        assertEquals(1L, ok.refId)
    }

    @Test
    fun `撤窗抛异常不改变"已关闭"的结论（窗口已被系统收走）`() = runBlocking {
        val ops = FakeOps(failRemove = IllegalStateException("view not attached"))
        val host = AndroidFloatingWindowHost(ops)
        val ref = host.create(FloatingWindowSpec.DEFAULT)
        host.close(ref)                       // 不抛：目标状态已达成
        host.close(ref)                       // 重复 close 仍幂等（第二次连 remove 都不再调）
        assertEquals(1, ops.removed.size)
    }

    private class FakeOps(
        private val failAdd: Exception? = null,
        private val failRemove: Exception? = null,
    ) : AndroidFloatingWindowHost.FloatingWindowOps {
        val added = ArrayList<Pair<FloatingWindowSpec, Boolean>>()
        val removed = ArrayList<Any>()

        override suspend fun add(spec: FloatingWindowSpec, overlay: Boolean): Any {
            failAdd?.let { throw it }
            added += spec to overlay
            return "token-${added.size}"
        }

        override suspend fun remove(token: Any) {
            // 先记账再抛：真机上 removeView 是"调用了但被拒"，不是"没调用"。
            removed += token
            failRemove?.let { throw it }
        }
    }
}
