plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 桥基础层（JVM 侧）：Router / RequestRegistry(TTL) / HandleRegistry(generation) / EventBus / transports。
// 契约见 docs/framework-design.md §7。只依赖 :domain；禁 UI。
kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

dependencies {
    api(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.archunit.junit5)
}

tasks.test {
    useJUnitPlatform()
}