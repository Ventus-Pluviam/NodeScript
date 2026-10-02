# 参与贡献（Contributing）

先读三份东西，再动手 —— 本仓的规矩大半不在这个文件里，而在那三份里：

1. [`docs/framework-design.md`](docs/framework-design.md) —— 架构设计的入口（薄索引，按 § 号进分卷）；
2. [`CLAUDE.md`](CLAUDE.md) —— 仓库地图、模块表、协作纪律；
3. 你要改的那块的**设计分卷** —— 例如改桥就读 [`docs/design/07-bridge.md`](docs/design/07-bridge.md)，
   改执行就读 [`08-execution.md`](docs/design/08-execution.md)。

## 文档即契约（本仓最重要的一条）

四份文档各有各的职责，**别把内容写错地方**（完整的「哪份文档回答什么」地图见
[`docs/README.md`](docs/README.md) —— 那张表是这件事的单一事实来源，下表只讲**写东西时的纪律**）：

| 文件 | 写什么 | 纪律 |
|---|---|---|
| [`docs/design/`](docs/design/) | 契约本身（**单一事实来源**）：「是什么」 | 改契约 = 改行为，必须与代码同批 |
| [`docs/design-status.md`](docs/design-status.md) | 落地台账（**当前状态页**）：接口期表 + 流水目录 | **只追加**；新条目加在 `docs/log/<当天>.md` 顶部并把目录表那一行的计数 +1 |
| [`docs/log/`](docs/log/) | 流水**按日期切片**（`README.md` 是索引） | **只追加**，新条目加在当天切片顶部；被推翻的原地划掉并注明日期与原因 |
| [`docs/design-decisions.md`](docs/design-decisions.md) | 口径变更：已拍板项 + 被推翻、改过的口径 | **只追加，不改写历史结论** —— 旧口径原文保留，新结论追加一行 |
| [`docs/backlog.md`](docs/backlog.md) | 尚未排期的待做项 | 是**收件箱不是承诺**：排期了移走、做完了去 status 记流水、裁定不做了去 decisions 记口径 |

**本仓刻意不设 `CHANGELOG`**：变更流水已经在 `docs/design-status.md` + `docs/log/`（2026-10-02 起按日期切片，见 `design-decisions.md` 第 22 项），再开一份必然漂移成第二个事实来源；
发版流程真立起来那天再谈（[`docs/backlog.md`](docs/backlog.md) 有登记）。

**别写会漂的数字**：模块数、测试任务数这类计数有派生门看着（`:domain` 的 `ModuleGraphTest` 从
`settings.gradle.kts` 的 `include` 条数与 `ci.yml` 的任务行派生并比对文档）；写数字就必须等于派生值，
或者干脆别写、指向事实来源。

## 改代码

- **契约先行**：接口 / DTO 以 `:domain` 的骨架为准，别自己发明跨模块类型。
- **依赖方向铁律**（archUnit 强制）：`:app` → `:app-service:*` / `:ui` → `:domain`；
  `:platform:*` → `:domain`（实现 SPI，不反向）；`:bridge:java` → `:domain`；`:domain` 零 Android 零桥。
  完整表见 [`docs/design/06-modules.md`](docs/design/06-modules.md)。
- **`settings.gradle.kts`、`gradle/libs.versions.toml`、根 `build.gradle.kts` 是冻结文件**：
  模块表、版本目录、根构建脚本的改动**先提出来**再动，别夹在别的 PR 里。新增 / 删除模块还要同步
  登记 `ModuleGraphTest` 的允许集与模块表。
- **升级依赖 = 一件事一个提交**，并跑全量门。

## 提交信息

```
type(scope): 摘要

（正文：为什么这么改、被推翻的旧口径是什么、门跑的结果。想清楚的话都在这里，
  不是复述 diff。）

Co-Authored-By: Claude Code <noreply@anthropic.com>
```

- `type` 用 `feat` / `fix` / `refactor` / `perf` / `test` / `docs` / `ci` / `chore` / `build`；
  `scope` 用模块名或面（`docs` / `settings` / `npm` / `bridge` …）。
- 正文写**依据与权衡**，一行摘要讲不清的就在这里讲清；引用设计条款请带 § 号。
- 借助 AI 助手共同完成的提交，按所用工具的约定补 trailer（本仓 agent 约定为
  `Co-Authored-By: Claude Code <noreply@anthropic.com>`）；人工独立完成的提交不必加。

## 提交前必跑的门

与 CI **逐字同源**（任务清单的事实来源是 [`.github/workflows/ci.yml`](.github/workflows/ci.yml)）：

```bash
npm --prefix bridge/js ci && npm --prefix bridge/js run build   # :app 随包任务要 dist
./gradlew :domain:test :bridge:java:test :app-service:runtime:test :app-service:scheduler:test \
  :app-service:script-repo:testDebugUnitTest :app-service:permission-center:test \
  :app-service:packager:test :app-service:npm:test :platform:capabilities:testDebugUnitTest \
  :platform:system:testDebugUnitTest :engine:node-process:testDebugUnitTest \
  :ui:testDebugUnitTest :app:testDebugUnitTest
npm --prefix bridge/js test          # facade（TS）单测
npm --prefix bridge/js run gen:wire && git diff --exit-code   # 改过 bridge/schema 就要跑（生成物漂移即红）
npm --prefix bridge/js run docs:api && git diff --exit-code   # 改过 bridge/js 的公开注释/签名就要跑（typedoc → docs/api/，漂移即红）
bash .github/scripts/check-doc-links.sh   # 文档相对链接门
```

- **`skipped` / `aborted` 不算绿**：守卫在 `build-logic` 的 `autoscript.test-guard` 里，测试出现跳过即红。
  设计上就该环境门禁的少数 E2E 在 `TestGuard.ENV_GATED` 名单内；其余要放行得显式 `-PallowSkipped=<类名>`，
  别用它掩盖失败。
- PR 门（`ci.yml`）用 `-PskipNpmE2E` 排除拉真 npm 进程的端到端用例；它们由
  [`.github/workflows/e2e-nightly.yml`](.github/workflows/e2e-nightly.yml) 每天跑（不带该 flag），
  跑完由 `bash .github/scripts/check-e2e-ran.sh` 验尸（缺结果 / 跳过即红）。**本机跑全量门不要带这个 flag**。
- 构建 / 测试的完整前置（JDK、SDK、Node 版本）见 [`README.md`](README.md)。
- 不改行为、纯文档的改动也一样过门 —— 文档链接门与 `ModuleGraphTest` 都会读到文档。

## 推进方式

一次一批（批次表在 [`docs/backlog.md`](docs/backlog.md) §F），每批做完跑上面那道门再提交；
结构性改动一次一个 PR，别混批。

## 分支与 PR

- 从 `main` 切分支开发（`feat/…`、`fix/…`、`docs/…`），PR 回 `main`。
- **不要为了触发 CI 而直推 `main`**：push 到分支、开 PR 即跑全套门。
- PR 描述里写清：改了哪些文档、跑了哪道门、结果如何（红过就说红过）。
- Android 构建与 Lint（`./gradlew :app:assembleDebug` / `:app:lintDebug`）已在 PR 门里
  （`ci.yml` 的 `android-build` job）。注意 CI 出的 APK **不含引擎二进制**（那些产物不在 git，
  装配期缺位只 warn）——改了装配面仍建议本机跑一次并说明。

## 安全问题

**不要开公开 issue。** 走私密渠道：[Security → Report a vulnerability](https://github.com/Ventus-Pluviam/NodeScript/security/advisories/new)
（GitHub 私密漏洞上报，2026-10-01 开通，只有维护者可见）；支持范围、密钥管理、已知缺口见 [`SECURITY.md`](SECURITY.md)。
