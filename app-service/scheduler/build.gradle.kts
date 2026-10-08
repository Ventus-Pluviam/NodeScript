plugins {
    id("autoscript.jvm")
}

// 定时/Intent/事件任务、checkpoint 意图日志、runNonce 幂等（docs §9.6）。
// 纯 JVM（零 `import android.`，2026-09-30 起走 kotlin.jvm —— 原 android.library 是插件错配）。
dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
}
