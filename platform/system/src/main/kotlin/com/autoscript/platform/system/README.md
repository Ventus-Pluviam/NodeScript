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

真机 ops 实现分别住 `WindowManagerOps.kt` / `PackageManagerOps.kt`（这两个文件里有真
`WindowManager`/`PackageManager` 调用，本机 JVM 只编译、不执行）。

## 铁律在本模块的落点

- **铁律 3（每次操作有 TTL）**：`AndroidShellExecutor` 的超时是**实现者义务** ——
  到点 `destroyForcibly` 并抛 `ERR_TIMEOUT`，不是返回半截输出，更不是继续挂着。
- **诚实上报**：`app.launch` 回 `false`、`currentPackage` 回 `null` 都是**答案**不是异常；
  `dialogs` 的 `DialogHost` 没实现就**不提供**（注入侧留 null → 桥回 `ERR_NOT_IMPLEMENTED`），
  绝不塞一个凑数实现。
- **能力门禁不在这里**（§9.5）：`PermissionFacade` 住 `:app-service:*`，由装配层先判后取；
  本模块只处理"系统在调用现场拒绝"这一事实（折成分类错误或 null）。

## 尚未实现（别在文档里写成"差不多能用"）

`dialogs`（`DialogHost`，overlay 真弹窗 + 通知回调，§14 P2）、settings/通知。
（datastore 已落地：`AndroidDataStore` + `SqliteKvOps`；zip 已落地：`JdkZipArchiver`，
入口 `SystemSpis.Bundle.{datastore,zip}`；生产拼装仍待装配层拓扑决策 ——
实现备好 ≠ 已接线，别在文档里写成"能用了"。）
