import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// facade dist 随包的生成位（§12.4）：声明须在 android.sourceSets 引用之前（kts 顺序求值）。
val bridgeDistAssetsDir = layout.buildDirectory.dir("generated/bridgeDistAssets/bridge-dist")

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
    sourceSets {
        getByName("main") {
            // 生成位在 build/ 下（不提交二进制副本）；prepareBridgeDistAssets 在资产合并前跑。
            // srcDir 必须是 **父目录**：拷贝目标是 .../bridgeDistAssets/bridge-dist/*，
            // 资产键 = 相对 srcDir 的路径 = `bridge-dist/<file>`（Application 侧
            // `assets.list("bridge-dist")` 认的就是这个键）。指成子目录会把文件拍平到
            // assets 根，list("bridge-dist") 恒空 —— 一条静默的"没货"故障。
            assets.srcDir(layout.buildDirectory.dir("generated/bridgeDistAssets"))
        }
    }
    testOptions {
        unitTests {
            all { it.useJUnitPlatform() }
        }
    }
}

// ── facade dist 随包（§12.4 资产交付轨）──────────────────────────────────────
// `bridge/js/dist` 是 git 跟踪的 tsc 产物（CI 无 npm build 也在 —— 与 E2E 对 dist 的
// 同一条判据：缺 = 仓库破损）。构建期拷进 assets/bridge-dist/，装配期由
// BridgeDistDeploy 落位到 filesDir/node_modules/auto（require('auto') 的解析点）。
// 不走 npm build 依赖：随包的是**已提交**的 dist，tsc 只在改 src 时由开发者重跑。
val prepareBridgeDistAssets = tasks.register("prepareBridgeDistAssets") {
    val srcDist = rootProject.layout.projectDirectory.dir("bridge/js/dist")
    inputs.dir(srcDist)
    outputs.dir(bridgeDistAssetsDir)
    doLast {
        val out = bridgeDistAssetsDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        var copied = 0
        srcDist.asFile.listFiles()?.forEach { f ->
            if (f.isFile) {
                f.copyTo(File(out, f.name), overwrite = true)
                copied++
            }
        }
        // 空 dist 不落盘（与 BridgeDistDeploy 的空字节防线同精神：0 个文件的 assets
        // 目录 = "没货"，部署侧如实空报告 —— 但这里更该红：dist 缺件是仓库破损）。
        require(copied > 0) { "bridge/js/dist 无文件可随包（仓库破损？srcDist=$srcDist）" }
        require(File(out, "bootstrap.js").isFile) {
            "dist 缺 bootstrap.js（attachNative 打包入口的落点，§12.4）"
        }
        require(File(out, "index.js").isFile) {
            "dist 缺 index.js（require('auto') 的缺省入口，§12.1）"
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
    // 呈现层（2026-09-23 拆出）：只为 APK 组装 + launcher manifest 合并 ——
    // :app **源码零 import** com.autoscript.ui（装配知识不流向呈现层；
    // Application 实现的是 :domain 的 HostSummary）。compose 依赖随 UI 同批迁去 :ui。
    implementation(project(":ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.archunit.junit5)
}
// 资产合并前必须先生成（AGP 的 preBuild 每变体都有；matching 覆盖配置期尚未注册的情形）。
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareBridgeDistAssets) }
