plugins {
    id("autoscript.jvm")
}

// 定时/Intent/事件任务、checkpoint 意图日志、runNonce 幂等（docs §9.6）。
// 纯 JVM（零 `import android.`，2026-09-30 起走 kotlin.jvm —— 原 android.library 是插件错配）。
dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
    // `InMemoryTaskStore` 随 SPI 搬进 `:domain` testFixtures（批 102 P4）：
    // 它与 `InMemoryIntentStore` 同一地位 —— 规格「今天确实被执行过」的那份零 IO 实现。
    testImplementation(testFixtures(project(":domain")))
}
