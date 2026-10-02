# AutoScript 落地状态台账

> **本文件不是契约。** 契约在 [`docs/design/`](design/) 12 卷（入口 [`framework-design.md`](framework-design.md) 索引）。
> 这里只记「哪些已落地、哪些还是接口期、哪次实测推翻了什么」——即原
> 框架设计 §19 那条 9,584 字符的流水账，以及散在各卷里的 `**已落地**` 块。
>
> **锚定规则**：条目一律以 `§X.Y` 锚回契约条款；§号是唯一权威锚，在本文件与
> 设计各卷里同义（§号不随文件位置变化）。
>
> **只追加，不改写历史结论**：新事实加在 [`docs/log/`](log/) 里**当天那个切片**的顶部
> （见下「流水目录」）；被推翻的记账不删除，原地标 `~~作废（日期 + 原因）~~`。
>
> **分片（2026-10-02，backlog C6）**：本文件原 175 KB，流水段 108 KB、实现注记段 59 KB
> 全挤在一处。现在本文件只留**接口期表 + 流水目录**（十几 KB），历史条目搬进
> [`docs/log/<日期>.md`](log/)，实现注记搬进 [`implementation-notes.md`](implementation-notes.md)。
> **§ 号仍是唯一权威锚**，不随文件位置变化；分卷正文里指回台账的链接一条都没改。
>
> **读契约时不要读这里**：正文里的落地注记是「实现注记」，不是规则本身。
> 判一条规则，看 `docs/design/` 对应卷；判它实现没有，看这里。

---

## 接口期（未落地，写了就是撒谎）

> **这张表怎么读（2026-10-02 起）**：它同时收**未落**与**已落地**两种行 —— 已落地的行保留
> 是为了让「这条曾经是缺口」有据可查，但**标题「未落地」只对 `未落` 那几行成立**。要找
> 「还剩什么没做」，看**现状列以「未落」开头**的行；要找「某条后来怎么解决的」，看该行里
> 的日期与链接（细节在 `docs/log/<日期>.md`，口径在 `docs/design-decisions.md`）。
> 本页**只做索引**：长叙事（>200 字）一律下沉到 log/decisions，这里留一行摘要 + 指针。

| 声明处 | 东西 | 现状 |
|---|---|---|
| §18 | 开放决策点 | **已全部拍板**（第 8/9 项 2026-09-25，第 1–7 项 2026-09-26，见 [`design-decisions.md`](design-decisions.md)；§18 保留作决策台账） |
| §14 P1 | MediaProjection 高清会话 | 未落（授权 UI + FGS；换 producer 即插，语义面不动） |
| §14 P1 | QuickJS `:sandbox` 进程 | 未落；模块壳 **2026-09-30 已从 settings 注释摘除**（不计入模块数），**空壳目录与 settings 注释行 2026-10-01 已一并删除**（复活 = 重建模块目录 + include 行加回 + ModuleGraphTest 登记） |
| §14 P1 | `ui` 原生 XML UI 宿主 / `ui_web` | 未落 |
| §9.7 | OCR（P1）/ 插件（P2） | 未落 |
| §10.5 | 生物特征二次确认 | 未落（`BiometricPrompt` 全仓零引用） |
| §8.5/§8.6 | 引擎侧 `waitCompletion` 超时不发起（无人 await 的 run 没人收尾） | **已覆盖（2026-10-01）**：`TimeoutEnforcer{WATCHDOG}` + 看门狗期限线（`KillCause.TIMEOUT`）—— 残余边界见 §8.6（期限只覆盖声明了期限的 run + 轮转须在跑） |
| §8.5 | 意图日志的 **SQLite 实现**（契约写的是「append-only（SQLite，启动即回放）」） | **未落，且分歧已如实标注**：今天全平台生产（含 Android）跑的都是 `JournalFileStore`（jsonl 追加 + fsync + 流式回放，`AppShellKit` 装的就是它）。卡点是接口住 `:app-service:scheduler`（纯 JVM、零 `import android.`），而依赖铁律 `:platform:*` → `:domain` 不反向 —— SQLite 实现要么把 `IntentStore` 搬到 `:domain`（跨模块契约变更，待裁），要么给该模块加 Android 依赖（丢掉纯 JVM 可测）。另：日志**只追加、从不清理**，体积随 run 数线性增长（每次 run 恒定两条行，已无冗余可压），**保留期策略**（老终态行 / 老 nonce 能否丢）会动到 §8.5 的幂等锚点，同样待裁；`JournalFileStore` 的类注释与 `IntentStore` 接口注释已按此改写 |
| §11.2 T2 / §10.2 | **npm 生产装配接线**（`lockKey` / `executor` / `scriptExecutor`） | **部分落地（2026-10-01）**：`executor` **已接线** —— 素材随包（`assets/npm/**` ← gradle `prepareNpmCliAssets` ← `node-runtime-build` 出口）→ 启动期 `AssetTreeCliSource` 幂等落位 `files/npm/` → 注入 `HostNodeExecutor`（宿主 = `nativeLibraryDir/libnoden.so`）；两条同时成立才注入（部署就位 + 有宿主），否则保持 `Unavailable` 且原因原文进 `AssembledShell.npmCliFailure`。**仍缺**：`lockKey`（`lock.sig` 既不签也不验，全仓无 `KeyProvider` 实现；接缝形状 A1c 已就位）与 `scriptExecutor`（T1 spawn 属 P1）、快照导出。另：~~素材版本 = **npm 11.19.0 ≠ §10 脊梁的 npm 12.x 系**（落差登记在 [`backlog.md`](backlog.md)）~~ **落差已消解（2026-10-02 批 9）**：素材换 registry `npm@12.2.0`，§10.1 脊梁满足（backlog A6 划掉，[`design-decisions.md`](design-decisions.md) 第 26 项）。契约侧标注同批更新（§10.1 接线现状 + §10.12 风险表 + `SECURITY.md`）。**2026-10-02 勘误**：本条原先还写了「+ §11.2 T2 + §11.3 第 8 条」—— 实测**没改到**：§11.2 T2 行本来就不含 npm 版本叙述（无物可改），而 `11-security.md:64-69` 的「素材版本落差」段（在 §11.3 第 8 条**之上**的同一子弹列表内，不在第 8 条里）**原文未动、仍写 npm 11.19.0**，且按 `SECURITY.md` 的优先级规则「两处有出入以设计文档为准」→ **错的那份赢**。已登记 [`backlog.md`](backlog.md) 的 **C10** |
| §15 | APK ≤ 40MB | **已超支**（实测 ≈81MB，见 [`design-decisions.md`](design-decisions.md#已推翻--已改口径)） |
| — | 真机红测：exec/dlopen + 桥全链 | **已做**（2026-09-29，见下「流水」；非 root、Android 13/arm64、生产布局） |
| §12.1 | **脚本 API 参考（用户向）** | **已落地（2026-10-02，批 10 / backlog C7）**：[`docs/api/`](api/index.md) 由 `bridge/js` 的公开注释经 typedoc 生成（15 个 md，生成物入库 + CI 零 diff 门）。**覆盖边界**：`auto.*` 门面的方法级说明 + 类型；引擎/桥的**内部件**不在入口面（作者划的边界）。契约侧指针加在 §12.1 |
| — | 真机红测：16KB 页机 / SELinux enforcing / `nativeLibraryDir` 提取路径 / targetSdk36 exec 策略 | 未做（设备 PAGE_SIZE=4096，这几项该机**原理上测不到**） |
| — | 真机红测：性能数字（冷启/帧往返/图像算子） | 部分（冷启 158ms→新件 181–206ms；桥往返 p95=1ms；引擎 RSS≈46MB；`Intl` zh/en 运行期**已验**；图像算子 A2–A4 已量 2026-09-30：A3 契约口径 0.88ms ✅；~~A4 933.6ms ❌、A2 计算段 1912.8ms ❌~~ **作废（2026-10-02，前提过期）** —— 2026-10-01 五次实测 A4 19.82ms ✅ / A2 88.86ms ✅，残余 48×48 全帧于 2026-10-02 E2 拆双门 + 精确兜底后 host 33ms / **真机 64.43ms**（同会话精确 882ms，13.7×）**仍 ❌ 差 1.6×** → 同日拍板放宽该形态判据 <100ms（`design-decisions.md` 第 25 项）**✅ 转绿（形态注明）**、E5 关闭不投优化，见 `log/2026-10-02.md` 与 §7.7 第七次块） |

---

## 已推翻 / 已改口径

见 [`design-decisions.md`](design-decisions.md#已推翻--已改口径) —— 口径变更属决策侧，
只在那里写一份（本文件不复制，避免两处漂移）。

## 流水（最新在最上）

**2026-10-02 起，流水按日期分片**（口径见 [`design-decisions.md`](design-decisions.md) 第 22 项）：
新条目加在**当天那个切片**的顶部 —— 该文件不存在就新建一个，并把下表那一行的「条目」数 +1。
被推翻的记账不删除，原地标 `~~作废（日期 + 原因）~~`。索引与全文见 [`docs/log/README.md`](log/README.md)。

| 日期 | 条目 | 主题 | 文件 |
|---|---|---|---|
| 2026-10-02 | 14 | 文档面改进批（C10 契约订正 + D2 状态页读法 + D7-② 参考件入档）· 第二轮外审处置（证伪 2 / 收窄 1 / 立刻改 1 / 登记 9）· 批 10 C7 typedoc API 参考 + B4 依赖供应链 · C9 总索引落地 · C6 分片落地 · A2b 拍板落地 · E1 拍板落地 · E2 拆双门 + 精确兜底 · E5 放宽拍板 · 批 9 A6+E4 素材换源 · 结构面批 D9+D10+D12（HostNpm 并份 / bridge/README.md / CODEOWNERS）· 批 12 D5+B7+D14（贡献门槛拆分 / INTERNET 真机 A/B / ModuleGraphTest 证伪） · **批 13 许可改 GPL-2.0-or-later + `:ui` 前端重构（Telegram 质感 / 深浅双主题 / 状态层三态收敛）** · 批 14 命名去 `Tg` 前缀 + 补齐动效（按压反馈 / 页签指示线滑动 / 列表落位） | [`log/2026-10-02.md`](log/2026-10-02.md) |
| 2026-10-01 | 17 | 外审整改收尾、批 1–7、两次外审建议入池 | [`log/2026-10-01.md`](log/2026-10-01.md) |
| 2026-09-30 | 15 | 外审整改步骤 1–8、图像提速三案、A 组真机实测 | [`log/2026-09-30.md`](log/2026-09-30.md) |
| 2026-09-29 | 1 | 真机垂直切片红测（非 root） | [`log/2026-09-29.md`](log/2026-09-29.md) |
| 2026-09-25 及更早 | — | 自 §19 结语整段外迁 | [`archive/status-2026-09-25.md`](archive/status-2026-09-25.md) |

## 归档

- [2026-09-25 及更早流水（自 §19 结语整段外迁，逐字保留）](archive/status-2026-09-25.md) —— 2026-10-01 归档，原 14,625 字节单行随之搬走。
- [2026-09-29 – 2026-10-02 流水（分片，逐字保留）](log/) —— 2026-10-02 分片（backlog C6），
  四个按日期的文件共 40 条，索引见 [`docs/log/README.md`](log/README.md)。

## 实现注记（自各分卷外迁，逐字保留）

**2026-10-02 起本节内容搬进 [`implementation-notes.md`](implementation-notes.md)**（分片，backlog C6）。
本节标题**逐字保留**：`docs/design/08-execution.md`、`09-capabilities.md` 等卷正文里指回本节的链接
（`design-status.md#实现注记自各分卷外迁逐字保留`）据此解析，标题不改则那些链接一条都不用动。
分卷的搬迁状态（已迁 / 未迁两表）随内容一并搬走，见该文件的头两节。
