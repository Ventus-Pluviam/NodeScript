plugins {
    id("autoscript.android-library")
    alias(libs.plugins.kotlin.compose)
}

// Compose UI 呈现层（2026-09-23 从 :app 拆出，§6 模块表 :ui 行 + 用户决策「UI 拆独立模块」）。
// 只画快照：状态经 :domain 的 HostSummary 读口现取（MainActivity 是装配级接线点），
// 业务/装配知识留在 :app —— :ui 禁反向依赖 :app（ModuleGraphTest 允许集量化）。
android {
    namespace = "com.autoscript.ui"
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":domain"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    // 系统级悬浮窗的离屏 ComposeView 三 owner（Lifecycle + ViewModelStore +
    // SavedStateRegistry）：窗外没有 Activity 的 ViewTree，宿主自己搭
    // （见 SystemLogWindowHost.OverlayOwners）。activity-compose 只带 lifecycle-runtime，
    // ViewModelStore 与 SavedStateRegistryOwner 要这两条显式依赖 —— 版本钉死
    // activity-compose 1.9.2 传递的那一份（lifecycle 2.8.6 / savedstate 1.2.1，
    // 本机 gradle 缓存里就是这两个版本），不引入第二套版本。
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.6")
    implementation("androidx.savedstate:savedstate:1.2.1")
    debugImplementation(libs.compose.ui.tooling)
}
