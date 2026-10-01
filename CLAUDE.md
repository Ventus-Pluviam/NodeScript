# AutoScript

内置 Node.js 的安卓自动化平台（对标 AutoJsPro v9）：每脚本一个 Node 进程、跨进程异步桥、能力三态门禁。架构设计见 **`docs/design/` 12 卷**（按 § 号分卷，入口/导航 = `docs/framework-design.md` 薄索引，**契约的单一事实来源**，2026-09-30 审查步骤 8 拆分、§号与标题逐字保留）；「实现到哪了」看 `docs/design-status.md`（**当前状态页**：接口期表 + 流水目录；历史流水按日期切片在 `docs/log/`，实现注记在 `docs/implementation-notes.md`），「为什么这么定 / 什么被改过」看 `docs/design-decisions.md`；`docs/` 各文档的分工见 `docs/README.md`。

## 仓库地图

| 路径 | 说明 | 设计章节 |
|---|---|---|
| `docs/README.md` | **`docs/` 总索引**：哪份文档回答什么（文档地图的单一事实来源） | — |
| `docs/framework-design.md` | 架构设计**薄索引**（分卷导航；2026-09-30 拆分后只做入口） | §0–§19 |
| `docs/design/*.md` | 架构设计**分卷**（**契约的单一事实来源**）—— 只写「是什么」；`00-overview/03-technology/04-architecture/06-modules/07-bridge/08-execution/09-capabilities/10-npm/11-security/12-js-api/13-roadmap-budget/18-19-ledger` | §0–§19 |
| `docs/design-decisions.md` | 决策记录：已拍板项（原 §18 全部九项 + 后续编号项）+ 被推翻/改过的口径（原口径不删，只追加） | 原 §18 |
| `docs/design-status.md` | **当前状态页**：接口期清单 + 流水目录（2026-10-02 backlog C6 拆分；原 §19 的 9,584 字符流水外迁于此） | 原 §19 |
| `docs/log/<YYYY-MM-DD>.md` | 流水**按日期切片**（+ `docs/log/README.md` 索引）；**只追加**，新条目加在当天切片顶部 | 原 §19 |
| `docs/implementation-notes.md` | 实现注记（自各分卷外迁的逐字叙事 + 搬迁状态两表） | 原 §19 |
| `docs/backlog.md` | **待办池**：未排期项 + 外审建议（逐条带证据位置、核实状态、成本、建议批次）——是收件箱不是承诺，排期/做完/裁定不做了都从这里移走 | — |
| `.claude/skills/skill-designer/` | 项目级 skill：设计/创建技能 + 外科手术式改代码 + git 提交 | — |

## Gradle 模块（模块表由 `settings.gradle.kts` 冻结，15 个 —— `:engine:sandbox` 空壳 2026-09-30 已注释摘除、`:app-service:npm` 同日审查步骤 5 自 packager 拆出；复活 sandbox = 重建模块目录 + include 行加回 + ModuleGraphTest 允许集登记）

- `:app` — AppShellApplication 启动装配（§4.1 Composition Root）；Compose UI 已拆去 `:ui`（2026-09-23 落地：launcher 随库 manifest 合并，`:app` 源码零 compose / 零 import ui）
- `:app-service:runtime` — RuntimeController / EnginePool / Watchdog 仲裁（§8）
- `:app-service:scheduler` — 定时/Intent/事件任务、checkpoint 意图日志、runNonce 幂等 + `workManager` 桥 handler（步骤 6c 自 :app 迁入，§9.6/§8.6）
- `:app-service:script-repo` — 项目/资源/脚本库、assets→filesDir 原子部署（§9.6）
- `:app-service:permission-center` — 权限三态门禁、引导页、降级路径（§9.5）
- `:app-service:packager` — 模板 APK 改写、签名向导（§14；整轨已移后续版本，加密资产/loader 已裁）
- `:app-service:npm` — npm 安装/审批/镜像验证 + `npm` 桥 handler（§10；2026-09-30 审查步骤 5 自 :app-service:packager 拆出）
- `:domain` — **纯 Kotlin 领域层**：SPI 接口 + DTO + 状态机（零 Android 依赖、JVM 可单测）
- `:bridge:java` — Kotlin Router / RequestRegistry(TTL) / HandleRegistry(generation) / EventBus（§7）
- `:bridge:native` — C++ N-API addon 控制面 + libnode.so 装载（§7，CI 构建）
- `:bridge:image` — C++ 图像管线 libopencv.so（OpenCV 4.x，§9.2，CI 构建）
- `:engine:node-process` — :nodeN 进程宿主：`NodeProcessEngine`（Kotlin spawn，实现 `:domain` 的 `ScriptEngine`）+ main.cpp（§5/§7.8；addon `.so` 本机 NDK 可交叉编译验证，APK `assembleDebug` 本机可直跑）
- `:engine:sandbox` — QuickJS 宿主进程（**已裁 §18 第 1 项；壳 2026-09-30 已从 settings 注释摘除、不计入模块数**，空壳目录 2026-10-01 已从盘上删除、settings 里那行注释也同批删掉，见 `design-decisions.md` 同批追加行；复活 = 重建模块目录 + include 行加回 + ModuleGraphTest 允许集登记）
- `:platform:capabilities` — **无障碍三面**：a11y 树/手势、screen 截图帧源、dialogs 对话框编排（`capabilities/{a11y,screen,dialogs,device}/` 子包；2026-09-30 步骤 6 系统面迁出，§9.1–9.4）
- `:platform:system` — **系统面 handler + SPI 实现 + 能力专用契约**：`SystemNamespaces` 十一件（shell/device/app/floatingWindow/datastore/zip/settings/notification/clipboard/sensors/images）+ 电源面 `PowerManagerNamespaceHandler`/`WakeLockLedger`（§8.7）+ `SystemSpis.of` 实现入口 + 五契约（步骤 6a 自 :domain 迁入；`DialogHost` 留 :domain，§9.6/§12.2）
- `:ui` — Compose UI 呈现层：启动 Activity（launcher）、首屏/任务中心/控制台/能力中心界面；状态经 `:domain` 的 `HostSummary` 读口现取，禁依赖 `:app`（§6）
- `bridge/js/` — **npm 包**（TS facade SDK，脚本侧导入名 `auto`，非 Gradle 模块、非 npm workspaces——空 `workspaces` 字段已删，§12.4）
- `node-runtime-build/` — **CI 构建管线**（Node 24 源码 recipe + 16KB 对齐门禁，非 Gradle 模块，§3）

## 依赖方向铁律（Gradle/archUnit 强制，见 §4.1）

`:app` → `:app-service:*`/`:ui` → `:domain`；`:platform:*` → `:domain`（实现 SPI，不反向）；`:bridge:java` → `:domain`；`:domain` 零 Android/零桥。全部禁止把 UI/Dialog 类、危险权限、循环依赖带进下层。

## 构建

- **前置工具一律走环境变量约定，机器路径不写进本文件**：`JAVA_HOME`（JDK 17）、`ANDROID_HOME`（或仓库根 `local.properties` 的 `sdk.dir=`，已 gitignore；需 platform-35 + build-tools 35 + platform-tools）、`ANDROID_NDK_HOME`（只在编 C++ 时要）。本机（这台开发机）三处都已配好，**具体值见 `CLAUDE.local.md`**（不入库、已 gitignore）—— 别再往跟踪文件里写 `/root/…` 这类路径。
  **CI 同款 `./gradlew …` 命令可本机直跑**（13 个测试任务，2026-09-30 实测全绿）——CI 仍是权威门，但本机已能同源复现。**本机快速门 = 同一条 `./gradlew` 命令**（`tools/jvm-test*` 旁路已删，
  2026-09-30）：约定插件 `autoscript.test-guard` 把「skipped/aborted ≠ 绿」守卫做进 Gradle，本机与 CI 同一口径、无第二口径脚本。
- **CI 验证门**：`.github/workflows/ci.yml` —— JVM 单测（13 个测试任务，即全部带 `src/test`
  的模块 —— 「15 个模块」「13 个测试任务」两个数**从 `settings.gradle.kts` 的 include 与
  `ci.yml` 的 `./gradlew` 行派生**，`:domain` 的 `ModuleGraphTest` 守着（文档里写了数字就必须
  等于派生值）；清单：`:domain`、`:bridge:java`、`:app-service:{runtime,scheduler,script-repo,permission-center,packager,npm}`、`:platform:{capabilities,system}`、`:engine:node-process`、`:ui`、`:app`）+ archUnit + `bridge/js` 的 npm test + **文档链接门**（`docs-check` job = `bash .github/scripts/check-doc-links.sh`，扫全部 `*.md` 的 markdown 相对链接是否指向存在的文件；本机同一条命令可跑，零依赖秒级），跑在 ubuntu-latest（JDK 17 + Gradle 8.9 + Android SDK license + Node 24）。
  **Android 构建与 Lint 已进 PR 门**（2026-10-01 批 5 / B1）：`android-build` job = `./gradlew lintDebug`（**全模块**：单跑 `:app` 看不见库模块的 NewApi，`checkDependencies` 缺省 false —— 首次全模块跑就抓出 13 处 `Stream#toList` API34 / `getMainExecutor` API28 级真错）+ `./gradlew :app:assembleDebug`，APK 与 lint 报告上传 artifact。**该 APK 不含引擎二进制**（noden/libnode/libopencv/npm 素材都不在 git，装配期「缺位只 warn」）—— 补齐路径记在 `docs/backlog.md` 的 **B5**。
  **真 npm E2E 走 nightly**：`.github/workflows/e2e-nightly.yml`（`schedule` + `workflow_dispatch`）跑**与 ci.yml 逐字同一条命令、只是不带 `-PskipNpmE2E`**（三条真 npm 路径：`HostNodeNpmE2ETest` / `NpmCacheSeedDeployerTest` / `P0LoopbackTest`），跑完由 `bash .github/scripts/check-e2e-ran.sh` **验尸**：缺 XML / `tests=0` / `skipped>0` 都红 —— 因为 `assumeTrue` 诚实跳过在本机对、在 CI 上会退化成"一片绿的假通过"。宿主 npm 路径由测试源集的 `HostNpm` 现查（`npm root -g` → node prefix 推 → 老静态位兜底），不再写死 `/usr/lib/node_modules/npm`。
  **`bridge/js/dist` 是构建产物**（2026-09-30 步骤 7 出库、不入 git）：jvm-tests job 前置 `npm --prefix bridge/js ci && run build`（`:app` bridgeDist 随包任务与 e2e 测试要 tsc 产物），js-tests job 另跑 `npm run gen:wire && git diff --exit-code` 门（`bridge/schema/wire.schema.json` → 两份生成物同步）。
- **可用 GitHub Actions 跑远端 CI**：远端 `origin` = `git@github.com:Ventus-Pluviam/NodeScript.git`（私有仓，SSH 可推）。`ci.yml` 在 `push→main` 与 `pull_request` 时触发——把分支 `git push origin <分支>` 后开 PR 即跑全套门（PR 门 = JVM 单测 + archUnit + npm test + 文档链接门 + Android 构建/Lint；真 npm E2E 只在 nightly），不用等合入 main 才知道红绿。本机 `gh` token 若无该私仓权限（`gh pr create` 报 404/解析不到仓库），用 push 后远端打印的 PR 链接手动开 PR；
  看不到 runs 输出时以本机 `./gradlew` 同源复现为准。**不要为触发 CI 直推 main**。
- **本机快速门**：与 CI **逐字同源**的 `./gradlew`（ci.yml L 任务行），单模块跑 `./gradlew :<模块>:test`（纯 JVM）或 `:<模块>:testDebugUnitTest`（android 模块）。守卫在约定插件里：测试出现 skipped/aborted 即红（`TestGuard.ENV_GATED` 只放行设计上环境门禁的 E2E；其余用 `-PallowSkipped=<类名>` 显式放行）。**`:ui`/`:app` 同源直跑**（`./gradlew :ui:testDebugUnitTest :app:testDebugUnitTest`）。

## 协作纪律（子 agent 必须遵守）

1. **改前先读**：动手前读 `docs/design/` 对应分卷（按 § 号定位，如 §7 → `07-bridge.md`；导航见 `docs/framework-design.md` 索引）+ 本文件 + `settings.gradle.kts`；未知默认问协调者。
2. **只动自己的模块目录**；`settings.gradle.kts`、`gradle/libs.versions.toml`、根 `build.gradle.kts` 由协调者冻结——需要改先提给协调者。
3. **外科手术式读写**：Grep/Glob 定位，Read 带 offset/limit，Edit 用最小唯一匹配，不整库读代码。
4. **git 提交**：每个逻辑完成点提交，信息 `type(scope): 摘要` + 结尾 `Co-Authored-By: Claude Code <noreply@anthropic.com>`；不提交无关文件；不 init 仓库（已是仓库）。
5. **契约先行**：接口/DTO 以 `:domain` 骨架为准；别自行发明跨模块类型。
## NDK

- 版本口径 = **r28c**（与 `node-runtime-build/VERSIONS.env` 的 `NDK_VERSION` 同源；
  zip 校验见该管线 §2）。本机的 NDK 根见 `CLAUDE.local.md`（不入库）。
- 用法：`export ANDROID_NDK_HOME=<你的 NDK 根>` +
  `PATH=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH`，
  直接调 `aarch64-linux-android26-clang(++)`（API 26 = minSdk 冻结值）。
- 本机只做 **C++ 交叉编译验证**（`bridge/native` 的 addon `.so` 能编出 arm64 ELF）；
  APK/AGP `assembleDebug` 本机可直跑（已有 SDK，2026-09-24 已实测出包）；真机红测与生产签名管线仍走 CI。
