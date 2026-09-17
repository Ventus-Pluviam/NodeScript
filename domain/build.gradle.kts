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
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.archunit.junit5)
}

tasks.test {
    useJUnitPlatform()
}