# :platform:system —— 系统侧 SPI 实现

> 设计章节：`docs/framework-design.md` §9.4 / §9.6 / §12.2。
> 本文件只记**本模块内部**的分层约定与新增实现的落点；跨模块契约以 `:domain` 为准。

## 分层（§12.2「分两层」的下面那层）

```
JS facade (bridge/js/src/extras.ts)
        │  bridge invoke('shell','exec',…)
        ▼
handler 语义层 ── :platform:capabilities  SystemNamespaces.kt
        │  参数校验 / 信封编码 / 错误码透传（无状态）
        ▼
SPI 契约 ────── :domain  com.autoscript.domain.system.SystemContracts
        ▲
        │  实现
实现层 ──────── :platform:system  ← 本模块（有状态：句柄、收尸、权限现场）
```

本模块**不挂 Router、不依赖 `:bridge:java`**（archUnit 黑名单整包挡住）。
挂载发生在装配层：`SystemSpis.of(context)` 造实现 → `CapabilityNamespaces.{shell,…}`
转接成 `NamespaceHandler` → `AppShell.assemble(systemHandlers = …)`。

## 每个实现的写法（照抄这套，别自创）

Android 调用面**只有一小块**，把它挡在一个可注入的 ops 缝后面，其余逻辑全部留给本机 JVM 单测：

| 实现 | Android 接触面（ops 缝） | 本机可测的语义 |
|---|---|---|
| `AndroidShellExecutor` | `ProcessLauncher`（`Runtime.exec`） | 超时强杀、收尸、双流并发读干、argv 形态 |
| `AndroidDeviceInfoProvider` | 两个 lambda（`Build.MODEL`/`SDK_INT`） | 空型号守卫透传 |
| `AndroidAppLauncher` | `AppOps` + `ForegroundEvents` | false/null 语义、前台事件选择 |
| `AndroidFloatingWindowHost` | `FloatingWindowOps` | 发号、close 幂等、未知/跨代句柄分辨 |
| `AndroidDataStore` | `KvOps`（真机 `SqliteKvOps`） | 空白键拒写不碰 ops、事务暂存→**恰好一次** `applyAll`、block 抛错 → ops **零调用**（零调用即回滚）、行编解码 `KvRowCodec`（kind 显式裁定 + 访问器按 kind 惰性） |
| `JdkZipArchiver` | **无**（`java.util.zip` 纯 JVM，整类真 IO 进单测） | zip-slip 先验后写（全包校验完才落字节）、目录/空目录往返、压缩 tmp+rename 原子落位、垃圾包如实 ERR_IO |
| `AndroidSystemSettings` | `SettingsSystemOps`（`android.provider.Settings`） | 写前 canWrite 门（未授 → ERR_PERMISSION_DENIED 非 false）、已授权仍拒 → ERR_IO、读侧缺失 null 不拿 0/空串冒充、空白键拒 |
| `AndroidNotificationPoster` | `NotificationOps`（`android.app.NotificationManager`） | 发前 canPost 门（未授 → ERR_PERMISSION_DENIED **非 false** —— 系统被拒时不抛异常直接丢弃，门禁必须在它前面）、空白正文拒、cancel 无回执（契约回 Unit 不编 Boolean）、默认 channel 懒建 |
| `AndroidClipboard` | `ClipboardOps`（`android.content.ClipboardManager`） | 读空/后台受限 null 原样透传不编错误码、写侧无门禁不设探针、空串是真值（与 a11y 剪贴板同口径 `coerceToText`） |
| `AndroidSensorSource` | `SensorOps`（`android.hardware.SensorManager`） | 名归一化（大小写/空白/别名收敛）+ 发号（refId 单调递增/generation 恒 1）+ 有界环（超界丢最旧 seq 空洞可见）+ 注销纪律（已知已关幂等/未知跨代 STALE 可分辨）+ 系统事实折叠（未知名或缺席 NOT_SUPPORTED/`start` 拒收 SERVICE_DISABLED 失败不占号） |
| `NativeImageAnalyzer` | `JniOps`（`System.loadLibrary("imgnative")` + 三个 external 方法） | 句柄发号（refId 单调/generation 恒 1）+ native 帧号↔refId 对照表（release 先删表再放 native，匹配即 STALE）+ 状态码原码对表（STALE/FILE_NOT_FOUND/IO 不折叠）+ so 缺位 → 构造回 null（装配层不喂，桥回 NOT_IMPLEMENTED） |

真机 ops 实现分别住 `WindowManagerOps.kt` / `PackageManagerOps.kt`（这两个文件里有真
`WindowManager`/`PackageManager` 调用，本机 JVM 只编译、不执行）。

## 铁律在本模块的落点

- **铁律 3（每次操作有 TTL）**：`AndroidShellExecutor` 的超时是**实现者义务** ——
  到点 `destroyForcibly` 并抛 `ERR_TIMEOUT`，不是返回半截输出，更不是继续挂着。
- **诚实上报**：`app.launch` 回 `false`、`currentPackage` 回 `null` 都是**答案**不是异常；
  未注入的实现**不提供**（注入侧留 null → 桥回 `ERR_NOT_IMPLEMENTED`；`dialogs` 生产已接，
  构造在 `PlatformWiring.of`，单测缺省仍 null），绝不塞一个凑数实现。
- **能力门禁不在这里**（§9.5）：`PermissionFacade` 住 `:app-service:*`，由装配层先判后取；
  本模块只处理"系统在调用现场拒绝"这一事实（折成分类错误或 null）。

## 尚未实现（别在文档里写成"差不多能用"）

`dialogs` 的 `DialogHost` 实现按 domain KDoc 住 `:platform:capabilities`（`AndroidDialogHost` + `device` 子包 `SystemDialogOps`），构造在 `PlatformWiring.of` —— 生产已接；本模块仍不造 `dialogs`（平台模块间无依赖边）。
（datastore：`AndroidDataStore` + `SqliteKvOps`；zip：`JdkZipArchiver`；
settings：`AndroidSystemSettings` + `SettingsSystemOps`；notification：`AndroidNotificationPoster` + `NotificationOps`；
clipboard：`AndroidClipboard` + `ClipboardOps`；
sensors：`AndroidSensorSource` + `SensorOps` —— 入口
`SystemSpis.Bundle.{datastore,zip,settings,notification,clipboard,sensors}`；生产已接
（`PlatformWiring.of` → `inject` → `installWithFiles` 喂独立缝）。）

**`images` 的图像分析面：SPI 实现住本模块、装配归 `PlatformWiring.of`**：SPI 是 `:domain`
的 `ImageAnalyzer`，桥处理器 `ImagesNamespaceHandler` 住 `:platform:capabilities`
（§12.2 第七条独立缝），真实现 = 本模块的 `NativeImageAnalyzer` + `JniOps`
（`System.loadLibrary("imgnative")` → `:bridge:image` 的 `libimgnative.so`，
OpenCV 静态链接，构建轨 `node-runtime-build/scripts/build-opencv.sh`）。
所以 `SystemSpis.Bundle` 里**没有** `images` 字段，`PlatformWiring.inject(images = ...)`
是独立的可选参数：`of` 的缺省值 `JniOps.loadOrNull()` so 缺位即 null → 桥回
`ERR_NOT_IMPLEMENTED`（**缺件不喂，不凑数**）。Android 接触面照 ops 表挡在
`JniOps` 后，本模块单测 `NativeImageAnalyzerTest` 注入内存替身跑全部分支。
