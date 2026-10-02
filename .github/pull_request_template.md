## 改了什么 / 为什么

<!-- 一句话讲清意图与依据；细节在提交信息里，这里别复述 diff。 -->

## 文档同步（本仓文档即契约）

- [ ] 改了契约 → `docs/design/*.md` 对应分卷已**同批**更新（带 § 号）
- [ ] 落地状态记进 `docs/design-status.md` 流水（**只追加**）
- [ ] 口径变更记进 `docs/design-decisions.md`（**只追加**，旧结论原文不动）
- [ ] 待办池 `docs/backlog.md` 相应条目出池 / 入池
- [ ] 无文档面（纯代码 / 纯 CI 改动），本项不适用

## 门

- [ ] 全量 JVM 单测（[`CONTRIBUTING.md`](../CONTRIBUTING.md) 里的全量门命令）
- [ ] `bridge/js` facade 单测（`npm --prefix bridge/js test`）
- [ ] 文档链接门（`bash .github/scripts/check-doc-links.sh`）
- [ ] `./gradlew :app:assembleDebug` + `:app:lintDebug` —— 仅当改了装配 / 资源 / manifest 面（两者已在 `ci.yml` 的 `android-build` job 里跑；本机跑是为了拿**带引擎二进制**的那个 APK）
- [ ] 冻结文件（`settings.gradle.kts` / `gradle/libs.versions.toml` / 根 `build.gradle.kts`）未动，或已事先提出并获准

**结果如实写**：红过就写红过、哪条没跑就写没跑。借助 AI 助手完成的 PR，按
[`CLAUDE.md`](../CLAUDE.md)「协作纪律」的约定在描述末尾补署名行（约定在那边，本模板只提醒）。
