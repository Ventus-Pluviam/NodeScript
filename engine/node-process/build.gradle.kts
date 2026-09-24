plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// :nodeN 进程宿主：Kotlin `NodeProcessEngine`（ScriptEngine 实现，spawn 缝 + pid/状态语义）
// + C++ main.cpp（§7.8 启动序）。实现 domain SPI 不反向；禁 Android SDK UI、禁 :bridge:java Kotlin 面
// （对 :bridge:native 的依赖是运行期 .so 装载，不是源码边）。见 docs/framework-design.md §5/§7.8/§8。
// main.cpp 已落 src/main/cpp/：本机 scripts/build-native.sh 做 NDK r28c 交叉编译验证
//（AArch64 + 符号对表 + 16KB 对齐）；jniLibs 交付已接（:app prepareEngineNativeLibs —
// libnoden/libnode/libc++_shared 三件齐才落包，半套红；addon 走 assets，§19 交付轨）。
android {
    namespace = "com.autoscript.engine.nodeprocess"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":bridge:native"))
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.archunit.junit5)
}

android {
    testOptions {
        unitTests {
            all { it.useJUnitPlatform() }
        }
    }
}
