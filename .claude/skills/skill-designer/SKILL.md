---
name: skill-designer
description: 设计并创建 Claude Code skill（技能），默认写入当前项目的 .claude/skills/<name>/。触发于用户要求「设计/创建/写一个 skill、技能或斜杠命令」，或要求参考 GitHub 上他人公开的 skill 来写、要求改动后 git 提交、要求给项目补描述、要求改动时不整库读代码。流程：GitHub 参考调研 → 套模板设计 → 外科手术式写改 → 自检 → 更新项目描述 → git 提交；也用于维护本 skill 自身。
---

# skill-designer —— Skill 设计与生成器

## 目的
产出一个「小而完整、可触发、可执行」的 Claude Code skill，写入**当前项目的 `.claude/skills/<name>/`**（项目级优先；用户明确说"用户级/全局"才放 `~/.claude/skills/`）。全程遵守四条铁律：先做参考调研、外科手术式改文件、改动必 git 提交、给项目补合适描述。

## 何时使用（触发）
用户提到以下任一项就启用本 skill：
- "设计 / 创建 / 写一个 **skill**、技能、斜杠命令（/xxx）"；
- "**参考/学习 GitHub 上别人的 skill** 来写"；
- "改动文件的时候要 **git 提交**"、"给**项目添加描述**"；
- "**不要读所有代码** / 改动时别整库读代码"。

## 工作流（严格按序，每步有产物）

### Step 0 · 目标确认（1-2 句完成）
- 一句话说清 skill 职责；确定目标目录（项目级 `.claude/skills/<name>/` 默认）。
- 一次只做一个 skill。
- 已存在同名 skill？→ 进入**更新模式**：先 `git diff` 或 Read 现有内容对齐意图，不静默重建。

### Step 1 · 参考调研（用户说"不用参考"则跳过；时间盒 ≤3 仓库 / ≤5 次检索）
- 打开 `references/github-search.md` 按其执行：浅克隆或 API 检索公开 skill 仓库（anthropics/skills、obra/superpowers、wshobson/agents 等），提炼 frontmatter / 目录结构 / 触发词写法。
- 只提炼不照抄；记录来源（URL + 日期）。
- 无网 / 超时：回退本地 bundled skills（如 dataviz）或直接按 `references/skill-template.md` 设计，并说明调研被跳过。

### Step 2 · 设计（产物：文件清单 + SKILL.md 大纲）
- 按 `references/skill-template.md` 定结构。典型清单：
  - `SKILL.md`（必）
  - `references/` 或 `scripts/`（仅当内容确实 >50 行才有必要，不为存在而存在）
- 若将改动文件 >3 个或意图有歧义：先列「拟改清单 + 提交计划」给用户确认；用户明确说"直接干"则跳过确认。

### Step 3 · 外科手术式写改（见「读改纪律」）
- 新 skill 的**全部新文件**用 Write；**既有文件一律用最小唯一 Edit**。

### Step 4 · 自检
- 跑 `scripts/validate-skill.sh <skill-dir>`，修正全部报错至通过。
- 读回 SKILL.md 首屏，确认：name 与目录名一致、description 是"给模型的触发说明"而非实现细节。
- 冒烟：`ls -R`（单层即可）确认引用路径齐全；向用户说明下次会话可 `/name` 触发。

### Step 5 · 项目描述（见「项目描述协议」）

### Step 6 · git 提交（见「git 提交协议」）

## 读改纪律（STRICT，违反即返工）
目标：只读/只改与本次改动相关的最小范围，**绝不整库读取**。
- 定位用 Grep / Glob / 单层 `ls`；**禁用** `find . -type f` 全量列出、`cat` 大文件、整目录递归 Read。
- 理解项目：优先 `CLAUDE.md` → `README.md` → 目录单层列表；不要为理解而遍历源码。
- Read 必须带 `offset` / `limit`，只读所需片段。
- 改既有文件用 Edit（最小唯一 old_string）；只有创建**新文件**才用 Write。
- 需要全局理解时交给 Explore 型 sub-agent，不要自己逐文件读。
- 批量只读尽量合并为一次工具调用，减少权限提示。

## 项目描述协议
- 产物：项目根 `README.md`（缺失则创建），含 2-4 行：项目一句话 + 关键内容位置（如 docs/）+ 新增 skill 一行。
- 已有 `package.json` / `project.json` 等带 description 字段的文件：把 description 同步为同一句话；**只改 description，不动其余字段**。
- **提炼自仓库既有素材**（CLAUDE.md / README 首段 / 文档标题），禁止虚构；单次只补描述，不重构 README 结构。

## git 提交协议
- 改动文件后**必须提交，不留未提交改动**。
- 先 `git status` + `git diff --stat` / `git diff --check`，确认只 stage 本次相关改动；无关文件不碰。
- 不是 git 仓库：向用户提议 `git init`，获准后才执行；可顺手补最小 `.gitignore`（build/、node_modules/、.idea/ 等）。
- 提交信息：`<type>(<scope>): <一句摘要>`，type 用 feat / fix / docs / chore；正文可写参考来源。结尾必须带归属行：
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  ```
- 一次逻辑改动 = 一次提交；不把"调研笔记"与"skill 文件"混进同一提交。
- 默认只本地 commit；push 需用户明确要求。

## Guardrails
- 不碰无关文件；不 force-push；不提交密钥 / 证书 / 大二进制（敏感项先确认）。
- 不整段复制他人 skill，尊重许可（MIT 也标注来源）；skill 标准见 agentskills.io。
- 同名 skill 不重建：进入更新模式，保留既有内容与历史。
- 项目描述不虚构。
- 用中文回应用户（除非要求英文）；命名 kebab-case、小写、≤64 字符。
- 不无限检索：时间盒到即停，给出结论而不是继续等。

## 维护本 skill
- 给 skill-designer 自身提需求 = 更新模式：先用 `git diff` 看现有内容，再按同一协议改 + 提交。
- 改进原则：**增规则不增篇幅**，保持 runbook 可 skim。

## 内部文件
- `references/skill-template.md` —— SKILL.md 模板与命名规范
- `references/github-search.md` —— 参考调研 playbook（仓库清单 / 检索命令 / 限流 / 代理）
- `scripts/validate-skill.sh` —— 自检脚本（校验 frontmatter / 目录一致 / 引用解析）