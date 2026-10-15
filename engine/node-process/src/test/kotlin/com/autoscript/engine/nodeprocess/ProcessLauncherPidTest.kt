package com.autoscript.engine.nodeprocess

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `pidOf` 第二级阶梯（私有字段 `pid` 的反射查找）的**方向**门（2026-10-15）。
 *
 * 病（设备实测坐实的那个）：原实现从 `Process::class.java` 起步、每轮 `cls.superclass`
 * 往上走，而 `getDeclaredField` **只看该类自己声明的成员** —— 这条循环走的是
 * `Process → Object`，**永远够不着子类**。而 pid 字段恰恰住在**子类**上：
 * 设备 Android 13 的 libcore 是 `java.lang.UNIXProcess.pid`（`core-oj.jar` 反编译实证，
 * `java.lang.Process` 一个字段都没有），桌面 JDK 是 `java.lang.ProcessImpl.pid`。
 * 于是这条阶梯**从来没生效过**：`SpawnedProcess.pid` 恒 null →
 * 悬浮窗「暂停」恒回 false（`pauseExecution` 第一句 `val target = pid ?: return false`
 * → UI 打「该执行不支持暂停」）、看门狗走 noPid 清单从不判死、`RunIdentityRegistry`
 * 的防劫持检查整段静默跳过。**一条反射方向错，三个上层功能同时静默降级。**
 *
 * 本测的形状**照着设备抄**（`PidBearingProcess` 把 `pid` 声明在自己身上 = UNIXProcess 的形状，
 * 中间再垫一层 `MidProcess` 复刻真实的多级继承），而不是照着实现抄 —— 照实现抄的测试
 * 会在实现写错时一起写错（这条 bug 能活到今天正是因为这个：**全仓没有一条测 pid 取值的用例**，
 * 只有 `NodeProcessEngineRealSpawnTest` 的 `receipt.pid > 0`，而它在桌面 JDK 上走 tier 1
 * 就过了，tier 2 从没被执行过）。
 *
 * **为什么 tier 1 在这里必然落到 tier 2**：桌面 JDK 的 `Process.pid()` 是个**默认方法**
 * （`Modifier.isAbstract == false`），默认实现走 `toHandle()` —— 自定义子类没实现它，
 * 于是抛 `UnsupportedOperationException`，被 `pidOf` 的 `runCatching` 吞成 null。
 * 这正好让本测**只**打第二级阶梯（设备上 tier 1 因方法不存在而缺席，落到同一级）。
 */
class ProcessLauncherPidTest {

    /** 复刻设备 libcore 的继承形状：`UNIXProcess extends Process`，`pid` 声明在子类上。 */
    private open class MidProcess : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun isAlive(): Boolean = false
    }

    /** `pid` 与运行时类**隔着两层**：只有从运行时类起往上走才摸得到（照实现抄就永远测不到）。 */
    private class PidBearingProcess(private val pid: Int) : MidProcess()

    @Test
    fun `pid 字段住运行时类上——从运行时类起步的阶梯必须取到（设备 libcore 形状）`() {
        assertEquals(
            4242, pidOf(PidBearingProcess(4242)),
            "pid 住子类上：从 Process::class.java 起步的旧写法在这里回 null（那正是设备上发生的事）",
        )
    }

    @Test
    fun `两级阶梯都探不到时回 null——诚实 noPid，绝不回 0 或自身 pid`() {
        // 没有 pid 字段、也没有可用的 pid()（默认实现抛 UnsupportedOperationException）：
        // 如实回 null 而不是编一个值 —— 编出来的 pid 会让看门狗去采别人的进程、
        // 让 kill -STOP 打到无关进程上，比"量不到"危险得多（§8.4 的诚实口径）。
        assertNull(pidOf(MidProcess()), "探不到就是 null（0/自身 pid 都是撒谎）")
    }
}
