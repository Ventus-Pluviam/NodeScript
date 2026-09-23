plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.autoscript"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.autoscript"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
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
    implementation(project(":app-service:runtime"))
    implementation(project(":app-service:scheduler"))
    implementation(project(":app-service:script-repo"))
    implementation(project(":app-service:permission-center"))
    implementation(project(":app-service:packager"))
    implementation(project(":domain"))
    // 仅 Composition Root（com.autoscript.shell.AppShell）可碰 :bridge:java：
    // 把各 handler 薄转接挂到 BridgeRouter。不做业务逻辑，见 AppShell 注释 + ArchitectureTest。
    implementation(project(":bridge:java"))
    // 仅 Composition Root（com.autoscript.shell 装配包）可碰 :platform:*（§6 包级例外二，
    // 与 :bridge:java 同形）：SystemSpis + CapabilityNamespaces 生产装配（PlatformWiring）。
    // 见 ArchitectureTest「平台实现只许装配包碰」+ ModuleGraphTest 允许集 + §6「例外不是开后门」。
    implementation(project(":platform:capabilities"))
    implementation(project(":platform:system"))
    implementation(project(":engine:node-process"))   // §19 Kotlin spawn：根包 Application 构造 engineFactory（shell 装配包仍禁碰 —— ArchitectureTest）

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.archunit.junit5)
}