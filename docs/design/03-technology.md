## 3. 技术选型总览

| 决策点 | 定案 | 关键理由 | 反方代价（已权衡） |
|---|---|---|---|
| **Node 发行渠道** | **Node 24.x LTS 源码自建 `libnode.so`**，fork/自持 `nodejs-mobile` 的构建 recipe 管线并自行维护 | 官方 nodejs-mobile 停在 18.20.4（已 EOL）且 ELF 按 4KB 对齐，**在 16KB 页设备上 dlopen 直接崩**；自建才能跟进 LTS 升级补漏洞 | 维护构建管线成本；需长期持有（预期树哈希门禁、NDK r27d/r28、jar 剥离、zlib/gzip 静态化约 26MB ABI 预算） |
| **引擎执行模型** | **每脚本一个 `:node` 进程**，进程池按设备内存自适应 | 唯一的真实执行隔离；`process.exit`/OOM/死循环只杀自己；全局变量天然隔离；崩溃可重建 | 内存开销（池默认 1–2，≥6GB RAM 到 3）；不能共享一个 Node 实例的模块缓存 |
| **JS 引擎** | **只有 Node**（QuickJS 沙箱已裁，§18 第 1 项 拍板） | 单引擎免掉双引擎 API 对齐与 `:sandbox` 进程/白名单三件大事；脚本特权改由**安装时用户选择 + §11 来源提示**约束，不靠进程隔离 | 第三方脚本与自写脚本同权（见 §11 表） |
| **桥** | TS facade → N-API addon（`NAPI_VERSION=10`）→ JNI → Kotlin Router；**全异步 JSON-RPC + requestId 关联**；每 context 一个 `napi_threadsafe_function` | N-API 稳定 ABI、nodejs-mobile 系已验证；TSF 允许任何 Java 线程安全投递事件，`napi_unref_threadsafe_function` 闲置不保活事件循环 | 无同步调用便利性（/并发模型语病，已在 §7.2 定死） |
| **无障碍通道** | `AccessibilityService` 部署在 **`:main`** 进程；**紧凑索引树**传输；句柄带 generation | 与引擎进程分离＝无障碍服务存活不依赖脚本进程；紧凑树省 IPC 体积（全树 JSON 序列化是性能杀手） | 树构建在 UI 进程承担；事件洪峰需节流 |
| **截图** | 默认 **a11y `takeScreenshot`**（API34 起 333ms 节流）；**MediaProjection** 做会话式实时截屏/录屏（API34 每会话确认 + FGS 前置）；**图像分析全走 native**（独立 `libopencv.so`，OpenCV 4.14.0 静态链接 + kleidicv 默认 ON） | 图像管线 0–1 拷贝直达 Native，避免 Bitmap→Byte[]→Buffer 多次拷贝；`FLAG_SECURE` 窗口如实返回 `ERR_SCREEN_LOCKED/ERR_BLACK_FRAME` 系错误对象 | 维护两份 so；OpenCV 静态链接体积 |
| **UI** | 脚本 UI = 桥把 XML 布局描述推给 `:main` 渲染（原生 View）；`ui_web` 走 WebView + JS 桥；悬浮窗独立小型宿主 Activity | AutoJsPro 已验证；XML→View 桥符合「脚本进程只产声明、主进程渲染」原则 | UI 事件回的桥链路较多 |
| **保活** | **specialUse FGS**（`onCreate` 即 `startForeground`，声明 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，无超时）+ 电池优化白名单引导 + 精确闹钟看门狗 + 开机 specialUse 恢复；**全部作为一级权限项进能力中心** | API35 普通 FGS 有 6h 超时、普通后台启动受限；specialUse 无超时，精确闹钟不受 FGS 后台启动限制 | Play Store 不可发行；部分 ROM 需要引导 |
| **打包** | 模板 APK 改写（AXML/ARSC 编辑替换 application + 注入 assets/project），复用宿主 Node 引擎 so | AutoJsPro 已验证的发行形态；不需要为每个脚本重编 C++ | APK 依赖宿主引擎版本 |
| **npm 支持** | **D（混合）**：vendored 真 npm CLI（npm 12.x 系，Node≥24.15）在**专用安装会话进程**内进程内执行——P0 默认零 spawn（npm12 官方 `allowScripts=none` 等默认语义把「无 child_process」从 workaround 变成契约）；P1 加 spawn 桥升级通道（批准后 lifecycle/npm run）；内置离线 bundle + 精选 tarball 种子通道 | 纯 JS 生态（axios/dayjs/lodash/ws 等）安装**实证无需子进程**（strace 实测 0 execve）；真 CLI 白拿 lockfile v3/audit/审批语义且可审计；不重造轮子 | npm CLI ~8–9MB 体积；供应链护栏只能靠带外信任锚与审批人机分离（§10） |
| **进程隔离** | `:main`(UI+服务) / `:node0..N`(脚本) | §4 进程拓扑（`:sandbox` 已裁，图中留痕标注） | — |
| **SDK 基线** | minSdk **26** / compile&target **36**（Android 16；真值见 [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml)）/ **ABI 只发 arm64-v8a**（`ndk.abiFilters` 单值，B10 拍板；x86_64 待 P1）；**16KB ELF 对齐进 CI 门禁** | 2026 事实标准；16KB 页设备成为主流 | 放弃 32 位旧机 |

---

