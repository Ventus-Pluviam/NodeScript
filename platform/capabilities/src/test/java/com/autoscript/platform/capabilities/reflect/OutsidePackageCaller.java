package com.autoscript.platform.capabilities.reflect;

import java.lang.reflect.Method;

/**
 * 从**另一个包**发起反射调用（2026-10-09）。
 *
 * 为什么需要它：Java 的包内可见性检查看的是**调用方所在的包**，而真机上那个坑正是跨包的
 * —— `ShizukuInput`（`com.autoscript.platform.capabilities.device`）拿到的是
 * `moe.shizuku.server.IRemoteProcess$Stub$Proxy`，两者不同包，于是从 proxy 自己的类上
 * 取到的 `Method.invoke` 抛 `IllegalAccessException`。
 *
 * 单测里那个替身与它的用例**必然同包**（不然连包内可见的类都引用不上），于是"同包调用"
 * 会把访问检查放过去、复现不出真机语义。把 invoke 这一步挪到本类（另一个包），
 * 跨包这一条才成立。
 *
 * 本类不碰任何 `android.*`（只收 `Method` 与 `Object`），故不违反「`android` 只许
 * `..capabilities.device` 碰」那条架构门。住 `src/test/java`，只在单测类路径上。
 */
public final class OutsidePackageCaller {

    private OutsidePackageCaller() {
    }

    /** 等价于 `method.invoke(target)`，但调用方是本包（不是替身所在的那个包）。 */
    public static Object invoke(Method method, Object target) throws Exception {
        return method.invoke(target);
    }
}
