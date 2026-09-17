# SKILL.md 模板（skill-designer 使用）

## 目录规范
- 位置：项目级 `.claude/skills/<name>/`；用户级 `~/.claude/skills/<name>/`。
- 结构：`SKILL.md` 必需；`scripts/`、`references/`、`resources/` 可选（内容真超 50 行才拆）。
- **多文件引用**：SKILL.md 内用**相对 SKILL.md 所在目录**的路径（如 `references/x.md`、`scripts/y.sh`），不用项目根路径。
- 单一职责：一个 skill 只做一件事。
- 命名：`name` 用 kebab-case、小写、≤64 字符，与目录名一致。

## frontmatter（必需两个字段）
```markdown
---
name: kebab-case-name
description: 给模型读的"何时触发 + 做什么"。写触发场景（用户会怎么描述这个需求），不写实现细节；3-6 句。
---
```
- `description` 触发可靠性 > 措辞优美：出现具体触发词（"设计 skill"、"git 提交"、"参考 GitHub"等）比华丽形容词更有用。
- 无 `allowed-tools` 白名单时不声明；白名单会限制模型可用工具，仅当 security 需要才加。

## 正文结构建议（可裁剪）
```markdown
# <name> —— 一句话职责

## 何时使用（触发）
- 用户说什么就该启用本 skill（罗列触发场景，写遇到的真实说法）。

## 工作流（步骤 + 每步产物）
### Step 0 · ...
### Step 1 · ...

## 硬性纪律（STRICT）
- 必须 / 禁止 清单，便于模型逐条遵守。

## Guardrails
- 不碰什么、不承诺什么、不做什么有副作用的事。

## 内部文件（多文件时）
- `references/...`、`scripts/...` 各自一句话说明。
```

## 篇幅与检查
- 目标：一屏内能 skim 完（约 100-250 行）；超过说明职责太宽，应拆分。
- 写完自检：跑 `scripts/validate-skill.sh`；拖到新会话 `/name` 能触发才算完成。
- 示例参考：`~/.claude/skills/` 与项目 bundled skills（dataviz 等）的写法。