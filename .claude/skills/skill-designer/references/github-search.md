# GitHub 参考检索 playbook

动手写 skill 之前，从公开 skill 仓库提炼可复用的结构 / 写法 / 触发词。**只提炼，不照抄。**

## 已知参考仓库（2026-09 实测）
| 仓库 | 内容 | 参考价值 |
|---|---|---|
| `anthropics/skills` | 官方 Agent Skills 仓库（约 176k★，2026-09 仍在更新） | 结构 / 写法权威参考 |
| `obra/superpowers` | 287k★，MIT | skills 框架 + 开发方法论 |
| `wshobson/agents` | 39k★，MIT | 多 harness 插件市场，跨工具惯例 |
| `davila7/claude-code-templates` | CLI 配置模板 | 含 skill 模板 |
| `agentskills.io` | 标准文档站 | **优先读这里**：官方 "how to create custom skills" |

搜索关键词建议：`agent skills`、`claude skills`、`SKILL.md`、`claude-code-templates`，按 stars 排序取前几个。

## 检索方式（本机环境实测）
1. **git 浅克隆（首选）**：`git clone --depth 1 --filter=blob:none <url> /tmp/skill-ref-<name>`。本机 git 已配代理，clone/fetch 直通。
2. **GitHub API（curl 需显式代理）**：
   ```bash
   curl -s -x http://127.0.0.1:10808 "https://api.github.com/search/repositories?q=claude+skills&sort=stars"
   ```
   未认证限流：**搜索 10/分钟，core 60/小时**。报 403/rate limit 先查 `https://api.github.com/rate_limit`，别硬重试。
3. **`gh` CLI**：本机未配置 token（`gh auth status` 为空）。已配置时 `gh search repos` / `gh api` 更好用。
4. **WebSearch / WebFetch 兜底**：走云端无需代理；但 `code.claude.com`、`www.anthropic.com` 在本环境被域名校验拦截 → 这类站点用 git clone 或 API 而不是 WebFetch。

## 提炼纪律
- 只看 frontmatter + 目录 + 一节大纲即可判断，**不整段读全文**。
- 提炼的是结构惯例（章节、命名、触发词写法、description 风格），在自己的模板里重写。
- 不整段复制正文；即使仓库是 MIT 也**标注来源 URL**。
- 记录来源（URL + 日期）到新 skill 的 references/ 或本文件。
- 用后清理：`rm -rf /tmp/skill-ref-*`，不留克隆垃圾。

## 时间盒与回退
- ≤3 个仓库 / ≤5 次检索调用，**够提炼即停**；目标是参考不是考古。
- 无网 / 持续失败：回退本地 bundled skills（dataviz 等）作结构参考，按 skill-template.md 开工，并在最终消息里说明"调研被跳过/降级"。

## 代理备忘（其他机器不一定有此环境）
- git：本机全局已配 `http://127.0.0.1:10808`。
- curl：必须显式加 `-x http://127.0.0.1:10808`。
- 陌生环境：先测直连，失败再找代理，不要假设。