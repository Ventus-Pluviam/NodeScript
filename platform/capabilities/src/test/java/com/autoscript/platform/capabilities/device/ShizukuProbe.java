package com.autoscript.platform.capabilities.device;

import android.os.ParcelFileDescriptor;

/**
 * [FakeRemoteProcess] 的**公开**取用口（2026-10-09）。
 *
 * 为什么需要它：替身必须是**包内可见**的（那正是真机上 AIDL proxy 的形态，也是被测的
 * 那个坑的成因），但 Kotlin 测试类一旦把包内可见的 Java 类当**类型**用，编译器会顺着
 * 它的 supertype（`moe.shizuku.server.IRemoteProcess`）去解析，报
 * 「Cannot access 'IRemoteProcess' which is a supertype of …」。于是这里把替身
 * **只以 `Object` 的形式**交出去，观测口也走静态方法 —— Kotlin 侧从头到尾不出现
 * 那个类型名，而替身照旧包内可见。
 *
 * 本类住 `src/test/java`，与替身、用例同一个包，只在单测类路径上。
 */
public final class ShizukuProbe {

    private ShizukuProbe() {
    }

    /** 造一个替身进程（返回类型刻意是 `Object`，见类注释）。 */
    public static Object newProcess(
            ParcelFileDescriptor stdout,
            ParcelFileDescriptor stderr,
            int exitCode,
            boolean finishes) {
        return new FakeRemoteProcess(stdout, stderr, exitCode, finishes);
    }

    /** 最后一次 `waitForTimeout` 收到的单位名（null = 没被调到）。 */
    public static String lastUnit(Object process) {
        return ((FakeRemoteProcess) process).lastUnit;
    }

    /** `destroy` 是否被调到（超时路径的收尸判据）。 */
    public static boolean destroyed(Object process) {
        return ((FakeRemoteProcess) process).destroyed;
    }
}
