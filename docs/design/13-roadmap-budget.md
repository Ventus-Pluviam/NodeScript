## 13. 设计模式应用总表（架构落地位置）

| 模式 | 落地位置 | 用在哪 / 为什么 |
|---|---|---|
| **Facade** | `auto` 根 / `RuntimeBridge` / `ScriptEngine` | 对脚本隐藏桥细节 |
| **Provider / SPI（服务定位的换代）** | `EnginePool`、`FrameSource`、`InputProvider`、`SchedulerProvider`、`UiHost`、`OcrProvider` | 全部可替换接缝 |
| **Strategy** | `SchedulerProvider`、`InputProvider`（a11y/root/Shizuku）、截图源 | 触发/输入/帧源多路实现平切 |
| **State Machine** | 执行单元生命周期、`CapabilityStatus`(3 态)、MediaProjection 会话 | 不可落定的状态自动终结 |
| **Observer / EventEmitter** | 引擎事件、a11y 事件、屏幕会话、数流 | 事件订阅隔离生产者消费者 |
| **Registry + Request-Reply 关联** | `RequestRegistry(requestId+TTL)`、`HandleRegistry(gen)` | 可靠超时、句柄安全、错误折叠 |
| **Watchdog + Circuit Breaker** | `:main` 看门狗三路、桥的连接熔断 | 防僵尸/风暴级联 |
| **Half-Sync / Half-Async** | 桥：异步调用 + 队列化同步任务 | 两个事件循环互不阻塞 |
| **Proxy（句柄代理）** | `UiObject`/`Image`/`MediaPlayer` JS 代理 | 跨进程资源生命周期 |
| **Object Pool** | `ImageReader(maxImages=2~3)`、页面帧、engine slot | 减少分配/避免堆外内存 |
| **Template Method** | 引擎宿主 start/stop 骨架、打包 AXML 改写 | quiesce 协议稳定、差异内聚 |
| **Adapter** | `nodejs-mobile` recipe → 自建管线；传输(unix socket/binder) | 隔离上游差异 |
| **Composition Root（手写 DI）** | `AppShellApplication` 启动装配 | 显式依赖、可测；**不用 Hilt**（模块扁平、装配少） |
| **Repository** | `ScriptRepo`、`Datastore`、`Scheduler` 的 RunRecord | 三处存取各自仅通过仓库 |
| **EventBus** | `:main` 内事件（能力状态变化、引擎状态） | 模块解耦、不直连 |
| **Idempotency 键（runNonce）** | checkpoint 意图日志 | 崩溃恢复不重复副作用 |
| **Interceptor/Decorator（child_process shim）** | npm 安装会话进程（`NpmSpawnGate` → `files/.autojs/npm-spawn-gate.cjs`，`NODE_OPTIONS=--require` 注入，2026-10-09 落地） | 零 spawn 强制不变量：拦截非批准 spawn 并硬失败（ERR_NPM_SPAWN_BLOCKED），把静默漂移变成响亮错误 |
| **Transaction Journal + 暂存目录/rename** | InstallCoordinator · reify | 安装原子化（node_modules.part-<ts> + install.journal begin/commit/fail），崩溃自愈坏树 |
| **Trust Anchor（带外）** | 供应链安全 | pin 注册表签名公钥 + 多镜像 integrity 交叉校验，破除 TOFU 自签 |
| **Generation/tombstone（Handle）** | HandleRegistry | 跨进程资源竞态安全 |
| ~~**Interrupt Handler**~~ | ~~QuickJS 沙箱 CPU 打断~~ **已裁**（§18 第 1 项）；CPU 风暴由 `:nodeN` 进程边界 + 看门狗吸收 | 风暴消融（改测 `:nodeN` 进程级） |

---

## 14. 需求优先级路线图

### P0 — 小而完整、可发布的最小闭环
**用户故事**：写一个无障碍脚本 → 在 App 内运行/停止/看 console → 被守护（看门狗杀僵尸不拖垮 UI）→ 能设一个每天定时任务。
- 构建链行：`:node` 进程宿主（单脚本）、Node 24 自建管线 + 16KB 门禁。
- 最小桥：TSF 双队列 + RPC + TTL + HandleRegistry、`console` 回传。
- a11y 基础：选择器/click/scroll/setText/文本事件；a11y 截图（333ms）。
  **Kotlin 侧已落地**：选择器/点击/滚动/文本/剪贴板/事件流/手势 + 截图（333ms 节流/会话），均 JVM 可测；`AppShell.assemble` 已留 `a11yHandler`/`screenHandler` 挂载缝（见 §12.2 接线现状表）。
- 执行：池（默认 1）状态机、四步 quiesce、心跳+Cpu+OOM 看门狗、崩溃重启(仅本轮 run)。
- 定时：单 alarm 定时任务 + 意图日志 + runNonce 幂等。
- 权限三态中心 UI + 引导页；specialUse FGS 骨架。
- 打包：模板 APK 改装（assets 注入、签名向导）——**整轨移入后续版本**（2026-09-23 决策：本版不做打包；下方已落地记录保留作既成事实）。
  **P0 领域+收集侧已落地**：`ApkIdentity`（包名/aapt2 关键字校验）+ `TemplateInfo`（引擎版本锚定）+ `TemplateApkPlans`（planDigest 组装/改写前复验，防清单错配；`offlineVariant` 参与摘要）+ `PackagerCollector.plan()`（规格+清单+身份一次产出计划），均 JVM 可测。
  **P0 AXML/ARSC 真改写已落地（纯 JVM，`:app-service:packager`）**：`IdentityTemplatePatch` 接 `PackagerPipeline.TemplatePatch` 缝——`AxmlPatcher` 改 manifest 的 package/versionName/versionCode/label，label 为 `@string` REF 时走 `ArscPatcher` 按资源 id 改全局池（REF 的 data 不变，AXML 无需重排；ARSC 缺资源则兜底降级为字面串），组件类名按**旧包**绝对化（`.X`/裸名 → 绝对名；
  dex 命名空间随模板编译定死，换 `package` 后相对名会按新包解析而类并不存在、装上即崩；本就绝对的与外部类不动，alias 的 `targetActivity` 同规则），`ApkRepacker` 重打包并剔除旧 v1 签名条目。字符串池**只追尾追加**（已有下标不动），未改动条目与未追加时的池字节逐字节保留，未知顶层块原样透传；
  夹具 APK（aapt2 产物）回读校验（另附组件+图标俱全的 `fixture-template-full.apk`）。换图标同趟条目级完成：`ApkPackager.iconPng` 换掉全密度 `ic_launcher(_round).png` 并剔除 `anydpi` 自适应 XML（API26+ 会拿自适应遮住 PNG），无密度 PNG/非 PNG 魔数/文件缺都在动模板前拒绝，`ApkRepacker.rewrite` 增删除集与 `entries()` 实况枚举。
  编排闭环已落地：`ApkPackager`（plan 两段式 → prepare → 身份改写 → `assets/project/` 批量注入（逐文件 sha256 对清单，collect 后被改即拒）→ `ZipAlignRunner` → `ApkSignerRunner`，**先对齐后签名**焊死，`apkSha256` 取对齐后字节），packager 模块内 200+ JVM 单测覆盖 argv/顺序/两道复验/失败口径；本机对真 `zipalign`+`apksigner`+debug keystore 手工跑通并 `zipalign -c`/`apksigner verify` 回读。
  **打包整轨已移入后续版本**（2026-09-23 决策：向导 UI 与 Keystore 取密随轨道走）；加密资产/loader 一并移出需求（与脚本加密同批收窄）。
  **P0 签名向导领域侧已落地**：`SigningKey`（Debug 临时/ECDSA 发布密钥库描述）+ `SignPlans`（请求组装绑定计划摘要，签名前复验）+ `ApkSignerArgs`（apksigner 参数表纯构造，口令只走 `env:NAME` 不进参数表），均 JVM 可测。
  **P0 apksigner 起进程已落地（纯 JVM 缝）**：`ApkSignerRunner` 注入 `ProcessLauncher`（对齐 `HostNodeExecutor` 惯例）——argv 与领域参数表逐字一致、口令只经 `AUTOSCRIPT_KS_PASS`/`AUTOSCRIPT_KEY_PASS` 环境变量、非 0 退出码与"报成功但没产出包"都如实失败。**参数形态经真 apksigner 验证**：必须是两项式 `--ks-pass env:NAME`（`--ks-pass:env` 连写会被拒 `Unsupported option`）。
  `ZipAlignRunner`（`zipalign -f -p 4 in out`，同样注入 `ProcessLauncher`）与编排顺序（先对齐后签名、`SignPlans` 摘要绑对齐后字节）已由 `ApkPackager` 焊死。仍留 Android/后续侧：Keystore 取密钥、签名向导 UI。
- 单测/archUnit CI（+ 2026-10-01 批 5：Android Lint / assembleDebug 进 PR 门，真 npm E2E 进 `.github/workflows/e2e-nightly.yml`）；Docker 构建镜像。**已落地**：`.github/workflows/ci.yml`（JVM 单测 + archUnit + Android 构建/Lint）；本机已配 Android SDK（2026-09-23）——CI 同款 `./gradlew …` 命令可本机直跑复现；本机快速门 = CI 同源 `./gradlew`（`tools/jvm-test*` 已删，见 §6 末）。
- npm P0（§10.11）：vendored npm CLI + 专用安装会话进程 + 零 spawn 主路径 + 事务化安装/journal 自愈 + 精选缓存种子离线首装 + 带外信任锚/lock 验签/审批卡 UI + 依赖面板 + 打包 node_modules 入包。

### P1 — 并发、图像、生态关键件（沙箱已裁，§18 第 1 项）
- 引擎池自适应（1-3）＋执行 slot FGS + 队列语义；`engines` 多引擎/`RuntimeChannel`。
- ~~QuickJS `:sandbox` 进程~~ **已裁（2026-09-26，§18 第 1 项「不要沙箱」）**：QuickJS 整条轨撤出排期，引擎只剩 Node 一条；`:engine:sandbox` 不进排期、也已无模块壳（**2026-09-30 追记：
  壳已从 settings 注释摘除，不占模块表**；**2026-10-01 追记：空壳目录与那行注释一并删除**）。连带作废的还有 npm P1 里的「QuickJS 白名单库独立 vendored」与 §16 的两条相关风险——第三方脚本的防线改为**安装时用户选择 + §11 来源提示**（进程隔离那条不再存在）。
- `libopencv.so` 全图像管线的 P1 算子与桥面消费方**均已全落**；**找色已落地**（2026-09-25，§9.2：单色 + 逐分量容差 + 可选区域 + 首个命中，四层同改，`x=-1` 哨兵与“扫过 0 像素”两条口径），
  模板匹配 + `decode`/`release` 亦已随 §9.2 落地，**灰度、裁剪、缩放、旋转与特征已落计算核**（2026-09-25：`imgnative_gray` 产出新帧 + 28 例；`imgnative_crop` 尺寸会变的产出 + 复用区域判据 + 真拷贝 + 46 例；
  `imgnative_resize` 目标尺寸入参 + 固定 LINEAR + 配额 + 45 例；`imgnative_rotate` 逆时针角度 + expand 包络画布 + 帧中心 + 45 例；`imgnative_feature` ORB+ratio+几何一致性+铺开度门只回坐标 + 51 例 host 断言；
  **五者桥面已于 2026-09-29 全部开通** —— P1 图像桥消费方兑现了当初"没有消费方就不开桥面"的判据，`:domain ImageAnalyzer` 扩到十方法）；MediaProjection 会话式截屏
  **已落地（2026-10-08，批 75）**（`MediaProjectionSource` 经 `PlatformWiring.screenHandler` 接入，换 producer 即插、语义面未动）；~~**录屏仍待**（`MediaRecorder` 全仓零引用）~~ **录屏亦已落地（2026-10-08，批 77）**：`MediaProjectionRecorder` 与截屏腿并列、共用同一条会话账，输出是视频文件。
- `ui` 原生 XML UI 宿主 + `ui_web` WebView JS 桥 + 悬浮窗。
- datastore SQLite、settings、sensors、notification、app Intent、zip、power_manager（**已落地**，见 §8.7；clipboard 亦已落地 §12.2 第五条独立缝，sensors 亦已落地 §12.2 第六条独立缝，
  images 桥面与 native 实现均已落地 §12.2 第七条独立缝 —— `libopencv.so`（OpenCV 4.14 静态链接，`node-runtime-build/scripts/build-opencv.sh` + `.github/workflows/image-native.yml`）+ `NativeImageAnalyzer`/
  `JniOps`（`:platform:system`）+ `PlatformWiring.of` 三件套齐全，so 缺位时桥回 `ERR_NOT_IMPLEMENTED`）。
- ~~OCR (MLKit 插件基准实现)~~ **不内置（2026-09-26 拍板，见 §9.7）** + `OcrProvider`（只保留接缝）。
- 插件框架骨架 + 打包合并插件资产。
- npm P1（§10.11）：spawn 桥 polyfill + **lifecycle 脚本真实执行**（§18 第 7 项口径：不做出厂卡口、安装时让用户自己选，不是"批准后才跑"的审批流 —— **宿主侧门禁面已落 2026-09-29**：解析/哈希/白名单/自请入队/执行接缝齐了，缺 spawn 桥本体，见 §10.3 T1 落地追记）+ npm 终端 + 在线/OSV 离线审计 + node-shim 红测。（原「QuickJS 白名单库独立 vendored」随第 1 项沙箱裁掉。）

### P2 — 生态与分发
- `dialogs` 全形态的 **Android 渲染侧**（overlay 真弹窗 / 通知回调的真投递；`mode` 选择与 BAL 降级判据已在 §9.6 的语义层落地）、`root_automator`/Shizuku 输入、`shell` 全量（`ShellMode.ROOT`/`ADB` 的真执行通道；`DEFAULT` 侧语义已落地）。
- 通知触发的 Intent 任务；cron `@宏`/`L`/`W` 扩展写法；alarm 生成日历视图。
- 分享（**文件形态**：导出项目包文件 → 对方经 §10 离线包导入通道落盘 + 验签后安装）、`axios`/第三方包预置。
- npm P2（§10.11）：离线 bundle 打包器 + 增量更新；native 依赖 **wasm 方案**（wasm 构建优先、纯 JS 替代兜底、安装期点名引导）。

### P3 — 前沿与实验
- worker_threads 实验性引擎（若手机端验证可行）——标记实验、默认关闭。
- Flutter/Compose 全重做 IDE 主题化；性能剖析面板。

**原则**：P0 的「小而完整」优先于「多而残缺」；每个 P 的退出标准都有可测验收（§16 预算联动）。

---

## 15. 性能与体积预算

| 指标 | 目标 |
|---|---|
| APK 体积 | ≤ 150MB release（`libnode.so` + `libopencv.so` + assets）—— **2026-10-07 用户裁定：预算由 `≤ 40MB` 改为 `≤ 150MB`**（口径见 [`design-decisions.md`](../design-decisions.md) 第 41 项；第 20 项的实测链与 `InstallSizeRead` 处置保留）。**按新预算不再超支**：真形态 debug APK 实测 51,838,708 B ≈ 49.4 MiB（artifact `37581330329`，2026-10-07） |

> **APK 体积预算被实测推翻过，随后按实测重定（2026-09-25 记账；2026-10-07 重定为 `≤ 150MB release`，见 [`design-decisions.md`](../design-decisions.md) 第 41 项）**：`:engine:node-process` 侧 jniLibs 三件套
> `libnoden.so` + `libnode.so` + `libc++_shared.so` 实测未压缩合计已 ≈81MB（APK 压缩安装后另计）——**2026-09-26 ICU 之后要按 ≈92MB 读**：`libnode.so` 由 `--with-intl=none` 换成 `small-icu zh,en` 后实测 70,725,976 → 81,950,376 B（**+11,224,400 B = +10.70 MiB = +15.87%**），
> 增量全在 `libnode.so`，故三件套 +10.70 MiB；取证 = 两个 `node-slice` artifact（`36153816811` / `36185853302`）+ 各自 `config.gypi`（旧 `icu_small=false`，新 `icu_small=true, icu_locales=en,root,zh, icu_path=deps/icu-small`）。
> §18 第 4 项的拍板（只要 zh,en）**已按本条买单**——不拍这条的话全量 ICU 还要再多，预算只会更超（下文 `libopencv` 两处「占三件套 8.6%/7.8%」的分母仍是 ICU 前的 81MB，
> 按 ≈92MB 折算应为 7.6%/6.6%，分母换了、结论不变：图像面不是超支原因）；
> `libopencv.so` 是 OpenCV 4.14 `core+imgproc+imgcodecs+features2d+flann` 静态链接（kleidicv=ON；五模块 device 构建实测 **7,298,272 B = 7.0 MiB**（特征落地前 6,328,916 B = 6.0 MiB，增量不足 1MB、+15.3%），占引擎三件套 81MB 的 8.6%）——
> 仅按 `BUILD_LIST` 裁剪，**未压缩实测 6,328,916 B = 6.0 MiB**（占三件套 81MB 的 7.8%）——
> 早前"再添一个数量级相当的份额"是不成立的推断，实测不是同一量级。因此超支**全在引擎三件套**，
> 图像面不是 §15 超支的原因；据此 (c)「继续裁 OpenCV 面」的性价比极低（最多省 6MB，且已是最小可用集），
> 三条选项的对比见 [`design-decisions.md`](../design-decisions.md)：
> (a) 接受超支并在能力中心明示安装体积（最省事，代价是转化率）；
> (b) 按需分发 —— 引擎/图像两条 native 轨改走首次启动下载或 Play 动态交付（`libopencv.so` 无 exec 需求，
> 可整轨后移；`libnode.so` 有 exec 硬需求，动它要先解决 §19 的落位链）；
> (c) 继续裁 OpenCV 面（`imgcodecs` 只留 PNG/JPEG 已是最小可用集，再裁要动 SPI 承诺）。
> 记账而非静默删除：预算数字是 §15 的契约，推翻它得留证据链（`node-runtime-build/out*/SHASUMS256` + artifact 体积）。
> **2026-10-07 的重定走的正是这条路**：新数字、理由与实测锚记在 [`design-decisions.md`](../design-decisions.md) 第 41 项，
> 本节实测账（≈92MB 取证、OpenCV 占比、(a)/(b)/(c) 三条出路）**一字未删**。

| 冷启动→就绪 | ≤ 800ms（无系统抖动） |
| 脚本 warm start（二次复用 slot） | ≤ 300ms |
| a11y 空 RPC p95 | < 2ms |
| 截图→找图 | < 1s；模板匹配 1080p < 40ms（小模板 48×48 全帧形态 <100ms，2026-10-02 决策 25）; 找色 < 10ms（`findColor` 与 P1 桥消费方五算子已落地，真机红测待补） |
| 紧凑树传输 | < 15ms / 数十 KB |
| 引擎进程 RSS | 80–160MB（Node 24 baseline）；低内存模式 ≤ 128MB 堆 |
| 池内存预算 | 默认 1–2 引擎；≥6GB 设备至多 3；峰值不可超出设备内存 1/3（自适应采样调节） |
| npm 安装会话 | 堆上限常规 192MB / 低内存 96MB（从池预算反推，RSS 记账超阈先降载再放弃）；设备端装 100 依赖 15–60s（eMMC/f2fs 更差，进度如实展示） |
| vendored npm CLI | ~8–9MB 运行态（APK 内压缩、首启解一次）；精选缓存种子 ~5MB |

---

## 16. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| **Node-on-Android 升级依赖自持管线** | 上游（nodejs-mobile）停更；我方需长期维护 recipe | 建立 `:node-runtime-build` 固化管线：固定 Node LTS、预期树哈希门禁、NDK 版本锁定、CI 每日构建冒烟、产物 ABI 号校验；管线减至「换版本号→跑一次→回归」 |
| **16KB 页 / ELF 对齐** | 未对齐 so 在新设备加载即崩 | **CI 门禁强制 `LOAD 0x4000` 对齐 + `p_offset ≡ p_vaddr (mod align)`**（用 `llvm-objdump --private-headers` / `llvm-readelf -l` 断言；三个产物 + `libc++_shared.so` 逐件过）；**不设 16KB 真机红测**（2026-10-06 拍板，`design-decisions.md` 第 34 项：风险在构建期被机械门禁吃掉，真机只是追加证明）；`libopencv.so` 同轨还有一个**JNI 符号面**断言（五个 `JniOps_*` 逐个在场）—— 2026-09-25 补，理由是符号名是字符串约定、改包名/类名漏一处照样编得过，前三类断言一条都不红；**2026-09-26 修过一次真错位**：cc 用 `NativeImageAnalyzer_` 而声明类是顶层 `JniOps`，JVM 按声明类找 `JniOps_` 一个都找不到（无 RegisterNatives 兜底），本机 `jni-names.test.cjs` 先钉、CI 的符号面断言同批改对 |
| **引擎进程被杀/LMK** | 长任务中断 | 执行 slot 与 `:main` 绑定继承进程重要性 + specialUse FGS；看门狗对「被杀」能恢复意图日志重调度（幂等）；low-memory 降池 |
| **无障碍树洪峰（滚动/动画）** | IPC 爆炸 / UI 卡顿 | 节流拉取（seq 游标批量）+ 数据面可丢包 + 紧凑索引树按需属性 |
| **`process.exit` / CPU 风暴 / OOM 单脚本** | 曾拖垮整个 app | **进程边界**吸收全部；外带 CPU 差分 + 心跳双通道 + 堆 cap（沙箱 interrupt handler 随 §18 第 1 项裁掉） |
| **屏幕锁定时守时任务失败** | 闹钟响但任务是黑帧/无窗口 | 诚实契约：亮屏+解锁保底；预热闹钟 -60s；`screen` 三态声明；分类错误可 catch |
| **厂商 ROM（MIUI/HyperOS/Vivo）杀后台** | 自启/保活失效 | 能力中心三态 +「一键引导」直达 ROM 白名单页；无保证的功能如实降级标注 |
| **SCHEDULE_EXACT_ALARM 默认拒绝** | 定时不准 | 一级权限项 + 可降级 setWindow；坏 case 用户可见偏差标注 |
| **MediaProjection 会话授权中断** | 截屏功能随会话失效 | 会话状态机 + 可重授权引导；**不做自动续期** —— 306s 上限由 FGS(`mediaProjection`) 消除（2026-10-08 裁定，`design-decisions.md` 第 44 项）；用户撤销/系统停止时如实报错并引导重新授权 |
| ~~**QuickJS 沙箱缺口**~~ **作废（2026-09-26，§18 第 1 项）** | 恶意脚本逃逸 | **换成：无进程隔离后的逃逸面** —— 第三方脚本与自写脚本同权，防线只剩安装时用户选择 + 来源提示 + TTL/看门狗；永不默认静默提级 |
| ~~**双引擎 API 漂移**（Node vs QuickJS）~~ **作废（2026-09-26，§18 第 1 项）** | 同脚本两处行为不同 | **只剩 Node 一条轨**，不存在两处行为；`ImageAnalyzer`/引擎缝仍留 SPI 以便将来换实现 |
| **桥死锁回归** | 事件循环冻结 | archUnit + 专项契约测试（双向同步禁令的静态检查 + 死锁压力测试）；线程规则写进 code review checklist |
| **脚本间广播/通信滥用** | 引擎间干扰 | RuntimeChannel 按来源分级过滤；低信任不得控制高信任引擎 |
| **打包 APK 依赖宿主引擎版本** | 旧 APK + 新宿主 mismatch | 打包时记录引擎 ABI 哈希，启动校验 |
| **npm 供应链 / 零 spawn 漂移 / 安装中断** | 依赖投毒、护栏静默消失、半截 node_modules | §10.5（带外信任锚/审批人机分离）与 §10.12（拦截 shim 金标准/事务化安装/离线闭包差距），不再在此重复 |

---

## 17. 兼容矩阵要点（修订版）

| 维度 | 决策 |
|---|---|
| SDK | minSdk **26**（Android 8.0）· target/compile **36**（Android 16 · 2025/26 基线；真值见 [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml)）；arm64-v8a 首发，x86_64/模拟器 P1 补 |
| 页对齐 | 16KB ELF 对齐为 CI 硬门禁（§16）；**真机 16KB 页测试不做**（2026-10-06 拍板，见 §16 与 `design-decisions.md` 第 34 项） |
| 无障碍 | API 31+ 需启用手势 → 能力中心引导；hidden API 在黑名单 → 不 curl，用 Safe-mode 替代路径 |
| 前台服务 | API 34 起必须带 type → specialUse；API 35 6h 超时对 specialUse 不适用（但要声明 subtype） |
| MediaProjection | API 34+ 每会话确认 + FGS(mediaProjection) 前置；**该型 FGS 即免除旧的 306s 上限，不自动续期**（2026-10-08 裁定，`design-decisions.md` 第 44 项） |
| Doze/App Standby | 精确闹钟豁免必须在白名单内；未豁免必须降级并如实标注 |
| FLAG_SECURE | 一律不采样 → `ERR_BLACK_FRAME`/`ERR_SCREEN_LOCKED` 分类错误 |
| 厂商 ROM | 华为/小米/OPPO/vivo 自启与保活白名单差异化 → PermissionCenter ROM 适配表 + 跳转写死到页 |
| 网络代理 | 国内网络环境可配 `HTTP_PROXY`/`HTTPS_PROXY` 注入引擎 env（对齐用户代理经验） |
| 存储 | scoped storage：脚本资产走应用私有目录 + 用户授权目录（SAF）；`MANAGE_EXTERNAL_STORAGE` 作为 P2 可选权限条目 |

---

