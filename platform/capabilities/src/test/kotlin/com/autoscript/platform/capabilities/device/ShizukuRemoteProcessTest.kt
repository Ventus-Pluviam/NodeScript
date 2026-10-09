package com.autoscript.platform.capabilities.device

import android.os.ParcelFileDescriptor
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.platform.capabilities.reflect.OutsidePackageCaller
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

/**
 * 远端进程那一半的反射面（2026-10-09 真机 bug 的回归用例）。
 *
 * **这些用例为什么非有不可**：真机上 adb 档 100% 不可用，而病因是「从 `process.javaClass`
 * 取方法」—— 正确的写法（从公开接口取）与错法在源码上只差一个表达式，编译、detekt、
 * 其余单测**全都绿**。反射的可访问性语义只在运行时兑现，所以只有把
 * 那个**包内可见**、逐个 override 接口方法的 AIDL 仿真替身（`FakeRemoteProcess`）真喂进去，
 * 这个差别才在 JVM 上可观测。替身只以 `Object` 的形式经 [ShizukuProbe] 进出 ——
 * Kotlin 侧一旦把包内可见的 Java 类当类型用，编译器会顺着它的 supertype
 * （`moe.shizuku.server.IRemoteProcess`）去解析并报「Cannot access … supertype of …」。
 *
 * 覆盖的判据（逐条对应真机上的一种坏法）：
 * - 两条流**真的读得到**内容（旧写法 `as? InputStream` 静默得 null → 输出永远为空）；
 * - 流不是 `ParcelFileDescriptor` 时**炸**，不静默当"没有流"；
 * - `waitForTimeout` 走的是 `(long, String)`，第二参是枚举常量名（旧写法传 `TimeUnit`
 *   → `NoSuchMethodException`）；
 * - 超时要**尝试终止**进程（`destroy`）；
 * - 截断如实置 `truncated`，且**留到上限为止**（不静默丢光字节）。
 *
 * 替身 `IRemoteProcess` 需要 `android.os.*` 的**类型**，mockable `android.jar` 提供了
 * （`ParcelFileDescriptor` 的实例造不出可读的，故流的打开口走 [ProcessStreamOpener] 缝）。
 */
class ShizukuRemoteProcessTest {

    /**
     * 流的"形状标记"：`streamOf` 只做**类型检查**，内容由替身 [ProcessStreamOpener] 给。
     * mockable `android.jar` 里 `ParcelFileDescriptor.open()` 是 "not mocked"，
     * 故只能造一个空壳（拷贝构造器 + null 源）。
     */
    private val pfdMark: ParcelFileDescriptor = ParcelFileDescriptor(null as ParcelFileDescriptor?)

    private fun fake(
        hasStdout: Boolean = true,
        hasStderr: Boolean = true,
        code: Int = 0,
        finishes: Boolean = true,
    ): Any = ShizukuProbe.newProcess(
        if (hasStdout) pfdMark else null,
        if (hasStderr) pfdMark else null,
        code,
        finishes,
    )

    /**
     * 两条流的内容替身：按**取用顺序**发放（`drainBoth` 先 `getInputStream` 后
     * `getErrorStream`；`drain` 只要后者）。顺序与 `ShizukuInput` 里的调用序一致。
     */
    private class Streams(private vararg val contents: String?) : ProcessStreamOpener {
        private var i = 0
        override fun open(pfd: ParcelFileDescriptor): InputStream {
            val text = contents.getOrNull(i++) ?: ""
            return ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `两条流都读得到内容——旧写法 as InputStream 会静默得 null`() {
        val r = ShizukuProcessReader.drainBoth(
            fake(code = 3),
            timeoutMillis = 5_000L,
            streams = Streams("hello\n", "boom\n"),
        )
        assertEquals(3, r.code)
        assertEquals("hello\n", r.stdout, "stdout 必须真的读到（这正是控制台要的那条流）")
        assertEquals("boom\n", r.stderr, "stderr 必须真的读到（旧写法这里永远是 null）")
        assertFalse(r.truncated)
    }

    @Test
    fun `流形状不符就炸，不静默当成没有流`() {
        val e = assertThrows<AutojsException> {
            ShizukuProcessReader.streamOf("我不是 ParcelFileDescriptor", "getInputStream", Streams(null))
        }
        assertTrue("形状不符" in (e.message ?: ""), "要说清是哪条流：${e.message}")
        assertTrue("java.lang.String" in (e.message ?: ""), "要点名实际类型：${e.message}")
    }

    @Test
    fun `流为 null 时如实当没有产出，不当成失败`() {
        assertNull(ShizukuProcessReader.streamOf(null, "getErrorStream", Streams(null)))
    }

    @Test
    fun `waitForTimeout 走 AIDL 签名：第二参是枚举常量名`() {
        val p = fake()
        ShizukuProcessReader.drainBoth(p, 1_234L, Streams("x\n", null))
        assertEquals(
            "MILLISECONDS",
            ShizukuProbe.lastUnit(p),
            "AIDL 上是 waitForTimeout(long, String) 且服务端做 TimeUnit.valueOf —— 传 TimeUnit 对象会 NoSuchMethodException",
        )
    }

    @Test
    fun `超时尝试终止进程并如实抛`() {
        val p = fake(finishes = false)
        val e = assertThrows<AutojsException> {
            ShizukuProcessReader.drainBoth(p, 10L, Streams("x\n", null))
        }
        assertTrue("超时" in (e.message ?: ""), "话术要说是超时：${e.message}")
        assertTrue(ShizukuProbe.destroyed(p), "超时必须尝试终止远端进程（否则设备上留孤儿进程）")
    }

    @Test
    fun `超时的错误码是 ERR_TIMEOUT，不是 ERR_PERMISSION_DENIED`() {
        // 2026-10-10 外审第 1 条：超时折成权限码，脚本侧按 `e.error` 分类时会读成
        // 「能力未授权或被降级」，进而做出「换通道 / 去能力中心」的错误处置 ——
        // 而超时是「命令发出去了、到点还没回来」，与授权无关。
        val e = assertThrows<AutojsException> {
            ShizukuProcessReader.drainBoth(fake(finishes = false), 10L, Streams("x\n", null))
        }
        assertEquals(ErrorCode.ERR_TIMEOUT, e.error, "超时必须报 ERR_TIMEOUT（与 ConsoleShellRunner.timedOut 同码）")
    }

    @Test
    fun `截断如实置 truncated——不静默丢字节`() {
        val big = "x".repeat(5 * 1024)
        val r = ShizukuProcessReader.drainBoth(fake(), 5_000L, Streams("$big\n", null))
        assertTrue(r.truncated, "超过上限必须置位，控制台据此打「已截断」")
        val kept = r.stdout
        assertTrue(kept != null && kept.isNotEmpty(), "截断不等于没输出：超长行也要留到上限为止")
        assertTrue((kept ?: "").length <= 4 * 1024, "留存内容不得超过上限")
    }

    @Test
    fun `drain 只留 stderr 尾部——注入路径不要 stdout`() {
        val (code, tail) = ShizukuProcessReader.drain(fake(code = 7), 5_000L, Streams("why\n"))
        assertEquals(7, code)
        assertEquals("why\n", tail)
    }

    /**
     * 形状判据的**反面**：真机上的 proxy 是包内可见且逐个 override 接口方法的，
     * 从它自己的类上取方法会失败 —— 这条用例把那个语义钉在测试里，免得有人日后
     * "优化"回 `process.javaClass.getMethod(...)`。
     */
    @Test
    fun `从运行时类取方法是错的——这正是真机上 adb 档全挂的机理`() {
        val p = fake()
        val runtime = p.javaClass

        assertFalse(
            Modifier.isPublic(runtime.modifiers),
            "替身必须是包内可见的（否则复现不出真机上那个坑）",
        )

        // 旧写法 1：按包装类（ShizukuRemoteProcess）的形状找签名 → 找不到。
        assertThrows<NoSuchMethodException> {
            runtime.getMethod("waitForTimeout", Long::class.javaPrimitiveType, TimeUnit::class.java)
        }

        // 旧写法 2：签名找对了，但声明类不可见 → invoke 抛 IllegalAccessException。
        // **必须经另一个包发起**：Java 的包内可见性看调用方所在的包，而真机上
        // ShizukuInput 与那个 proxy 不同包 —— 同包调用会把访问检查放过去，
        // 这条判据就假绿了（`OutsidePackageCaller` 存在的唯一理由）。
        val fromRuntime = runtime.getMethod("exitValue")
        assertFalse(Modifier.isPublic(fromRuntime.declaringClass.modifiers))
        assertThrows<IllegalAccessException> { OutsidePackageCaller.invoke(fromRuntime, p) }

        // 正确写法：从公开接口取同一个方法签名 → 正常分派。
        val fromIface = Class.forName("moe.shizuku.server.IRemoteProcess").getMethod("exitValue")
        assertTrue(Modifier.isPublic(fromIface.declaringClass.modifiers))
        assertEquals(0, fromIface.invoke(p))
    }
}
