plugins {
    alias(libs.plugins.android.library)
}

// QuickJS 沙箱宿主进程（P1）：白名单 auto.* 子集 + interrupt handler + CPU 配额。独立进程，最不可信最隔离。
// 见 docs/framework-design.md §4.2/§5。P0 形状可空，P1 再实装。
android {
    namespace = "com.autoscript.engine.sandbox"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
}