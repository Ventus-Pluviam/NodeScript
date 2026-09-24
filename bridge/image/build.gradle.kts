plugins {
    alias(libs.plugins.android.library)
}

// C++ 图像分析管线 addon：独立 so libopencv.so（OpenCV 4.x，不依赖 node）。
// CI 构建；见 docs/framework-design.md §9.2。OpenCV 静态链接方案并入管线后接入。
android {
    namespace = "com.autoscript.bridge.image"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
}