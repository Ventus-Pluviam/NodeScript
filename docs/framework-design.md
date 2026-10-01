# AutoScript —— 框架设计 · 索引

> **契约的单一事实来源已按 § 号拆分为 [`docs/design/`](design/) 12 卷**（本文件原为 1,380 行单体，
> 2026-09-30 外部审查步骤 8 纯拆分：§号与标题文本逐字保留，只移动文件；分卷按序拼接 == 拆分前原文）。
> 本文件只做入口与导航。**§号是唯一权威锚，不随文件位置变化。**

## 分卷导航

| 分卷 | 覆盖章节 | 回答什么 |
|---|---|---|
| [`design/00-overview.md`](design/00-overview.md) | §0–§2 | 一句话架构、目标与非目标、五条架构铁律 |
| [`design/03-technology.md`](design/03-technology.md) | §3 | 技术选型总览 |
| [`design/04-architecture.md`](design/04-architecture.md) | §4–§5 | 总体架构、进程/线程模型 |
| [`design/06-modules.md`](design/06-modules.md) | §6 | Gradle 模块结构与依赖规则 |
| [`design/07-bridge.md`](design/07-bridge.md) | §7 | 桥接层设计（JS ↔ Native ↔ Android） |
| [`design/08-execution.md`](design/08-execution.md) | §8 | 执行层设计 |
| [`design/09-capabilities.md`](design/09-capabilities.md) | §9 | 自动化能力设计 |
| [`design/10-npm.md`](design/10-npm.md) | §10 | npm 支持（包管理与依赖生态） |
| [`design/11-security.md`](design/11-security.md) | §11 | 安全模型（来源分级） |
| [`design/12-js-api.md`](design/12-js-api.md) | §12 | JS API 设计（§12.2 接线现状表 / §12.3 调用形状 / §12.4 typings） |
| [`design/13-roadmap-budget.md`](design/13-roadmap-budget.md) | §13–§17 | 设计模式总表、路线图、预算、风险、兼容矩阵 |
| [`design/18-19-ledger.md`](design/18-19-ledger.md) | §18–§19 + 文档边界 | 决策台账（9 项均已拍板，标注在该卷 §18 正文）、结语、文档边界 |

## 文档边界

本目录里哪份文档回答什么 —— **单一事实来源是本目录的 [`README.md`](README.md)**（backlog C9，2026-10-02
把这张表搬了过去、原地留指针，免得同一事实写两遍）：契约说「是什么」、
[`design-decisions.md`](design-decisions.md) 说「为什么这么定 / 什么被改过」、
[`design-status.md`](design-status.md) 说「实现到哪了」。

**推进顺序与接线现状不在契约里**，见 [`design-status.md`](design-status.md) ——
那里是唯一权威（§12.2 接线现状表属契约的一部分，在 `design/12-js-api.md`；两条互为印证时以
更晚的日期为准）。
