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