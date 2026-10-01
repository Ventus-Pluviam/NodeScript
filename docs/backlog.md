# AutoScript 待办池（backlog）

> **本文件不是契约，也不是台账。** 契约在 [`docs/design/`](design/)（入口 [`framework-design.md`](framework-design.md)），
> 落地状态在 [`design-status.md`](design-status.md)，口径变更在 [`design-decisions.md`](design-decisions.md)。
> 这里只放**尚未排期的待做项** —— 是收件箱，不是承诺。三条纪律：
>
> 1. **排期了就从这里移走**（或就地标 `已排期（谁/何时）`）；**做完了**去 `design-status.md` 记流水；
>    **裁定不做了**去 `design-decisions.md` 记口径 —— 本文件不留"历史结论"。
> 2. **「核实」列是硬要求**：`✅（日期）` = 在当前 tip 上真读过代码/文件确认过；
>    `待核实` = 只是外部审查的原始陈述（静态阅读、不构建不运行），**动手前先自己验一遍**。
> 3. **一次只做一批**：按 §F 的批次走，每批做完跑 CI 同源门（见 `CLAUDE.md` 构建节）。

**来源**：2026-10-01 外部审查（静态阅读约 15 个文件，未构建未运行）：优化 / 文档 / 结构三部分，
已逐条落进下表并标了核实状态。成本记号沿用外审：**S** < 1 天、**M** 1–3 天、**L** > 1 周。

---

## A. 安全与正确性

| # | 事项 | 证据位置 | 核实 | 影响 | 成本 |
|---|---|---|---|---|---|
| ~~**A2b**~~ | ~~**shell 捕获输出无上限**~~ —— **已拍板并落地（2026-10-02，选「静默截断 + 显式日志 Warning + 返回截断标志」）**：口径见 [`design-decisions.md`](design-decisions.md) 第 21 项，契约见 §9.6，流水见 [`design-status.md`](design-status.md)。上限 = 每条流 1 MiB（`ShellCaptureLimit.MAX_CAPTURE_BYTES`，最坏 JSON 膨胀 ×6 仍在 §7.5 的 8 MiB 单帧预算内）；`ShellResult.truncated` 纯增量、缺省 false；到顶后仍读到 EOF（否则退化成满管道死锁） | `AndroidShellExecutor.PipeReader` | ✅ 2026-10-02（拍板 + 落地） | S（已做完） |
| **A5** | 桥没有 per-engine 身份：同一 uid 的任何进程可达全部命名空间（`SECURITY.md` 已承认是有意为之），无法按脚本/按 run 归因与审计 | 同 uid 门禁 `BridgeSocketListener.kt:117-122` 是 fail-closed；abstract 名可预测 | ✅ 2026-10-01 | 以后想加 per-run 权限会很贵 | M（协议变更，须与 `main.cpp` + JS bootstrap 同批） |
| **A6** | **vendored npm 版本低于契约脊梁**（2026-10-01 A1 收口时暴露）：素材取自 Node 24.21.0 源码树的 `deps/npm` = **npm 11.19.0**，而 §10.1 脊梁写的是「npm 12.x 系」，§10.12 风险表明写「降级 npm11 会恢复『脚本默认执行』使护栏静默消失」。**护栏没有静默消失，但只剩一层**：`HostNodeExecutor` 对每条命令硬编码 `--ignore-scripts`（§11.1 T1 主控，与版本无关）；npm 12 的 `allowScripts=none`「官方默认」这层不在位，且**非脚本** spawn 路径的第二层兜底（§10.12 末行 child_process 拦截 shim）本就未落。升级路径二选一：换素材来源（另下 registry tarball / 自建裁剪）或等 Node 线携带 12.x；改 `NPM_CLI_VERSION` 即触发 `node-slice` 全链回归（~2h20m）。**能不能靠「等 Node 线携带」升**——2026-10-01 用 `nodejs.org/dist/index.json` 实测否掉了：**868 条官方发布里一条 npm 12.x 都没有**（最新 v26.10.0 / 2026-09-21 携带的是 npm 11.19.1），即走「Node 源码树 `deps/npm`」这条路**原理上拿不到 12.x**，要升必须换素材来源（另下 registry tarball / 自建裁剪）；**npm 12 本身是否存在、其 `allowScripts` 默认语义是否如 §10 所述仍未核实**（本机 `registry.npmjs.org` 不可达，WebFetch 亦被网络策略挡住） | `node-runtime-build/VERSIONS.env`（`NPM_CLI_VERSION=11.19.0`）、`fetch-and-build.sh` §9、`docs/design/10-npm.md` §10.1/§10.12、§11.3 第 8 条 | ✅ 2026-10-01（版本号实读素材 `package.json`；契约落差逐字比对过） | S（若只是换素材源）/ M（若要自建裁剪或改调用链） |

## B. CI / 工程基建

| # | 事项 | 证据位置 | 核实 | 影响 | 成本 |
|---|---|---|---|---|---|
| **B5** | **CI 出的 APK 里没有引擎二进制**（2026-10-01 加 `android-build` job 时暴露）：`engine/node-process/build/native-local/*`、`node-runtime-build/out/{libnode.so,npm}` 都不在 git，装配期按「缺位只 warn」放行 → CI 的 APK 是**无引擎**形态（能装、能起 UI、脚本跑不了）。要 CI 出真形态就得先拿 node-slice / image-native 的 artifact（跨 workflow 取件：`actions/download-artifact` 需同一 run 或 `gh api` 拉历史 artifact），再喂 `LIBNODE` / `NPM_CLI_ROOT` / `LIBOPENCV` 起 assemble | `.github/workflows/ci.yml`（`android-build` job）；`build-logic/src/main/kotlin/autoscript.engine-natives.gradle.kts` | ✅ 2026-10-01（三大来源全不在 git，实读 gradle 任务确认） | CI 的 APK 门只证明「能构建」，不证明「能跑」 | M |
| **B6** | **覆盖率仍为零**（B1 的原始清单里唯一没做的一项）：jacoco 要挂在**根 `build.gradle.kts`**（协调者冻结）或各模块约定插件里；先要定口径 —— 门设不设阈值、报告传不传 artifact、`:domain` 之外哪些模块纳入 | `build.gradle.kts`（根，冻结）；`build-logic/` | ✅ 2026-10-01（全仓零 jacoco 引用） | 改动质量只有"红/绿"，没有盲区可见性 | S（技术）/ 待批（改冻结文件） |
| **B3** | 无设备/仪器化测试道；`libs.versions.toml` 里的 `espresso` / `androidx-test-junit` **零引用**（要么用起来要么删目录项）；16KB 页 / SELinux / targetSdk exec 三条真机检查仍空白 | 全仓无 `androidTest` 目录；`libs.versions.toml:15,31,32` | ✅ 2026-10-01 | native exec/dlopen/a11y 只在一台设备上验过 | L |
| **B4** | 无依赖漏洞扫描 / SBOM / `dependency-review-action`；`bridge/js/package.json` 无 `engines` 字段 | `package.json`；CI 无相关 job | 待核实（`engines` 未逐字读） | 升级债与漏洞看不见 | M |

## C. 文档

| # | 事项 | 证据位置 | 核实 | 成本 |
|---|---|---|---|---|
| ~~**C6**~~ | ~~`design-status.md` 122KB / 737 行、单元格极长，`design-decisions.md` 46KB —— 外审建议拆「当前状态页 + 按日期的日志文件」并加 `docs/README.md` 索引。**注意**：拆分要保住 § 锚点与「只追加」纪律（§号是唯一权威锚）~~ —— **2026-10-02 已做**：实测当时是 175KB / 1372 行（比登记时又涨了 53KB）。拆成三件：`design-status.md` 只留**接口期表 + 流水目录**（7.4KB）、流水按日期切片到 `docs/log/{2026-09-29,09-30,10-01,10-02}.md`（35 条，索引 `docs/log/README.md`）、实现注记搬到 `docs/implementation-notes.md`（61KB）。**§ 锚一条没动** —— 分卷正文 9 处 `design-status.md#实现注记自各分卷外迁逐字保留` 靠「标题逐字保留 + 占位指针」解析；搬迁逐字（切片拼接 == 原 45–1016 行、注记正文 == 原 1022–1372 行，按字节比对通过）。口径见 `design-decisions.md` 第 22 项。**未做**：`docs/README.md` 总索引（外审建议里的第三件）—— `framework-design.md` 的「文档边界」表已经承担了这个角色，再开一份就是第二个事实来源，登记为 **C9** | `docs/design-status.md` | ✅ 2026-10-02（拍板 + 落地） | M（已做完） |
| **C7** | JS facade 没有**用户向** API 参考（`12-js-api.md` 是设计文档）→ 可从 `bridge/js` 生成 typedoc | `bridge/js/src` | 待核实（未评估 typedoc 覆盖度） | M |
| ~~**C9**~~ | ~~**要不要给 `docs/` 加一份总索引 `docs/README.md`**（外审 C6 建议里的第三件，2026-10-02 拆 C6 时未做）：现在 `docs/framework-design.md` 的「文档边界」表 + 根 `README.md` 的仓库地图已经覆盖了「哪份文档回答什么」，再加一份就是**同一事实写两遍**（本仓对这件事的判据见 `design-decisions.md` 第 15/22 项）。若要做，正确形态是**把 `framework-design.md` 的边界表搬过去、原地留指针**，而不是新增一份并列的表~~ —— **2026-10-02 已做**：按登记时的形态落地 —— [`docs/README.md`](README.md) 收下「文档地图」（`framework-design.md` 的边界表 + 扩展：逐行带**纪律**一列、覆盖 12 卷 / 入口 / 决策 / 状态页 / 流水切片 / 实现注记 / 待办池 / 归档），`framework-design.md` 与 `design/18-19-ledger.md` 两处「文档边界」段**原地换成指针**（该表全仓只剩一份）。`CONTRIBUTING.md` 的四份文档分工表**保留**（讲的是「写东西时的纪律」，与地图的分工在文字里各自点明）。口径见 `design-decisions.md` 第 23 项 | `docs/README.md`；`docs/framework-design.md` | ✅ 2026-10-02（拍板 + 落地） | S（已做完） |
> **旁注（2026-10-02，PR #21 的 CI 红换来的一条）**：文档链接门读的是 `git ls-files '*.md'` —— **输入是索引不是工作树**。拆 C6 时我在 `git add` **之前**跑了门，新增的五个文件还是 untracked、不在扫描面里，于是本地 148 条全绿、CI 214 条红 19 条。**跑门的顺序是「先 add 再跑」**；同类教训（本地绿 ≠ 门禁有效）见 `design-decisions.md` 与 `docs/design-status.md` 的口径。

> **C8（文档数字改成生成片段）已裁定：保持现状、不动作** —— 2026-10-01 提案人本人撤回，
> 理由与两条附带观察记在 [`design-decisions.md`](design-decisions.md) 第 15 项。**不再作为待办。**

## D. 结构 / 重构

| # | 事项 | 核实 | 成本 |
|---|---|---|---|
| **D1** | `:app-service:permission-center` main 只有 **91 行**（独立模块偏重）：并回现有模块，或明确"等它长"。**核实结论（2026-10-01 批 7，建议维持现状）**：① 它是 §9.5「**所有模块不得直接查 Settings，一律经此门禁**」那条例外的物理载体 —— 合并进别的 `:app-service:*` 会让"门禁住 `:platform:*`（archUnit 黑名单含 `com.autoscript.appservice..`，见 `SystemNamespaces` KDoc）"这条边界变成模块内的口头约定，独立模块正是把这条边界变成 Gradle 依赖图上的**硬边**（`ModuleGraphTest` 允许集里 `permission-center → :domain` 单点）；② 它只依赖 `:domain`，并回任何 `:app-service:*` 都要给那个模块新增一个上游依赖或开子包 —— 代价大于收益；③ 待它长：门禁面已经在长（`Capability` 九项），**建议拍板维持独立**，记入 `design-decisions` | ✅ 2026-10-01（依赖图与 archUnit 边界已逐条核对） | S（仅决策记录） |
| **D6** | 命名不一致。**已做（2026-10-01 批 7）**：`@autojs/*` 这个 npm scope 在**描述面**的 9 处（`06-modules`/`07-bridge` ×3/`09-capabilities`/`12-js-api`/`CLAUDE.md`/`bridge/js/package.json`/`ImageAnalyzer.kt`）全部改成事实侧口径 —— 脚本侧导入名 `auto`（`filesDir/node_modules/auto`）与真实交付物名 `bridge_native.node`；**`AutoJsPro` 九处保留**（那是**对标产品名**，不是自己的名字）。**还剩两件**：① `.autojs` 存储目录与 `autojs-lock-v1` 签名前缀在契约正文（§10.2/§10.5）与全仓 30+ 处实现/测试里一致使用 —— 改它是**存储格式变更**（会读不出用户既有 lock 签名），须拍板并给迁移/兼容策略，不是命名顺手能改的；② **发布用的 npm scope 是否自己拥有**需你确认（`bridge/js` 标 `"private": true`、不发布，发布 scope 归属未核） | ✅ 2026-10-01（描述面 9 处已改；① 需拍板 / ② 需你确认） | S（①）/ 待你确认（②） |
| **D7** | 大文件**余量**：`AppShellApplication.kt` **633 行**（Android 生命周期本体）、`Scheduler.kt` **522 行**（两个 DTO + 一个 470 行类，**无干净接缝**）—— 另有四个 2026-10-01 批 6 已拆（`InstallCoordinator` 997→859+`InstallSeams`/`SeqRing`、`NativeImageAnalyzer` 567→407+`JniOps`、`AppShellKit` 537→328+`AssembledShell`、`imgnative.cpp` 1440→688+match/feature TU+`imgnative_internal.h`），流水见 [`design-status.md`](design-status.md) | 待核实（余下两个是否有值得付的刀口） | S–M |

## E. 需要拍板（产品面，不是工程顺手能做）

| # | 事项 | 现状 | 核实 |
|---|---|---|---|
| ~~**E1**~~ | ~~APK 体积~~ —— **已拍板（2026-10-02，选 (a) 接受 + 明示）**：口径见 [`design-decisions.md`](design-decisions.md) 第 20 项。落地 = 能力中心单列一段**实测**安装体积（总数 + 其中引擎那一段 + 引擎 .so 在不在）：`InstallSizeRead`（`:app`）/ `CapabilityCenterSnapshot.installSize`（`:domain`）/ `InstallSizeState.text()`（`:ui`）。**量的是已装 APK 与 native 库目录的真实字节，不是抄 §15 的 ≈92MB**（那是未压缩三件套，与用户装的不是同一个数）。注意 `useLegacyPackaging=true` 是 exec 的前提，会抬安装体积 | ✅ 2026-10-02（拍板 + 落地） |
| **E2** | 图像算子预算：A4 933.6ms、A2 计算段 1912.8ms 均 ❌（契约口径不达）—— 修还是改口径？ | `design-status.md` 流水 2026-09-30 实测段 | ✅ |
| **E3** | 设备/仪器化测试道（含 16KB 页镜像）值不值得投入 L 级成本 | B3 | ✅ |
| **E4** | npm 素材还能再剪：Node 源码树 `deps/npm` 原树里 `test/`（1.9MB 表观）+ `tap-snapshots/`（816K）是纯测试件，设备上永远用不到 —— 现在 §9 只剪 `docs/` `man/`。剪掉约省 2.7MB 表观（压缩后更少），**属 §15 体积预算那笔账**（已超支 81MB，见 E1）。改一行剪裁列表即可，但会触发 `node-slice` 全链回归（~2h20m；ccache 命中时短些），故与其它 `node-runtime-build` 改动合并成一次 | `node-runtime-build/scripts/fetch-and-build.sh` §9；2026-10-01 本机对 v24.21.0 tarball 实测的目录体量 | ✅ 2026-10-01（体量为本机实测，非估算） |

---

## F. 建议批次（一次一批，每批跑完整 CI 同源门）

1. ~~**批 1（S）**：A2 / A3 / A1b / A4~~ —— **2026-10-01 已完成**，流水见 [`design-status.md`](design-status.md)。修 A2 时露出的新口子 **A2b 已于 2026-10-02 拍板并落地**（见该行）。
2. ~~**批 2（S）**：B2（CI 卫生）+ C3（失效引用）+ D4（README 归位）+ D2（删 sandbox 目录）~~ —— **2026-10-01 全部完成**，流水见 [`design-status.md`](design-status.md)。D2 当时因与 design-decisions 2026-09-30「目录留盘」裁定冲突而暂缓，经拍板后执行，口径变更追加在 design-decisions 同批。
3. ~~**批 3（S）**：C1/C5（人类 README + CONTRIBUTING）+ C4（只剩维护者开通上报入口）~~ —— **2026-10-01 完成**，流水见 [`design-status.md`](design-status.md)。C4 当时因「只剩维护者动作」留在池里 —— **该动作 2026-10-01 已由维护者完成**（GitHub 私密上报入口开通，`private-vulnerability-reporting` 复核为 `enabled:true`），仓库侧四处照实写法同批改掉，C4 随之出池。
4. ~~**批 4（M）**：A1/A1c~~ —— **2026-10-01 全部完成**，流水见 [`design-status.md`](design-status.md)：A1c 接缝形状 `secretKey(): SecretKey` + 实现落 `:app` 装配层；A1 四个子缺口按依赖序全补（素材出库 → 随包任务 → `AssetTreeCliSource` → 启动期落位 + 注入 `HostNodeExecutor`），素材来源拍板「Node 源码树 `deps/npm`」（口径追加在 [`design-decisions.md`](design-decisions.md#已推翻--已改口径)）。**收口时露出一个新口子**：素材版本 npm 11.19.0 ≠ §10 脊梁的 npm 12.x —— 登记为 **A6**，要不要升是独立的产品判断。
5. ~~**批 5（M）**：B1（CI 覆盖：nightly + assembleDebug + lint）~~ —— **2026-10-01 完成**，流水见 [`design-status.md`](design-status.md)：Android Lint 从 **15 error 修到 0**（`:app` 2：`Path.of`→`Paths.get` ×6、manifest 补 `POST_NOTIFICATIONS`；开成全模块后又抓出 13 处 minSdk 26 上的真崩 —— `Stream#toList` API34 / `URLEncoder(String,Charset)` API33 / a11y 截图的 API30·34 面 / `getMainExecutor` API28，逐条已修）、`ci.yml` 新增 `android-build` job、新 workflow `e2e-nightly.yml`（不带 `-PskipNpmE2E`）+ `check-e2e-ran.sh` 验尸门、`HostNpm` 三来源发现替掉写死的宿主 npm 路径。**收口时露出两个新口子**：CI 的 APK 不含引擎二进制 → **B5**；覆盖率仍为零（要动冻结的根 build 文件）→ **B6**。
6. ~~**批 6（M）**：D3/D5（platform 子包对齐）、D7（大文件拆分）~~ —— **2026-10-01 完成**，流水见 [`design-status.md`](design-status.md)：D5 测试树逐包镜像 main；D3 `:platform:system` 36 个 main `.kt` 拆成十二个子包（根包只剩 `SystemNamespaces`/`SystemSpis`），**同批必修的 JNI 符号面**——十入口靠缺省名字改编，包名段就是 ABI，`JniOps` 随迁 `images/` 后四处事实源同批改齐（漏改=编译绿/单测绿/真机整缝 `ERR_NOT_IMPLEMENTED`）；D7 六个目标拆了五个（`imgnative.cpp` 那刀把匿名 namespace 的帧表单实例问题显式化成 `imgnative_internal.h`，host 门禁拆前拆后逐例同值 422 检查）。**收口时如实留两件没拆**（`AppShellApplication.kt` 633 / `Scheduler.kt` 522，后者无干净接缝），退回 D7 行。
7. ~~**批 7（S 收窄）**：D8 许可声明 + D6 命名面 + D1 模块归属~~ —— **2026-10-01 完成**（不需拍板的三项），流水见 [`design-status.md`](design-status.md)，口径追加在 [`design-decisions.md`](design-decisions.md) 第 17–19 项。**D8 出池**（许可声明=生成物 + 随 APK 分发）；**D1 维持现状**（91 行模块是 §9.5 边界的物理载体）；**D6 只完成描述面**，`.autojs`/`autojs-lock-v1` 属已落盘格式、改动要兼容策略，退回池里等拍板。
8. **批 8（L/产品）**：**E1 已拍板并落地**（2026-10-02，选 (a) 接受 + 能力中心明示实测安装体积，见 E1 行与 `design-decisions.md` 第 20 项）；E2/E3 + B3 仍需人拍板后再排。
