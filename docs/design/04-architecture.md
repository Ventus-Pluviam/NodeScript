## 4. 总体架构

### 4.1 分层与依赖方向（文本图）

```
┌─────────────────────────────────────────────────────────────────────┐
│  :app  (Compose UI · 脚本 IDE / 任务中心 / 控制台命令 / 日志管理 / 能力中心 / 打包向导)    │
├─────────────────────────────────────────────────────────────────────┤
│  应用服务层 (androidx 生命周期 / FGS / 定时 / 仓库)                     │
│   RuntimeController · EnginePool · Watchdog · Scheduler              │
│   ScriptRepo · PermissionCenter · Packager · PluginManager           │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ 只依赖领域层接口
┌──────────────────────────────▼──────────────────────────────────────┐
│  领域层 :domain (纯 Kotlin，零 Android 依赖，可单测)                   │
│   ● ScriptEngine / EngineSession / ExecutionHandle   (引擎 SPI)      │
│   ● AutomationChannel / UiNodeTreeReader / FrameSource               │
│   ● ImageAnalyzer / OcrProvider / Datastore / UiHost                 │
│   ● PermissionFacade / CapabilityState (三态状态机)                   │
└───────▲──────────────────────────────▲───────────────────────────────┘
        │ 实现 SPI                      │ 实现 SPI
┌───────┴────────────────────┐   ┌──────┴──────────────────────────────┐
│ :platform:capabilities     │   │ :engine:node-process    :engine:sandbox*│
│  a11y / 截图 / 输入 / 悬浮窗 │   │  Node host (.so 装载 + bridge)      │
│  / 系统 / 存储              │   └─────────┬──────────────────────────┘
└────────────────────────────┘             │ JNI
┌──────────────────────────────────────────▼──────────────────────────┐
│  桥基础设施 :bridge (双向)                                            │
│   Kotlin Router · RequestRegistry(TTL) · HandleRegistry(generation) │
│   EventBus · transports · JS facade (TS) · N-API addon · JNI glue    │
└─────────────────────────────────────────────────────────────────────┘
        ▲ 控制面                    ▲ 图像数据面（独立 so：libopencv.so）
┌───────┴──────────────────┐   ┌───┴───────────────────────────────────┐
│ :bridge:native (libnode) │   │ :bridge:image (OpenCV 管线)            │
│  node::Start / TSF 管理   │   │  RGBA→灰度/找色/模板匹配/特征/旋转       │
└──────────────────────────┘   └───────────────────────────────────────┘
```

依赖规则（Gradle 强制，`api`/`implementation` 配置 + 架构测试把关）：
- `:app` → `:app-service:*` → `:domain`
- `:platform:*` → `:domain`（**实现**领域接口，**不反向**）
- `:bridge:java` 依赖 `:domain`（DTO 复用）；`:bridge:native` 仅被引擎宿主进程使用
- `:domain` 零 Android / 零桥依赖，纯 Kotlin，全部可 JVM 单测

### 4.2 进程拓扑

```
┌──────────────────────────────────────────────────────────────────────────┐
│ :main  （前台进程 · 显著性最高）                                            │
│   Compose UI（脚本列表/编辑器/控制台命令/日志管理/任务中心/能力中心/打包向导）              │
│   AccessibilityService（本进程，绑定了一次 Binder，离系统最近）              │
│   RuntimeController / 引擎池控制 / Watchdog 仲裁                          │
│   Scheduler（意图日志 checkpoint 驻留）+ 精确闹钟 + 开机 specialUse FGS     │
│   PermissionCenter（three-state 门禁）                                    │
│   CapabilityManager：MediaProjection 会话 / overlay / 通知 / datastore     │
└───────┬──────────────────────────────────────────────────────────────────┘
        │  bridge router（unix socket / binder？见 §7.5）
┌───────▼──────────────┐   ┌───────▼──────────────┐   ┌──────▼────────────────┐
│ :node0 引擎进程        │   │ :node1              │   │ :sandbox *已裁          │
│  node::Start          │   │  ...                 │   │  (QuickJS 不进排期)     │
│  N-API addon+TSF      │   │  池容量由设备内存决定 │   │  interrupt handler     │
│  单脚本/单 context     │   │  执行 slot 持 FGS     │   │  白名单 auto.* 子集      │
│  libopencv.so      │   │                      │   │  独立进程：最不可信最隔离 │
└───────────────────────┘   └──────────────────────┘   └───────────────────────┘
```

进程职责切割的推演（含批判结论）：
- **无障碍服务放 `:main`**（离系统最近、显著最高、Binder 调用最省），且**与脚本进程解耦**——脚本崩了无障碍服务不死，反之亦然。批判 5/8 交叉验证了「无障碍与运行时同进程=一损俱损」「无障碍进引擎进程是错误」，主进程承接它被否决否认性证伪。
- **脚本（Node）只在 `:nodeN`**，执行中的 slot 持有 specialUse FGS（绑定到 :main 继承进程重要性，形成 group），防 LMK 优先回收。
- ~~**QuickJS 沙箱 `:sandbox`** 单独进程~~ **已裁（2026-09-26，§18 第 1 项）**：不再有第三个进程。CPU 风暴/死循环的吸收者就是 `:nodeN` 自己（进程边界 + 心跳/差分看门狗，§8.4/§8.8），与沙箱无关。

---

## 5. 进程/线程模型

### 5.1 进程
- `:main`：UI + 服务 + 无障碍 + 调度 + 能力（1 个常驻进程，specialUse FGS 保活）。
- `:node0…N`：脚本引擎进程。**每引擎进程恰好一个 `node::Start`/单一 v8 isolate/单一 context**（P0 单脚本进程一对一；并发 = 池化多进程）。孤立过程：
  - **池容量自适应**：由 `/proc/meminfo` + 设备分级决定，默认 1–2，≥6GB 内存 → 至多 3；低内存模式堆上限降为 128MB / 池=1。
  - 一个常驻引擎 slot 绑一个持 FGS 的「执行 slot」；闲时进程被复用（脚本之间不共享内存）。
- ~~`:sandbox`：QuickJS（P1）~~ **已裁（2026-09-26）**，引擎进程只有 `:node0..N`。

### 5.2 线程
| 线程 | 归属 | 职责 | 规则 |
|---|---|---|---|
| Android 主线程 | `:main` | Looper / 渲染 / 无障碍回调 / Router dispatch | **永不阻塞等待 Node**；桥调用经队列异步化 |
| Node 事件循环线程 | `:nodeN` | JS 执行 / 全部 JS 逻辑 | 原生入口只应 `GetEnv`+局部 attach，**绝不缓存 `JNIEnv*` 跨函数** |
| libuv worker 池 | `:nodeN` | fs/网络等 off-main | 进 Java 回调时 `AttachAsDaemon`，短生命周期 |
| `:main` 桥接收队列 | `:main` | 收 Node→Java 事件 | 走 Handler/Looper 或直接线程池（不阻塞） |
| 看门狗外带线程 | `:main` | CPU 差分采样 `/proc/<pid>/stat`（O(1) 读，不依赖 Node 心跳）| 见 §8.4 |

### 5.3 互不阻塞协定（桥的线程铁律）
1. **Java 线程向 JS 投递一律 `napi_tsfn_nonblocking`**；Node 事件循环闲置时 `napi_unref_threadsafe_function` 不导致进程不退出（对应 `console.log`/事件流）。**绝不 `tsfn_blocking` 等在自身上**。
2. **JS → Java 请求由 `:main` 路由器异步派发**；严重路径（如无障碍节点读）不要求调用方阻塞——统一走 request-reply，调用方 `await` 且带 TTL。
3. **持有 Java lock 时禁止回调用 JS**（反向调用会与锁序互相等待 → 死锁）。所有回调在释放锁的临界区外投递。
4. **`async_work` 线程不碰 JS**（N-API 约束）；需要回报安全的线程（TSF）或 Android Handler。

### 5.4 错误 → 状态一致性
任何线程挂掉 → 看门狗仲裁 → `:main` 决定「重启 slot」还是「炸任务」→ 按 §8.5 checkpoint 语义恢复。绝无「半死不活还占着 slot」状态。

---

