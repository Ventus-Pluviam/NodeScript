# 流水切片（按日期）

> [`design-status.md`](../design-status.md) 的**流水段**按日期拆成的切片，2026-10-02 分片
> （backlog C6）。**逐字搬入，只追加纪律不变**：新条目加在**对应日期文件的顶部**（新日期就新建
> 一个文件），并把 [`design-status.md`](../design-status.md) 目录表里那一行的条目计数同步 +1。
> 被推翻的记账不删除，原地标 `~~作废（日期 + 原因）~~`。
>
> **为什么拆**：单文件 175 KB 时「加一条」要动一个所有人都要读的文件，且 § 锚与条目文本在同一个
> 巨大的 blob 里。拆完：当前状态页只留**接口期表 + 目录**（十几 KB），历史条目按日期分文件。
> **§ 号仍是唯一权威锚**，不随文件位置变化；分卷正文里指回台账的链接一条都没改。

## 切片

| 日期 | 条目 | 主题 | 文件 |
|---|---|---|---|
| 2026-10-05 | 6 | 批 39 拆掉顶栏底部的全宽分割线（TG `ActionBar` 无底线，本仓自加）；批 40 任务栏重做（TG 联系人页复刻：灰底白卡/GraySection 分组头/UserCell 行/搜索栏/排序钮/FAB）；批 41 任务栏按用户口径收窄（删刷新钮/副标题空话/恢复账未结算块，顶栏同灰，分组头 CollapseTextCell 白底 + 三角，空组不可展开，搜索框悬浮）；批 42 搜索框药丸宽对齐 TG（屏宽−18dp）+ 搜索框下 8dp 空隙 + 运行中/定时拆两卡；批 43 搜索框几何再纠偏（补第二层 3dp InsetDrawable 内缩 → 屏宽−24dp/高 40dp）+ 首卡间距 12dp + 首页搜索框对齐 TG（新字形 SEARCH_FIELD/40dp/灰框）；批 44 任务中心改名（「任务栏」→「任务中心」）+ 搜索框↔首卡 14dp（`contentPadding.top` 60dp）+ 卡间距 12dp（TG `ShadowSectionCell` 缺省高）+ 空组可收放（删 `hasContent`，批 41 那条作废）+ 回执改自绘浮层（TG `Bulletin` 版式；判读在纯层 `state/OpToast.kt`，失败>回执>挂起；`ToastHost` 一处挂四屏共用） | [`2026-10-05.md`](2026-10-05.md) |
| 2026-10-04 | 6 | 批 38 菜单开/关动画 + 间隙渐变 + 投影环描边（MenuPopup 自建壳）· 批 37 菜单圆角精确到 12dp（popup_fixed_alert4 实测 11.88–11.93）+ 主题切换死区修复 + 两处 import 补回 · 批 36 勘误 底栏高亮块底色被 blend 成了灰（`multAlpha` 只改 alpha，色相恒为选中蓝）· 批 36 底栏整条按 TG 重做（不等宽分格 / 三键各自 blend / 删掉自加的按压态）· 批 35 拆掉下拉刷新（参考项目没有这个手势）· 批 34 项目页目录下钻 / 选择模式 / 底部操作面板（TG `ChatAttachAlertDocumentLayout` 的一层一层走 + 搜索跨层） | [`2026-10-04.md`](2026-10-04.md) |
| 2026-10-02 | 19 | 批 10 C7 typedoc API 参考 + B4 依赖供应链 · C9 总索引落地 · C6 分片落地 · A2b 拍板落地 · E1 拍板落地 · E2 拆双门 + 精确兜底 · E5 放宽拍板 · 批 9 A6+E4 素材换源 · 结构面批 D9+D10+D12 | [`2026-10-02.md`](2026-10-02.md) |
| 2026-10-01 | 17 | 外审整改收尾、批 1–7、两次外审建议入池 | [`2026-10-01.md`](2026-10-01.md) |
| 2026-09-30 | 15 | 外审整改步骤 1–8、图像提速三案、A 组真机实测 | [`2026-09-30.md`](2026-09-30.md) |
| 2026-09-29 | 1 | 真机垂直切片红测（非 root） | [`2026-09-29.md`](2026-09-29.md) |
| 2026-09-25 及更早 | — | 自 §19 结语整段外迁 | [`../archive/status-2026-09-25.md`](../archive/status-2026-09-25.md) |

## 逐条索引

### [2026-10-05](2026-10-05.md)（6 条，最新在最上）

- 2026-10-05 —— 批 44：任务中心改名 + 两处间距对齐 TG + 空组可收放 + 回执改浮层 —— 「任务栏」→「任务中心」（`ActionBar` 标题 + KDoc；页签「任务」不动）；列表 `contentPadding.top` 52→60dp（搜索框↔首卡 14dp）；两卡灰缝 8→12dp（TG 多 section 页组间 `ShadowSectionCell` 的缺省高，`setSections` 的 12 是左右边距）；`GroupHeader.hasContent` 整个删掉（恒有三角、恒可点，批 41 的「空组不给三角」作废）；三屏回执（`CopyNotice` + 6/2/3 条 `FeedbackLine`）改走外壳浮层 —— 新组件 `ToastAction`/`LocalToast`/`ToastHost`/`ToastNotice`（TG `Bulletin` 版式：最小高 48dp/内边距 16·8/圆角 16dp/14sp/1.6s），判读在纯层 `state/OpToast.kt`（失败>回执>挂起，新增 `OpToastTest` 5 例），主题加 `toastBackground`/`toastText`（深色不照抄 TG night 的近屏底色）（分支 `rough-robin`）
- 2026-10-05 —— 批 43：搜索框几何再纠偏 + 首行间距 + 首页搜索框对齐 —— 批 42 漏了药丸背景那层 `InsetDrawable(…, 3,3,3,3)`，补上后真实药丸 = 屏宽 −24dp、高 40dp、距屏边 12dp（槽位 padding 9→12dp、药丸 46→40dp）；列表 `contentPadding.top` 54→52dp（药丸底 40 + 12dp 净留白）；圆角 20dp 落在 40dp 高 = 两端全圆；首页 `ProjectScreen` 搜索框对齐 TG `DialogsActivity` 档（`GlyphKind.SEARCH`→`SEARCH_FIELD`、槽位 12/6、高 40dp、灰框无投影）（分支 `rough-robin`）
- 2026-10-05 —— 批 42：搜索框尺寸修正 + 搜索框下空隙 + 两组拆两卡 —— `SearchField` 药丸补上 `setSectionBackground()` 的 3dp 自缩（~~屏宽 −18dp~~，此前宽了 6dp —— ~~作废：批 43 补第二层内缩后为 屏宽 −24dp/高 40dp~~）；列表 `contentPadding.top` 44→~~54dp~~（药丸下 8dp 空隙 —— ~~作废：批 43 改 52dp~~）；批 40「同卡」口径作废，运行中/定时各自一张白卡、卡间 8dp 灰缝（TG 多 section 本来就是各卡各缝）（分支 `rough-robin`）
- 2026-10-05 —— 批 41：任务栏按用户口径收窄 —— 删右上「刷新」（`onRefresh` 参数一并摘除，`TabReloadEffect`/`performTaskOp` 现取照旧）；副标题「读到了，没有」换空串；顶栏传 `ActionBar.background = surfaceMuted` 跟页面同灰；恢复账/未结算两块全删；分组头换 CollapseTextCell 白底 46dp + 右端 CHEVRON 三角（340ms EASE_OUT_QUINT，收起尖朝下/展开尖朝上），空组不给三角、`pressable(enabled=false)` 点不开；「无在途执行」「读到了没有已登记」两行占位提示摘除；搜索框挪进列表 Box `align(TopCenter)` 最后画（TG 叠层同款），`contentPadding.top = 44dp`，Glyphs 新增 CHEVRON/SEARCH_FIELD 两字形（分支 `rough-robin`）
- 2026-10-05 —— 批 40：任务栏重做（TG 联系人页复刻）—— TaskCenterScreen 全量重写：标题「任务栏」+ 排序切换钮（两态字形）/「搜索任务」白药丸/灰底上一张白卡（运行中 + 定时任务两组，GraySection 头可展开收起）/UserCell call 样式行（行尾播放三角，挂起中转圈）/点行弹操作面板/登记收进 FAB；状态层加 TaskSort/sortedTasks/matches/opTargetTaskId（分支 `rough-robin`）
- 2026-10-05 —— 批 39：拆掉顶栏底部的全宽分割线 —— `ActionBar` 末尾的 `Separator(indentDp = 0)` 是四屏顶栏底下那条全宽 1dp 线，TG 的 `ActionBar` 底下没有分割线（内容直接接栏底）；列表行间分隔线不动（分支 `electric-crocodile`）

### [2026-10-04](2026-10-04.md)（5 条，最新在最上）

- 2026-10-04 —— 批 38：菜单开/关动画 + 间隙渐变 + 投影环描边 —— M3 `DropdownMenu` 的进出场硬编码（120/75ms scale+fade）改不了，自建 `MenuPopup` 壳：打开 = `150+16×可见项`ms 线性 + `backScaleY`/`backAlpha` + 子项 cascade（`AndroidUtilities.cascade(t,pos,count,4)`，disabled 终值 alpha 0.5）；关闭 = 150ms `translationY ∓5dp` + 淡出；间隙补上 `GapView.onDraw` 叠的 `greydivider` 上下渐变（峰值 14/255 —— 批 37 只画实色条所以「宽度不够」）；描边从 1dp 硬边改为 9-patch 实测的 5dp 渐变投影环（贴边 33/255，5 层阶梯）
- 2026-10-04 —— 批 37：控件圆角/颜色按 TG 校准 —— 菜单圆角 11→12dp（`popup_fixed_alert4.9.png` 四档实测 11.88–11.93dp）；菜单项按压圆角的「12 还是 6」之争由读源码定案（实际走 `updateRadialSelectors()`，字段默认 `selectorRad=12`）；撤掉用负 padding 抵消 M3 `DropdownMenuVerticalPadding` 的自伤写法；`ThemeMode.next(isDark)` 修掉 SYSTEM 档的死 tap；补回 `Menus.kt` 的 `MaterialTheme` 与 `ProjectScreen.kt` 的两个 slide 动画 import（分支 `electric-crocodile`）
- 2026-10-04 —— 批 36 勘误：底栏高亮块的底色被 blend 成了灰 —— `Theme.multAlpha(colorSelected, 0.09f * alpha)` **只改 alpha 通道**，色相恒为选中蓝；我写成了 `lerp(selected, unselected, f)`，f=0.5 时是蓝与近黑的中点（一坨灰）。同批把透明度那一路对齐成 TG 的**双 DECELERATE**（`0.09f * (1-(1-f)²)`），并把「两套 attheme 都没设 `glass_tab*` 三个键 + 各自回退落点」写进 `Theme.kt` 的 KDoc（分支 `electric-crocodile`）

- 2026-10-04 —— 批 36：底栏整条按 TG 重做（`MainTabsLayout.onMeasure` 的三趟试排 + 文字宽自适应分格抽成 `state/TabBarMeasure.kt`；`glass_tab*` 三键与两条 blend；选中/未选中换字重 Medium↔ExtraBold；删掉自加的按压态与恒为 null 的 `TabItem.badge` 槽位）（分支 `electric-crocodile`）

- 2026-10-04 —— 批 35：拆掉下拉刷新 —— 参考项目没有这个手势（`RefreshableBox` 去掉 action 参数、实现从 M3 `PullToRefreshBox` 换成裸 `Box`；`RefreshAction` 保留但不再对外暴露 `isRefreshing`；顺带把「标志先立后 launch」从派发器细节里解出来）（分支 `electric-crocodile`）

- 2026-10-04 —— 批 34：项目页「一层一层走」—— 目录下钻 / 选择模式 / 底部操作面板（`childrenOf` 全路径前缀 + 搜索跨层 `poolFor` + 长按进多选 + `ActionBottomSheet` 首次接线；`ActionBar` 加 `backGlyph`）（分支 `electric-crocodile`）

### [2026-10-02](2026-10-02.md)（19 条，最新在最上）

- 2026-10-02 —— 批 19：冷启两行红字不会自己变绿（首屏加一条**有界**重问 `HomeRetryEffect`；`summaryWired == false` 立即停；真机 5s 红 → 11s 自己绿）（分支 `rich-owl`）

- 2026-10-02 —— 批 18：`:ui` 三处返工（边到边 inset / 底栏改回 TG 底栏语法（图标+文字、无指示线）/ 切页每帧重组的收口）（分支 `rich-owl`）
- 2026-10-02 —— 批 17：保活服务的进程级真错（装配层投的 `ACTION_START` 不带契约 → 服务拒收 → 5 秒窗口无人认领 → 系统连进程一起杀；真机复验 `isForeground=true`）（分支 `rich-owl`）
- 2026-10-02 —— 批 16：本机出「真形态」APK（引擎三件首次随包）+ 随包资产被 aapt2 缺省忽略表静默剪裁的真错 + 内置示例脚本与门禁（分支 `rich-owl`）
- 2026-10-02 —— 批 15：TG 手势与动效补齐（页签切换 / 删除粒子 / 下拉刷新 / 长按菜单）（分支 `rich-owl`）
- 2026-10-02 —— 批 14：`:ui` 命名去参考项目前缀 + 补齐三处动效（分支 `rich-owl`）
- 2026-10-02 —— 批 13：许可改 GPL-2.0-or-later + `:ui` 重构为 Telegram 质感（深浅双主题 / 状态层三态收敛）（分支 `rich-owl`）
- 2026-10-02 —— 批 12：D5 贡献门槛拆分（快速开始 + AI 署名迁 CLAUDE.md）· B7 `INTERNET` 真机 A/B 冒烟 · D14 `ModuleGraphTest`「看不见」证伪（分支 `hellish-shrimp`）

- 2026-10-02 —— 结构面批：D9 `HostNpm` 两份并成一份（testFixtures 注源）· D10 `bridge/README.md` · D12 `.github/CODEOWNERS` + 指名维护者（分支 `hellish-shrimp`）

- 2026-10-02 —— 文档面改进批：C10 契约侧 npm 版本过期叙述订正 · D2 状态页加「怎么读」 · D7-② `autojspro-docs.txt` 入档 `docs/reference/` · `CLAUDE.md` 可见性口径改 public

- 2026-10-02 —— 第二轮外审处置：证伪 2 条 / 收窄 1 条 / 立刻改 1 条（README 如实警告）/ 登记 backlog 九条（A7、B7、B8、C10、D9–D13）/ 有意不采信 1 条 —— 口径见 `design-decisions.md` 第 28 项

- 2026-10-02 —— 批 10：C7 脚本 API 参考（typedoc 生成物入库 + 零 diff 门）+ B4 依赖供应链面（分支 `hellish-shrimp`）

- 2026-10-02 —— 批 9：A6+E4 落地 —— vendored npm 素材换 registry `npm@12.2.0`（脊梁 12.x 兑现，测试件随换源消失）（分支 `hellish-shrimp`）

- 2026-10-02 —— E5 拍板：48×48 形态判据放宽 <100ms（64.43ms 接受转绿），E2 全收口（分支 `hellish-shrimp`）
- 2026-10-02 —— E2 落地：`matchTemplate` 拆 std/相位双门 + 负结果精确兜底（backlog E2 裁决「修」；分支 `hellish-shrimp`）
- 2026-10-02 —— C9 落地：`docs/README.md` 总索引（「文档边界」表搬家，不是并列）（分支 `hellish-shrimp`）
- 2026-10-02 —— C6 落地：`design-status.md` 拆「当前状态页 + 按日期流水切片 + 实现注记」（分支 `hellish-shrimp`）
- 2026-10-02 —— A2b 拍板落地：shell 捕获输出超限改「静默截断 + Warning + 截断标志」（分支 `hellish-shrimp`）
- 2026-10-02 —— E1 拍板落地：接受 APK 超支 + 能力中心明示实测安装体积（分支 `hellish-shrimp`）

### [2026-10-01](2026-10-01.md)（17 条，最新在最上）

- 2026-10-01 —— CI 红的两例 AppShellTest：裁决输入借了宿主 `/proc`（分支 `hellish-shrimp`）
- 2026-10-01 —— 批 7（三项 S 级）：D8 许可声明 / D6 命名面 / D1 模块归属（分支 `hellish-shrimp`）
- 2026-10-01 —— 批 6：**D3/D5 platform 子包对齐 + D7 大文件拆分**（分支 `hellish-shrimp`）
- 2026-10-01 —— 批 5：**B1 CI 覆盖收口**（Android Lint / APK 构建进 PR 门；真 npm E2E 进 nightly + 验尸门；分支 `hellish-shrimp`）
- 2026-10-01 —— 批 4 后半：**A1 npm 生产装配接线收口**（素材随包 → 启动期落位 → 注入执行体；分支 `hellish-shrimp`）
- 2026-10-01 —— 批 4 前半：**A1c 接缝形状**（`secretKey(): SecretKey`；分支 `hellish-shrimp`）
- 2026-10-01 —— backlog **C2** 收口（机器路径出跟踪文件；分支 `hellish-shrimp`）
- 2026-10-01 —— C4 收口（维护者已开通 GitHub 私密上报；分支 `hellish-shrimp`）
- 2026-10-01 —— 待办池**批 3**（C1 / C5；C4 复核；分支 `hellish-shrimp`）
- 2026-10-01 —— 待办池**批 2**（B2 / C3 / D4 / D2；分支 `hellish-shrimp`）
- 2026-10-01 —— 待办池**批 1**（A2 / A3 / A1b / A4；分支 `hellish-shrimp`）
- 2026-10-01 —— 第二次外审：建议落进新建的 **[`docs/backlog.md`](../backlog.md)**（待办池）
- 2026-10-01 —— 外审整改·文档侧收尾 + 四处稳健性修复（5+1+4；`0e42ed3`…`08e89a6`，分支 `hellish-shrimp`）
- 2026-10-01 —— npm P1 T1 放行门禁的**存盘移植**（`node-slice` 两提交 → `feat/npm-t1-lifecycle`）
- 2026-10-01 —— §8.5/§8.6 收口：无人 await 的 run 自带期限（`feat/fastpath-16x`）
- 2026-10-01 —— 三大形态修复：matchTemplate 大模板 / findFeature 恒假 / findColor 全帧（commits `a78515c`/`34fd80e`/`f6cb926`，`feat/fastpath-16x` 叠在 `8d20500` 之上）
- 2026-10-01 —— FastPath 12a + 场景端粗筛缓存（commits `a22fbfd`/`8d20500`，PR #13）

### [2026-09-30](2026-09-30.md)（15 条，最新在最上）

- 2026-09-30 —— 相位探针门 + 自适应 K（评审二轮原型移植，commit `0ec6ef4`，PR #12）
- 2026-09-30 —— 评审 patch 验证轮 → 采纳（commit `852fb45`；粗筛下限 48px + kMinCoarseSide 12 + needle 缓存）
- 2026-09-30 —— `images` 匹配提速：金字塔粗筛 + `region` + 计算出锁（评审拍板案，两提交）
- 2026-09-30 —— A2–A4 优化后真机复测（同日第二次；run1 金字塔 vs run2 强制精确 A/B）
- 2026-09-30 —— A2–A4 真机性能实测（恢复自挂起；云手机 Android 13/API 33/arm64/4KB，OpenCV 4.14）
- 2026-09-30 —— 外部审查整改·步骤 8：framework-design.md 拆 12 卷 + 机器读者/文档漂移修缮
- 2026-09-30 —— 外部审查整改·步骤 7：单一 schema 生成契约 + dist 出库
- 2026-09-30 —— 外部审查整改·步骤 6：platform 按能力重组 + Wm/Power 移出 `:app`（零新模块，模块表 15 不动）
- 2026-09-30 —— 外部审查整改·步骤 5：拆 `:app-service:npm`（模块 14 → 15）
- 2026-09-30 —— 外部审查整改·步骤 3：JSON 只留一个（DomainJson 合一）
- 2026-09-30 —— 外部审查整改·步骤 1：机器路径 18 处清零 + 原生暂存入约定 + `:engine:sandbox` 空壳摘除
- 2026-09-30 —— 外部审查整改·步骤 4：删 \*Lite + RpcNamespaceHandler 基类承接解码与错误映射
- 2026-09-30 —— 外部审查整改·步骤 2/4 先行：build-logic 约定插件 + JVM 插件纠偏 + 共享架构门
- 2026-09-30 —— A 组第一批实测（云手机，Android 13 / API 33 / arm64 / PAGE_SIZE=4096）
- 2026-09-30 —— 等设备的那笔账：真机红测可执行清单（未执行，只列账）

### [2026-09-29](2026-09-29.md)（1 条，最新在最上）

- 2026-09-29 —— 真机垂直切片红测（非 root shell，Android 13 / API 33 / arm64-v8a / PAGE_SIZE=4096）
