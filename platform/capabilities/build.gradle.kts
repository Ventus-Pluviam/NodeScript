plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// 系统能力实现：a11y 服务 / UiNodeTreeReader、MediaProjection、截图 FrameSource、输入通道（无障碍/root/adb/Shizuku）。
// 实现 :domain SPI，不反向；禁服务逻辑。见 docs/framework-design.md §9.1–9.3。
android {
    namespace = "com.autoscript.platform.capabilities"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
}