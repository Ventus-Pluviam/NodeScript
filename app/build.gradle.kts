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
            // bridge addon 随包（§19 交付轨）：srcDir 取**父目录**，资产键 =
            // `bridge-addon/<file>`（Application 侧 `assets.open("bridge-addon/…")` 认的
            // 就是这个键）—— 与 bridge-dist 同一条"指成子目录会拍平"的教训。
            assets.srcDir(layout.buildDirectory.dir("generated/engineAddonAssets"))
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
                // （与 :app-service:packager 的 HostNodeNpmE2ETest 同一条纪律；
                // 本机闭环不带该 flag 即跑）。
                if (project.hasProperty("skipNpmE2E")) {
                    it.exclude("**/P0LoopbackTest*")
                }
            }
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

// ── 引擎二进制随包（§19 jniLibs 交付轨）────────────────────────────────────────
// 三个来源都不在 git（NDK/node-runtime-build 产物），所以**不能**像 bridge/js/dist
// 那样缺了就红 —— 分层诚实：
//   · 三件齐（noden + libnode + libc++_shared）→ 拷进 generated/engineNativeLibs/arm64-v8a/
//     （noden 改名 libnoden.so —— PackageManager 只提取 *.so，exec 要真文件）；
//   · 半套（有 noden 没 libnode，或反过来）→ **红**：半个交付比没交付更糟
//     （APK 看起来有引擎、设备上必 exit 2；预检虽点名，但那是运行期才炸的形态）；
//   · 全无 → 警告 + 空产出：装配照过（开发机没跑 build-native.sh 不挡 assemble），
//     设备侧 execute 预检点名绝对路径（已有单测）。
// addon 独立：.node 不进 jniLibs（PM 不提取非 .so），走 assets/bridge-addon/。
val engineNativeLibsDir = layout.buildDirectory.dir("generated/engineNativeLibs")
val engineAddonAssetsDir = layout.buildDirectory.dir("generated/engineAddonAssets/bridge-addon")

val prepareEngineNativeLibs = tasks.register("prepareEngineNativeLibs") {
    val nodenSrc = rootProject.layout.projectDirectory
        .file("engine/node-process/build/native-local/noden")
    val addonSrc = rootProject.layout.projectDirectory
        .file("engine/node-process/build/native-local/bridge_native.node")
    // libnode 候选序（先命中先用）：显式 env → node-runtime-build 出口 → 本机验证位。
    // 与 build-native.sh 的 LIBNODE 默认同源（本机验证配方）。
    val libnodeCandidates = listOfNotNull(
        System.getenv("LIBNODE")?.let { File(it) },
        rootProject.layout.projectDirectory.file("node-runtime-build/out/libnode.so").asFile,
        File("/tmp/nrb-out7/libnode.so"),
    )
    // libc++_shared：libnode 的 NEEDED（readelf 实证），NDK sysroot 同款 ABI。
    val ndkHome = System.getenv("ANDROID_NDK_HOME") ?: "/root/ndk/android-ndk-r28c"
    val cxxShared = File(
        ndkHome,
        "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so",
    )
    // libopencv.so 候选位（§9.2 图像管线）：显式 env → node-runtime-build 出口
    // （build-opencv.sh 的 OUT）→ image-native.yml 的 artifact 落点位。
    // 与引擎三件套**同一条选填纪律**：缺位不红（装配侧 JniOps.loadOrNull() 拿不到
    // so 即不喂分析器，桥对 images.* 回 ERR_NOT_IMPLEMENTED），在位才随包。
    // 装载名与文件名必须同为 opencv：JniOps.loadLibrary("opencv") 找的是 libopencv.so。
    val libopencvCandidates = listOfNotNull(
        System.getenv("LIBOPENCV")?.let { File(it) },
        rootProject.layout.projectDirectory
            .file("node-runtime-build/out-opencv/libopencv.so").asFile,
        File("/tmp/img-opencv-out/libopencv.so"),
    )
    outputs.dir(engineNativeLibsDir)
    outputs.dir(engineAddonAssetsDir)
    // 来源不在 git：每次装配现查现拷（outputs.upToDateWhen false —— 否则 build-native.sh
    // 刚重编的 noden 会被"outputs 已存在"跳过，装进 APK 的还是旧件）。
    outputs.upToDateWhen { false }
    doLast {
        val abiDir = engineNativeLibsDir.get().asFile.resolve("arm64-v8a")
        abiDir.deleteRecursively()
        abiDir.mkdirs()
        val addonOut = engineAddonAssetsDir.get().asFile
        addonOut.deleteRecursively()
        addonOut.mkdirs()

        val noden = nodenSrc.asFile.takeIf { it.isFile }
        val libnode = libnodeCandidates.firstOrNull { it.isFile }
        when {
            noden != null && libnode != null -> {
                if (!cxxShared.isFile) {
                    throw GradleException(
                        "libnode 在但 libc++_shared 缺：$cxxShared（libnode 的 NEEDED，" +
                            "缺它设备上 dlopen 必败 —— 检查 ANDROID_NDK_HOME=$ndkHome）",
                    )
                }
                noden.copyTo(File(abiDir, "libnoden.so"), overwrite = true)
                libnode.copyTo(File(abiDir, "libnode.so"), overwrite = true)
                cxxShared.copyTo(File(abiDir, "libc++_shared.so"), overwrite = true)
                logger.lifecycle(
                    "[engine-natives] 三件齐 → lib/arm64-v8a/{libnoden.so,libnode.so,libc++_shared.so}",
                )
            }
            noden != null || libnode != null -> throw GradleException(
                "半个引擎交付比没交付更糟：noden=${noden?.absolutePath ?: "缺"} " +
                    "libnode=${libnode?.absolutePath ?: "缺"} " +
                    "（两者都来自本机构建：build-native.sh 出 noden，node-runtime-build/ " +
                    "LIBNODE 出 libnode —— 补齐其一再 assemble）",
            )
            else -> logger.warn(
                "[engine-natives] 未交付（noden/libnode 都缺）：APK 无引擎二进制。" +
                    "跑 engine/node-process/scripts/build-native.sh + node-runtime-build 出 " +
                    "libnode（或 export LIBNODE=…）后再 assemble；设备侧 execute 预检会点名。",
            )
        }

        // libopencv.so（§9.2 图像面）：选填件，与引擎三件套同目录同纪律（有就随包，
        // 无则不红 —— 装配侧据此不喂 images 分析器，脚本拿到的是诚实的 NOT_IMPLEMENTED）。
        val libimg = libopencvCandidates.firstOrNull { it.isFile }
        if (libimg != null) {
            if (libimg.length() == 0L) {
                throw GradleException(
                    "libopencv.so 源是 0 字节：${libimg.absolutePath}" +
                        "（空 so 落包 = 运行期 UnsatisfiedLinkError，比缺件更难查）",
                )
            }
            libimg.copyTo(File(abiDir, "libopencv.so"), overwrite = true)
            logger.lifecycle(
                "[engine-natives] libopencv.so → lib/arm64-v8a/（§9.2 图像面；" +
                    "source=${libimg.absolutePath}）",
            )
        } else {
            logger.warn(
                "[engine-natives] libopencv.so 未交付（候选位 = node-runtime-build/" +
                    "out-opencv/ 或 export LIBOPENCV=…）：images.* 运行时如实 " +
                    "ERR_NOT_IMPLEMENTED —— 跑 node-runtime-build/scripts/build-opencv.sh " +
                    "或下载 image-native.yml 的 artifact。",
            )
        }

        // addon（独立选填件）：有就随包成 assets/bridge-addon/bridge_native.node。
        val addon = addonSrc.asFile
        if (addon.isFile) {
            if (addon.length() == 0L) {
                throw GradleException("addon 源是 0 字节：${addon.absolutePath}（空 .node 落包=运行期 SyntaxError）")
            }
            addon.copyTo(File(addonOut, "bridge_native.node"), overwrite = true)
            logger.lifecycle("[engine-natives] addon → assets/bridge-addon/bridge_native.node")
        } else {
            logger.warn(
                "[engine-natives] addon 未交付（${addon.absolutePath}）：装配期不注入 " +
                    "AUTOSCRIPT_BRIDGE_ADDON，脚本照跑、桥调用点 ERR_ENGINE_STOPPED（选填纪律）。",
            )
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
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(prepareBridgeDistAssets)
    dependsOn(prepareEngineNativeLibs)
}
