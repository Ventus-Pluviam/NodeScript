plugins {
    alias(libs.plugins.android.library)
}

// :nodeN 引擎进程宿主（main.cpp、Node config、JNI 注册、桥服务端）。禁 Android SDK UI。
// CI 构建；见 docs/framework-design.md §5/§7.8/§8。依赖 :bridge:native（libnode.so 装载）。
// main.cpp 已落 src/main/cpp/：本机 scripts/build-native.sh 做 NDK r28c 交叉编译验证
//（AArch64 + 符号对表 + 16KB 对齐）；AGP externalNativeBuild/JNI 注册/pid 心跳仍 CI 接入。
android {
    namespace = "com.autoscript.engine.nodeprocess"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
}

dependencies {
    implementation(project(":bridge:native"))
}