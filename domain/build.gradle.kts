plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 领域层：纯 Kotlin，零 Android / 零桥依赖。全部 SPI 契约见 docs/framework-design.md §4.1/§7/§9.5。
kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

dependencies {
    // kotlinx.coroutines（Flow/suspend）是契约层签名的一部分；core 仅含 Flow/协程原语，零 Android。
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.archunit.junit5)
}

tasks.test {
    useJUnitPlatform()
}