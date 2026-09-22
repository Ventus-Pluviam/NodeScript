# :platform:capabilities —— 能力命名空间语义层

> 设计章节：`docs/framework-design.md` §9.1–9.4 / §12.2 / §12.3。
> 本文件只记**本模块内部**的分层约定与「真实现怎么接」；跨模块契约以 `:domain` 为准。

## 分层（§12.2「分两层」的上面那层）

```
JS facade (bridge/js/src/a11y.ts …)
        │  bridge invoke('a11y','findOne',…)
        ▼
handler 语义层 ── 本模块  A11yNamespaceHandler / ScreenNamespaceHandler / SystemNamespaces
        │  参数校验 / 信封编码 / 错误码分类（无 Android 接触面）
        ▼
SPI 契约 ────── :domain  UiNodeTreeReader / UiActionExecutor / InputProvider / FrameSource / SystemContracts
        ▲
        │  实现（两处，别混）
内存可测形态 ──── 本模块  InMemoryUiTree / InMemoryInputProvider / ScreenshotSource(FrameProducer 缝)
Android 真实现 ── :platform:system（SystemSpis.of）或 §9.1 的无障碍服务（待落地）
```

挂载发生在装配层：`CapabilityNamespaces.*` 把 handler 折成 `:domain` 的
`NamespaceHandler` → `AppShellKit.assemble` 的注入缝（`a11yHandler`/`screenHandler`/`systemHandlers`，存储面另有 `datastoreHandler`/`zipHandler`/`settingsHandler` 三条独立缝，见 `AppShellKit` KDoc）→ `AppShell.assemble`。

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
| a11y | `A11yNamespaceHandler`（已就绪，只依赖 `:domain`） | `AccessibilityService` 遍历 `AccessibilityNodeInfo` + `dispatchGesture`，**待落地**（§9.1） |
| screen | `ScreenNamespaceHandler` + `ScreenshotSource`（333ms 节流/会话/分类错误已就绪） | MediaProjection 会话 + `SnapshotAwareProducer` 的 Android 实现，**待落地**（§9.2） |
| 系统五个 | `SystemNamespaces`（五个 handler） | `SystemSpis.of(context)` 已给四件（`shell`/`device`/`app`/`floatingWindow`）；`dialogs` 待 §14 P2 |
| 存储三个（§9.6） | `DatastoreNamespaceHandler` / `ZipNamespaceHandler` / `SettingsNamespaceHandler`（三个独立注入缝，不入 `systemHandlers` 束） | `SystemSpis.of(context)` 三件齐（`AndroidDataStore`/`JdkZipArchiver`/`AndroidSystemSettings`）；**生产拼装待装配层拓扑决策** |

## 尚未实现（别在文档里写成「差不多能用」）

`AccessibilityService` 真实现、MediaProjection 会话真实现、root/Shizuku 输入通道（§9.3 P1）。
