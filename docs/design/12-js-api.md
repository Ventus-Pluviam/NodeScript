## 12. JS API 设计

### 12.1 设计原则（对齐 AutoJsPro v9 二代 API 风格）
- **Promise 优先**：`await` 一切；同步语义的系统能力（如纯计算）由明确的同步函数提供（`images.format` 等纯函数）。
- **EventEmitter 事件**：a11y 事件、引擎事件、截图流、数据流统一 EventEmitter。
- **超时/取消**：`{timeout}` 选项默认给；返回 Promise 的可选 `AbortSignal`（图形接口）。
- **唯一入口**：脚本 `require('auto')` 返回命名空间根对象（`auto.a11y` / `auto.engines` / …），结构化维护 API。**落位**（2026-09-24 资产交付轨）：装配期 `BridgeDistDeploy` 把随包 dist 放进 `filesDir/node_modules/auto`（`ScriptPaths.autoModuleRoot` 单一出处；无 package.json 走 `index.js` 缺省入口），每个项目脚本沿目录树向上第 3 站解析到；
  桥 handler 由打包入口 kBootstrap 的 `attachNative` 在脚本前装上（见 §12.4 切片路线）。
  **用户向 API 参考**（逐方法签名与说明；本卷只讲设计）：[`docs/api/`](../api/index.md) —— 由 `bridge/js` 的公开注释经 typedoc 生成（backlog C7），**生成物**、手改无意义。
- **错误码**：`ERR_*` 目录 + `instanceof AutojsError`，可 try/catch 策略化。
- 兼容垫片：对知名差异（如 `uc_obj` 语义）通过 `compat` 标志位提供，**不反向攻坚原生语义**。

### 12.2 命名空间清单（对应 AutoJsPro v9，含设计说明）
- `auto.a11y` —— 无障碍（选择器/控件/手势/事件 + 输入通道选路 `setInputChannel`，§9.3）
- `auto.ui` / `auto.ui.activity / layout / view / web / res / selector`
- `auto.engine`（自身引擎）/ `auto.engines`（多引擎, `engines.getEngine/exec/stop/channel`）
- `auto.screen`（`capture()` 截图 / `ScreenCapturer` 会话）/ `auto.images`（OpenCV 图像）
- `auto.floatingWindow`（悬浮窗）/ `auto.dialogs`（对话框，降级到 overlay path）
- `auto.device` / `auto.app` / `auto.shell` / `auto.rootAutomator`
- `auto.clipboard` / `auto.notification` / `auto.sensors` / `auto.media`
- `auto.datastore` / `auto.settings`  / `auto.zip`
- ~~`auto.ocr`（P1）~~ **不内置（2026-09-26 拍板，见 §9.7）** / `auto.plugins`（P2）/ `auto.workManager`（定时/Intent 任务）
- `auto.power`（脚本电源：`power_manager` 桥面的 `acquire`/`release`/`status`，§8.7）
- `auto.npm`（包管理与依赖生态，§10：install/ci/list/audit/offlineGap/importOfflineBundle/requestApprove——审批人机分离；事件面 `onProgress`/`onApproval`/`onWarning`/`onFinished` 四方法，wire 走 `events`/`approvals` 两个拉取口而非推送，§10.7）
- Node 内建：`fs/path/http/os/process` 等**完整可用**（除 `child_process` 显式报 `ERR_NOT_IMPLEMENTED`）；facade 侧 SDK（`require('auto')`，见 §12.1/§12.4）。

**接线现状（Kotlin 侧，与 `AppShell.assemble` 对齐；未列出的命名空间在两侧都还没有 handler）**：

> 下表第四列（挂载状态）是**状态**，权威台账见 [`design-status.md`](../design-status.md)。
> 本表保留第四列是为了就近阅读；两者不一致时，以台账（日期更晚）为准，并回来改本表。

> 本表有**机械化门禁**：`bridge/schema/wire.schema.json` 是 wire 面单一事实来源（审查步骤 7），`bridge/js/test/wire-schema.test.cjs` 四向对账（不经 mock）——facade `invoke` 的命名空间/
> 方法必须在 schema 表内、生产源 `register` 集合与 schema 键**双向相等**、19 个 handler 的 `methods()` 申报（单源指生成物 `WireMethods.BY_NS`）与 schema 键双向相等、
> schema 方法必须被 facade 发或登记在 schema `aliases`（收了没人发的 wire 名要么 facade 漏调、要么写进 schema `aliases` 并说明为什么，且不许虚报）；生成物 `wire-types.ts`/
> `WireMethods.kt` 由 `generate.mjs --check` 钉同步（CI 另跑 `npm run gen:wire && git diff --exit-code`）。它是 `npm test` 的一部分，随 CI 跑；a11y 选择器动作经 `call('<m>')` 字面量（schema `dynamicSinks` 登记）与 `findOneOrNull`、
> `shell.shell()` 两条 alias 是仅有的登记（原「正则啃 `when` 块」的 `wire-reconcile.test.cjs` 已删，底账换 schema）。 同族另有三道：`event-wire.test.cjs`（npm **事件面** wire 逐字对账—
> —宿主 `phaseWire`/`kindWire`/`actionWire`/`type` ⇄ `npm.ts` 的 `PHASES`/`WARNING_KINDS`/`APPROVAL_ACTIONS`/`routeInstallEvent` 分支双向集合相等，防 `.name.lowercase()` 折出 `post_check` 那类连字符漂移），
> 并钉**键名面**——`encodeEvent`/审批 `mapOf` 发的每个键 ⇄ JS `w.x` 读的键逐分支对账，JS 读宿主不发的键即红、宿主发了没人读的键须登记 `UNREAD` 并写明理由（mock 测试发的永远是 JS 自己认识的键，
> 键名漂移只有这道门能抓）与 `err-catalog.test.cjs`（错误目录三面对账，见 §7.6）；`wiring-table.test.cjs`（**本表 ↔ schema** 对账：facade 列点名的 `.ts` 真存在且与 schema `facade` 字段一致、
> handler 列点名的类真存在（花括号组展开，且每行至少认出一个候选防改名绕过）、行覆盖与 schema 键**双向相等** + 状态列写「已挂/已可挂」的必须在 schema 里—
> —表是手写的，§19 又宣布它为事实来源，就该有门看着；`register`↔schema 的那半由 wire-schema 门钉）；`pull-wire.test.cjs`（**另两条拉取环** a11y.events / sensors.drain 的回包键名 + 入参键名对账—
> —入参 `sinceSeq` 改名的失败面是宿主读不到、游标恒 0、事件重复投递，不报错只出错数据）。

| 命名空间 | JS facade | Kotlin handler | 挂载状态 |
|---|---|---|---|
| `console` | `console.ts` | `ConsoleCollector`（`:bridge:java`） | `AppShell.assemble` 已挂；`:ui` 控制台屏读口已接（`HostSummary.console`，§7.3 末） |
| `engines` | `engines.ts` | `EnginesNamespaceHandler`（`:app-service:runtime`） | 已挂（含 `heartbeat` 打点，§8.4；命名通道 `channel/channelEmit/channelDrain/channelClose` 双侧对齐：Kotlin 侧缓冲 + 游标、`EngineChannel` 按 `sinceSeq` 节流轮询；`status` 只读在途表、结算后 `ERR_NOT_FOUND` 不伪造 `STOPPED`，`exec` 回 `EngineSessionImpl`（`cancel`→`stop` 归口、`onExit`→`status` 轮询：本会话 cancel 后结算报 null、外部结算报 UNKNOWN），**`exec` 的 `timeoutMillis` 必填**（§8.6 期限线：缺席/`null`/`<= 0` → `ERR_INVALID_PARAM`；到点由看门狗落 `KillCause.TIMEOUT` → `onExit` 报 `{cause:'UNKNOWN'}`）） |
| `a11y`（+ `setInputChannel`，§9.3 三通道选路） | `a11y.ts` | `A11yNamespaceHandler`（`:platform:capabilities`）+ `CapabilityNamespaces.a11y(tree, actions, input, events, channels)` 装配缝（树/动作/输入/事件四 SPI + 通道表）+ **Android 真实现** `AndroidUiTree`/`AndroidGestureInput` 经 `SystemA11yBridge`（`A11yServiceHolder` 连接态）；`adb`/`root` 两条走 `ShellInputProvider`（`:platform:system`，`su -c` / Shizuku 远端进程） | **生产已接**：`PlatformWiring.inject` → `a11yHandler` → `AppShellApplication.installWithFiles`（服务未连 = 桥如实 `ERR_SERVICE_DISABLED`，装配期即可注入不必等 `onServiceConnected`）；内存实现仍是单测缺省；未注入缝保留 → 仍如实 `ERR_NOT_IMPLEMENTED`。**通道表**：`auto` 恒登记；`root` 恒登记（走既有 `su -c`，真无 root 时命令自己失败）；`adb` **只在 `ShizukuInput.isAvailable()` 为真时登记**（装了 + 服务活着）—— 未登记即调用方拿 `ERR_PERMISSION_DENIED`，**不回落 auto**。会话通道经 `InputChannelSession` 随桥连接隔离（`NewlineFrameServer` 每连接建一个） |
| `screen` | `images.ts` | `ScreenNamespaceHandler`（`:platform:capabilities`）+ `ScreenshotSource`（333ms 节流/§8.8 策略预检/句柄记账）+ **Android 真实现** `AndroidFrameProducer`（经 `SystemA11yBridge.takeScreenshot`：API34+ 窗口级、API30–33 显示级、API<30 如实 `ERR_NOT_IMPLEMENTED`；失败码分类 SECURE→BLACK_FRAME/限频→INVALID_PARAM/通道失效→SERVICE_DISABLED/内部→ERR_IO） | **生产已接**：`PlatformWiring.screenHandler` → `AppShellApplication.installWithFiles`（与 a11y 同底：服务未连 = `ERR_SERVICE_DISABLED`）；**回包尺寸 = 系统真值**（`ProducedFrame` 随帧走，不再固定 1080×2400）。~~MediaProjection 高清会话仍待（换 producer 即插）~~ **已接（2026-10-08，批 75）**：`MediaProjectionSource` 经同一条 `PlatformWiring.screenHandler` 缝进 `screen` 命名空间；~~**录屏仍待**~~ **录屏亦已接（2026-10-08，批 77）**：`screen.startRecording`/`stopRecording`（`MediaProjectionRecorder` + VirtualDisplay → 视频文件，落 `files/scripts/<projectId>/.recordings/`） |
| `images`（decode/matchTemplate/findImage/findColor/toGrayscale/crop/resize/rotate/findFeature/release —— 十方法，2026-09-29 起） | `images.ts` | `ImagesNamespaceHandler.kt`（`:platform:system`，经 `SystemNamespaces.images(analyzer)` 转接；SPI = `:domain` `ImageAnalyzer`+`ImageFrame`/`ImageMatch`，真身 = `:platform:system` 的 `NativeImageAnalyzer`/`JniOps` + `:bridge:image` 的 `libopencv.so`，2026-09-25 已接） | **桥面已可挂**：`assemble` 的 `imagesHandler` **独立缝**（同 datastore/zip/settings/notification/clipboard/sensors —— 图像面无共担门禁：读图是应用私有目录内 IO、匹配是纯计算，`ERR_FILE_NOT_FOUND`/`ERR_STALE_HANDLE` 判据在 SPI；不入 `systemHandlers` 束；未注入则如实 `ERR_NOT_IMPLEMENTED`；`AppShellKit.assemble` 透传同一缝）。**生产侧已喂**（2026-09-25）：`PlatformWiring.of` 构造 `NativeImageAnalyzer.of(JniOps.loadOrNull())` 传 `inject(images = …)` —— so 缺位（未跑 `build-opencv.sh` 的 CI JVM / 无 native 的设备）→ null → 桥对 `images.*` 如实 `ERR_NOT_IMPLEMENTED`（一个看不见像素的内存分析器只能靠自报坐标假装匹配成功，那比没有更坏 —— 这条防线从"不喂"变成"缺件不喂"，语义不变）。两侧钉子：`ImagesNamespaceHandlerTest` + `images.test.cjs` + `NativeImageAnalyzerTest` |
| `dialogs`/`shell`/`device`/`app`/`floatingWindow` | `extras.ts` | `DialogsNamespaceHandler`（`:platform:capabilities`，经 `CapabilityNamespaces.dialogs` 转接）+ `SystemNamespaces.{Shell,Device,App,FloatingWindow}NamespaceHandler`（`:platform:system` —— 2026-09-30 步骤 6 handler 归位实现模块） | **生产已接**：`com.autoscript.shell.PlatformWiring.of(context)`（§6 包级例外二）把 `SystemSpis.of` 十件拼成 `systemHandlers` 束 + 七独立缝，`AppShellApplication.installWithFiles` 喂 `AppShellKit.assemble`（七个字段各自可空，未注入仍如实 `ERR_NOT_IMPLEMENTED`；`dialogs` **生产已接** —— `PlatformWiring.of(context)` 构造 `AndroidDialogHost(SystemDialogOps(...))`（实现住 :platform:capabilities，`inject` 单测缺省不传仍 null→`ERR_NOT_IMPLEMENTED`）。SPI 侧 `shell`/`device`/`app`/`floatingWindow` 四件走 `:platform:system` 真实现；JS 双侧契约见 `extras.test.cjs`（mock 宿主验 wire 形状）。**参数面已收口（2026-09-26，原 §12.3.3 记的两处缺口）**：`floatingWindow.create` 三处一起改齐 —— facade 把 `{title,width,height}` 原样发 payload（缺省显式 `null`，不静默删键）+ 补 `close({ref})`，与 handler 的 payload 要求对上；`screen.startCapturer` 的 `{width,height}` 现透给 `FrameSource.openSession`（**请求提示**，回包尺寸仍是真实帧）。两侧各加契约测试（`extras.test.cjs` / `screen.test.cjs` + `ScreenNamespaceHandlerTest`/`ScreenshotSourceTest`），任一侧漂移即红 |
| `datastore` | `datastore.ts`（`get/put/remove/contains/keys/clear`；`get` 拆 `{found,value}` 信封：缺失 `undefined` ≠ 存的 JSON `null`） | `DatastoreNamespaceHandler`（`:platform:system`，经 `SystemNamespaces.datastore(store)` 转接；SPI = `:domain` `DataStore` —— 留 `:domain`：非能力专用、`:app` 测试在读，测试传 `InMemoryDataStore`） | **已可挂**：`assemble` 的 `datastoreHandler` **独立缝**（不入 `systemHandlers` 束 —— 存储面无共担门禁；未注入则如实 `ERR_NOT_IMPLEMENTED`；`AppShellKit.assemble` 透传同一缝）。字节值不过桥（§7.4 side-channel 未接 → `get` 如实 ERR_NOT_IMPLEMENTED）、`transaction` 不上桥（facade 无此方法）；SPI 真身 `:platform:system` `AndroidDataStore`，生产已接（`PlatformWiring.of` → `inject` → `installWithFiles` 喂 `datastoreHandler` 独立缝；`PlatformWiringTest` 同路径真转接覆盖）。双侧钉子：`DatastoreNamespaceHandlerTest` + `datastore.test.cjs` |
| `zip` | `zip.ts`（`compress`/`extract` 两方法，TTL 缺省 60s） | `ZipNamespaceHandler`（`:platform:system`，经 `SystemNamespaces.zip(archiver)` 转接；SPI = 同模块 `ZipArchiver`（步骤 6a 自 `:domain` 迁入，grep 判据仅 handler+impl 消费），真身 `JdkZipArchiver`） | **已可挂**：`assemble` 的 `zipHandler` **独立缝**（同 datastore —— 归档无共担门禁，不入 `systemHandlers` 束；未注入则如实 `ERR_NOT_IMPLEMENTED`；`AppShellKit.assemble` 透传同一缝）。SPI 错误原码透传不折叠；`unzip` 等未约定别名两侧都不提供。双侧钉子：`ZipNamespaceHandlerTest` + `zip.test.cjs`；归档语义（zip-slip、体积上限）钉在 `JdkZipArchiverTest` |
| `settings` | `settings.ts`（`canWrite`/`getString`/`getInt`/`putString`/`putInt` 五方法与 SPI 1:1，读缺失回 `null`；不提供猜型的 `get`/`put`） | `SettingsNamespaceHandler.kt`（`:platform:system`，经 `SystemNamespaces.settings(systemSettings)` 转接；SPI = 同模块 `SystemSettings`（6a 迁入），真身 `AndroidSystemSettings`） | **已可挂**：`assemble` 的 `settingsHandler` **独立缝**（同 datastore/zip —— `WRITE_SETTINGS` 判据在 SPI、与五命名空间无共担门禁，不入 `systemHandlers` 束；未注入则如实 `ERR_NOT_IMPLEMENTED`；`AppShellKit.assemble` 透传同一缝）。双侧钉子：`SettingsNamespaceHandlerTest` + `settings.test.cjs`；授权语义钉在 `AndroidSystemSettingsTest`；生产已接（`PlatformWiring.of` → `inject` → `installWithFiles` 喂 `settingsHandler` 独立缝；`PlatformWiringTest` 同路径覆盖） |
| `notification` | `notification.ts`（`canPost`/`post`/`cancel` 三方法与 SPI 1:1；`post` 未授权**抛** `ERR_PERMISSION_DENIED`、`cancel` 回 void 无回执；不带 `channelId`/actions/`ongoing`） | `NotificationNamespaceHandler.kt`（`:platform:system`，经 `SystemNamespaces.notification(poster)` 转接；SPI = 同模块 `NotificationPoster`+`NotificationSpec`（6a 迁入），真身 `AndroidNotificationPoster`） | **已可挂**：`assemble` 的 `notificationHandler` **独立缝**（同 datastore/zip/settings —— `POST_NOTIFICATIONS` 判据在 SPI、与五命名空间不共担 OVERLAY/ROOT/ADB_INPUT，不入 `systemHandlers` 束；未注入则如实 `ERR_NOT_IMPLEMENTED`；`AppShellKit.assemble` 透传同一缝）。id 必填（§8.5「只发一次」的幂等键目标，不自动发号）。双侧钉子：`NotificationNamespaceHandlerTest` + `notification.test.cjs`；门禁语义钉在 `AndroidNotificationPosterTest`；生产已接（`PlatformWiring.of` → `inject` → `installWithFiles` 喂 `notificationHandler` 独立缝；`PlatformWiringTest` 同路径覆盖） |
| `clipboard` | `clipboard.ts`（`getText`/`setText` 两方法与 SPI 1:1；读空回 `null`，空串是真值；写侧无门禁；不带 `clear`/`hasText`/富文本） | `ClipboardNamespaceHandler.kt`（`:platform:system`，经 `SystemNamespaces.clipboard(clipboard)` 转接；SPI = 同模块 `Clipboard`（6a 迁入），真身 `AndroidClipboard`+`ClipboardOps`） | **生产已接**：`assemble` 的 `clipboardHandler` **独立缝**（同 datastore/zip/settings/notification，不入 `systemHandlers` 束；未注入如实 `ERR_NOT_IMPLEMENTED`；`PlatformWiring.of` → `inject` → `installWithFiles` 喂缝；`PlatformWiringTest` 同路径覆盖）。双侧钉子：`ClipboardNamespaceHandlerTest` + `clipboard.test.cjs`；读写语义钉在 `AndroidClipboardTest` |
| `sensors` | `sensors.ts`（`isSupported`/`register`/`unregister`/`unregisterAll`/`drain` 五方法与 SPI 1:1；拉取式游标不做 push 回调，`on('change')` 只是 facade 节流轮询；delay 缺省 `NORMAL`、wire 传名字面量；`ignoresUnsupported` 只折叠 `ERR_NOT_SUPPORTED`；P0 只做 motion/environment 名单） | `SensorsNamespaceHandler.kt`（`:platform:system`，经 `SystemNamespaces.sensors(sensors)` 转接；SPI = 同模块 `SensorSource`+`SensorDelay`/`SensorEvent`（6a 迁入），真身 `AndroidSensorSource`+`SensorOps`） | **生产已接**：`assemble` 的 `sensorsHandler` **独立缝**（同 datastore/zip/settings/notification/clipboard，不入 `systemHandlers` 束；未注入如实 `ERR_NOT_IMPLEMENTED`；`PlatformWiring.of` → `inject` → `installWithFiles` 喂缝；`PlatformWiringTest` 同路径覆盖）。双侧钉子：`SensorsNamespaceHandlerTest` + `sensors.test.cjs`；采样语义（归一化/发号/有界环/幂等/拒收折叠）钉在 `AndroidSensorSourceTest` |
| `npm` | `npm.ts` | `NpmBridgeHandler`（`:app-service:npm`；步骤 4 起直挂——`mount()` 壳已删） | **已可挂**：`assemble` 的 `npmHandler` 缝（未注入则如实 `ERR_NOT_IMPLEMENTED`；方法表 **14 项**（2026-10-09 批 81 订正：原写「13 项」，与生成物 `WireMethods.kt` / `wire.schema.json` 的 14 条不符 —— 是**数错**，不是漂移，桥面自 2026-09-26 起就是这 14 条） —— 含 `events`/`approvals` 两个事件拉取口，`resolveApproval` 刻意不在桥面，§10.5 人机分离）。**wire 形状已两侧对齐**（原与 `a11y.waitFor` 同类漂移：facade 读宿主从不发的键）：`install` → `:domain` `InstallHandle`（`{handleId,projectId,enqueuedAtMillis}`，非包体）；`audit` 键名 `vulns`；`list` 不带 `sizeBytes`；`offlineGap` 带 `version`；`requestApprove` 校验 + 回显 `scripts`；`InstallEvent.phase` 取 `:domain` 六阶段。钉子在 Kotlin `NpmBridgeHandlerTest` + JS `npm-contract.test.cjs`（mock 逐字复刻宿主回包）；事件面另有 `NpmEventDrainTest` + `npm-events.test.cjs`（游标/环语义与响亮分档，§10.7） |
| `workManager`（create/cancel/list 建任务面） | `workManager.ts`（排期工具 + 桥门面） | `WorkManagerNamespaceHandler`（`:app-service:scheduler`，直驱本模块 `Scheduler`、直写注册表 —— 步骤 6c 自 `:app` 迁入，与 `EnginesNamespaceHandler` 住 runtime 同形态） | **已挂**（恒挂载，调度器是本壳自建、无注入缝；cron 非法表达式桥侧 `ERR_INVALID_PARAM`，校验出处 `CronTab.parse` 与 UI 侧同口径） |
| `power_manager`（acquire/release/status 脚本电源面） | `power.ts`（`acquire`/`release`/`status`） | `PowerManagerNamespaceHandler`（`:platform:system` —— 步骤 6d 迁入，与 `WakeLockLedger` 同模块同一本账（账本随迁、语义零改）；keepalive 走 `:domain` `KeepAliveRenew` 窄缝，`ForegroundKeeper` 实现之） | **已挂**（`powerManagerHandler` **独立缝**，与 datastore/zip/settings/notification/clipboard/sensors 同形、不入 `systemHandlers` 束；生产由 `AppShellApplication.installWithFiles` 经 `PlatformWiring.powerManagerHandler(keeper)` 造好喂缝（根包零 platform 类型，见步骤 6d）；脚本锁必须限时、无期限只属框架；token 服务端分配；取不到锁 `ERR_SERVICE_DISABLED` 且未记账；直驱账本不走 `ForegroundKeeper.start(token)` 单槽） |

**为什么能力命名空间走注入缝**：`a11y`/`screen` 的真实现住 `:platform:capabilities`，而 §6 禁止 `:app` **非装配包**直连 `:platform`（装配包 shell 经包级例外二可直连，
见 `PlatformWiring` —— 但注入缝本身仍是设计答案：`AppShellKit`/`AppShell` 保持纯 JVM 可测，真假实现共用同一条缝）。解法是 `:domain` 上的挂载缝 `NamespaceHandler` + 薄转接工厂束 —
— 无障碍面 `CapabilityNamespaces.{a11y,screen,dialogs}`（`:platform:capabilities`）、系统面 `SystemNamespaces.*`（`:platform:system`，2026-09-30 步骤 6 随 handler 归位），由持有真实现的 Android 侧在调用 `assemble` 时注入；
`BridgeRouter` 的 `RequestHandler` 只是这条缝的 typealias。这不违反依赖规则：两侧都只见 `:domain`。

**五个系统命名空间（`dialogs`/`shell`/`device`/`app`/`floatingWindow`）分两层，别混**（2026-09-30 步骤 6：语义层与实现层**同批归位 `:platform:system`**；原「语义层住 capabilities / handler 不住 system」口径已反转，原文照抄与推翻记录见 design-decisions）：
- **语义层**（handler）住 `:platform:system` 的 `SystemNamespaces.kt`（2026-09-30 步骤 6 与实现同模块；`dialogs` 例外住 `:platform:capabilities` 的 `DialogsNamespaceHandler` —— `DialogHost` 实现按约定在同模块，
  见下一条），纯 JVM 可测（假 SPI 注入即可跑）：参数校验（spec 守卫、必填字段、`timeout > 0`）、枚举字面量解析（`ShellMode`/`DialogMode`，拼错即报错不静默套默认）、
  默认值（shell 超时 30s）、错误分类**透传**（`AutojsException.error` 原码回桥）、响应形状编码（与 `extras.ts` 逐字对齐）；
- **Android 实现层**住 `:platform:system` —— 契约四件 `ShellExecutor`/`DeviceInfoProvider`/`AppLauncher`/`FloatingWindowHost`（`shell/ShellContracts.kt`、`device/DeviceContracts.kt`、`app/AppContracts.kt`、`floatingWindow/FloatingWindowContracts.kt` 各一份；2026-10-01 D3 前是同名的 `SystemHostContracts.kt` 一份四面）（步骤 6a 自 `:domain` 迁入，
  grep 判据仅 handler+impl 消费）+ 实现四件**已落地**（`AndroidShellExecutor`/`AndroidDeviceInfoProvider`/`AndroidAppLauncher`/`AndroidFloatingWindowHost`，入口 `SystemSpis.of(context)`；
  各自只碰一小块 Android，其余在可注入的 ops 缝后面，本机无 SDK 也能跑契约测试），`DialogHost` **住 :platform:capabilities 而非本模块**（domain KDoc 约定 + 平台模块间无依赖边；
  编排 `AndroidDialogHost` 纯 JVM 可测，设备面在 `…capabilities.device` 子包）。**有状态的判断归实现层**：句柄记账与 generation、`close` 幂等、`ERR_STALE_HANDLE`/`ERR_PERMISSION_DENIED` 的起源、
  `DialogMode.AUTO` 按 overlay 可见性选路（降级决策需要 overlay 实况，handler 看不到）。

**原「为什么 handler 不住 `:platform:system`」两层理由已随步骤 6 推翻**（(1) 共担门禁组、(2) 装配层双模块直连 —— 原文照抄与推翻记录见 design-decisions）：
handler 归位实现模块（与 `EnginesNamespaceHandler` 住 `:app-service:runtime` 同形态），共担门禁的校验仍单点住在 `SystemNamespaces` 工厂束（同模块一处，不各写一份），
装配层 `PlatformWiring` 本就经包级例外二同时可见两模块。**§9.6 的存储面（datastore/settings/zip）与这五个命名空间无关**：`datastore` 已单列入上表（handler 住 `:platform:system`、
独立注入缝；SPI `DataStore` 留 `:domain` —— 非能力专用）；`zip` 已单列入上表（SPI+实现+桥面俱全，§9.6）；`settings` 已单列入上表（SPI+实现+桥面俱全，§9.6）—
— 三者都与五个命名空间无共担门禁，已逐条单列；`notification` 是**第四条独立缝**（门禁是 `POST_NOTIFICATIONS`，同样不与那五个共担），故也单列入上表。
`clipboard` 是**第五条独立缝**（剪贴板无门禁，读受限是系统的 null 答案、写不受限，判据在 SPI，同样不与那五个共担），故也单列入上表。`sensors` 是**第六条独立缝**（P0 名单无运行时门禁，
未知名→`ERR_NOT_SUPPORTED`、系统拒收→`ERR_SERVICE_DISABLED` 判据在 SPI，同样不与那五个共担），故也单列入上表。`images` 是**第七条独立缝**（§9.2 图像面：
无运行时门禁，`ERR_FILE_NOT_FOUND`/`ERR_IO`/`ERR_STALE_HANDLE` 判据在 SPI 自己身上）：桥面十方法 `decode`/`matchTemplate`/`findImage`/`findColor`/`release`/`toGrayscale`/`crop`/`resize`/
`rotate`/`findFeature` 已就位（阈值**一个键** `threshold`、域 `[0,1]`、未匹配回裸 `null` 不是异常；找色的 `color` 恒四分量 `[r,g,b,a]`、`tolerance` 逐分量 `[0,255]`、
`region` 四元组，未命中同样回裸 `null`，而“扫过 0 像素”是 `ERR_INVALID_PARAM`），真实现也已接（`NativeImageAnalyzer` + `libopencv.so`，见 §9.2 末）—— 与那六条现在完全同形：
`PlatformWiring.of` 都喂真实现，唯独图像面多一条"so 缺位即不喂"的判据（`JniOps.loadOrNull()`）。

**能力门禁不在 handler 里**：`:app-service:permission-center` 的 `PermissionFacade` 住 `:app-service:*`，而 `:platform:capabilities` 的 archUnit 黑名单含 `com.autoscript.appservice..`（§6）。
门禁由装配层在取用这些 handler 之前完成（`ensure(Capability.OVERLAY)` 等），handler 只负责**能力已保证之后的语义**；被拒时由 `PermissionFacade` 抛带引导文案的 `ERR_PERMISSION_DENIED`，
handler 侧的分类错误（如句柄过期 `ERR_STALE_HANDLE`、服务未启用 `ERR_SERVICE_DISABLED`）原样透传到 JS。

### 12.3 关键签名示例（风格示范）

**本节的口径**：下面每一行都在 `bridge/js/dist` 上真跑过（mock 宿主逐字复刻 Kotlin handler 的回包），不是照 §12.2 的命名空间清单手写的。所以这里同时是 **facade 现状的实测记录** —— 已落地与未落地分开写，未落地的一律按**接口期两侧都不提供**处理（宿主如实 `ERR_NOT_IMPLEMENTED`），示例不写"将来会通"的用法。

#### 12.3.1 已落地的调用（照抄可跑）

```ts
// ── a11y：选择器链（条件之间 AND；findOne 无匹配抛 NotFoundError，findOneOrNull 回 null）
const btn = await auto.a11y.selector()
  .text('启动').packageName('com.example')   // 条件名与 :domain UiSelector 1:1（没有 .package() 这种截断别名）
  .time(2_000)                               // 超时挂在**选择器**上：findOne 未传 timeout 时取它
  .findOne()
await btn.click({ channel: 'auto' });        // UiObject 句柄代理：动作经 invoke 回桥（携带 generation 校验）
                                             // **channel 必填**（§9.3 三通道）：不传且没设过会话值 → ERR_INVALID_PARAM
await btn.bounds;                            // getter 也是桥调用（一次 invoke）—— 循环里逐节点读属性要先想清楚
await btn.dispose();                         // void（fire-and-forget；释放失败不抛给脚本）

// 只问"在不在"：无匹配是控制流不是异常 —— findOneOrNull 回 null（其余错误照常抛）
const maybe = await auto.a11y.selector().text('登录成功').findOneOrNull({ timeout: 5_000 })

// 等到出现为止（回 boolean；超时也回 false，**不抛** NotFoundError）
const ok = await auto.a11y.waitFor(
  auto.a11y.selector().text('登录成功'),
  { timeout: 10_000, interval: 300 })

// 事件流是"拉取式游标"（不是 push 回调）：空增量回 {first:sinceSeq,last:sinceSeq,events:[]}，
// 调用方以前进游标为准 —— 别拿"这轮 0 条"当"界面没变化"（两者不是同一件事）
const batch = await auto.a11y.events({ sinceSeq: 0, batch: 32 })

// 输入通道（§9.3）：auto 无障碍 / adb Shizuku / root su。**三条平级，不是降级链** ——
// 指定哪条走哪条，不可用即 ERR_PERMISSION_DENIED，绝不改用别的通道。
await auto.a11y.setInputChannel('root');     // 会话级：设过之后本脚本可省略 channel（按桥连接隔离）

// 手势：先问能力（false 时走能力中心引导），再派发（通道关门回 false；非法手势抛 ERR_INVALID_PARAM）
if (await auto.a11y.canPerformGestures({ channel: 'auto' })) {
  await auto.a11y.gesture({
    strokes: [{ points: [{ x: 540, y: 1800 }, { x: 540, y: 600 }], durationMillis: 300 }],
  }, { channel: 'auto' })
}
await btn.click();                           // 走上面 setInputChannel 设的会话通道（root）
await btn.click({ channel: 'auto' });        // 单次覆盖：只这一次走无障碍，会话值不变

// ── screen：截图帧源（句柄归 screen 自己发号）
const img = await auto.screen.capture();     // 锁屏 ERR_SCREEN_LOCKED / FLAG_SECURE ERR_BLACK_FRAME /
                                             // 无窗口 ERR_SERVICE_DISABLED / 节流 ERR_INVALID_PARAM（退避重试）
console.log(img.width, img.height);          // 尺寸是**系统真值**（随帧走，不是固定 1080×2400）
await img.recycle();                         // 打 screen/recycle

// 会话式（MediaProjection）：open 时即做策略判定，会话内逐帧拉；close 是连接态（二次关 ERR_NOT_FOUND）
const cap = await auto.screen.startCapturer();          // 也可 {width,height}：**请求提示**，生产者可忽略
// await auto.screen.startCapturer({ width: 720, height: 1280 });  // 回包与帧尺寸仍是真实值，不按提示编
try {
  const f1 = await cap.nextFrame();
  await f1.recycle();
} finally {
  await cap.close();
}

// ── images：图像分析面（**与 screen 共用一张帧表**，§18 第 8 项 (b)，见 12.3.2 第 3 条）
const shot = await auto.images.decode('/sdcard/shot.png');   // 宽高是**文件真值**
const icon = await auto.images.decode('/sdcard/icon.png');
const m = await auto.images.findImage(shot, icon, { threshold: 0.9 });  // null = 没找到（**不是异常**）
if (m) console.log(m.x, m.y, m.width, m.height, m.confidence);

// matchTemplate 与 findImage 是同一个 opencv 概念的 v9 两名（wire 逐字段相同，宿主同一套校验）
const m2 = await auto.images.matchTemplate(shot, await auto.images.fromFile('/sdcard/part.png'),
  { threshold: 0.85 });

// 找色（P1 第一个算子）：null = 扫过了、没有；ERR_INVALID_PARAM = 根本没找（空区域/region 越界）
const px = await auto.images.findColor(shot, [18, 52, 86, 255], 10, { region: [0, 0, 540, 2400] });

// P1 图像桥消费方（2026-09-29 开通）：四算子产新帧（回包与 decode 同形），findFeature 回模板中心
const gray = await auto.images.toGrayscale(shot);                 // 三通道同灰值、alpha 原样带过去
const sub = await auto.images.crop(shot, [0, 0, 540, 2400]);      // region 必须 [x,y,w,h]（缺省 = 参数错）
const small = await auto.images.resize(sub, 270, 1200);           // 目标尺寸（不是倍数），插值固定 LINEAR
const upright = await auto.images.rotate(shot, -90);              // 逆时针角度；expand 画布，宽高随回包
const spot = await auto.images.findFeature(shot, icon);           // 容忍缩放/旋转；null = 场景里没有
if (spot) console.log(spot.x, spot.y, spot.confidence);           // (x,y) 是模板中心（不是左上角）
await small.recycle();                                            // 产出帧与 decode 帧同一条释放路

await icon.recycle();                        // 谁的帧谁来放（images/release）
await shot.recycle();                        // 再放同一帧 → ERR_STALE_HANDLE（不是静默成功）

// ── workManager：定时任务（亮屏+解锁是保底契约；screen 三态显式声明）
const task = await auto.workManager.createTimedTask({
  name: '早安打卡', projectId: 'p1', scriptPath: 'entry.js',
  schedule: auto.workManager.cron('0 9 * * 1'),   // 5 字段 分 时 日 月 周；每周一 09:00
  timezone: 'Asia/Shanghai',
  screen: 'SCREEN_ON',                            // SCREEN_ON / ANY / SCREEN_OFF
});
// 登记前本地预览下一跳（纯本地排期工具，唯一时序来源；段内合法性仍归宿主 CronTab.parse 裁决）
const next = auto.workManager.nextFireAfter(auto.workManager.cron('0 9 * * 1'), Date.now());
await auto.workManager.cancelTask(task.id);       // 幂等：从未登记的 id 照样 true
const tasks = await auto.workManager.listTasks();

// ── power：限时唤醒锁（CPU 不休眠；不碰屏幕亮灭，也不起停前台服务）
const token = await auto.power.acquire(10 * 60_000);  // 超时**必填**（无期限只属框架保活）；token 服务端分配
try {
  await longRunningWork();
} finally {
  await auto.power.release(token);               // 重复放/已过期 → false（如实不对账成功）
}
const lock = await auto.power.status();          // {held, holders}；分歧时 held=false 而 holders>0，不折叠

// ── engines：多引擎（池仲裁；超载排队，不静默丢弃）
const other = await auto.engines.exec({
  projectId: 'p1', scriptPath: 'worker.js',
  timeoutMillis: 5 * 60_000,        // **必填**（墙钟总时长）：没人 await 终结，期限须由调用方声明
});                                  // 缺席/非正 → ERR_INVALID_PARAM（宿主守卫，本层不预检）
const chan = await auto.engines.channel('progress');       // 命名通道**显式打开**（session.channel 恒 null）
await chan.emit('progress', JSON.stringify({ done: 3 }));  // 载荷是 JSON 字符串，不是对象
const sub = chan.on('progress', (payload) => console.log('子脚本说', payload), { pollMillis: 500 });
other.onExit((info) => {                                    // info 是 CrashInfo | null，不是数字退出码
  console.log(info === null ? '干净结束' : `异常：${info.cause}`);
  // 外部结算（看门狗/他人 stop）报 {cause:'UNKNOWN'} —— 结算即离表，不把"查不到"伪造成干净结束
});
await chan.close(); sub.cancel();
await other.cancel();                                       // → engines.stop(runId)，池四步 quiesce
console.log(await auto.engines.poolStats());                // {capacity, free, busy}

// ── dialogs / shell / device / app / floatingWindow
const name = await auto.dialogs.prompt('输入名字', { mode: 'auto' });  // auto：overlay 可见弹窗，否则通知回调
const out = await auto.shell.exec('pm list packages');      // shell 是**命名空间对象**，不是可调用函数；
                                                            // 分级 DENIED 抛 ERR_PERMISSION_DENIED
console.log(out.code, out.stdout, out.stderr, out.truncated);  // 每条流上限 1 MiB，超了 out.truncated === true
                                                            // （截断 ≠ 失败：code 仍是真实退出码；要完整输出请分页/落盘）
console.log(await auto.device.model(), await auto.device.sdkInt());
console.log(await auto.app.launch('com.example'), await auto.app.currentPackage());  // false/null 是**诚实答案**
const win = await auto.floatingWindow.create({ title: '面板', width: 300, height: 200 });  // {refId,generation}；缺省项显式 null
await auto.floatingWindow.close(win);   // 幂等；晚到/重放 → ERR_STALE_HANDLE

// ── datastore / zip / settings / notification / clipboard / sensors（六条独立缝）
await auto.datastore.put('progress', { chapter: 3 });
const saved = await auto.datastore.get('progress');         // 缺键 undefined ≠ 存的 JSON null（不折叠）
await auto.zip.compress('/sdcard/out', '/sdcard/out.zip');  // TTL 缺省 60s（归档可大可慢，5s 默认必超）
const bright = await auto.settings.getInt('screen_brightness');  // 缺键 null（0 是合法亮度，不拿 0 冒充）
if (await auto.settings.canWrite()) await auto.settings.putInt('screen_brightness', 128);
if (await auto.notification.canPost()) {
  await auto.notification.post({ id: 1, text: '脚本跑完了', title: 'AutoScript' });  // 未授权**抛**，不静默丢弃
}
await auto.clipboard.setText('要粘贴的文本');                  // 空串是合法内容，读空回 null
const sub2 = await auto.sensors.register('accelerometer', { delay: 'UI' });  // 未知名抛 ERR_NOT_SUPPORTED
if (sub2) {
  const stop = sub2.on('change', (evs) => console.log(evs[0].values), { intervalMs: 200 });
  await sub2.unsubscribe(); stop();                          // on 只是节流轮询，不是第二套订阅语义
}

// ── npm：跨进程路由到全局安装会话（TTL 绑定，绝不阻塞脚本事件循环）
const handle = await auto.npm.install('axios', { timeout: 60_000 }); // → {handleId, projectId, enqueuedAtMillis}
// 回包只代表**已入队**：宿主此刻还不知道会装出什么版本，回猜的版本号就是伪造（§1）
const installed = await auto.npm.list();                    // 装了什么以 lockfile 为准（含 version）
await auto.npm.ci({ offline: true });                       // lockfile v3 严格重建（验签后）
const gap = await auto.npm.offlineGap();                    // 离线闭包缺哪些包（名+版本+尺寸）
const report = await auto.npm.audit({ offline: true });     // 键名是 vulns（不是 vulnerabilities）
auto.npm.onProgress((e) => console.log(e.phase, e.name, e.percent));  // phase: queued/resolve/download/
                                                                     // reify/post-check/done
auto.npm.onApproval((req) => notify('需人工确认', req.pkg)); // 只能提交请求，绝不脚本直调（人机分离）
auto.npm.onFinished((f) => (f.success ? done() : fail(f.detail))); // 成功+失败都到（install 回包只是已入队）
                                                             // 四条订阅 2026-09-26 起由 events/approvals 拉取口真投递
await auto.npm.requestApprove('evil-pkg', { scripts: ['postinstall'] });  // → {requestId, status:'pending', scripts}
                                                             // scripts 是**入参回显**（宿主确认收到了这份清单）

// ── console：数据面（可丢包，永不抛给脚本；丢包经 onQueueError 报）
const offQe = auto.console.onQueueError((e) => console.warn('日志丢了', e.level, e.reason));
await auto.console.log('普通日志', { a: 1 });               // log/info/warn/error/debug 五档，都回 Promise<void>
offQe();
```

#### 12.3.2 读这段示例时必须知道的七条（每一条都是踩过的坑）

1. **错误面要从 `require('auto')` 具名导入，不在 `auto` 根对象上**：
   `const { AutojsError, ERROR_CODES } = require('auto')` 成立，`auto.AutojsError` 是 `undefined`（`index.ts` 的具名导出，不挂在命名空间根上）。判错两条路：`e instanceof AutojsError && e.code === 'ERR_FILE_NOT_FOUND'`，或 `e.is('ERR_FILE_NOT_FOUND')`。
   **`ErrCode` 是 TS `const enum`，运行期不存在**（编译期内联，`dist` 里只剩 `/* ErrCode.NOT_FOUND */` 注释）。所以 `e.code === ErrCode.FILE_NOT_FOUND` 只对 TS 脚本成立；`.js` 脚本用 `ERROR_CODES` 里的字符串字面量。`bridge/js/src` 内部用 `ErrCode` 是因为它整体过 `tsc`，不是"运行期也能拿到"的证据。
2. **`auto.shell` 是命名空间对象，不是可调用函数**：`await auto.shell('pm list packages')` 当场 `TypeError`（`auto.shell` 是 `{exec, shell}`）。**`shell.shell()` 是别名，wire 上仍是 `shell/exec`**。`auto.a11y.selector().timeout(2000)` 同理——选择器上的超时方法叫 `time()`（`timeout` 只在 `findOne` 的选项里）。
3. **`screen.*` 与 `images.*` 是两个释放入口、一张帧表**（§18 第 8 项 (b) 2026-09-26 落地，"帧不通用"取消）：`decode` 的帧 `recycle()` 打 `images/release`，`capture` 的帧打 `screen/recycle` —— **打进去是同一张表**，所以 `screen.capture()` 的帧可以直接当 `findImage`/`findColor` 的 haystack，反过来 `images.release()` 也放得掉一帧截屏。
  放过的帧任一侧再用都是 `ERR_STALE_HANDLE`（同一个"已释放"事实）。**"截屏→找图"不再需要先落成文件**（§9.2 的落地段写明了链路与钉子）。
4. **未命中 / 缺键 / 空结果是答案，不是异常**：`findImage`/`matchTemplate`/`findColor` 未命中回裸 `null`（`findColor` 的 native 侧用 `x = -1` 哨兵，因为 `(0,0)` 是合法首像素）；`findOneOrNull` 回 `null`；`datastore.get` 缺键回 `undefined` 而存的 JSON `null` 回 `null`（两者不折叠）；`settings.getInt`/`clipboard.getText` 缺键回 `null`。
  **但"扫过 0 像素"（空 region / region 越界）是 `ERR_INVALID_PARAM`** —— 那不是"没有"，是"根本没找"，混成 `null` 会让脚本把空区域当成搜过一遍。**`findFeature` 未命中同款（回 `null`，见 §9.2 末）。
5. **引擎会话的两个名字都是 v9 的两代形态，别照旧写法**：`engines.exec({projectId, scriptPath})`（不是 `{script}`）；`session.onExit(info => …)` 且 `info` 是 `CrashInfo | null`（不是 `on('exit', code => …)` 的数字码，也没有 `.on` 这个方法）；`session.channel` 恒 `null`，命名通道要 `engines.channel(name)` **显式打开**（隐式建通道会在宿主侧留一条永远没人 drain 的缓冲）。
6. **npm 的事件订阅名与 §12.2 表格一致，不是 `on('progress')`**：`onProgress`/`onApproval`/`onWarning`/`onFinished` 四个独立方法（各有退订返回值）。`on('progress')`/`on('approval')` 在 facade 上**不存在**（会 `TypeError`），
  wire 上也没有对应方法（§10.8 的示例同批改）。四条都是**拉取轮询**投递（首订立拉、退订自停）：宿主侧没有推给脚本的通道，谁把 wire 上的 `events`/
  `approvals` 删了，`pump*` 会响亮抛 `ERR_NOT_IMPLEMENTED` 而不是安静空转。
7. **`engines.exec` 的 `timeoutMillis` 是必填的墙钟总时长，不是排队上限**（2026-10-01）：桥这条路拿到句柄就返回、**没人 await 终结**，而看门狗三路健康判据（心跳/
  CPU/RSS）全看进程表现 —— 心跳正常、CPU 空闲的长跑脚本三路都判它健康，谁也收不住它。所以期限必须由调用方声明，宿主对缺席/`null`/`非正` 一律回 `ERR_INVALID_PARAM`（**本层不预检**：
  两处校验必然漂移，与空事件名同一条纪律）。到点宿主落 `KillCause.TIMEOUT` 强杀，`onExit` 报 `{cause:'UNKNOWN'}`（外部结算同款：结算即离表，不把「查不到」伪造成干净结束）。
  排队上限是另一个参数 `waitTimeoutMillis`（缺省取请求 TTL），别混。

#### 12.3.3 接口期未落地（示例里故意不写，写了就是撒谎）

- **`images` 的 `pixel`/`captureScreen`**：`fromFile` 是 `decode` 的合法别名（两侧同名 `decode`），其余名字**两侧都没有**（`pixel` 读单个像素值、`captureScreen` 截图 —— 都要新的 native 算子，脚本侧也没有消费方，不开）。~~灰度 / 裁剪 / 缩放 / 旋转 / 特征~~ —— **桥面已于 2026-09-29 开通**（P1 图像桥消费方：`images.toGrayscale`/`crop`/`resize`/`rotate` 产新帧 + `images.findFeature` 回模板中心坐标；
  `:domain ImageAnalyzer` 扩到十方法，handler 同批认，§9.2 末推演兑现）。
- **`engines.stop(runId)` 之外的会话操作**、`npm` 的 `resolveApproval`（人机分离，§10.5）等：刻意不在桥面，脚本调即 `ERR_NOT_IMPLEMENTED`（诚实）。
- ~~`floatingWindow.create` 的参数面 / 缺 `close`~~、~~`screen.startCapturer` 的 `{width,height}` 不生效~~ —— **两处已于 2026-09-26 收口**（facade 发真 payload + 补 `close`；尺寸经 `openSession(w,h)` 透给生产者，回包尺寸仍 = 真实帧）。故示例（§12.3.1）现在**照写**；余下本节的其他条目仍是"写了就是撒谎"。

#### 12.3.4 本节与 §12.2 的分工

§12.2 是**命名空间清单**（有什么、接线到哪、谁注入），§12.3 是**调用形状**（怎么调、回什么、哪里会抛）。两者冲突时以本节为准（本节是实测），并应回来改 §12.2。§18 的**已拍板缺口**别在本节自行发明口径：第 8 项（截屏帧 ↔ images 帧的通路）**已于 2026-09-26 落地**（(b) 两缝共用帧表，见 §9.2）；
第 9 项（`images.decode` 的相对路径口径）**已于 2026-09-25 拍板 (a)** —— 只收绝对路径，故本节示例**一律绝对**（`fromFile('/sdcard/part.png')`）。相对写法按 `:main` 的 CWD（= `/`）解析，`fromFile('part.png')` 回 `ERR_FILE_NOT_FOUND` 而报的路径是对的，看起来像"文件真的不在"，不像口径没定。

### 12.4 typings 工程
`:bridge:js` 产出全套 `.d.ts`（@types/auto），IDE 补全不依赖文档站点；d.ts 作为 API 契约的单一事实来源，API 评审以 d.ts diff 为准。

**与 `bridge/schema/wire.schema.json` 的分工**（2026-09-30 审查步骤 7 拍板）：d.ts 管**对外 API 形状**（参数/返回/重载，脚本作者看得见的 TS 面），schema 管**桥线 wire 名**（每 ns 方法表 + aliases + dynamicSinks + facade 归属，双发射 `wire-types.ts`/`WireMethods.kt`）—— 两份各司其职、各自入库、各有一道门，不合并（理由见 [`design-decisions.md`](../design-decisions.md) 第 12 项）。
对账门在 §12.2 门禁说明段。

---

