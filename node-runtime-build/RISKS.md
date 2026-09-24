# node-runtime-build 风险清单

> 对应设计 §777「Node-on-Android 升级依赖自持管线」与 §16/§778 门禁。**升级 = 改 `VERSIONS.env` → CI 一键全链重跑**，管线减至「换版本号→跑一次→回归」。

## 1. 上游停更，长期自持（高）
nodejs-mobile 停更于 2021（真实验证：仓库最后推送 2021-10-27，默认分支 `mobile-master`，对应 Node 18.x 且 ELF 4KB 对齐）。本管线 fork 其 **configure 语法**（`--dest-cpu/--dest-os/--cross-compiling/--shared/--openssl-no-asm/--with-intl=none`，实抓自其 `android-configure`），但工具链路径完全替换：上游用已废弃的 NDK pre-r19 `make-standalone-toolchain.sh`，我们直连 NDK r28 的 clang wrapper（`aarch64-linux-android${API}-clang`）。维护责任转为内部：每 Node LTS 升级必须回归。

## 2. NDK 版本锁 r28c vs r27d（中）
- **r28+ 链接器默认 16KB ELF 对齐**（Android 官方 page-sizes 指南原文），故 `fetch-and-build.sh` 不需要手工 `-Wl,-z,max-page-size=16384`；**check-alignment.sh 才是最终仲裁**（防 lld 默认值漂移）。
- 全文核对过 `configure.py:24-25`：Node configure **只从 env 读 `CC`/`CXX`**（不读 LDFLAGS）——若未来回退 r27d（需显式对齐旗标），注入点必须走 `node.gyp`/gyp 的 `ldflags` 或 wrapper 脚本包一层 clang，**不能假设 `LDFLAGS` 环境变量生效**。r29 已存在但未验证，冻结在已验证的 r28c。

## 3. `--with-intl=none` 的 ICU 面（中）
无 ICU → `Intl.*`、`toLocaleString` 等受限，**不影响 npm 安装语义**；运行期可用 `full-icu` npm 包按需填充（需评估体积）。回退 `--with-intl=small-icu` 会引入 ~10MB+ 数据文件，纳入 40MB APK 预算（§18）时再议。

## 4. ABI 锁步：NODE_MODULE_VERSION=137（高）
`libnode.so.<137>` + V8 快照 + `process.versions.modules=137` 三者必须同源同构（设计 §583）。预编译 `.node` addon（napi-rs/NDK 交叉产物）必须 `NODE_MODULE_VERSION==137` 且 `--dest-os=android`，否则 dlopen 即崩。CI 侧静态断言（`config.gypi` 的 JSON 键 + 文件名）只是**前置护栏**；`process.platform==='android' / process.arch==='arm64'` 的运行时断言归属垂直切片（设计 §844 里程碑）。

## 5. 16KB 兼容的排查路径（中）
链接链任一处（我们自己的 so / 预编译 so / assets 里的模型）非 16KB 对齐，在新设备 dlopen 直接崩。排查序：`adb shell getconf PAGE_SIZE` 确认设备（16384）→ 逐 so 跑 `llvm-objdump -p | grep LOAD` 看 align → APK 侧 `zipalign -v -c -P 16 4`（Packager 模块 P0 承接）。本模块只保证 **libnode.so 这条链**。

## 6. `--openssl-no-asm` 性能损耗（低）
对称/非对称加解密走 C 实现，较汇编有损耗；换取跨编译器可复现（上游同款选择）。后续可按需开 asm，前提是 NDK clang 汇编器验证通过。

## 7. minSdk/API 对齐（低）
`ANDROID_API=26` 与根 Gradle catalog 冻结 minSdk 26 一致（设计文本 §72 写 24，以冻结基线为准）。升降 minSdk 必须同步改 `VERSIONS.env`（bionic 符号面变化会影响 Node 的 `uv`/`zlib` 编译期探测）。

## 8. Node 版本快照策略
未启用 `--without-node-snapshot`：arm64 + 现代 V8 默认可用。若垂直切片出现快照/JIT 相关崩溃，先试 `--without-node-snapshot` 再定位（一次提交+全量回归，符合纪律）。

## 9. 构建环境漂移
Docker 单文件固化（ubuntu:24.04 + 固定依赖集），`VERSIONS.env` 为缓存失效开关。NDK 下载 ~722MB，CI 需镜像缓存策略（构建一次后层缓存；变更才重下）。本地无 Android SDK 者一切以 Docker/CI 门禁为准。
**现状声明：本管线交付时未在本环境实跑过 Docker 全链（无 Docker/Android SDK）；首次真实验证 = 提交后 CI/开发者机器上的 Docker 构建。跨编译 host 工具（如 mksnapshot）路径以「首次跑通」为校准点，任何偏差按一次提交修正（符合设计 §844 把垂直切片作为第一里程碑的安排）。**

## 10. 产物可信链
`fetch-and-build.sh` 对 Node 源校验 **官方 SHASUMS256.txt 逐字 sha256**、对 NDK zip 校验 **repository2-3.xml 的 size+sha1**（sdkmanager 同源），并在产物目录再生成一层 `SHASUMS256` 基表，供发布/审计比对。任何一层校验失败即整链中止（`set -euo pipefail`）。
## 11. zlib BUILD.gn/gyp 缺口：ndk_compat（v24.21.0 + NDK r28c 实证）

Node 的 `deps/zlib` 源集跟随 Chromium 的 `BUILD.gn`：`cpu_features.c` 在 `ARMV8_OS_ANDROID` 分支调用 `android_getCpuFeatures()`，而 `BUILD.gn` 要求 `//third_party/cpu_features:ndk_compat` 提供该符号实现 —— **Node 源码树里没有这个目录**（Chromium 才有）。gyp 路径下无人编译它，于是 `libzlib.a` 带着未决符号进终链，在 `openssl-cli`/`node`/`libnode.so` 的 `ld.lld` 处确定性断链（`undefined symbol: android_getCpuFeatures`）。

修法（`fetch-and-build.sh` §3b）：把 NDK 自带的 `sources/android/cpufeatures/cpu-features.c/.h`（就是 ndk_compat 的本体，纯 C 只依赖 `sys/*` 头）拷贝进 `deps/zlib/android-ndk-compat/`，随 zlib 主 target 同编同链。两个坑都是实证过的：
- **gyp 的 `sources` 必须相对 `.gyp` 文件**：`<(android_ndk_path)/...` 绝对写法会在 make 层产生非法 obj 路径（`No rule to make target .../obj.target/zlib//build/ndk/...`）——故先拷贝再相对引用。
- 补丁锚点是 zlib 主 target 的 `USE_FILE32API` 条件块；`anchor assert` 失败 = 上游 `zlib.gyp` 漂移，需人工跟进。

## 12. V8 gyp 独有：arm64 trap-handler 条件缺 android（v24.21.0 + NDK r28c 实证）

`tools/v8_gypfiles/v8.gyp` 的 arm64 段按 OS 分 trap-handler 实现文件（native-posix / x64-simulator），但两个条件的 OS 列表都没有 `android`；而 host-x64 + target-arm64 + `V8_OS_LINUX`（含 Android）时 `V8_TRAP_HANDLER_SUPPORTED=true`，`handler-outside.cc` 的桩被裁 —— mksnapshot 终链悬空双符号（`v8_internal_simulator_ProbeMemory` + `RegisterDefaultTrapHandler()`）。修法见 `fetch-and-build.sh` §3c（两条件行各加 `android`，行号 assert 防漂移）。上游 GN 路径无此缺口，是 gyp 独有。

## 13. OpenCV/kleidicv 轨（`build-opencv.sh`，2026-09-25 记账）

与 Node 轨**不共 out/**：产物名、门禁项、可信链校验源都不同（本轨产 `libimgnative.so`，无 `config.gypi`/`libnode.so.<137>` 契约），合目录会让 `check-alignment.sh` 的 Node 专属断言误扫图像产物。两轨共用 NDK zip 与 `ANDROID_API`。

- **kleidicv 是软降级，必须留审计行**：下载源是 gitlab.arm.com 的 release 包，CI 网络可达性不受我们控制。`ocv_download` 失败只 WARNING 不 fatal，于是产物可能悄悄从"带 kleidicv 加速"变成"纯 OpenCV"而**功能不报错、体积缩小**。对策：pin 写死在 `VERSIONS.env`（`KLEIDICV_COMMIT=26.03` + md5），构建时 grep 上游 `hal/kleidicv/kleidicv.cmake` 的两处 pin（上游换 pin 即 die），收尾按 `CMakeCache.txt` 的 `HAVE_KLEIDICV` 把 ON/OFF 写进 `SHASUMS256` 旁审计行 —— 只 sha256 回答不了"这个 so 里到底有没有加速面"。
- **kleidicv 不覆盖我们要的算子**（上游 doc 实证）：它加速 add/sub/absdiff/cvtColor/GaussianBlur/Sobel/resize/… 但**不含 `matchTemplate`/`imdecode`**。开着是给未来算子铺路 + 现状不亏，**不要**拿它当找图提速的依据。
- **arm64 的 `matchTemplate` 实际走通用 C**：carotene 的 NEON 入口门槛 `width>=8 && width*height<=256`（4.8/4.14 逐字节相同）—— 正常按钮模板即超出；4.14 起入口改走 `cv_hal_matchTemplate`，但 `hal_ni_matchTemplate` 仍是 `CV_HAL_ERROR_NOT_IMPLEMENTED` 死桩；imgproc 的 `NEON_DOTPROD` dispatch 条目为空。性能预期按通用实现估（1080p 模板匹配 < 40ms 是 §15 预算，未被本节推翻）。
- **静态链接是硬要求**：产物 NEEDED 白名单只许 `libc/libdl/libm/liblog/libc++_shared`，出现 `libopencv_*.so` 即门禁失败 —— 否则等于把 OpenCV 共享库推给装载面（版本漂移必崩）。格式库 `libjpeg-turbo`/`libpng`/`zlib` 走 `BUILD_JPEG/BUILD_PNG/BUILD_ZLIB=ON` 树内源码，不找宿主 sysroot。
- **装载面漏编 = 静悄悄没有图分析**（2026-09-24 CI 实证）：终链行只列 `imgnative.cpp`（纯计算核）时，so 编得过、门禁全绿（16KB/NEEDED/ELF 与装载面无关），但**没有 `Java_com_autoscript_platform_system_*` 符号** → Kotlin `System.loadLibrary` 照常成功（so 存在、能解析），首次调用 native 方法才 `UnsatisfiedLinkError` → `JniOps.loadOrNull()` 回 null → 桥对 `images.*` 回 `ERR_NOT_IMPLEMENTED`。**症状像"so 没交付"而不是"链接行漏文件"**，最难查的一类。故终链行必须同时列 `imgnative.cpp` + `images_jni.cc`，脚本头部对两个文件各 assert 一次。
- **OpenCV commit pin + 首次跑未验证声明**：`OPENCV_COMMIT` 按 SHA 固定（不用浮动 tag），构建时 rev-parse 复核。与 Node 轨同样：本管线交付时未在本环境实跑过全链，首次真实验证 = Actions（`image-native.yml`）/ Docker；偏差按一次提交修正。
- **体积**：`core+imgproc+imgcodecs` 静态链接进 APK 的代价见 §15 已记账（该预算条目唯一被实测推翻的一项），后续若按需分发，本轨无 exec 需求、可整轨后移。

## 14. 全量 make 会捎带 cctest（API 26 无 aligned_alloc）

顶层 `make`（含 `make node`）的默认依赖图含 `cctest`，其 `test_crypto_clienthello.cc` 用 `aligned_alloc`（bionic API 28+，`ANDROID_API=26` 头文件不暴露）确定性断链。`node`/`libnode.so` 不依赖它。CI 全链（`fetch-and-build.sh` §5）如命中，应点名编 `libnode` + `node` 目标（`make -C out BUILDTYPE=Release libnode node`）而非裸 `make`；或升 `ANDROID_API>=28`（牵动 minSdk，以升级纪律另议）。
