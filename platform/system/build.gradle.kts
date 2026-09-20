plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// 系统侧实现：overlay、通知、datastore(SQLite)、shell、设备信息、zip、系统设置。
// 实现 :domain SPI，不反向；禁服务逻辑。见 docs/framework-design.md §9.6。
android {
    namespace = "com.autoscript.platform.system"
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