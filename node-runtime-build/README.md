# :node-runtime-build —— Node 24 / OpenCV 4.14（arm64·Android）自建管线

非 Gradle 模块（docs §3）：两条**独立**构建轨，共居一个版本矩阵（`VERSIONS.env`）与同一套 16KB 门禁纪律，但**产物、门禁项、可信链都不共用**：

| 轨 | 脚本 | 产物 | 用途 | CI |
|---|---|---|---|---|
| Node 24 | `scripts/fetch-and-build.sh` | `libnode.so.137` + `node` | `:bridge:native` dlopen 宿主引擎、§844 垂直切片 | `Dockerfile`（体量 ~600MB 源码 + 半小时级，不进 PR 门） |
| OpenCV 4.14.0 | `scripts/build-opencv.sh` | `libopencv.so` | `:bridge:image` 图像分析管线（JNI 装载，不进 Node） | `.github/workflows/image-native.yml` |

OpenCV 轨 20 分钟级，故单独走 PR 门（改了图像构建面就跑，路径过滤）；Node 轨不进 PR 门，构建门由 Dockerfile 承担。

> 下方「为什么自建」起的各节都是 **Node 轨**视角（冻结版本矩阵里 NDK/ABI 行两轨共用）；
> OpenCV 轨单列在文末「OpenCV 轨（`libopencv.so`）」，版本矩阵另见 `VERSIONS.env` 的 OpenCV 块与 RISKS §13。

## 为什么自建（短版）

| 上游方案 | 结论 |
|---|---|
| 官方 nodejs-mobile | 停更于 2021（最后推送 2021-10-27），Node 18.x 已 EOL，ELF 4KB 对齐 → 16KB 页设备 dlopen 崩 |
| 自建（本管线） | Node 24 LTS 长期支持 + `--dest-os=android` + r28 默认 16KB 对齐 + 全链校验 |

## 冻结版本矩阵（`VERSIONS.env`，单一事实来源）

| 项 | 值 | 校验 |
|---|---|---|
| Node.js | **v24.21.0**（Krypton，24 LTS 当前最新） | 官方 SHASUMS256.txt（sha256 逐字） |
| Android NDK | **r28c** = `28.2.13676358` | repository2-3.xml（size + sha1，与 sdkmanager 同源） |
| 目标 ABI | `aarch64-linux-android26`（= 根 catalog 冻结 minSdk 26） | — |
| 共享库 ABI | `libnode.so.137`（NODE_MODULE_VERSION=137） | 门禁断言 `.so.<137>` 存在 |
| 构建工具 | ubuntu:24.04 + build-essential/python3/curl/unzip/xz | Dockerfile 固化 |

## 快速开始

```bash
docker build -t autoscript/node-runtime-build node-runtime-build
id=$(docker create autoscript/node-runtime-build)
docker cp "$id":/build/out ./out && docker rm "$id"
ls out/          # node  libnode.so.137*  SHASUMS256  config.gypi  config.mk
```

## 交付物

| 产物 | 用途 |
|---|---|
| `out/node` | 垂直切片：adb push 到设备 `LD_LIBRARY_PATH=. ./node -e 'console.log(process.platform, process.arch, process.versions.modules)'` |
| `out/libnode.so.137`（+软链） | `:bridge:native` dlopen 宿主引擎；**16KB LOAD 对齐** |
| `out/config.gypi` + `out/config.mk` | 平台契约审计（config.gypi 的 JSON `variables.OS=android` / `node_shared=true`） |
| `out/SHASUMS256` | 产物基表（发布/增量比对） |

## 硬门禁（Node 轨：`check-alignment.sh`，build 尾部必过）

1. **16KB**：每个 `LOAD` 段 `align >= 2**14`（`llvm-objdump -p`；未对齐在新设备 dlopen 即崩）——r28 链接器默认满足，门禁是最终仲裁。
2. **平台**：`config.gypi` 的 `variables.OS == "android"` 且 `node_shared == true`（`config.mk` 只含 `BUILDTYPE`/`NODE_TARGET_TYPE`，CLI 参数不回显；JSON 键才是契约证据）。
3. **ABI**：存在 `libnode.so.<137>`（预编译 `.node` addon 必须同号，见 RISKS §4）。

## 升级流程（一次提交，Node 轨）

1. 改 `VERSIONS.env`（新 `NODE_VERSION` / `NODE_SHA256` 取自 `nodejs.org/dist/<v>/SHASUMS256.txt`；必要时一并评估 NDK）。
2. 跑 Docker 全量重建，门禁 + 基表变化即回归证据。
3. 交垂直切片验证（§844 里程碑：最小 `:node` 进程 `console.log` 回传）后合入。

OpenCV 轨的升级同纪律但走它自己的门：改 `VERSIONS.env` 的 OpenCV/kleidicv 块 → `image-native.yml` 自动跑（改 `build-opencv.sh` 同触发）→ 看审计行的 kleidicv ON/OFF 与 NEEDED 白名单是否仍成立。

## OpenCV 轨（`libopencv.so`）

```bash
# 手工（与 CI 同一脚本）：先备好 $WORK/ndk/android-ndk-r28c 与 $WORK/src/opencv 的下载源
WORK_DIR=/build bash scripts/build-opencv.sh
ls out-opencv/    # libopencv.so  SHASUMS256（旁带 kleidicv ON/OFF 审计行）
```

- 版本：OpenCV **4.14.0**（按 commit SHA `0654a42…` 固定）+ kleidicv pin `26.03`（md5 校验，上游 hal 里 grep 复核）。
- 裁剪：`BUILD_LIST=core,imgproc,imgcodecs`；格式库 `libjpeg-turbo`/`libpng`/`zlib` 走树内源码（`BUILD_*=ON`），无外部下载。
- `WITH_KLEIDICV` 保持**默认 ON**（AArch64+Android 默认开）——它**不含 `matchTemplate`/`imdecode`**，是给未来算子铺路；下载失败软降级，结论写进审计行。**已在启用中**（2026-09-25 复核：交付 so 内含 `kleidicv::hal::*` 42 个符号与 `HAL implementation … ==> kleidicv::hal::…` dispatch 串）。ON/OFF 的判据是 configure 摘要的 `Custom HAL: … KleidiCV (ver …)`，**不是** `CMakeCache.txt` 的 `HAVE_KLEIDICV`（上游只 `set` 普通变量、无 CACHE，cache 里没这一项）。
- 编译面：`imgnative.cpp`（纯计算核，零 JNI）+ `images_jni.cc`（装载面，全仓唯一 `#include <jni.h>`）两个 .cpp 一起链进同一个 so —— **只编计算核会得到一个没有 JNI 入口的 so**（2026-09-24 CI 实测：链接行不含装载面时产物缺 `Java_com_autoscript_platform_system_NativeImageAnalyzer_*`，Kotlin 侧 `loadOrNull()` 只会回 null，症状是"图分析全 NOT_IMPLEMENTED"而非编译错误）。脚本头部对两个源文件各做一次存在 assert。
- 门禁（`check-opencv-alignment.sh`）：16KB LOAD 对齐 + AArch64/ET_DYN + **NEEDED 白名单（不许 `libopencv_*.so` = 静态链接的验收点）** + **JNI 符号面**
  （`Java_com_autoscript_platform_system_NativeImageAnalyzer_{decode,match,release,color}Native` 四个逐个在场）——
  最后这条 2026-09-25 补：JNI 符号名是字符串约定，Kotlin 的 `external fun` 与 `images_jni.cc` 之间没有编译器看护，
  改包名/类名漏一处照样编得过、前三条照样绿，要到真机 dlopen 才以 `UnsatisfiedLinkError` 现形（本机已吃过一次近失：
  手边那份产物早于 findColor 提交 87 分钟、`colorNative` 0 个，前三条一条都不红）。
- 交付位：`app/build.gradle.kts` 的 `prepareEngineNativeLibs` 候选位含 `node-runtime-build/out-opencv/libopencv.so`（或 `export LIBOPENCV=…`）；**缺位只 warn 不 fail**（"选填纪律"与 addon 同）—— so 不在时 `images.*` 桥如实 `ERR_NOT_IMPLEMENTED`，不塞内存替身。

## 风险与决策记录

见 [RISKS.md](RISKS.md)：上游停更自持、r28c 锁版（r27d 备选需手工对齐旗标但 configure 不读 LDFLAGS）、ICU 面、ABI 锁步 137、16KB 排查路径、`--openssl-no-asm` 取舍、minSdk 对齐纪律；OpenCV/kleidicv 轨的单列风险（软降级审计行、matchTemplate 不走 kleidicv、静态链接的 NEEDED 白名单）见 RISKS §13。