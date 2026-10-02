// `:app` 随包产物装配（审查步骤 1：原 `app/build.gradle.kts` 内联两任务迁入约定，
// 机器路径全清 —— /root、/tmp 类缺省一律不入构建脚本）：
//   · prepareBridgeDistAssets（§12.4 facade dist → assets/bridge-dist/）
//   · prepareEngineNativeLibs（§19 引擎三件 + libopencv 选填 + addon 随包）
//   · prepareNpmCliAssets（§10.2 vendored npm CLI → assets/npm/，选填）
//   · prepareNoticesAssets（第三方许可声明 → assets/，见下段）
// 「三件齐/半套红/全无警」与选填件「缺位只 warn」语义逐字保留；ANDROID_NDK_HOME
// 无缺省且只在三件齐分支必填（没 NDK 的机器走「全无 → 警告」照常 assemble）。
import java.io.File

// facade dist 随包的生成位（§12.4）：声明须在 android.sourceSets 引用之前（kts 顺序求值）。
val bridgeDistAssetsDir = layout.buildDirectory.dir("generated/bridgeDistAssets/bridge-dist")

// ── facade dist 随包（§12.4 资产交付轨）──────────────────────────────────────
// `bridge/js/dist` 是 **tsc 构建产物**（2026-09-30 审查步骤 7 起出库、不再 git 跟踪）：
// CI jvm-tests job 前置 `npm --prefix bridge/js ci && run build`，本机先
// `npm --prefix bridge/js run build` —— 缺件 = 没跑 npm build，不是仓库破损。
// 构建期拷进 assets/bridge-dist/，装配期由 BridgeDistDeploy 落位到
// filesDir/node_modules/auto（require('auto') 的解析点）。
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
        // 目录 = "没货"，部署侧如实空报告 —— 但这里更该红：没跑过 npm build）。
        require(copied > 0) {
            "bridge/js/dist 无文件可随包（构建产物缺失：npm --prefix bridge/js run build；srcDist=$srcDist）"
        }
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
    // libnode 候选序（先命中先用）：显式 env → node-runtime-build 出口。
    // 本机临时出口不入表（审查步骤 1 清机器路径）：复现旧配方 export LIBNODE=/path/to/libnode.so。
    val libnodeCandidates = listOfNotNull(
        System.getenv("LIBNODE")?.let { File(it) },
        rootProject.layout.projectDirectory.file("node-runtime-build/out/libnode.so").asFile,
    )
    // libc++_shared：libnode 的 NEEDED（readelf 实证），NDK sysroot 同款 ABI。
    // ANDROID_NDK_HOME 无缺省（审查步骤 1：机器路径不入构建脚本），且求值刻意留在
    // doLast 的「三件齐」分支里 —— 全无 → 警告 的分支没 NDK 也要能 assemble。
    // r28c 与 node-runtime-build/VERSIONS.env 的 NDK_VERSION 同源。
    // libopencv.so 候选位（§9.2 图像管线）：显式 env → node-runtime-build 出口
    // （build-opencv.sh 的 OUT）→ image-native.yml 的 artifact 落点位。
    // 与引擎三件套**同一条选填纪律**：缺位不红（装配侧 JniOps.loadOrNull() 拿不到
    // so 即不喂分析器，桥对 images.* 回 ERR_NOT_IMPLEMENTED），在位才随包。
    // 装载名与文件名必须同为 opencv：JniOps.loadLibrary("opencv") 找的是 libopencv.so。
    // 本机临时验证位不入表（审查步骤 1）：复现旧配方 export LIBOPENCV=/path/to/libopencv.so。
    val libopencvCandidates = listOfNotNull(
        System.getenv("LIBOPENCV")?.let { File(it) },
        rootProject.layout.projectDirectory
            .file("node-runtime-build/out-opencv/libopencv.so").asFile,
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
                val ndkHome = System.getenv("ANDROID_NDK_HOME") ?: throw GradleException(
                    "三件齐要随包 libc++_shared.so，但缺 ANDROID_NDK_HOME（export " +
                        "ANDROID_NDK_HOME=/path/to/android-ndk-r28c；版本见 " +
                        "node-runtime-build/VERSIONS.env 的 NDK_VERSION）",
                )
                val cxxShared = File(
                    ndkHome,
                    "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so",
                )
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
                    "（两者都来自本机构建：build-native.sh 出 noden、node-runtime-build/out " +
                    "出 libnode —— 补齐其一再 assemble，或 export LIBNODE=/path/to/libnode.so 显式给）",
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

// ── vendored npm CLI 素材随包（§10.2 调用链首段）──────────────────────────────
// `files/npm/` 的素材来自 `assets/npm/**`，而素材本身**不在 git**（14MB 级，且是
// node-runtime-build 的产物：`fetch-and-build.sh` §9 从 registry tarball（npm@钉版本，
// 2026-10-02 A6 换源）收敛，
// CI 走 node-slice artifact 出库）。因此与引擎三件套**同一条选填纪律**：
//   · 有货 → 递归拷进 generated/npmCliAssets/npm/（**解引用符号链接**：assets 装不了）；
//   · 有货但缺锚（bin/npm-cli.js、bin/npx-cli.js）→ **红**：半瘫 CLI 比没交付更糟
//     （设备上 npm-cli.js 在、require 的依赖树不在，报错面离病因极远）；
//   · 无货 → 警告 + 空产出：装配照过（本机没跑过 Node 构建不挡 assemble），
//     设备侧 `NpmCliDeployer.deploy` 拿不到锚即如实失败 → 装配层不注入 executor →
//     桥对 npm.* 回 ERR_NOT_IMPLEMENTED（**不是**"npm 已可用"）。
// 点条目（以 . 开头）一律不拷：AssetManager 对点条目的可见性在 ROM 间不一致，
// 拷进 assets 只会制造"源里有、设备上没有"的静默差。产出脚本已保证素材零点条目，
// 这条过滤只兜手工 export NPM_CLI_ROOT 指向现成 npm 树的实验路径。
val npmCliAssetsDir = layout.buildDirectory.dir("generated/npmCliAssets/npm")

val prepareNpmCliAssets = tasks.register("prepareNpmCliAssets") {
    // 候选序（先命中先用）：显式 env → node-runtime-build 出口（同 LIBNODE/LIBOPENCV 纪律，
    // 机器路径不入脚本）。本机复现配方：export NPM_CLI_ROOT=/path/to/npm。
    val candidates = listOfNotNull(
        System.getenv("NPM_CLI_ROOT")?.let { File(it) },
        rootProject.layout.projectDirectory.dir("node-runtime-build/out/npm").asFile,
    )
    outputs.dir(npmCliAssetsDir)
    // 来源不在 git：每次装配现查现拷（否则刚解包的 artifact 会被"outputs 已存在"跳过）
    outputs.upToDateWhen { false }
    doLast {
        val out = npmCliAssetsDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val src = candidates.firstOrNull { it.isDirectory }
        if (src == null) {
            logger.warn(
                "[npm-cli] 素材未交付（候选位 = node-runtime-build/out/npm 或 export " +
                    "NPM_CLI_ROOT=…）：APK 无 vendored npm CLI，设备侧 npm.* 如实 " +
                    "ERR_NOT_IMPLEMENTED —— 跑 node-runtime-build/scripts/fetch-and-build.sh " +
                    "或下载 node-slice artifact 解包到 node-runtime-build/out/。",
            )
            return@doLast
        }
        var files = 0
        var bytes = 0L
        var skippedDot = 0
        src.walkTopDown()
            .filter { it.isFile }
            .filter { f ->
                // 相对路径的任一段以 . 开头即跳过（同产出脚本的剪裁口径）
                val dotted = f.relativeTo(src).path.split(File.separatorChar).any { it.startsWith(".") }
                if (dotted) skippedDot++
                !dotted
            }
            .forEach { f ->
                val dest = File(out, f.relativeTo(src).path)
                dest.parentFile.mkdirs()
                f.copyTo(dest, overwrite = true)
                files++
                bytes += f.length()
            }
        if (skippedDot > 0) {
            logger.warn("[npm-cli] 跳过 $skippedDot 个点条目（AssetManager 可见性 ROM 间不一致；source=$src）")
        }
        // 锚文件：与 NpmCliDeployer.ANCHORS 同名单（部署侧还会再验一次，这里红在装配期）
        listOf("bin/npm-cli.js", "bin/npx-cli.js").forEach { anchor ->
            require(File(out, anchor).isFile) {
                "npm CLI 素材缺锚文件 $anchor（源 = $src）—— 半瘫 CLI 不随包：素材树要么是" +
                    "未剪裁完的半成品，要么 NPM_CLI_ROOT 指错了目录"
            }
        }
        logger.lifecycle("[npm-cli] 素材随包：$files 个文件 / ${bytes / 1024 / 1024}MiB → assets/npm/（source=$src）")
    }
}

// ── 第三方许可声明随包（backlog D8）─────────────────────────────────────────
// 为什么声明文件也要进 APK：仓里有一份 `THIRD_PARTY_NOTICES.md` 只解决「审计者看得到」，
// 而随 APK 分发的二进制（Node / OpenCV / libc++ …）其许可条款**必须随分发一起可达**——
// 只在仓库里放一份、装到用户手机上就没有，等于没声明。落位 `assets/third-party/`，与
// 能力中心的「关于/许可」页将来取用是同一个键。
//
// 与前三件的纪律差别（刻意不同）：本件**在 git 里**（生成物已入库，评审面可见），所以
// 缺件是**仓库破损**而不是「本机没构建」——按 bridgeDist 的口径红，不按选填件的口径只 warn。
// 同步面（改 VERSIONS.env 必须重跑生成器）由 CI 的 `gen-notices.mjs && git diff` 门管。
val noticesAssetsDir = layout.buildDirectory.dir("generated/noticesAssets/third-party")

val prepareNoticesAssets = tasks.register("prepareNoticesAssets") {
    val src = rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")
    val licensesDir = rootProject.layout.projectDirectory.dir("node-runtime-build/licenses")
    inputs.file(src)
    inputs.dir(licensesDir)
    outputs.dir(noticesAssetsDir)
    doLast {
        val out = noticesAssetsDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        if (!src.asFile.isFile) {
            throw GradleException(
                "THIRD_PARTY_NOTICES.md 缺位（${src.asFile}）：随包二进制（Node / OpenCV / " +
                    "libc++ 等）的许可声明是分发义务，不是可选项 —— 跑 " +
                    "`node node-runtime-build/licenses/gen-notices.mjs` 生成",
            )
        }
        src.asFile.copyTo(File(out, "THIRD_PARTY_NOTICES.md"), overwrite = true)
        // 逐字原文一并随包：清单只说「见原文」，原文不在包内等于让用户去网上找。
        // 文件名与生成器 licenses/ 下的名字一致（清单里的链接指向同名件）。
        var copied = 0
        // 放行判据 = 生成器 COMPONENTS 表里的原文文件（`licenses/` 下的七件；libjpeg-turbo
        // 是**子目录**，因为它自己的 LICENSE.md 里有一条指向 README.ijg 的相对链接 ——
        // 拍平到一层会让那条链接断，与脚本 KDoc 同一口径）。**不是**「目录里所有文件」：
        // 同目录的 gen-notices.mjs 是生成器本体，随包没有意义。漏一份原文 = 清单里那个
        // 链接指向不存在。
        val licenseFiles = listOf(
            "node-LICENSE", "opencv-LICENSE", "kleidicv-LICENSE", "libpng-LICENSE", "zlib-LICENSE",
        )
        licenseFiles.forEach { name ->
            val f = licensesDir.asFile.resolve(name)
            require(f.isFile) { "node-runtime-build/licenses/$name 缺位（清单指向的原文缺失）" }
            f.copyTo(File(out, name), overwrite = true)
            copied++
        }
        // libjpeg-turbo 两份**保持上游的相对布局**（LICENSE.md ↔ README.ijg 互指）
        val jpegDir = licensesDir.asFile.resolve("libjpeg-turbo")
        listOf("LICENSE.md", "README.ijg").forEach { name ->
            val f = jpegDir.resolve(name)
            require(f.isFile) { "node-runtime-build/licenses/libjpeg-turbo/$name 缺位（双许可原文不完整）" }
            val dest = File(out, "libjpeg-turbo/$name")
            dest.parentFile.mkdirs()
            f.copyTo(dest, overwrite = true)
            copied++
        }
        require(copied > 0) {
            "node-runtime-build/licenses/ 无许可原文可随包（$licensesDir）—— 清单指向的原文缺失"
        }
        logger.lifecycle(
            "[notices] 许可声明随包：THIRD_PARTY_NOTICES.md + $copied 份逐字原文 → " +
                "assets/third-party/",
        )
    }
}

// 资产合并前必须先生成（AGP 的 preBuild 每变体都有；matching 覆盖配置期尚未注册的情形）。
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(prepareBridgeDistAssets)
    dependsOn(prepareEngineNativeLibs)
    dependsOn(prepareNpmCliAssets)
    dependsOn(prepareNoticesAssets)
}
