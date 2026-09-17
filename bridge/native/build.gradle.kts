plugins {
    alias(libs.plugins.android.library)
}

// C++ N-API addon 控制面 + libnode.so 装载（:nodeN 进程宿主引用）。
// CI 构建；见 docs/framework-design.md §7.3。externalNativeBuild 由 :engine:node-process 集成时统一接入。
android {
    namespace = "com.autoscript.bridge.native"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    // externalNativeBuild + NDK r27d 由 node-runtime-build 管线产出后接入（CI）
}