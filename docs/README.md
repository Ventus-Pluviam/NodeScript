# docs 总索引

> 本目录里**哪份文档回答什么** —— 这张表是这件事的**单一事实来源**。
> [`framework-design.md`](framework-design.md) 与 [`design/18-19-ledger.md`](design/18-19-ledger.md)
> 各自那张「文档边界」表 2026-10-02 起**原地换成指针**（backlog C9）：本仓不再有第二份并列的表。
> 仓库根的地图（模块、构建、许可、安全）见 [`../README.md`](../README.md)。

## 文档地图

| 文件 | 回答的问题 | 纪律 / 沿革 |
|---|---|---|
| [`design/`](design/) 12 卷 | **是什么** —— 契约正文 §0–§17（§18 决策台账 / §19 结语在 [`design/18-19-ledger.md`](design/18-19-ledger.md)） | **契约的单一事实来源**。改契约 = 改行为，必须与代码同批。2026-09-30 审查步骤 8 自 `framework-design.md`（原 1,380 行单体）按 § 号拆分，§号与标题文本逐字保留 |
| [`framework-design.md`](framework-design.md) | 契约的**入口**：§ 号 → 分卷文件的导航 | 拆分后只做入口；**§ 号是唯一权威锚，不随文件位置变化** |
| [`design-decisions.md`](design-decisions.md) | **为什么这么定 / 什么被改过**（已拍板项 + 被推翻、改过的口径） | **只追加，不改写历史结论** —— 旧口径原文保留，新结论追加一行。原 §18 九项拍板在此 |
| [`design-status.md`](design-status.md) | **实现到哪了** —— 当前状态页：接口期表 + 流水目录 | 流水**只追加**；原 §19 那条 9,584 字符的流水账，2026-10-02 分片 |
| [`log/`](log/) | 流水**按日期切片**（`<YYYY-MM-DD>.md`；索引 [`log/README.md`](log/README.md)） | **只追加**，新条目加在当天切片顶部；被推翻的原地划掉并注明日期与原因 |
| [`implementation-notes.md`](implementation-notes.md) | 各分卷的**实现注记**（「已落地 / 实测」叙事，自契约正文外迁） | 搬迁**逐字**、不做压缩；`design-status.md` 留同名空壳标题护着分卷里的 9 条链接 |
| [`backlog.md`](backlog.md) | **尚未排期的待做项** + 外审建议 | **是收件箱不是承诺**：排期了移走、做完了去流水记、裁定不做了去 decisions 记口径 |
| [`api/`](api/index.md) | **脚本 API 参考（用户向）** —— `auto.*` 门面逐方法说明，与 `design/12-js-api.md`（设计文档）分工不同 | **生成物**：由 `bridge/js` 的注释经 typedoc 生成（`npm --prefix bridge/js run docs:api`），手改无意义；CI 有零 diff 门看着，漂移即红 |
| [`reference/`](reference/README.md) | **外部参考件**（如对标产品 AutojsPro 的文档链接索引） | **不是本项目文档**：不设 § 号、不进流水、不被契约引用；来源与性质见 [`reference/README.md`](reference/README.md) |
| [`archive/`](archive/) | 归档的历史切片（如 [`status-2026-09-25.md`](archive/status-2026-09-25.md)） | 只进不出，逐字保留 |

**推进顺序与接线现状不在契约里**，见 [`design-status.md`](design-status.md) —— 那里是唯一权威
（§12.2 接线现状表属契约的一部分，在 [`design/12-js-api.md`](design/12-js-api.md)；两条互为印证时
以更晚的日期为准）。

## 怎么读

- **要改代码**：先读你要改的那块的**设计分卷**（按 § 号定位，导航见 [`framework-design.md`](framework-design.md)），
  再读 [`../CLAUDE.md`](../CLAUDE.md)（模块表、依赖铁律、协作纪律）与
  [`../CONTRIBUTING.md`](../CONTRIBUTING.md)（提交信息、冻结文件、提交前必跑的门）。
- **想知道现在能不能用**：读 [`design-status.md`](design-status.md) 的**接口期表**（「未落地，写了就是撒谎」）。
- **想知道某条设计为什么是这样**：读 [`design-decisions.md`](design-decisions.md)。
- **判一条规则 / 判它实现没有**：前者看 [`design/`](design/) 对应卷，后者看
  [`design-status.md`](design-status.md) 与 [`log/`](log/)。

## 三条跨全部文档的纪律

1. **§ 号是唯一权威锚**，不随文件位置变化；引用设计条款一律带 § 号。
2. **只追加**：流水与决策都不删旧 —— 被推翻的原地划掉（`~~作废（日期 + 原因）~~`）或追加一行，
   历史结论不改写。
3. **同一事实只写一份**：文档里写的模块数 / 测试任务数有派生门看着（`:domain` 的 `ModuleGraphTest`，
   只扫 `CLAUDE.md` / `docs/design/06-modules.md` / 两个 `settings.gradle.kts`）；文档分工由本文件看着。
   要指向**还没建的文件**时写成反引号、不写成链接（文档链接门只认精确路径）。

> **本文件与 [`log/README.md`](log/README.md) 不是同一份**：后者是**流水切片的索引**
> （按日期逐条列出条目），本文件是**整个 `docs/` 的地图**。
