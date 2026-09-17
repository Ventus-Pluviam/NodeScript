plugins {
    alias(libs.plugins.android.library)
}

// :nodeN 引擎进程宿主（main.cpp、Node config、JNI 注册、桥服务端）。禁 Android SDK UI。
// CI 构建；见 docs/framework-design.md §5/§8。依赖 :bridge:native（libnode.so 装载）。
android {
    namespace = "com.autoscript.engine.nodeprocess"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
}

dependencies {
    implementation(project(":bridge:native"))
}