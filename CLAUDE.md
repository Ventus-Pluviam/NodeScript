# AutoScript

内置 Node.js 的安卓自动化平台（对标 AutoJsPro v9）：每脚本一个 Node 进程、跨进程异步桥、能力三态门禁。架构设计见 `docs/framework-design.md`（§0–§19 全覆盖）。

## 仓库地图

| 路径 | 说明 | 设计章节 |
|---|---|---|
| `docs/framework-design.md` | 架构设计（契约的单一事实来源） | 全部 |
| `.claude/skills/skill-designer/` | 项目级 skill：设计/创建技能 + 外科手术式改代码 + git 提交 | — |
| `module-stubs` 之外的模块 | 各模块职责见下 | §6 |

## Gradle 模块（模块表由 `settings.gradle.kts` 冻结，15 个）

- `:app` — AppShellApplication 启动装配（§4.1 Composition Root）；Compose UI 已拆去 `:ui`（2026-09-23 落地：launcher 随库 manifest 合并，`:app` 源码零 compose / 零 import ui）
- `:app-service:runtime` — RuntimeController / EnginePool / Watchdog 仲裁（§8）
- `:app-service:scheduler` — 定时/Intent/事件任务、checkpoint 意图日志、runNonce 幂等（§9.6）
- `:app-service:script-repo` — 项目/资源/脚本库、assets→filesDir 原子部署（§9.6）
- `:app-service:permission-center` — 权限三态门禁、引导页、降级路径（§9.5）
- `:app-service:packager` — 模板 APK 改写、签名向导（§14；整轨已移后续版本，加密资产/loader 已裁）
- `:domain` — **纯 Kotlin 领域层**：SPI 接口 + DTO + 状态机（零 Android 依赖、JVM 可单测）
- `:bridge:java` — Kotlin Router / RequestRegistry(TTL) / HandleRegistry(generation) / EventBus（§7）
- `:bridge:native` — C++ N-API addon 控制面 + libnode.so 装载（§7，CI 构建）
- `:bridge:image` — C++ 图像管线 libimgnative.so（OpenCV 4.x，§9.2，CI 构建）
- `:engine:node-process` — :nodeN 进程宿主：`NodeProcessEngine`（Kotlin spawn，实现 `:domain` 的 `ScriptEngine`）+ main.cpp（§5/§7.8；addon `.so` 本机 NDK 可交叉编译验证，APK `assembleDebug` 本机可直跑）
- `:engine:sandbox` — QuickJS 宿主进程（P1）
- `:platform:capabilities` — a11y/截图/输入/悬浮窗/系统/存储（§9.1–9.4）
- `:platform:system` — overlay/通知/datastore/shell/zip/设备信息（§9.6）
- `:ui` — Compose UI 呈现层：启动 Activity（launcher）、首屏/任务中心/控制台/能力中心界面；状态经 `:domain` 的 `HostSummary` 读口现取，禁依赖 `:app`（§6）
- `bridge/js/` — **npm workspace**（TS facade SDK `@autojs/*`，非 Gradle 模块，§12.4）
- `node-runtime-build/` — **CI 构建管线**（Node 24 源码 recipe + 16KB 对齐门禁，非 Gradle 模块，§3）

## 依赖方向铁律（Gradle/archUnit 强制，见 §4.1）

`:app` → `:app-service:*`/`:ui` → `:domain`；`:platform:*` → `:domain`（实现 SPI，不反向）；`:bridge:java` → `:domain`；`:domain` 零 Android/零桥。全部禁止把 UI/Dialog 类、危险权限、循环依赖带进下层。

## 构建

- 本机已配置 Android SDK：`/root/android-sdk`（platform-35 + build-tools 35 + platform-tools；`local.properties` 指 `sdk.dir`，已 gitignore），JDK 17 = `/root/develop/claude/tools/jdk-17.0.17+10`。**CI 同款 `./gradlew …` 命令可本机直跑**（12 个测试任务 2026-09-23 实测全绿）——CI 仍是权威门，但本机已能同源复现。`tools/jvm-test*` 旁路保留作快速门；**注意其结构性盲区**：kotlinc 直跑的 `java.*` 来自 JDK（有 `Process.pid` 等），AGP 来自 android.jar 桩面（没有）——新增 `java.*` 较新 API 必须过 gradle（首跑即抓出四处）。
- **CI 验证门**：`.github/workflows/ci.yml` —— JVM 单测（12 个模块：`:domain`、`:bridge:java`、`:app-service:{runtime,scheduler,script-repo,permission-center,packager}`、`:platform:{capabilities,system}`、`:engine:node-process`、`:ui`、`:app`）+ archUnit + `bridge/js` 的 npm test，跑在 ubuntu-latest（JDK 17 + Gradle 8.9 + Android SDK license）。Android assemble 走后续 `node-runtime-build/Dockerfile`。
- **可用 GitHub Actions 跑远端 CI**：远端 `origin` = `git@github.com:Ventus-Pluviam/NodeScript.git`（私有仓，SSH 可推）。`ci.yml` 在 `push→main` 与 `pull_request` 时触发——把分支 `git push origin <分支>` 后开 PR 即跑全套门（JVM 单测 + archUnit + npm test），不用等合入 main 才知道红绿。本机 `gh` token 若无该私仓权限（`gh pr create` 报 404/解析不到仓库），用 push 后远端打印的 PR 链接手动开 PR；看不到 runs 输出时以本机 `./gradlew` 同源复现为准。**不要为触发 CI 直推 main**。
- **本机自测旁路**：`tools/jvm-test.sh [--android-jar] <main-src-roots> <test-src-root>` 单模块编译+跑测；`tools/jvm-test-all.sh [模块名...]` 全模块驱动（逐模块最小依赖）。`--android-jar` 补一份**编译期** android.jar 桩，供 `:app`/`:platform:system` 这类含 `android.*` 源码的模块本机验证——运行期 android stub 会抛异常，所以这些模块的单测必须把 Android 接触面挡在可注入 ops 缝后（写法见 `platform/system/README.md`）。**这是提速旁路，不是权威**：`./gradlew`（AGP/资源/Manifest 合并）本机已可直跑（见本节首条），CI 仍是最终门。**`:ui` 不入旁路**（compose/`@Composable` 没有裸 kotlinc 配方）——它的门 = `./gradlew :ui:testDebugUnitTest`（CI 任务表已列）。详见 `docs/framework-design.md` §6 末。

## 协作纪律（子 agent 必须遵守）

1. **改前先读**：动手前读 `docs/framework-design.md` 对应章节 + 本文件 + `settings.gradle.kts`；未知默认问协调者。
2. **只动自己的模块目录**；`settings.gradle.kts`、`gradle/libs.versions.toml`、根 `build.gradle.kts` 由协调者冻结——需要改先提给协调者。
3. **外科手术式读写**：Grep/Glob 定位，Read 带 offset/limit，Edit 用最小唯一匹配，不整库读代码。
4. **git 提交**：每个逻辑完成点提交，信息 `type(scope): 摘要` + 结尾 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`；不提交无关文件；不 init 仓库（已是仓库）。
5. **契约先行**：接口/DTO 以 `:domain` 骨架为准；别自行发明跨模块类型。
## NDK（本机）

- 本机 NDK：`/root/ndk/android-ndk-r28c`（r28c，与 `node-runtime-build/VERSIONS.env`
  的 `NDK_VERSION` 同源；zip 校验见该管线 §2）。
- 用法：`export ANDROID_NDK_HOME=/root/ndk/android-ndk-r28c` +
  `PATH=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH`，
  直接调 `aarch64-linux-android26-clang(++)`（API 26 = minSdk 冻结值）。
- 本机只做 **C++ 交叉编译验证**（`bridge/native` 的 addon `.so` 能编出 arm64 ELF）；
  APK/AGP `assembleDebug` 本机可直跑（已有 SDK，2026-09-24 已实测出包）；真机红测与生产签名管线仍走 CI。
