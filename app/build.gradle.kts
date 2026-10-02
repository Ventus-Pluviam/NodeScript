plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // skipped/aborted 守卫（convention 之外唯一单独应用它的模块：application 不走
    // autoscript.android-library 约定，android{} 里塞满装配特例）。
    id("autoscript.test-guard")
    // 引擎二进制 / facade dist 随包任务（§19/§12.4）+ preBuild wiring（审查步骤 1 迁入）。
    id("autoscript.engine-natives")
}

android {
    namespace = "com.autoscript"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.autoscript"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // 版本号是**占位**：本仓不发行正式版（§18 第 3 项「不发行」），没有发版流程 ——
        // 所以刻意**不引第二个版本来源**（gradle.properties / 版本目录），那只会变成一处
        // 与这里漂移的事实来源（本仓对"同一事实写两遍"的代价有惨痛先例，见 ModuleGraphTest
        // 的派生计数注释）。真要发版时：两个数一起改成有单一来源的形态，versionCode 必须
        // 单调递增，并同步 §13/§14 的交付轨。
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
            // bridge addon 随包（§19 交付轨）：srcDir 取**父目录**，资产键 =
            // `bridge-addon/<file>`（Application 侧 `assets.open("bridge-addon/…")` 认的
            // 就是这个键）—— 与 bridge-dist 同一条"指成子目录会拍平"的教训。
            assets.srcDir(layout.buildDirectory.dir("generated/engineAddonAssets"))
            // vendored npm CLI 素材随包（§10.2 调用链首段）：srcDir 取**父目录**，资产键 =
            // `npm/<rel>`（启动期 AssetTreeCliSource("npm", …) 认的就是这个键）——
            // 与 bridge-dist 同一条"指成子目录会拍平"的教训。
            assets.srcDir(layout.buildDirectory.dir("generated/npmCliAssets"))
            // 第三方许可声明随包（backlog D8）：srcDir 取**父目录**，资产键 =
            // `third-party/<file>`（`THIRD_PARTY_NOTICES.md` + `licenses/` 的七份逐字原文；
            // 随分发可达是许可义务，仓里那份只解决审计面）—— 同样「指成子目录会拍平」。
            assets.srcDir(layout.buildDirectory.dir("generated/noticesAssets"))
            // 引擎二进制随包（§19）：srcDir 根下按 ABI 分目录（`arm64-v8a/libnoden.so`），
            // prepareEngineNativeLibs 拷进 generated/engineNativeLibs/arm64-v8a/。
            jniLibs.srcDir(layout.buildDirectory.dir("generated/engineNativeLibs"))
        }
    }
    packaging {
        jniLibs {
            // exec 需要**真文件**：默认 extractNativeLibs=false（lib 留在 APK 里给
            // linker 直读）时 nativeLibraryDir 是空的，`hostBinary` 预检/ exec 都落空。
            // legacy 打包 = 安装期解压 lib/<abi>/* 到 nativeLibraryDir（§19 交付轨前提）。
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests {
            all {
                it.useJUnitPlatform()
                // P0 回环（P0LoopbackTest）拉真 npm 进程：CI 走 -PskipNpmE2E 排除
                // （与 :app-service:npm 的 HostNodeNpmE2ETest 同一条纪律；
                // 本机闭环不带该 flag 即跑）。
                if (project.hasProperty("skipNpmE2E")) {
                    it.exclude("**/P0LoopbackTest*")
                }
            }
        }
    }
}

dependencies {
    implementation(project(":app-service:runtime"))
    implementation(project(":app-service:scheduler"))
    implementation(project(":app-service:script-repo"))
    implementation(project(":app-service:permission-center"))
    implementation(project(":app-service:packager"))
    implementation(project(":app-service:npm"))
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
    // P0LoopbackTest 的宿主 npm 发现器取自 :app-service:npm 的测试夹具（backlog D9）：
    // 此前 `:app` 测试源集自己抄了一份、与那边**已分叉**，现在物理上只有一份。
    testImplementation(testFixtures(project(":app-service:npm")))
}
