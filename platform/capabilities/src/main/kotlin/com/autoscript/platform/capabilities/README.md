# :platform:capabilities —— 能力命名空间语义层

> 设计章节：`docs/framework-design.md` §9.1–9.4 / §12.2 / §12.3
> （§9.2 图像分析面 = `ImagesNamespaceHandler`，本模块第七个桥面）。
> 本文件只记**本模块内部**的分层约定与「真实现怎么接」；跨模块契约以 `:domain` 为准。

## 分层（§12.2「分两层」的上面那层）

```
JS facade (bridge/js/src/a11y.ts …)
        │  bridge invoke('a11y','findOne',…)
        ▼
handler 语义层 ── 本模块  A11yNamespaceHandler / ScreenNamespaceHandler / SystemNamespaces
                         ImagesNamespaceHandler（图像面，单独成文件——无共担门禁）
        │  参数校验 / 信封编码 / 错误码分类（无 Android 接触面）
        ▼
SPI 契约 ────── :domain  UiNodeTreeReader / UiActionExecutor / InputProvider / FrameSource / ImageAnalyzer / SystemContracts
        ▲
        │  实现（两处，别混）
内存可测形态 ──── 本模块  InMemoryUiTree / InMemoryInputProvider / ScreenshotSource(FrameProducer 缝)
Android 真实现 ── :platform:system（SystemSpis.of）与 `device/` 的无障碍服务（生产已接，见下表）
```

挂载发生在装配层：`CapabilityNamespaces.*` 把 handler 折成 `:domain` 的
`NamespaceHandler` → `AppShellKit.assemble` 的注入缝（`a11yHandler`/`screenHandler`/`systemHandlers`，系统/存储/传感器面另有 `datastoreHandler`/`zipHandler`/`settingsHandler`/`notificationHandler`/`clipboardHandler`/`sensorsHandler`/`imagesHandler` 七条独立缝，见 `AppShellKit` KDoc）→ `AppShell.assemble`。

## 铁律在本模块的落点

- **语义层只认 `:domain` SPI**。`A11yNamespaceHandler(tree, actions, input)` 的三个参数是
  `UiNodeTreeReader`/`UiActionExecutor`/`InputProvider`，**不是** `InMemoryUiTree`/
  `InMemoryInputProvider`（后两者只是这两条 SPI 的 JVM 可测形态）。写死内存类的后果是
  「接真实现」必须回头改语义层；只认 SPI 则 Android 侧 `AccessibilityService` 落地时
  改的只有取实现的来源（装配层一次 `CapabilityNamespaces.a11y(...)`，当前落点见 `AppShellKit`）。
  这条由 `A11yNamespaceHandlerSeamTest` 在**编译期**把着：它手写另一套 SPI 实现驱动
  handler，谁把实现专有方法（如 `InMemoryUiTree.nextEvents`）塞回 handler，那个文件先编译失败。
- **树只读、动作走 `UiActionExecutor`**。读（find/children/parent/bounds/attribute）与写
  （click/longClick/setText/scroll/copy/paste/dispose）是两条 SPI，handler 不许把它们混成
  一个参数 —— 真实现里这两者的线程/权限前提不同（读可节流快照，动作要服务在场）。
- **能力门禁不在这里**（§9.5）：`PermissionFacade` 住 `:app-service:*`，由装配层先判后取。
- **诚实上报**：`false`/`null` 是答案（起不来、不可滚动、空剪贴板）不是异常；缺实现就
  **不提供**（装配侧留 null → 桥回 `ERR_NOT_IMPLEMENTED`），绝不塞凑数实现。

## Android 接触面

本模块源码里**没有** `import android.*`（`ArchitectureTest` 把 `android..`/`androidx..`
整包列进黑名单），因此全部 JVM 单测都不需要 `--android-jar`。Android 侧的落点：

| 能力 | 语义层（本模块） | 真实现落点 |
|---|---|---|
| a11y | `A11yNamespaceHandler`（只依赖 `:domain`） | 生产已接（`device/AutoScriptAccessibilityService` + `A11yServiceHolder` + `SystemA11yBridge`：`AndroidUiTree`/`AndroidGestureInput`；服务未连桥如实 `ERR_SERVICE_DISABLED`） |
| screen | `ScreenNamespaceHandler` + `ScreenshotSource`（333ms 节流/会话/分类错误） | a11y 截图路径生产已接（`AndroidFrameProducer` 经 `SystemA11yBridge`）；MediaProjection 高清会话仍待（换 producer 即插，§9.2） |
| 系统五个 | `SystemNamespaces`（五个 handler） | `SystemSpis.of(context)` 给四件（`shell`/`device`/`app`/`floatingWindow`）；`dialogs` 生产已接（`AndroidDialogHost` + `device` 子包 `SystemDialogOps` 住本模块，构造在 `PlatformWiring.of`） |
| 存储三个（§9.6） | `DatastoreNamespaceHandler` / `ZipNamespaceHandler` / `SettingsNamespaceHandler`（三个独立注入缝，不入 `systemHandlers` 束） | `SystemSpis.of(context)` 三件齐（`AndroidDataStore`/`JdkZipArchiver`/`AndroidSystemSettings`）；生产已接（`PlatformWiring.of` → `inject` → `installWithFiles` 喂独立缝） |
| 通知 | `NotificationNamespaceHandler`（独立注入缝 `notificationHandler`；参数口径在本层，`POST_NOTIFICATIONS` 门禁在 SPI） | `SystemSpis.Bundle.notification` = `AndroidNotificationPoster`+`NotificationOps`（默认 channel 归实现，契约不暴露 `channelId`） |
| 剪贴板 | `ClipboardNamespaceHandler`（独立注入缝 `clipboardHandler`；读空裸 `null`、写侧无门禁） | `SystemSpis.Bundle.clipboard` = `AndroidClipboard`+`ClipboardOps`（与 a11y 剪贴板同口径 `coerceToText`）；生产已接（同存储三件） |
| 传感器 | `SensorsNamespaceHandler`（独立注入缝 `sensorsHandler`；拉取式游标 `drain`，`on('change')` 只是 facade 节流轮询；delay 缺省 `NORMAL`） | `SystemSpis.Bundle.sensors` = `AndroidSensorSource`+`SensorOps`（P0 只做 motion/environment 名单；未知名→`ERR_NOT_SUPPORTED`、系统拒收→`ERR_SERVICE_DISABLED`）；生产已接（同存储三件） |
| 图像面（§9.2） | `ImagesNamespaceHandler`（独立注入缝 `imagesHandler`；`decode`/`matchTemplate`/`findImage`/`findColor`/`release` 五方法，阈值一个键 `threshold`、域 `[0,1]`，未匹配回裸 `null` 不是异常；`findColor` 的 `color` 恒四分量 `[r,g,b,a]`、`tolerance` 逐分量 `[0,255]`、`region` 四元组可选，未命中同样回裸 `null` 而“扫过 0 像素”是 `ERR_INVALID_PARAM`；**单独成文件、刻意不住 `SystemNamespaces.kt`** —— 那五个共担 OVERLAY/ROOT/ADB_INPUT 门禁组，图像面没有门禁） | **生产已接**（2026-09-25）：`PlatformWiring.of` 构造 `NativeImageAnalyzer.of(JniOps.loadOrNull())`（`:platform:system`，装载 `:bridge:image` 的 `libopencv.so`）；so 缺位 → null → 桥对 `images.*` 如实 `ERR_NOT_IMPLEMENTED`，绝不塞一个看不见像素的假分析器。帧表自管（`ScreenshotSource` 同套纪律：单调 refId + generation 恒 1 + `Mutex` 串行闸），`HandleRegistry` 住 `:bridge:java`、本模块黑名单碰不到 |
| 图像面宿主机语义门禁 | `bridge/image/test/cpp/run-host-tests.sh`（OpenCV 4.14.0 同 commit x86_64 静态库直链 `imgnative.cpp`；`host_color_test` 36 例 + `host_decode_norm_test` 21 例） | 不在这份 README 的门禁范围（它是 C++ 面、住 `:bridge:image`）：补在这儿只为「图像面有第三道门」这件事有据可查。NDK `-fsyntax-only` 只管 aarch64 能编、它管判读对，两者互不替代（2026-09-25 它抓出 `IMREAD_COLOR` 丢 alpha 导致 findColor 的 a 分量从未参与判定）|

> 图像面这道门不替代 JVM/JS 双侧契约：前者证**像素判读对**，后者证**wire 形状与错误码**。

## 尚未实现（别在文档里写成「差不多能用」）

MediaProjection 高清会话真实现、root/Shizuku 输入通道（§9.3 P1）、
`images` native 面的剩余算子（灰度/裁剪/缩放/旋转/特征 —— §9.2：桥面五方法与
`:domain` `ImageAnalyzer` SPI 已就位，`libopencv.so` 的 `decode`/`matchTemplate`/`findColor`
也已接，缺的是那些还没开桥面的操作）。计算核判读的宿主机门禁已补上（`run-host-tests.sh`，57 例）—— 新算子落地时**先补它的 host 断言再上真机**，否则又是一次「三门全绿、alpha 从来没参与判定」。
