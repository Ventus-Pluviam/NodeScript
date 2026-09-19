plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.autoscript.appservice.packager"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests {
            all {
                it.useJUnitPlatform()
                // 真实 npm e2e（HostNodeNpmE2ETest）默认跳过：CI 走 -PskipNpmE2E，
                // 本机闭环由 tools/jvm-test.sh 直跑（宿主机 node+npm 存在才启用）。
                if (project.hasProperty("skipNpmE2E")) {
                    it.exclude("**/HostNodeNpmE2ETest*")
                }
            }
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.archunit.junit5)
}