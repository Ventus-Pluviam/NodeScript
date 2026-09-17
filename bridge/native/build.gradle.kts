plugins {
    alias(libs.plugins.android.library)
}

// C++ N-API addon 控制面 + libnode.so 装载（:nodeN 进程宿主引用）。
// CI 构建；见 docs/framework-design.md §7.3。externalNativeBuild 由 :engine:node-process 集成时统一接入。
android {
    // namespace 不能含 Java 关键字 `native`（AGP 校验拒绝 com.autoscript.bridge.native）。
    // 模块路径 :bridge:native 由 §6 模块表冻结，此处只取合法包名变体；本模块零 JVM 源码（纯 C++），
    // namespace 仅用于 AGP 生成 R/manifest 包，无跨模块引用，改名零影响。
    namespace = "com.autoscript.bridge.nativelib"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    // externalNativeBuild + NDK r27d 由 node-runtime-build 管线产出后接入（CI）
}