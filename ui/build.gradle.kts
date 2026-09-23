plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Compose UI 呈现层（2026-09-23 从 :app 拆出，§6 模块表 :ui 行 + 用户决策「UI 拆独立模块」）。
// 只画快照：状态经 :domain 的 HostSummary 读口现取（MainActivity 是装配级接线点），
// 业务/装配知识留在 :app —— :ui 禁反向依赖 :app（ModuleGraphTest 允许集量化）。
android {
    namespace = "com.autoscript.ui"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            all { it.useJUnitPlatform() }
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
