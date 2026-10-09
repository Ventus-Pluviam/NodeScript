package com.autoscript.platform.capabilities.device;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import moe.shizuku.server.IRemoteProcess;

/**
 * 「AIDL 生成的实现类在运行时是**包内可见**的」这件事在 JVM 上的载体（2026-10-09）。
 *
 * 真机上的形态：`IShizukuService.newProcess(...)` 的返回值是
 * `moe.shizuku.server.IRemoteProcess$Stub$Proxy` —— 一个**包内可见**的类，且它
 * **自己 override 了接口的每一个方法**。两件事叠在一起才有那个坑：
 *
 * 1. `process.javaClass.getMethod("waitForTimeout", long, TimeUnit)` 找的是
 *    **proxy 自己的方法表**，而 AIDL 上的签名是 `(long, String)` → `NoSuchMethodException`；
 * 2. `process.javaClass.getMethod("exitValue").invoke(process)` 取到的是 proxy
 *    **自己的**那个 override，声明类不可见 → `IllegalAccessException`。
 *    （若 proxy 不 override、`getMethod` 顺着接口找到 `IRemoteProcess.exitValue`，
 *    声明类反而是公开的 —— 坑就没了。所以本类必须**逐个 override**，形状才对。）
 *
 * 用 Java 写而不是 Kotlin：Kotlin 的 `internal class` 编译出来是 **public** 的
 * （可见性只是编译器约束），复现不出"运行时类包内可见"。本类住 `src/test/java`，
 * 与它的用例**同一个包**（Kotlin 与 Java 同包时能访问 Java 的包内可见类），
 * 只在单测类路径上，不进 APK。
 *
 * **为什么值得为它写一个真实现接口的替身**：`ShizukuInput` 那条路径在真机上出过一次
 * 100% 不可用的 bug（见 `ShizukuInput.RemoteProcessApi` 的 KDoc），而修法与错法在源码上
 * 只差一个 `process.javaClass` / `Class.forName("…IRemoteProcess")`。没有本类，
 * 这个差别在 JVM 上**测不出来** —— 反射的可访问性语义是运行时的事。
 */
final class FakeRemoteProcess implements IRemoteProcess {

    private final ParcelFileDescriptor stdout;
    private final ParcelFileDescriptor stderr;
    private final int exitCode;
    private final boolean finishes;

    /** [destroy] 是否被调到（超时路径的收尸判据）。 */
    volatile boolean destroyed;
    /** 最后一次 [waitForTimeout] 收到的单位名（AIDL 契约：枚举常量名，不是 `TimeUnit`）。 */
    volatile String lastUnit;

    FakeRemoteProcess(ParcelFileDescriptor stdout, ParcelFileDescriptor stderr, int exitCode, boolean finishes) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.exitCode = exitCode;
        this.finishes = finishes;
    }

    @Override
    public ParcelFileDescriptor getOutputStream() {
        return null;
    }

    @Override
    public ParcelFileDescriptor getInputStream() {
        return stdout;
    }

    @Override
    public ParcelFileDescriptor getErrorStream() {
        return stderr;
    }

    @Override
    public int waitFor() {
        return exitCode;
    }

    @Override
    public int exitValue() {
        return exitCode;
    }

    @Override
    public void destroy() {
        destroyed = true;
    }

    @Override
    public boolean alive() {
        return !finishes;
    }

    @Override
    public boolean waitForTimeout(long timeout, String unit) {
        lastUnit = unit;
        return finishes;
    }

    @Override
    public IBinder asBinder() {
        return null;
    }
}
