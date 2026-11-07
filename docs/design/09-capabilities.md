## 9. 自动化能力设计

### 9.1 无障碍通道（`accessibility` / `ui_selector` / `ui_object`）
- **服务在 `:main`**；EventDelegate 面向桥暴露**节流拉取式**事件流（按 `seq` 游标，批量取，背压到数据面 TSF）。
- **紧凑索引树**：节点序列化只含基础索引（id、className、关键 attr 指针），属性**按需二次查询**；全树 JSON 序列化性能杀手已从协议层面排除。
- 查找：`UiSelector` 构建 → 树读 + [可选] 属性谓词过滤，`findOne/findAll/findOneOf`（Promise），超时 `ERR_NOT_FOUND/NotFoundError`。
- 操作：`click/longClick/scroll/setText/copy/paste` 走无障碍 Action；手势 `dispatchGesture`（canPerformGestures）。
- **句柄代理**：JS 侧 `UiObject` = 代理对象（§7.4），操作带 generation，控件已离开窗口树 → `ERR_STALE_HANDLE`。
- 窗口树：`window('modal/active/…)`、`UiObject.window`、event 监听（`EventEmitter`）。
- 全链路如实时树可能加速：惰性属性化已内建在索引树设计中。
- **已落地（Kotlin 侧）**：`A11yNamespaceHandler`（方法表与 payload 见该类 KDoc；构造只收 `:domain` SPI `UiNodeTreeReader`/`UiActionExecutor`/`InputProvider`，Android 真实现替换内存树/
  输入即插——handler 逻辑不变）+ 内存窗口树/输入替身，共 62 项 JVM 单测（`A11yWaitForTest` 5；app 侧挂载测试另计）；**Kotlin 侧 `waitFor` 读的载荷键是 `conditions`**（与 `findOne` 同构；
  轮询等待是宿主责任，内存树是单次快照，`timeout/interval` 只透传回显，不伪造等待）。JS facade `a11y.ts` 的 `waitFor` 已对齐：发 `conditions`（曾发 `selector`，
  会在白名单外字段上回 `ERR_INVALID_PARAM`——已修，两侧同构），其余方法键早已对齐。**`waitFor` 回 boolean，`findOne` 回 `{ref}`（别混）**：`waitFor` 命中回 `Ok "true"`、
  无匹配回 `Ok "false"`，**不回**节点体也不回 `Err NOT_FOUND` —— JS facade 的 `waitFor` 是 `Promise<boolean>`（§12.3 `const ok = await auto.a11y.waitFor(...)`，`result === true`），
  复用 `findOne` 的回包路径会让它恒 `false`（这正是刚修掉的漂移：Kotlin 侧曾是 `"waitFor" -> findOne(...)`，两侧各自的测试都没抓到）。**参数错误仍是 `Err ERR_INVALID_PARAM`**，
  不一并折成 `false`（「没等到」与「发错了」是两回事）。两侧各有钉它的测试：Kotlin `A11yWaitForTest`（5 项）、JS `a11y.test.cjs` 的「waitFor 返回 boolean」用例。
  **Android 真实现已落**（`:platform:capabilities`，分层两答）：语义层 `AndroidUiTree`（树+动作一体、句柄注册表共享——与 `InMemoryUiTree` 双身份同形）+ `AndroidGestureInput` 只认 `A11yBridge` 缝（纯 JVM，
  假桥注入跑全部选择器/句柄/手势逻辑）；设备层 `AutoScriptAccessibilityService`（清单+配置随库合并）实现同一接口并在 `onServiceConnected` 登记 `A11yServiceHolder`、
  `onDestroy` 清空。**桥面方法**（root/findBySelector/findByText/剪贴板/手势）服务未连 → `ERR_SERVICE_DISABLED`；**句柄面动作**先解注册表（未登记 → `ERR_STALE_HANDLE`；
  已登记但服务已死 → `refresh=false` → `ERR_STALE_HANDLE` 并回收）；`MODAL` 作用域以焦点窗为模态代理（AOSP `AccessibilityWindowInfo` **没有** `isModal`，主源码实证）；
  手势关门位读服务 `CAPABILITY_CAN_PERFORM_GESTURES`（系统没有 `AccessibilityManager.canPerformGestures()`，同样主源码实证——设计里这个方法名是错的）；四向滚动走 `AccessibilityAction.ACTION_SCROLL_*.getId()`（顶层 int 只有 FORWARD/
  BACKWARD）。ArchUnit 量化分层：`android..` 只许服务三件（`AutoScriptAccessibilityService`/`ServiceBridge`/`ServiceNode`），语义层碰 android 即红；capabilities 本机测试因此连 android.jar 的桩面都不碰—
  —测试全走假桥）。事件环 `A11yEventRing`（有界 512、`nodeHandle` 恒 null 不伪造句柄、type 用 `windowStateChanged`/`windowContentChanged`/`viewScrolled` 诚实名）。

### 9.2 截图与图像管线（`media_projection` / `image` / OpenCV）
```
FrameSource (SPI)
  ├─ AccessibilityScreenshotSource  API34 takeScreenshotOfWindow · 333ms 节流 · 默认
  ├─ MediaProjectionSource          会话式 · createScreenCaptureIntent→同意→FGS(type mediaProjection)→createVirtualDisplay
  │    └─ Surface → ImageReader(maxImages=2~3 对象池) → Frame 进入 libopencv.so
  │         └─ 灰度/裁剪/缩放/旋转/找色/模板匹配/特征(ORB)/颜色查找 — 全 native, 0~1 拷贝
  │            （计算核八算子全落：找色/模板匹配/灰度/裁剪/缩放/旋转/特征，P1 native 面收官；桥面八算法已全开）
  └─ MediaProjectionRecorder        会话式 · **同一条会话账**（同意 + FGS 同上），输出汇不同：Surface → MediaRecorder → 视频文件
       （落地；落点 files/scripts/<projectId>/.recordings/，路径**开的时候就回**）
```
- 截图对象生命周期：JS `Image` 句柄 → native 帧句柄；`recycle()` 显式 + ~~finalize 兜底~~（**作废 2026-11-07**：全仓无 `FinalizationRegistry`/finalize 实现，见 §7.4 同批注记）；兜底 = 连接终结时 `NativeImageAnalyzer.releaseConnection` 的连接级收口（§7.4）；`dispose` tombstone 协议同 §7.4。
- `FLAG_SECURE` → 分类错误（§7.6），不返回黑图（让脚本可判断）。
- **实现注记已外迁**：Kotlin/native 落地链、八个算子的逐条记账与真机实测数字，逐字见 [`design-status.md` §9.2 实现注记](../design-status.md#实现注记自各分卷外迁逐字保留)。
  留此的口径要点：`images` 十方法 SPI 走 §12.2 第七条独立缝（native 侧 OpenCV 4.14.0 静态链接）；**两缝帧表已合一**（§18 第 8 项 (b)：`screen.capture` 与 `images.decode` 的帧同号段互认，
  「帧不通用」纪律取消，落点是 §7.4 发号侧归一）；~~**仍缺** MediaProjection 高清会话（换 producer 即插，语义面不动）~~ **作废：会话式截屏已落地** —— `MediaProjectionSource` 经 `PlatformWiring.screenHandler` 接入，一次性同意 + API 34+ 顺序 + 幂等释放 + 会话资源随连接终结收口；**录屏（`MediaRecorder`）亦已落地** —— `MediaProjectionRecorder` 与截屏腿并列、共用同一条投屏会话账（同意 + FGS），输出汇换成 `MediaRecorder` → 视频文件；桥面 `screen.startRecording/stopRecording` 两侧契约与生成物齐全。
- MediaProjection **会话语义**：`capture()` 一次性授权会话（API34 每会话确认）；`reconnect` 不自动重试授权，由 PermissionCenter 引导用户重授权。
- **两缝帧表已合一（§18 第 8 项 (b)）**：`screen.capture()` 出的帧与
  `images.decode` 出的帧现在**同号段、互认** —— 拿截屏帧当 `images.findImage()` 的
  haystack 通，`images.release()` 也放得掉一帧截屏。"帧不通用"那条纪律**取消**。
  落点是**发号侧归一**（§7.4）—— 归一的两条落点（`ImageAnalyzer.ingest` 第 6 方法、
  共用 `nextRefId`）与其钉子清单随上条实现注记外迁。

### 9.3 输入通道（`auto` / `adb` / `root`）
`InputProvider` SPI 三实现：无障碍手势 / root / Shizuku-ADB。统一 `touchDown/Move/Up` + 手势 DSL。root 能力分级进 PermissionCenter，无 root 不降级渲染为禁用（不假装可用）。

**三通道平级、显式选路（拍板，见 [`design-decisions.md`](../design-decisions.md) 第 35 项；
含 Shizuku 依赖引入与「未真机验证」这条诚实缺口）**：
- 取值 `auto`（无障碍）/ `adb`（Shizuku）/ `root`（`su`）。**必须显式指定**：单次调用在
  payload 带 `{channel}`，会话级用 `a11y.setInputChannel`（**按桥连接隔离**，一个脚本设的
  不会漏给另一个）；**两者都没有 → `ERR_INVALID_PARAM`，没有缺省通道**（本仓别处「不传即用
  缺省」的惯例在这里刻意不成立）。会话值是**显式选择的一种**，设过之后本脚本可省略 `channel`。
  受这条约束的方法只有**与通道有关**的那几个：`gesture`/`click`/`longClick`/`scroll`/
  `canPerformGestures`；`copy`/`paste`/`setText`/`bounds`/`text`/`desc`/`children`/`parent`/
  `dispose`/`findOne`/`findAll`/`waitFor`/`events` 与通道无关，**不要求** `channel`。
- **不是降级链**：指定的那条不可用 → `ERR_PERMISSION_DENIED`（带引导），**绝不改用别的通道**。
  这一条与 §9.5 的 `DEGRADED` 哲学刻意相反，因为三者**可观测后果不同**（无障碍注入会被前台
  应用看出、root 注入在系统层不留无障碍痕迹、adb 注入的进程身份是 shell）—— 静默换通道等于
  让脚本作者以为在测 A 实际在测 B。
- **节点动作在非 auto 通道降级为坐标注入**：`click`/`longClick`/`scroll` 在 `auto` 走节点语义
  `ACTION_CLICK`/`ACTION_LONG_CLICK`/`ACTION_SCROLL`；`adb`/`root` **没有节点语义可用**，改为解出
  节点 `bounds` 再注入（点中心 / 按方向在节点内划一条）。语义上这是「点这个控件所在的位置」，
  不是「对这个控件发 action」。
- **`copy`/`paste` 没有第二条通道**：它们是纯语义动作（shell 面没有「复制节点文本」这种原语），
  所以不在上一条的降级映射里，也不要求 `channel`。
- **shell 面只有直线**：`input tap` / `input swipe` 两个原语，没有轨迹 —— 经 `adb`/`root` 的
  `gesture` 会逐笔画串行注入且每条只取首尾两点。真轨迹要 `sendevent`（按设备事件节点写，未落地）。
- **`canPerformGestures` 问的是当前通道**：`auto` 问服务能力位；`adb`/`root` **没有对应开关**
  （能不能用取决于进程身份，而那正是该通道被接线的前提），故恒 `true`；真正的失败以命令退出码
  形式出现在动作调用里（**不折成 `false`** —— `false` 的语义是「系统拒绝这次注入」）。
- **接线（`PlatformWiring`）**：`auto` 恒登记；`root` 恒登记（走既有 `su -c`，真无 root 时命令
  自己失败）；`adb` **只在 `ShizukuInput.isAvailable()` 为真时登记**（装了 **且** 服务活着，两问
  都要）。未登记 = 调用方拿 `ERR_PERMISSION_DENIED`，**不回落 `auto`**。

### 9.4 悬浮窗 / UI 宿主
- `floating_window`：`TYPE_ACCESSIBILITY_OVERLAY`（可信窗口易保持）＋ `SYSTEM_ALERT_WINDOW`（普通）；运行时权限 checkbox 进能力中心。
- **落地分层（勿混）**：`floatingWindow` handler 只管**参数与信封**（spec 守卫 → `ERR_INVALID_PARAM`、句柄两字段 `{refId,generation}` 的原样编码、分类错误原码透传），
  住 `:platform:capabilities` 的 `FloatingWindowNamespaceHandler`（纯 JVM 可测）；**句柄记账（generation 递增）、`close` 幂等、`ERR_STALE_HANDLE` 的起源、窗口类型选择（`TYPE_ACCESSIBILITY_OVERLAY`/
  `SYSTEM_ALERT_WINDOW`）全在实现侧** —— 由 `:domain` 的 `FloatingWindowHost` SPI 承接，Android 实现 `AndroidFloatingWindowHost` 已落在 `:platform:system`（§12.2 分两层）。
- 脚本 UI：JS 声明 XML 布局 → 桥传 `:main` 渲染原生 View（`UiHost` SPI）；`ui_web` → WebView + JS 桥（双向事件回 Node）；`ui` Activity 方式独立宿主 Activity（BAL 限制内，仅当可见/继承时启动）。
- 事件回投（点击/输入/页面生命周期）→ RuntimeChannel → JS 侧 `EventEmitter`。

### 9.5 权限与能力中心（three-state 门禁）
统一 `CapabilityStatus = GRANTED / DEGRADED / DENIED`：
- **GRANTED**：系统授予且当前可用（含会话型 MediaProjection 已激活）。
- **DEGRADED**：可降级但受限（如 a11y 树只能节流读、闹钟降 setWindow、无 root、BAL 限制、电池未豁免、ROM 自启被关）。
- **DENIED**：被用户/系统拒绝，操作抛 `ERR_PERMISSION_DENIED`。
**实现注记已外迁**：`AndroidPermissionGates`/`permissionCenterOf` 生产接线、`HostSummary.capabilityCenter()` 读口与 `:ui` 能力中心屏的判据，逐字见 [`design-status.md` §9.5 实现注记](../design-status.md#实现注记自各分卷外迁逐字保留)。
- 能力中心 UI：枚举所有能力 + 当前三态 + 一键跳转系统页 + 降级说明；`PermissionFacade` 是唯一的权限入口（模块不直接查 `Settings`/`ActivityCompat`，可 Mock）。

### 9.6 数据与存储（`datastore` / `settings` / `zip`）
- `datastore` = SQLite-backed KV + serializer 适配（JSON/native 对象/byte），事务语义；同步 importer 仅供纯内存。**契约已落 `:domain`**（`storage/`）：`DataStore` SPI（全挂起，
  同步 importer 不进契约 —— 铁律 2，纯 JS 进程内路径专有）+ `StoredEntry`（`Json` 透传文本 / `Bytes` 内容相等；存 JSON `null` ≠ 键缺失）+ `DataStoreTxn`（暂存写、
  block 抛错整批不落、提交对并发读者一个原子点）+ `InMemoryDataStore` 纯内存参考实现与契约测试 —— **桥面已通**：handler（`:platform:capabilities` 的 `DatastoreNamespaceHandler`，
  `{found,value}` 信封保 `undefined`≠`null`、字节值不过桥如实 NOT_IMPLEMENTED、`transaction` 不上桥）+ 独立注入缝（`assemble.datastoreHandler`，不入 `systemHandlers` 束 ——
  存储面无共担门禁）+ JS facade（`datastore.ts`，`get` 拆信封）。**SQLite 实现已落** `:platform:system`（`AndroidDataStore` + `SqliteKvOps` 单表 `kv(key,kind,value)`，`KvRowCodec` 显式 kind 列裁定文本/
  字节、访问器按 kind 惰性；`SystemSpis.Bundle.datastore` 入口就绪）——**生产已接**（`PlatformWiring.of` → `inject` → `AppShellApplication.installWithFiles` 喂 `datastoreHandler` 独立缝；
  `PlatformWiringTest` 同路径真转接覆盖）。多库 = handler 键前缀 `name:key`，领域层不发明第二套路由。
- `settings` = 系统设置读写（`:domain` `SystemSettings` SPI + `:platform:system` `AndroidSystemSettings`，P0 钉在 `Settings.System` 命名空间：string/int 两型 × 读写 + `canWrite` 探针）。
  写入口径（§9.5）：未授 `WRITE_SETTINGS` → 抛 `ERR_PERMISSION_DENIED`（不是回 false —— 授权问题是分类错误）；已授权仍被系统拒 → `ERR_IO`；读侧缺失回 null（不拿 0/
  空串冒充 —— 0 是合法亮度）。**桥面已通**：handler（`:platform:capabilities` 的 `SettingsNamespaceHandler`，方法表照抄 SPI 五件 `canWrite`/`getString`/`getInt`/`putString`/
  `putInt`，**不提供猜型的 `get`/`put` 别名** —— 串与数是两套系统 API，按 `typeof` 推断就是发明策略；读缺失回**裸 JSON `null`** 而非 datastore 的 `{found,value}` 信封 —
  — 本契约值面只有 String/Int，`null` 不与任何合法值撞，空串/0 是真值一律不冒充缺失；写前**不预检 `canWrite`** —— 未授 `WRITE_SETTINGS` 抛 `ERR_PERMISSION_DENIED` 的判据唯一出处是 SPI，
  handler 再判一遍必漂移）+ 独立注入缝（`assemble.settingsHandler`，同 datastore/zip 不入 `systemHandlers` 束）+ JS facade（`settings.ts`，五方法与 SPI 1:1，缺失回 `null` 不回 `undefined`）。
  双侧钉子：`SettingsNamespaceHandlerTest` + `settings.test.cjs`。**授权引导页未进 `GrantPage`**（能力中心有 settings 入口时再开）；**生产已接**（`PlatformWiring.of` → `inject` → `installWithFiles` 喂 `settingsHandler` 独立缝；
  `PlatformWiringTest` 同路径覆盖）。
- 脚本文件目录：`files/scripts/<projectId>/` 标准化；要求 `assets→filesDir` **原子部署**（tmp 写入 + sha256 校验 + rename 替换），防止半截断电文件。
- `shell`：SPI 是 `:domain` 的 `ShellExecutor`（`exec(command, mode, timeoutMillis)` → `ShellResult`，超时是**实现者义务**而非可选项 —— §10 零 spawn 的副作用落在宿主侧，且铁律 3 禁止无限等待）；语义层 `ShellNamespaceHandler` 住 `:platform:capabilities`（参数校验、超时默认值 30s、`ShellMode` 字面量 `default`/`root`/`adb`），Android 实现 `AndroidShellExecutor`（`Runtime.exec("sh","-c",…)`）已落在 `:platform:system`。
  **捕获有上限（拍板，口径见 `design-decisions.md` 第 21 项）**：每条流至多留 `ShellCaptureLimit.MAX_CAPTURE_BYTES`（1 MiB），超出即**静默截断 + 显式日志 Warning + `ShellResult.truncated=true`**（纯增量字段，缺省 false）。三条硬约束：① **到顶后仍要把管道读到 EOF**，只是不再落缓冲 —— 停下来不读会让子进程憋死在写满的管道上，那正是「双流并发读干」要防的死锁；② **两条流各自计数**（stdout 截了不挤掉 stderr 的额度，错误信息恰是最该留住的短输出）；③ 上限必须满足 `MAX_CAPTURE_BYTES × JSON_WORST_CASE_EXPANSION(6) < 单帧上限`（§7.5 的 8 MiB）—— 单帧超限在桥上是 `FrameTooLargeException` → **关连接**，比截断重得多，所以必须在实现侧先兜住、不让超限载荷走到 JSON 编码那一步。**截断不进 `isSuccess` 的判据**：`code` 是子进程真实退出码，截断是宿主侧捕获策略，两者正交；被截的那条流末尾另追加 `TRUNCATION_MARK` 说明行（人读的那一层，机器判定一律用 `truncated`）。日志走可注入的 `LogSink`（缺省 `java.util.logging`，其 `ConsoleHandler` 写 `System.err`，Android 把 `System.err` 重定向进 logcat）—— **刻意不直接调 `android.util.Log`**：`:platform:system` 的 JVM 单测没有 `isReturnDefaultValues`，直接调会抛 "not mocked"，一条"输出超限"的告警不该把测试判红。
**实现注记已外迁**：五个系统命名空间（`dialogs`/`shell`/`device`/`app`/`floatingWindow`）的落地清单与 `dialogs` 的回投/取消语义，逐字见 [`design-status.md` §9.6 实现注记](../design-status.md#实现注记自各分卷外迁逐字保留)。
  **Promise 封装的诚实口径**：`stdout`/`stderr` 为 `null` 表示「流无输出」，不是「有输出但为空」；退出码非 0 不是异常，原样回 `{code, stdout, stderr, truncated}` 由 JS 侧判；child_process 副作用限制如实上报。
- `zip` = 归档压缩/解压（`:domain` `ZipArchiver` SPI + `:platform:system` `JdkZipArchiver`）。契约级安全底线：**zip-slip 先验后写** —— 全包条目名校验通过才落字节（`../` 逃逸/绝对路径条目 → `ERR_INVALID_PARAM`，界内零写入；与 shell 超时同级的实现方义务）。压缩 tmp+rename 原子落位、空目录条目保留、垃圾包如实 `ERR_IO`（`ZipFile` 中央目录校验，不用对垃圾"零条目静默成功"的流式读）。**体积上限（backlog B13）**：解压前先卡**单条目** 256 MiB 与**整包合计** 1 GiB（两道都要 —— 单条目的闸防「一个巨物」，总量的闸防「一万个小东西」），超限整次拒绝并回 `ERR_INVALID_PARAM`（与 zip-slip 同码：都是「这个归档本身不合格」，是**输入**的问题，不是目标盘的问题）。判据分两拍：第一拍按包的**声明**早退（诚实炸弹在写任何字节之前就被拦下），第二拍在字节流经过的地方按**真读出来的字节数**再判一次（`ZipEntry.getSize()` 是包自己声明的数，可以撒谎）。**不查剩余空间** —— 那是配额面的事（§10.9 UX 5），混在一起会让「盘满了」与「包是炸弹」报同一个错。
**实现注记已外迁**：`ScriptPaths` 三处复用、`ScriptDeployRecovery` 的「只补缺不覆盖」与诚实边界，逐字见 [`design-status.md` §9.6 实现注记](../design-status.md#实现注记自各分卷外迁逐字保留)。
  **桥面已通**：handler（`:platform:capabilities` `ZipNamespaceHandler`，参数口径+错误原码透传，不碰归档字节）+ 独立注入缝（`assemble.zipHandler`）+ JS facade（`zip.ts`）。与 packager 的 npm 专用 zip（`NpmSnapshot` 固定 mtime+integrity）边界分明，不互相复用。

### 9.7 OCR（P1）与插件（P2）
- `OcrProvider` SPI：**不内置 OCR 实现（拍板）**——MLKit 插件基准实现（可下载模型）不做；`OcrProvider` 只保留可替换接缝（§2/§6），有需要的脚本自带外部方案。插件以独立 `:plugin:*` 模块 + 清单注册，native addon 走 `:node-runtime-build` 交叉编译管线（arm64 `.node`）。
- 插件加载: P2，plugin.json 声明 require 钩子/资源/权限；市场脚本不可加载任意插件（白名单）。**沙箱已裁（§18 第 1 项）** —— 这条白名单是能力面的，不再有"引到 QuickJS 子集"的去处。

---

