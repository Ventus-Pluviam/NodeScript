## 6. Gradle 模块结构与依赖规则

> 批判建议「约 12 个模块、不要过度拆分」，下表为落定清单（**17 个模块**；`:engine:sandbox` 空壳 2026-09-30 已从 settings 摘除、`:app-service:npm` 同日审查步骤 5 自 packager 拆出；
> sandbox 处置见 [`design-decisions.md`](../design-decisions.md) 已推翻表），薄模块已合并（原 4 个 `:platform:*` 合并为 2 个，插件管理器/打包器等薄服务并入对应模块）—
> —拆分的唯一目的是：**让依赖方向能在 Gradle 层面被强制**。

| 模块 | 职责 | 允许依赖 | 所有模块禁止 |
|---|---|---|---|
| `:app` | `AppShellApplication` 启动装配（§4.1 Composition Root）。Compose UI 已拆出为 `:ui`（2026-09-23 落地：launcher 随库 manifest 合并；`:app` 源码零 compose、零 import ui —— Application 实现的是 `:domain` 的 `HostSummary`，装配知识不流向呈现层） | `:app-service:*`、`:domain`、`:ui`、`:engine:node-process`†、`:bridge:java`*、`:platform:capabilities`*、`:platform:system`*（带 * 者仅装配包可用，见右列；† 仅根包 `AppShellApplication` 构造 `engineFactory` 注入，`com.autoscript.shell` 装配包仍禁碰 engine —— :app ArchitectureTest「shell 装配包零跨层泄漏」量化） | 直连 `:platform`/`:bridge` 于装配包之外（**包级例外两则**，均仅限 `com.autoscript.shell`、只做字段级转接无业务逻辑：① 把 handler 挂上 `BridgeRouter` 可依赖 `:bridge:java`；② `SystemSpis`+`CapabilityNamespaces` 生产装配（落点 `PlatformWiring`）可依赖 `:platform:capabilities` 与 `:platform:system`） |
| `:ui` | Compose UI 呈现层：启动 Activity（launcher，manifest 随库合并进 `:app`）、**首屏、能力中心、任务中心与「管理面板 → 控制台（命令面）」已落地**（2026-09-23/24 四页签齐；**2026-10-09 批 84**：控制台由「日志屏」改为**命令面** —— 跑 npm 命令，§10.9 第 3 条；原控制台的日志内容整体搬去管理面板 → 日志管理，日志管理三段 = 系统日志/脚本输出/任务日志）。状态经 `:domain` 的 `HostSummary` 读口现取（`MainActivity` 是装配级接线点）；门 = `:ui:testDebugUnitTest`（CI 任务表，本机同源直跑） | `:domain` | 依赖 `:app` 或业务模块（成环）；承载装配/业务逻辑（呈现层只画快照） |
| `:app-service:runtime` | 执行编排：RuntimeController、EnginePool、Watchdog 仲裁、kill 权威、`engines` 命名空间处理器 | `:domain` | 依赖 UI/Dialog 类、`com.autoscript.bridge..`（archUnit 强制，严于本表的历史约定） |
| `:app-service:scheduler` | 定时/Intent/事件任务、checkpoint 意图日志、runNonce 幂等 | `:domain` | 依赖 RunRecord 之外的引擎细节 |
| `:app-service:script-repo` | 项目/资源/脚本库、assets→filesDir 原子部署（tmp+rename+sha256 校验） | `:domain` | 直访 danger 权限 |
| `:app-service:permission-center` | 权限三态门禁、引导页、降级路径 | `:domain` | — |
| `:app-service:packager` | 模板 APK 改写、签名向导（npm 面 2026-09-30 拆去下一行） | `:domain` | — |
| `:app-service:npm` | npm 安装/审批/镜像验证（`InstallCoordinator`/`ApprovalLedger`/`NpmRegistryVerifier`…）+ `npm` 命名空间 handler（§10；审查步骤 5 拆分——`npm/` 对父包零 import，切分即净） | `:domain` | — |
| `:domain` | **纯 Kotlin 领域：全部 SPI 接口 + DTO + 状态机 + 领域规则** | 无（std 仅） | 禁 Android 依赖 |
| `:bridge:java` | Kotlin Router、RequestRegistry(TTL)、HandleRegistry(generation)、EventBus、transports | `:domain` | 禁 UI |
| `:bridge:native` | C++：N-API addon 控制面（含 JNI glue、TSF 管理、node::Start）、`libnode.so` 装载 | 被引擎宿主进程引用 | 禁 Android 业务 |
| `:bridge:image` | C++：图像分析管线 addon（独立 so `libopencv.so`，OpenCV 4.14.0 静态链接 + kleidicv，不依赖 node；`imgnative.cpp` 计算核（帧表 + ingest/decode/release + 灰度/裁剪/缩放/旋转/取色）+ `imgnative_match.cpp`（matchTemplate 族 + needle/scene 两张 prep 缓存）+ `imgnative_feature.cpp`（ORB 特征族）+ 四件共享面 `imgnative_internal.h`（帧表互斥量/查帧/区域解析/派生缓存失效的显式 API —— C++ 匿名 namespace 的状态**每个 TU 一份**，跨 TU 共用必须走这层，否则 match 族会摸到空帧表），`images_jni.cc` 装载面，构建轨 `node-runtime-build/scripts/build-opencv.sh` + `.github/workflows/image-native.yml`；宿主机语义门禁 `bridge/image/test/cpp/`，**422 例**直链同 commit OpenCV 跑像素断言（拆 TU 前后逐例同值），覆盖 ingest 26 + findColor 36 + decode 归一 21 + matchTemplate 124 + 灰度 28 + 裁剪 46 + 缩放 45 + 旋转 45 + 特征 51） | 被引擎宿主 + `:main` 分析器引用 | — |
| `:bridge:js` | npm 包：TS facade SDK（运行时导入名 `auto`，产物 `filesDir/node_modules/auto`）、RuntimeChannel、bootstrap loader、d.ts | 仅 npm 依赖 | 禁 Gradle 反向 |
| `:engine:node-process` | `:nodeN` 进程宿主：**`NodeProcessEngine`（Kotlin spawn：ProcessLauncher 缝 + env 契约 + pid/状态语义，实现 `:domain` 的 `ScriptEngine`）**、main.cpp、Node config、JNI 注册 | `:bridge:native`、`:domain` | 禁 Android SDK UI；Kotlin 侧禁 `com.autoscript.bridge..`/`appservice`/`platform`（ArchitectureTest 量化） |
| `:engine:sandbox` | QuickJS 宿主进程 —— **已裁、不进排期**（§18 第 1 项）；模块壳 **2026-09-30 已从 `settings.gradle.kts` 注释摘除**（原「协调者冻结、壳保留」口径已推翻，见 design-decisions），不占模块表；**空壳目录与 settings 里那行注释 2026-10-01 已一并删除**（只此一步是新的，裁撤口径不变，见 design-decisions 同批追加行）—— 复活 = 重建模块目录 + include 行加回 + ModuleGraphTest 允许集登记 | — | — |
| `:platform:capabilities` | **无障碍三面**（2026-09-30 审查步骤 6 收敛；系统面十一件随 handler 迁 `:platform:system`）：`a11y`（`A11yNamespaceHandler` + `AndroidUiTree`/`SystemA11yBridge` 树与动作、`AndroidGestureInput` 输入通道，a11y 服务/UiNodeTreeReader）、`screen`（`ScreenNamespaceHandler` + `ScreenshotSource`/`AndroidFrameProducer` 截图 FrameSource，a11y 路径已接；**`MediaProjectionSource` 高清会话 2026-10-08 已接**；**`MediaProjectionRecorder` 录屏腿 2026-10-08 批 77 亦已接**，与截屏并列共用同一条会话账）、`dialogs`（`DialogsNamespaceHandler` + `DialogHost`：`AndroidDialogHost` 编排 + **设备面全部住 `…capabilities.device` 子包** —— ArchUnit 按包豁免 `android..`，语义层保持纯 JVM）；`CapabilityNamespaces.{a11y,screen,dialogs}` 装配工厂束 + 挂载缝薄转接（模块内重组为 `capabilities/{a11y,screen,dialogs,device}/` 子包） | `:domain` + 系统 API | 禁服务逻辑；禁直连 `com.autoscript.bridge..`（挂载缝类型住 `:domain`，见 §12.2） |
| `:platform:system` | **系统面十一个命名空间的 handler + SPI 实现 + 能力专用契约**（2026-09-30 审查步骤 6：handler 归位实现模块，原「语义层不在 system」口径反转，见 design-decisions）。**子包按命名空间对齐（2026-10-01 D3）**：`shell/` `device/` `app/` `floatingWindow/` `datastore/` `zip/` `settings/` `notification/` `clipboard/` `sensors/` `images/` `power/` 十二子包，与 `:platform:capabilities` 的 `a11y/ screen/ dialogs/ device/` 同构 —— 每个子包里**契约 + ops 缝 + Android 实现 + handler** 四件同住，测试树逐包镜像；**handler/工厂**：十一件 handler 各住自己子包（`ShellNamespaceHandler`/`DeviceNamespaceHandler`/`AppNamespaceHandler`/`FloatingWindowNamespaceHandler` 原为 `SystemNamespaces.kt` 的「四内」，D3 拆出）+ 根包 `SystemNamespaces.kt` 十一工厂束 + `power/` 电源面 `PowerManagerNamespaceHandler`（步骤 6d，不经工厂束）；**契约（步骤 6a 自 `:domain` 迁入，grep 判据仅 handler+impl 消费）**：`shell/ShellContracts.kt`（原 `SystemHostContracts.kt` 的 shell 面）、`device/DeviceContracts.kt`、`app/AppContracts.kt`、`floatingWindow/FloatingWindowContracts.kt`（同前，D3 按面拆开）+ `clipboard/`/`notification/`/`sensors/`/`zip/`/`settings/` 各自的 `*Contracts.kt` —— `DialogHost` 六型与 `DataStore` 系**反例留 `:domain`**（装配层生产读面 / 非能力专用）；**Android 实现**：`SystemSpis.of` 十件入口（`Runtime.exec`/`Build`/`PackageManager`/`WindowManager`/SQLite/`java.util.zip`/`Settings.System`/通知/剪贴板/传感器）+ `power/WakeLock.kt`（`WakeLockOps`/`WakeLockLedger`/`AndroidWakeLockOps`，§8.7，步骤 6d）+ `images/NativeImageAnalyzer`+`JniOps`（`System.loadLibrary("opencv")`，so 缺位即不构造，不住 `SystemSpis.Bundle` —— `images` 是独立可选参数，构造归 `PlatformWiring.of`）；`dialogs` **不在本模块**（`DialogHost` 实现住 :platform:capabilities，平台模块间无依赖边，构造归 `PlatformWiring.of`） | `:domain`（挂载缝 `NamespaceHandler`/`HandleRef`、`KeepAliveRenew` 窄缝、`DomainJson`；契约除步骤 6a 迁入者外仍以 `:domain` 为家） | 禁服务逻辑；禁直连 `com.autoscript.bridge..`（本模块不挂 Router，挂载在 `:app` 装配包） |
| `:node-runtime-build` | **构建管线（不打包进 APK）**：Node 源码 recipe、NDK 编译、16KB 对齐门禁、产物 hash | CI 脚本 | — |

架构测试（archUnit）进 CI：验证「领域层零 Android import」「`:app` 非装配包不直连平台」「依赖方向无环」。
前者由各模块内 `ArchitectureTest` 按字节码校验（`ClassFileImporter().importPackages(...)`）；后两者由
`:domain` 的 `ModuleGraphTest` 按 build.gradle.kts 依赖边校验 —— 空模块（尚无源码）同样被覆盖，
且能拦住 Gradle 层反向依赖与依赖成环。

**例外不是开后门**：`:app` 碰 `:bridge:java` 与 `:platform:capabilities`/`:platform:system` 都只发生在 `com.autoscript.shell` 一个包（后者是 `SystemSpis` + `CapabilityNamespaces` 的生产装配，落点 `com.autoscript.shell.PlatformWiring` → `AppShellApplication.installWithFiles` 喂 `AppShellKit.assemble`）；`:platform:capabilities` 挂 Router 只碰 `:domain` 的 `NamespaceHandler`。
越界由 `:app` 的 `ArchitectureTest` 量化执行（shell 之外的 :app 类碰 platform/bridge 即红）+ `:domain` 的 `ModuleGraphTest` 按 build.gradle.kts 依赖边校验，不是口头约定。

**本机自测（与 CI 逐字同源，无第二口径）**：`./gradlew` 单模块任务即快速门（纯 JVM `:x:test`、
android 模块 `:x:testDebugUnitTest`；全量命令见 [`../../README.md`](../../README.md) 测试段）。
「skipped/aborted ≠ 绿」由约定插件 `autoscript.test-guard`（`build-logic/`）承接：测试出现跳过即红，
环境门禁类（`TestGuard.ENV_GATED`）与 `-PallowSkipped=<类名>` 是仅有的两条放行路。

本机门里跑的是**可 mock 的 android.jar 桩**：`android.*` 方法体一调就抛 `RuntimeException`（"not mocked"），所以含 Android 源码的模块要做到「本机可测」，
必须把 Android 接触面挡在可注入的 ops 缝后面（模式与落地清单见 `platform/system/README.md`）。`./gradlew` 是唯一权威（AGP/资源合并/Manifest 合并只有它能验），改动以 CI 绿为准。

---

