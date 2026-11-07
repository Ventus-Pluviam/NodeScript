package com.autoscript.platform.system.sensors

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `sensors` 宿主侧实现的契约测试（docs §12.2；假 [AndroidSensorSource.Ops]）。
 *
 * 守五件事（真实现是系统服务调用，只能编译不能跑 —— 语义全在这边）：
 * 1. **名归一化在本层**：大小写/空白/别名（`accel`/`gyro`/`temperature`/`humidity`）
 *    收敛到同一张表；空白名 `isSupported` 回 false、`register` 抛 `IllegalArgumentException`；
 * 2. **发号在本层**：refId 单调递增、generation 恒 1；
 * 3. **有界环在本层**：超 [AndroidSensorSource.RING_CAPACITY] 丢最旧，seq 空洞可见；
 *    `drain` 按 `seq > sinceSeq` 取增量，空增量回 `first == last == sinceSeq` + 空表；
 * 4. **注销纪律**：已知已关幂等成功，未知/跨代抛 `ERR_STALE_HANDLE`；已关订阅的
 *    `drain` 同样 `ERR_STALE_HANDLE`（关过的与没见过的必须能分辨）；
 * 5. **系统事实折叠在本层**：名不在表/设备缺席 → `ERR_NOT_SUPPORTED`；
 *    `start` 回 false → `ERR_SERVICE_DISABLED`（不发假句柄，失败不占号）。
 */
class AndroidSensorSourceTest {

    private class FakeOps(
        var present: Set<String> = setOf("accelerometer", "light"),
        var refuseStart: Boolean = false,
    ) : AndroidSensorSource.Ops {
        val started = mutableListOf<Triple<String, SensorDelay, Any>>()
        val stopped = mutableListOf<Any>()
        val samplers = mutableMapOf<Any, (List<Double>, Int, Long) -> Unit>()

        override fun hasSensor(normalizedName: String): Boolean = normalizedName in present

        override fun start(
            normalizedName: String,
            delay: SensorDelay,
            token: Any,
            onSample: (values: List<Double>, accuracy: Int, timestamp: Long) -> Unit,
        ): Boolean {
            if (refuseStart) return false
            started += Triple(normalizedName, delay, token)
            samplers[token] = onSample
            return true
        }

        override fun stop(token: Any) {
            stopped += token
            samplers.remove(token)
        }

        fun emit(token: Any, values: List<Double>, accuracy: Int = 3, timestamp: Long = 1_000L) {
            samplers[token]!!(values, accuracy, timestamp)
        }
    }

    @Test
    fun `名归一化——大小写空白别名收敛，空白名不支持不抛`() {
        val ops = FakeOps()
        val source = AndroidSensorSource(ops)
        assertTrue(source.isSupported("accelerometer"))
        assertTrue(source.isSupported("  ACCEL  "), "别名+大小写+空白收敛")
        assertFalse(source.isSupported("gyro"), "归一化对了（gyro→gyroscope），替身无该设备 → 如实 false")
        assertFalse(source.isSupported("heart_rate"), "BODY_SENSORS 不在 P0 名单")
        assertFalse(source.isSupported(""), "空白名回 false 不抛")
        assertFalse(source.isSupported("   "), "空白名回 false 不抛")
        Unit
    }

    @Test
    fun `register 发号单调递增代次恒1，delay原样交ops`() = runBlocking {
        val ops = FakeOps()
        val source = AndroidSensorSource(ops)
        val a = source.register("accelerometer", SensorDelay.GAME)
        val b = source.register("light")
        assertEquals(1L, a.refId)
        assertEquals(2L, b.refId)
        assertEquals(1L, a.generation)
        assertEquals(SensorDelay.GAME, ops.started[0].second)
        assertEquals(SensorDelay.NORMAL, ops.started[1].second, "缺省 NORMAL")
        Unit
    }

    @Test
    fun `register 空白名抛IAE，未知名或缺席抛NOT_SUPPORTED`() = runBlocking {
        val source = AndroidSensorSource(FakeOps())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { source.register("  ") }
        }
        val e1 = assertThrows(AutojsException::class.java) {
            runBlocking { source.register("heart_rate") }
        }
        assertEquals(ErrorCode.ERR_NOT_SUPPORTED, e1.error)
        val e2 = assertThrows(AutojsException::class.java) {
            runBlocking { source.register("gyroscope") }
        }
        assertEquals(ErrorCode.ERR_NOT_SUPPORTED, e2.error, "在表但设备缺席同样 NOT_SUPPORTED")
        Unit
    }

    @Test
    fun `系统拒收折SERVICE_DISABLED且失败不占号`() = runBlocking {
        val ops = FakeOps(refuseStart = true)
        val source = AndroidSensorSource(ops)
        val e = assertThrows(AutojsException::class.java) {
            runBlocking { source.register("accelerometer") }
        }
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED, e.error)
        assertTrue(ops.started.isEmpty(), "拒收路径 start 回 false，不记 started")
        // 失败不占号：换个肯收的 ops，首个成功订阅仍是 1（发号器只在成功后前进）。
        val ok = AndroidSensorSource(FakeOps()).register("accelerometer")
        assertEquals(1L, ok.refId)
        Unit
    }

    @Test
    fun `采样进环drain按游标取增量，空增量游标回显`() = runBlocking {
        val ops = FakeOps()
        val source = AndroidSensorSource(ops)
        val ref = source.register("accelerometer")
        val token = ops.started.single { it.first == "accelerometer" }.third
        ops.emit(token, listOf(0.1, 9.8, 0.2), accuracy = 3, timestamp = 100)
        ops.emit(token, listOf(0.2, 9.7, 0.3), accuracy = 3, timestamp = 200)

        val batch = source.drain(ref, sinceSeq = 0, max = 128)
        assertEquals(1L, batch.firstSeq)
        assertEquals(2L, batch.lastSeq)
        assertEquals(2, batch.events.size)
        assertEquals(listOf(0.1, 9.8, 0.2), batch.events[0].values)
        assertEquals(100L, batch.events[0].timestamp)

        val rest = source.drain(ref, sinceSeq = 2, max = 128)
        assertEquals(2L, rest.firstSeq)
        assertEquals(2L, rest.lastSeq, "空增量 first == last == sinceSeq")
        assertTrue(rest.events.isEmpty())

        val capped = source.drain(ref, sinceSeq = 0, max = 1)
        assertEquals(1, capped.events.size, "max 截断")
        assertEquals(1L, capped.lastSeq)
        Unit
    }

    @Test
    fun `环超界丢最旧seq空洞可见`() = runBlocking {
        val ops = FakeOps()
        val source = AndroidSensorSource(ops)
        val ref = source.register("accelerometer")
        val token = ops.started.single().third
        val cap = AndroidSensorSource.RING_CAPACITY
        for (i in 1..(cap + 10)) ops.emit(token, listOf(i.toDouble()))
        val batch = source.drain(ref, sinceSeq = 0, max = cap + 100)
        assertEquals(cap, batch.events.size, "环只留最近 CAP 条")
        assertEquals(11L, batch.firstSeq, "丢掉的是最旧 10 条，空洞可见不断流")
        assertEquals((cap + 10).toLong(), batch.lastSeq)
        Unit
    }

    @Test
    fun `drain max非正抛IAE`() = runBlocking {
        val source = AndroidSensorSource(FakeOps())
        val ref = source.register("accelerometer")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { source.drain(ref, sinceSeq = 0, max = 0) }
        }
        Unit
    }

    @Test
    fun `unregister已知已关幂等，未知跨代抛STALE`() = runBlocking {
        val ops = FakeOps()
        val source = AndroidSensorSource(ops)
        val ref = source.register("accelerometer")
        source.unregister(ref)
        source.unregister(ref)   // finally 补刀是常态：不该把清理变成新的失败源
        assertEquals(1, ops.stopped.size, "幂等关闭只碰一次传感器系统")

        val e1 = assertThrows(AutojsException::class.java) {
            runBlocking { source.unregister(HandleRef(999, 1)) }
        }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, e1.error, "没见过的订阅")

        val e2 = assertThrows(AutojsException::class.java) {
            runBlocking { source.unregister(HandleRef(ref.refId, 2)) }
        }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, e2.error, "跨代")
        assertEquals(1, ops.stopped.size, "代次不匹配不得误关")
        Unit
    }

    @Test
    fun `已关订阅的drain抛STALE与未知可分辨`() = runBlocking {
        val source = AndroidSensorSource(FakeOps())
        val ref = source.register("accelerometer")
        source.unregister(ref)
        val closed = assertThrows(AutojsException::class.java) {
            runBlocking { source.drain(ref, sinceSeq = 0, max = 10) }
        }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, closed.error)
        assertTrue(closed.message!!.contains("已关闭"), "关过的订阅要能分辨，实际 ${closed.message}")
        val unknown = assertThrows(AutojsException::class.java) {
            runBlocking { source.drain(HandleRef(999, 1), sinceSeq = 0, max = 10) }
        }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, unknown.error)
        assertTrue(unknown.message!!.contains("未知"), "没见过的订阅要能分辨，实际 ${unknown.message}")
        Unit
    }

    // ── 号段水位：`closedEver` 换成 O(1) 判据之后，语义一条不许松 ────────────

    @Test
    fun `水位判据把三种号分开——已关、没见过、垃圾号`() = runBlocking {
        val source = AndroidSensorSource(FakeOps())
        val ref = source.register("accelerometer")
        source.unregister(ref)

        // 已关：号在水位以下、不在 live → "已关闭"（不是"未知"）
        val closed = assertThrows(AutojsException::class.java) {
            runBlocking { source.drain(ref, sinceSeq = 0, max = 10) }
        }
        assertTrue(closed.message!!.contains("已关闭"), "实际 ${closed.message}")

        // 没见过：号在水位以上 → "未知"
        val future = assertThrows(AutojsException::class.java) {
            runBlocking { source.drain(HandleRef(ref.refId + 1, 1), sinceSeq = 0, max = 10) }
        }
        assertTrue(future.message!!.contains("未知"), "实际 ${future.message}")

        // 垃圾号（0 与负数都在水位以下，却从没发过）：必须报"未知"，不许借"已关闭"糊过去
        for (junk in listOf(0L, -1L, Long.MIN_VALUE)) {
            val e = assertThrows(AutojsException::class.java) {
                runBlocking { source.drain(HandleRef(junk, 1), sinceSeq = 0, max = 10) }
            }
            assertEquals(ErrorCode.ERR_STALE_HANDLE, e.error)
            assertTrue(e.message!!.contains("未知"), "垃圾号 $junk 不是「已关闭」：${e.message}")
        }
        Unit
    }

    @Test
    fun `系统拒收不推进水位——从没发出去的号不许被报成"已关闭"`() = runBlocking {
        // 前半程拒收：那次消耗的号若落进水位以下，就会让 1 号被答成"关过的订阅"。
        val refusing = AndroidSensorSource(FakeOps(refuseStart = true))
        assertThrows(AutojsException::class.java) { runBlocking { refusing.register("accelerometer") } }
        val junk = assertThrows(AutojsException::class.java) {
            runBlocking { refusing.drain(HandleRef(1, 1), sinceSeq = 0, max = 10) }
        }
        assertTrue(junk.message!!.contains("未知"), "从没发出去的号必须是「未知」：${junk.message}")

        // 同一个实例上拒收之后真建一个：首号仍是 1（发号器只在成功后前进）
        val ops = FakeOps(refuseStart = true)
        val source = AndroidSensorSource(ops)
        assertThrows(AutojsException::class.java) { runBlocking { source.register("accelerometer") } }
        assertThrows(AutojsException::class.java) { runBlocking { source.register("light") } }
        ops.refuseStart = false
        val ok = source.register("accelerometer")
        assertEquals(AndroidSensorSource.FIRST_REF_ID, ok.refId, "两次失败没占号")
        Unit
    }

    @Test
    fun `unregister 的水位判据同样分三种——已关幂等、没见过与跨代如实 STALE`() = runBlocking {
        val ops = FakeOps()
        val source = AndroidSensorSource(ops)
        val a = source.register("accelerometer")
        val b = source.register("light")
        source.unregister(a)

        source.unregister(a)                      // 已关 → 幂等成功，不再碰传感器系统
        assertEquals(1, ops.stopped.size, "幂等关闭只碰一次系统")

        val future = assertThrows(AutojsException::class.java) {
            runBlocking { source.unregister(HandleRef(b.refId + 1, 1)) }
        }
        assertTrue(future.message!!.contains("未知"), "水位以上的号：${future.message}")

        val junk = assertThrows(AutojsException::class.java) {
            runBlocking { source.unregister(HandleRef(0, 1)) }
        }
        assertTrue(junk.message!!.contains("未知"), "0 从没发过：${junk.message}")
        assertEquals(1, ops.stopped.size, "垃圾号不许误关活着的订阅")
        Unit
    }

    @Test
    fun `unregisterAll 之后全部号仍答"已关闭"——不用另记 tomb`() = runBlocking {
        val source = AndroidSensorSource(FakeOps())
        val a = source.register("accelerometer")
        val b = source.register("light")
        source.unregisterAll()

        for (ref in listOf(a, b)) {
            val e = assertThrows(AutojsException::class.java) {
                runBlocking { source.drain(ref, sinceSeq = 0, max = 10) }
            }
            assertTrue(e.message!!.contains("已关闭"), "清空的号本来就在水位以下：${e.message}")
            // 且 unregister 仍是幂等成功（不抛）
            source.unregister(ref)
        }
        Unit
    }

    @Test
    fun `unregisterAll清全场逐个停`() = runBlocking {
        val ops = FakeOps()
        val source = AndroidSensorSource(ops)
        val a = source.register("accelerometer")
        val b = source.register("light")
        source.unregisterAll()
        assertEquals(2, ops.stopped.size)
        val e = assertThrows(AutojsException::class.java) {
            runBlocking { source.drain(a, sinceSeq = 0, max = 10) }
        }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, e.error)
        val e2 = assertThrows(AutojsException::class.java) {
            runBlocking { source.drain(b, sinceSeq = 0, max = 10) }
        }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, e2.error)
        Unit
    }
}
