# AutoScript

内置 Node.js 的安卓自动化平台（对标 AutoJsPro v9）：脚本作者用 Node.js / JavaScript 编写自动化脚本，
运行在安卓系统之上。每脚本一个 Node 进程，宿主与脚本之间走跨进程异步桥，能力按三态门禁授予。

> ⚠️ **先读安全前提**：本平台以**完整权限、无进程隔离**运行脚本。第三方脚本与你自己写的脚本
> **同进程同权** —— 无障碍读屏、点击、输入、截屏、网络、文件访问全部同权。**装入一个脚本，
> 等于把你这台设备上已授予的全部能力交给它。** 这是「引擎只有 Node 一条轨」的直接后果，不是待修的
> 缺陷。支持范围、密钥管理、已知缺口都在 [`SECURITY.md`](SECURITY.md)，请先读那一份。

**本项目处于开发期，不发行正式版**（§18 第 3 项）。「哪些能用、哪些还是接口期」以
[`docs/design-status.md`](docs/design-status.md) 的**接口期表**为准 —— 例如 npm 生产装配尚未接线，
真机安装会如实回 `ERR_NOT_IMPLEMENTED`；QuickJS 沙箱引擎已裁。别把设计文档里的**目标形态**读成现状。

## 仓库里有什么

| 路径 | 内容 |
|---|---|
| [`docs/README.md`](docs/README.md) | **`docs/` 总索引**：哪份文档回答什么（先读这个） |
| [`docs/framework-design.md`](docs/framework-design.md) | 架构设计**薄索引**（分卷导航） |
| [`docs/design/`](docs/design/) | 架构设计**分卷**，按 § 号分卷 —— **契约的单一事实来源**（进程模型 / 桥 / 执行 / 能力 / npm / 安全 / JS API / 路线图） |
| [`docs/design-status.md`](docs/design-status.md) | 落地台账（**当前状态页**）：已落地 / 还是接口期 / 流水目录 |
| [`docs/log/`](docs/log/) | 流水**按日期切片**（`README.md` 是索引）；历史条目逐字保留 |
| [`docs/implementation-notes.md`](docs/implementation-notes.md) | 实现注记：自各分卷外迁的「已落地 / 实测」叙事 |
| [`docs/design-decisions.md`](docs/design-decisions.md) | 决策记录：已拍板项 + 被推翻、改过的口径 |
| [`docs/backlog.md`](docs/backlog.md) | 待办池（收件箱，不是承诺） |
| [`SECURITY.md`](SECURITY.md) | 安全策略：支持范围、怎么报告、密钥怎么管 |
| [`CLAUDE.md`](CLAUDE.md) | 面向 AI 协作者的仓库导览与协作纪律 |
| `app` / `app-service` / `bridge` / `domain` / `engine` / `platform` / `ui` | Gradle 模块（模块表见 `settings.gradle.kts`；依赖方向铁律见 [`docs/design/06-modules.md`](docs/design/06-modules.md)） |
| `bridge/js` | npm 包 `@autoscript/bridge-js`（TS facade SDK；`private`，不发布；非 Gradle 模块） |
| `node-runtime-build` | CI 构建管线：Node 24 源码与 OpenCV 交叉编译 recipe（产物不入 git） |

## 前置条件

| 需要 | 版本 | 说明 |
|---|---|---|
| JDK | **17** | 编译与单测都用它（`compileOptions` / `kotlinOptions` 均钉 17） |
| Android SDK | `platforms;android-35` + `build-tools;35.0.0` + `platform-tools` | `minSdk 26` / `targetSdk 35`（版本钉在 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)） |
| Node.js | **24** | 构建 facade dist 与跑 `bridge/js` 测试；与 `node-runtime-build/VERSIONS.env` 同口径 |
| Android NDK | r28c | **只有**要自己编引擎/图像核（C++）时才需要，见下 |
| Gradle | 不用装 | 用仓库里的 `./gradlew`（wrapper 8.9，与 AGP 8.5.2 匹配） |

SDK 位置二选一：环境变量 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`），或仓库根的 `local.properties`
写 `sdk.dir=/path/to/android-sdk`（该文件已 gitignore，不入库）。JDK 同理走 `JAVA_HOME`。

## 构建

```bash
# 1) facade dist —— :app 的随包任务**硬依赖**它（缺件即红：dist 里必须有 bootstrap.js / index.js）
npm --prefix bridge/js ci
npm --prefix bridge/js run build

# 2) 出 debug APK
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

**关于引擎二进制**（可选，不影响 assemble 能否通过）：`libnode.so` / `noden` / `libc++_shared.so`
与 `libopencv.so` 都是 CI 交叉编译产物、**不入 git**。本机没有它们时，上面的装配**照过**，
只是产出一个**没有引擎的 APK**（设备侧 `execute` 预检会点名绝对路径）；两件里只有一件才红（半个交付
比没交付更糟）。要出自带引擎的 APK，需要 NDK r28c，并按
[`build-logic/src/main/kotlin/autoscript.engine-natives.gradle.kts`](build-logic/src/main/kotlin/autoscript.engine-natives.gradle.kts)
的候选位提供产物（`node-runtime-build/out/libnode.so`，或 `LIBNODE=` / `LIBOPENCV=` 显式指路；
三件齐时另需 `ANDROID_NDK_HOME`）。C++ 侧的交叉编译与真机红灯测试纪律见
[`node-runtime-build/`](node-runtime-build/)。

## 测试

```bash
# 全量门（与 CI 逐字同源；任务清单的事实来源是 .github/workflows/ci.yml，加模块时以那里为准）
./gradlew :domain:test :bridge:java:test :app-service:runtime:test :app-service:scheduler:test \
  :app-service:script-repo:testDebugUnitTest :app-service:permission-center:test \
  :app-service:packager:test :app-service:npm:test :platform:capabilities:testDebugUnitTest \
  :platform:system:testDebugUnitTest :engine:node-process:testDebugUnitTest \
  :ui:testDebugUnitTest :app:testDebugUnitTest

# 单模块（纯 JVM 模块用 :test；Android 模块用 :testDebugUnitTest）
./gradlew :domain:test
./gradlew :ui:testDebugUnitTest

# facade（TS）单测（内部先跑 tsc build）
npm --prefix bridge/js ci && npm --prefix bridge/js test

# wire 生成物同步门：schema 改了必须重生成两份产物，漂移即红（CI js-tests job 同款）
npm --prefix bridge/js run gen:wire && git diff --exit-code

# 文档链接门：全部 *.md 的相对链接必须存在（CI 的 docs-check job 同一条命令，秒级）
bash .github/scripts/check-doc-links.sh
```

两条纪律：

- **`skipped` / `aborted` 不算绿**。守卫做在约定插件里（`build-logic` 的 `autoscript.test-guard`）：
  测试出现跳过即红。设计上就该环境门禁的少数 E2E 例外在 `TestGuard.ENV_GATED` 名单里，其余要放行得
  显式 `-PallowSkipped=<类名>`。
- **npm 端到端测试本机默认跑，PR 门排除、nightly 必跑**：`P0LoopbackTest` / `HostNodeNpmE2ETest` /
  `NpmCacheSeedDeployerTest` 要拉真 npm 进程，PR 门（`ci.yml`）用 `-PskipNpmE2E` 排除；
  [`.github/workflows/e2e-nightly.yml`](.github/workflows/e2e-nightly.yml) 每天跑**不带**该 flag 的那条，
  并由 `bash .github/scripts/check-e2e-ran.sh` 证明它们真跑过（不是 `assumeTrue` 静默跳过）。
  本机跑全量门**不要**带这个 flag。

## 运行

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

装好后在设备的**无障碍设置**里为 AutoScript 开启服务（读屏 / 点击 / 输入都建立在它上面）；
应用内的**能力中心**列出每项能力的门禁三态（§9.5）与跳转系统设置的引导入口。APK 里没有引擎二进制的
构建只能用到这里为止 —— 要真跑脚本，按上一节出自带引擎的 APK。

运行期已实测过的部分（非 root 设备、Android 13 / arm64）：引擎冷启、桥往返、无障碍读屏与手势、
截屏帧源；**未**实测：16KB 页设备、SELinux enforcing、`targetSdk` 提取策略、MediaProjection 高清会话。
逐条记在 [`docs/design-status.md`](docs/design-status.md)。

## 参与贡献

提交纪律、提交信息格式、冻结文件清单、一次一批的推进方式见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。
安全问题**不要**开公开 issue，走 [`SECURITY.md`](SECURITY.md) 里的渠道。

## 许可

本项目本体是 MIT，见 [`LICENSE`](LICENSE)。随包分发的第三方组件（Node.js / OpenCV /
KleidiCV / libc++ / libjpeg-turbo / libpng / zlib / vendored npm）的许可清单见
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)，逐字许可原文在
[`node-runtime-build/licenses/`](node-runtime-build/licenses/)。

该清单是**生成物**：版本事实来源是 [`node-runtime-build/VERSIONS.env`](node-runtime-build/VERSIONS.env)，
改版本后必须重跑 `node node-runtime-build/licenses/gen-notices.mjs`，CI 有一道同步门
（漂移即红）—— 许可声明与事实脱节在分发时是法律问题，不是文档瑕疵。
