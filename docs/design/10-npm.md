## 10. npm 支持（包管理与依赖生态）

> 硬性需求「必须支持 npm」的设计超纲部分。核心矛盾：Node-on-Android 无 `child_process`，而 npm CLI 重度依赖 spawn。以下方案把「零 spawn」从 workaround 变成**官方默认语义**。

### 10.1 总体策略 —— D（混合）

**脊梁：vendored 真 npm CLI（npm 12.x 系，要求 Node≥24.15，由 24.21.0 满足）在专用安装会话进程内「进程内执行」。**

> **素材来源（2026-10-02 起）**：**registry 发布态 tarball `npm@12.2.0`**（sha1 `9b58e3ad…`
> 钉 `VERSIONS.env`，`fetch-and-build.sh` §9 下载 + 双闸校验后同库收敛出库，与 libnode 同一
> artifact）。此前的「从 Node 源码树取 `deps/npm`」口径已作废（那一路只能拿到 11.19.0，低于
> 本行脊梁；2026-10-01 实测 `nodejs.org/dist/index.json` 868 条官方发布无一条携带 12.x，
> 「等 Node 线携带」原理上不成立）—— 沿革见 [`design-decisions.md`](../design-decisions.md)
> 第 26 项。**接线链不变**：`OUT/npm` → gradle `prepareNpmCliAssets` 随包 `assets/npm/` →
> 启动期 `AssetTreeCliSource` 幂等落位 `files/npm/` → 注入 `HostNodeExecutor`。
> **钉死 + 全链回归纪律不降级**：改 `NPM_CLI_VERSION` / `NPM_CLI_SHA1` 即命中 node-slice
> paths 触发面。发布态天然无 `test/`、`tap-snapshots/`（素材 1846 → 1674 文件）。

- **零 spawn 是实证事实**：`npm install` 的实质 = `@npmcli/arborist reify()` + pacote 下载/解包/链接；本机 strace 实测 `npm install --ignore-scripts` 全程 **0 次 execve**。纯 JS 生态（axios/dayjs/lodash/cheerio/ws/express ≈99% 用例）根本不需要子进程。
- **用真 CLI 而非重造轮子**：lockfile v3、audit、~~`approve-scripts`~~ **`install-scripts`（12.2.0 实名，`npm install-scripts approve <pkg>` 放行）**、`replace-registry-host`、`--prefer-offline` 免费获得且可审计。npm 12 默认「拒绝~~全部~~ **依赖** lifecycle + allow-git=none + allow-remote=none」，把 child_process 缺失从 workaround 变成**官方默认语义**。~~（**现状是 npm 11.19.0**：这层「官方默认」当前不存在，护栏由硬编码 `--ignore-scripts` 单独承担 —— 见 §10.12 风险表。）~~（**2026-10-02 A6 落地后，素材 npm 12.2.0 逐字实测**：**依赖** lifecycle 默认拒 —— `allow-scripts` 白名单缺省为空，拦时带 `npm warn install-scripts` 播报（列出被拦包与脚本名，不静默）+ `install-scripts approve` 放行通道；`allow-git=none`、`allow-remote=none` 实测吻合；**项目自身** lifecycle 仍执行（历代如此）→ 硬编码 `--ignore-scripts` 是主控、继续担另一半，不撤。）
- **三通道合一**：B（零 spawn 编程式）作 P0 主线；A（spawn 桥）作 P1 升级通道（批准后脚本/`npm run`/`npm exec`）；C（离线 bundle + 精选 tarball 种子）作首发与离线通道。
- 否决纯 B（丢 CLI audit/approve/config 语义，多维护一层）、纯 A（P0 依赖未经验证的真子进程，OEM exec/SIGKILL-only/内存峰值风险最高）、纯 C（桌面预装无法满足「用户自主安装」的硬性需求）。
- **沙箱关系（2026-09-26 已裁，口径随之简化）**：npm 是 Node 高信任层的能力，而引擎只剩 Node 一条轨 —— 不存在"`:sandbox` 白名单不含 `auto.npm`"这回事，能力中心也不再显示「沙箱不支持」。
- **process.exit() 边界**：安装长任务与 `process.exit` 风险由专用安装会话进程吸收，与用户脚本引擎池隔离（`slotTag='npm'`，heapCap 列见 §10.6）。

### 10.2 调用链与存储布局

```
用户/IDE「安装」按钮 · auto.npm API · 打包内嵌依赖
  → :main InstallCoordinator（全局唯一安装调度器）
      门禁：项目信任分级 / CapabilityMask={fs-write,network} / per-project 互斥锁
            / 磁盘 free≥500MB 预检 / 项目+全局配额(80%黄·100%拦) / 惰性脚本审批检查
      后台合并：走 SchedulerProvider 统一调度；强停后持久化队列恢复未完成安装
  → EnginePool.acquire(slotTag='npm', maxOldSpace=§10.6) 拉起专用 :nodeN 安装会话
  → 顶层脚本执行 node <filesDir>/npm/bin/npm-cli.js <install|ci|uninstall|ls|prune|dedupe|audit>
      --cache <cacheDir>/npm-cache --prefer-offline   （npm12 依赖脚本默认拒 · allow-scripts 白名单空，实测注见 §10.1）
      + 强制注入 child_process 拦截 shim（非批准路径 spawn 硬失败 ERR_NPM_SPAWN_BLOCKED）
  → 进度/警告/审批事件经 TSF 双队列 JSON-RPC 回 :main → UI 渲染
  → post-check（lock 验签 / hasInstallScript 告警 / 防篡改比对）→ 四步 quiesce 回收槽
```

- **出网前提（2026-10-02 补，backlog B7 落地）**：APK 必须声明 `android.permission.INTERNET`
  —— **普通权限**，安装即授、无需运行时申请，不入 §9.5 三态门禁。缺它时上一链的取包段
  （registry packument 拉取 + pacote 下 tarball）**静默必败**（超时/ENOTFOUND，不是崩溃），
  即「能装能起、`npm install` 必失败」。脚本侧无感：桥走 abstract unix socket、§12.1 无 HTTP 面。
  落点 `:app` manifest（唯一 APK 组装点）。

存储布局（**分层到不同生命周期目录**，整改自批判「状态单点系于 filesDir」）：
- `files/scripts/<projectId>/`：`package.json`、`package-lock.json`(v3)、`node_modules/`、`.npmrc`（项目级）。⚠ `filesDir` 所在分区文件系统由厂商决定（ext4/f2fs 皆有）——f2fs+eMMC 纳入真机红测矩阵，bin-links/符号链接/20k 小文件写方差按最差形态设计超时。
- `files/.autojs`（**App 私有、安装会话只读、HMAC keyed 于 :main**）：`approve-ledger.json`（审批记录，条目绑定 `pkg+版本+脚本内容哈希`，新版本必须重新审批）、`lock.sig`、`install.journal`（事务日志）、`install-history`（审计）。
- `cacheDir/npm-cache`（**系统可自动清，损失可接受**）：npm 内容寻址缓存 `content-v2 + index-v5`（非 SQLite）；`cacheDir/npm-cache-seed`：精选 tarball 种子（axios/dayjs/lodash/cheerio 等 ~5MB），首启播种。
  **「npm 的缓存在哪」只有一份判据（2026-10-09 批 86）**：`NpmCacheSeedDeployer.cacheRoot(cacheDir)` = `cacheDir/npm-cache`，四处读者（喂给 npm 的 `--cache`、`CacacheIndex` 查询根、缓存回收根、离线 bundle 导入落点）一律经它。此前四处各拼各的，实测**互不相同**：`--cache` 拿到的是 `cacheDir` 本身（少一层）、`CacacheIndex` 读 `cacheDir/npm-cache`、导入落点又是第三个目录（协调器 `npmCacheDir` 未传时的 `projectsRoot` 同级兜底）—— 而 `cacheRoot` 自己当时是**恒等映射**却挂着「缓存根（cacheDir/npm-cache）」的注释，注释与实现相反。后果不是报错而是**静默失效**：`offlineGap` 恒报缺口、导入完 `ci --offline` 照样不命中。
- `files/npm/`：vendored npm CLI（assets→filesDir 原子部署 tmp+sha256+rename；首启/升级落盘）。**已落地（2026-10-01）**：素材根 = `assets/npm/**`（键形状 `npm/<rel>`，零点条目 —— AssetManager 对点条目的可见性 ROM 间不一致），幂等锚 = `files/npm/.cli-manifest.sha256`（内容 = 全树清单规范化后的 sha256；2026-10-06 B9 起，构建期生成 `assets/npm-manifest.json`，含 count/bytes/files[path,sha256]。部署先核对 APK 文件集合、总字节和每件摘要，再核对落盘树；一致才免写，缺件/不可读/坏摘要拒绝部署并保留旧树。每次启动需要读盘校验，不再承诺零 IO）。**剪裁口径已实测**：按此口径剪出来的树（1668 文件 / 9MiB / 点条目 0）`npm ls`（装进 arborist）与真 `npm install` 都通过 —— 「丢点条目会不会弄瘸 npm」有实测答案，不走推断。
- `files/offline-bundles/<bundleId>`、`files/npm-import/`：离线 bundle / 本地 tarball 导入区。
- registry 配置：项目 `.npmrc` → `files/.npmrc`(userconfig) → `NPM_CONFIG_REGISTRY` env；默认 **`registry.npmjs.org` 官方**（§18 第 7 项 2026-09-26 拍板；要快自己 `setRegistry` 切 npmmirror/华为/腾讯），`replace-registry-host=npmjs` 使 lockfile 跨 registry 可用；代理 `Settings.Global.HTTP_PROXY` → 引擎 env `HTTP(S)_PROXY`。
  **userconfig 层已落地（2026-10-09 批 83）**：`files/.npmrc` 由 `NpmGlobalConfig` 读写（整文件重写，非行级 append —— 用户手编的文件，行级追加会让同一个键出现两行），并与喂给 npm 的 `--userconfig` 指**同一个文件**；项目级那份由 `HostNodeExecutor.prepareWorkDir` 拷进 workDir，而 `--prefix` 就是 workDir —— 于是 npm 的项目级配置读的就是它。此前 `--prefix` 一给、`.npmrc` 不拷，项目 `.npmrc` 是**死配置**。

### 10.3 spawn 三层策略与不可行边界

| 层 | 内容 | 阶段 |
|---|---|---|
| **T0 零 spawn**（P0 承诺面） | npm12 默认拒绝全部 lifecycle + `--no-audit --no-fund`；install/ci/ls/dedupe/prune/uninstall/audit(在线)/cache 全进程内；bin-links 走纯 fs（sdcard 才需 `--no-bin-links`） | P0 |
| **T1 批准后脚本**（P1） | per-package approve 后的 lifecycle/`npm run`/`npm exec` 触发 spawn，被 `--require` 注入的 child_process shim 拦截 → 桥 → `:main` 沿 EnginePool 同路径拉临时引擎执行；stdio 走 TSF 二进制数据通道做假管道；**shim 直接拒绝 `detached:true`/`setsid`**（ERR_PERMISSION_DENIED + 可操作话术）；脚本宿主独立 pgrp，回收顺序 TERM→超时→SIGKILL 且 `kill -- -<pgid>` + `/proc` 同 UID+PPID 链二次收割；stdio write-end 由桥独占持有（CLOEXEC 注入）+ EOF 看门狗，宿主退出即强制 close 全部挂接 FD 防孤儿占管 | P1 |
| **T2 sh 包裹与 PATH `node`**（P1红测项） | 需要「node 可执行身份」= node-shim PIE（dlopen libnode.so + node::Start，几十~200KB，同源同 16KB ELF 门禁），经 jniLibs 交付，PATH 注入；`sh -c` 由 `:main` ProcessBuilder 起 `/system/bin/sh`；targetSdk36 设备若 app 数据区 exec 被拒 → **降级 T1-only + UI 明示** | P1 |

**明确不可行（写死拒绝、报可操作错误而非假成功）**：
- `git:` 依赖 → `ERR_NOT_SUPPORTED`（install 入口即拒，引导本地 tarball 导入）；
- `node-gyp` 设备端编译 → `ERR_NOT_IMPLEMENTED`（设备无 NDK/编译器，引导 **wasm 优先、纯 JS 兜底**——如 `esbuild-wasm`/`node:sqlite`/`bcryptjs`；prebuild 小工具已移出排期，真需要时再立需求）；
- `fork`/`cluster` → `ERR_NOT_IMPLEMENTED`（并发用 engines 进程池）；
- 从 app 数据区任意 exec 二进制（W^X）→ 拒绝（原生 bin 走「代码签名 exec」独立通道，见 §10.11 P3）；
- `npm exec` 非 node 二进制 → 仅走既有 `auto.shell`(root/adb) 能力且 Node 高信任才可（沙箱已裁，不存在另一档"一律拒绝"的引擎）。

**T1 宿主侧门禁面已落（2026-09-29，`:app-service:npm`；拆模块前属 `:app-service:packager`）**：`PackageManagerFacade.runScript`/`exec`
不再是「一律 `ERR_PERMISSION_DENIED`」的拒收桩，改成真实的三段式 —— **解析 → 门禁 → 执行接缝**：

1. **解析（`NpmScriptResolver`，`:main` Kotlin 直读，零 Node 进程）**：从盘上 manifest 重算
   `(pkg, versionHash)`。两个哈希口径都与 `pkg@version` 绑定，故「改脚本 / 升版本 = 另一份授权」
   自动生效（§10.5-2「版本升级必须重新审批」的同一条纪律）。scripts 哈希吃**键排序后的规范化文本**
   （只换键序不该让用户重批），身份与正文**带长度**再拼（`("ab","c")` 与 `("a","bc")` 不得同哈希）。
   bin 侧另过**纯 JS 白名单**（`.js`/`.cjs`/`.mjs`）：非 JS 目标 `ERR_NOT_SUPPORTED` 并指向
   「wasm 优先、纯 JS 兜底」（本节「明确不可行」那节）；同名 bin 多包声明 → 拒绝并点名
   （npm 靠安装顺序消歧，宿主重算不出稳定答案，猜一个就是让审批哈希对不上真正的执行目标）；
   路径逃出包目录 → 拒。简写 `bin` 的隐含命令名按 npm 口径取**包名**（scope 剥掉），不是文件名 ——
   `{"name":"my-server","bin":"./server.js"}` 装出来的命令叫 `my-server`。
2. **门禁（`InstallCoordinator.runScriptOps`）**：键 = `projectId + "<pkg>|<脚本名|bin名>" + versionHash + action`，
   必须命中 ledger 的 APPROVED 票。**未获批时不是干巴巴报错**：请求自请入队（走 approvals 流 +
   脚本侧 `drainApprovals` 拉取口），用户在能力中心审批卡上当场决定 —— 这正是 §18 第 7 项
   「不做出厂卡口、安装时让用户自己选」的交互形态（§18 第 7 项的「口径在此定死，实现排 P1」
   按本条落地，未重新拍）。键的哈希段**只能**由门禁自己重算，脚本既不知道也不该有资格编。
3. **执行（`ScriptOpExecutor` 接缝）**：`Unavailable` 是缺省 → 有审批票也如实 `ERR_NOT_IMPLEMENTED`，
   并点名缺的是「child_process shim → 临时引擎」这一段。**刻意不复用 `HeavyOpExecutor`**：
   那条通道编排的是事务（stageDir + journal + 原子落位），为的是 npm CLI 会重写 `node_modules`；
   lifecycle 脚本不做 reify，走那条链会为一个不改依赖树的操作凭空造暂存目录与 commit 记录，
   `unfinished()` 里多出没有产物的残骸。两条通道共用 TTL（脚本侧 `SCRIPT_TIMEOUT_MILLIS` = 60s，§7 铁律 3）/
   取消 / 事件面，差异只在落位。参数交出的是 npm 口径（`run <name> -- <args>` / `exec <args> -- <bin>`，
   分隔符**在参数之前** —— 少了它 `--watch` 会被 npm 自己吃掉，等于静默丢用户显式给的参数）。
4. **取消路径按「有没有事务」分流（2026-09-29 补）**：`TrackedOp.journaled` 为 false 的（T1 全属此类）
   取消时**不写** `journal.fail`、不 sweep —— T1 没有事务，写进去就是在 §10.4 的事务状态机里塞一条
   「从未 begin 却已 fail」的记录，而 journal 正是残骸清扫的判据源，无源之记会让自愈去扫不存在的残骸。
   同一补丁还收了口子的另一半：取消与执行体收尾是**竞态**，`cancel()` 先到时已发过
   `Finished(success=false,「已取消」)`，执行体随后收尾会再发一条终态 —— 同一句柄两个 `Finished`，
   订阅方无从裁决那次到底成没成。执行侧收尾复查 `cancelled`，已取消则**不采纳**结果；
   catch 分支只在还没发过终态时补发。

**仍未落**：spawn 桥本体（stdio 假管道、pgrp 杀树、node-shim PIE 与 PATH 注入）——
上面第 3 段是它的**接缝**，不是它的实现。

**已落（2026-10-09，T0 面）**：**强制注入的 child_process 拦截 shim**（上表 T0 末行 /
§10.12 末行那条「零 spawn 不变量漂移」）—— `NpmSpawnGate` 把 `npm-spawn-gate.cjs`
（`:app-service:npm` 的 classpath 资源）落到 `files/.autojs/`，`HostNodeExecutor` 经
`NODE_OPTIONS=--require=<它>` 注入安装会话进程（**追加不覆盖**父环境里那份），
七个入口（`spawn`/`spawnSync`/`exec`/`execSync`/`execFile`/`execFileSync`/`fork`）一律抛错：
非批准 spawn → `ERR_NPM_SPAWN_BLOCKED`、`detached:true` → `ERR_PERMISSION_DENIED`
（上表 T1 那条「shim 直接拒绝 detached」）、`fork` → `ERR_NOT_IMPLEMENTED`。
**落位失败 → 装配层不注入安装执行体**（fail closed：它是 P0 承诺面，静默降级成
「装是能装、守卫没了」正是这条风险本身）。§10.12 末行的**桌面 CI 金标准**落成
`NpmSpawnGateMatrixTest`（门禁注入下 install/ls/dedupe/prune/uninstall/ci 全绿 +
不注入也全绿的反向变异 + `npm run` 确实被拦），并登记进 `check-e2e-ran.sh`。
**边界**：它是不变量守卫，不是安全边界（已获批脚本可 `delete require.cache` 绕过）。


### 10.4 事务化安装与崩溃自愈（整改自批判 android-runtime F1）

> 批判指出：安装是原地、非原子写 node_modules，而 Android 上进程死亡是常态；半解包 + 坏符号链接会让后续 ci/install 在坏树上反复 EINTEGRITY。

- **reify 目标 = `node_modules.part-<ts>` 暂存目录 → 完成校验 → rename 到位**；写入 `.autojs/install.journal`（begin/commit/fail 三段）。
- 启动与每次安装前置扫描 journal：检测 incomplete → UI 提示**一键回滚重建**（按 lock 走 `ci --offline`）。
- 安装会话挂 **specialUse FGS（副类型 automation）+ oomAdj 前台豁免**，并纳入 `:main` 看门狗/kill 权威（RuntimeController 语义）而非独立存活；`:main`↔session 心跳断 → 新会话接管收尾。
- Doze/强停：安装统一走 SchedulerProvider；Doze 下降级为「仅解析+下载+离线物化 cache，reify 写 node_modules 推迟到前台机会」；网络失败返回 `ERR_REGISTRY_UNAVAILABLE`（可诊断）而非挂死。
- 产品规则改为「**熄屏仅允许下载物化，reify 需前台或 specialUse FGS 持有时**」——而非一刀切「熄屏不安装」。

### 10.5 供应链安全（带外信任锚 + 审批人机分离）

1. **Lockfile-first + 带外信任锚**（整改自批判「TOFU 自签」）：
   - **pin npm 官方 ECDSA 注册表签名公钥**；首装前验 packument/tarball 签名。
   - 镜像不支持签名端点（npmmirror 暂无）→ **多镜像 integrity 交叉校验**（npmmirror+npmjs 对同一 spec 的 extract 哈希一致才接受）。
   - 设备端首生锁标记「来源未校验」**降信任级 + UI 明示**；正式链路走桌面/CI 离线生成 + 来源证明（sigstore 可选）。
   - App 以应用密钥对 lock 做 HMAC/ECDSA 签名（`lock.sig`，私钥入 **Android Keystore**；密钥丢失 = 显式「安全降级」状态而非假装模型成立）；`npm ci` 前验签；市场/第三方项目**只开放 npm ci**（无裸 install）。

   **已落地（裁决与处置，`:app-service:npm`）**：`NpmRegistryVerifier` 是三分裁决而非布尔——`Agreed` / `Disagreed` / `Unverifiable`，调用方必须能区分「验过且一致」与「没能验」（否则 UI 只能画同一个绿勾）。第二意见恒为 `registry.npmjs.org`，**不随用户首选变**（首选容易被自己改成 npmjs，那就成了自己跟自己比）。
   处置写死在 `InstallCoordinator.crossCheckRegistry` 一处：`Disagreed` → `ERR_REGISTRY_UNAVAILABLE` + 两家版本/完整 integrity，安装会话不起、事务不建、拒本身入史；`Unverifiable` → **不拦安装**但发 `InstallEvent.Warning(TRUST_DOWNGRADED)` + 入史「来源未校验」（副镜像不可达 / 版本只在一侧 / 无 integrity 锚点都是「没验成」而非「验出问题」，当分歧拒掉会把镜像同步窗口期误判成攻击）。
   **首选来源与实际安装同源（2026-10-09 批 83）**：`crossCheckRegistry` 的 primary 取自 `InstallCoordinator.resolveRegistry` —— 项目 `.npmrc` → 全局 `files/.npmrc` → 出厂官方**两层解析链**，与喂给 npm CLI 的配置同一处读出。此前 primary 只读项目 `.npmrc`、而安装被 `--registry` 钉死在官方，两者可以不是一家 —— 交叉校验就变成了「拿 A 跟 B 比、装的是 C」。
   判定对象是 `dist.integrity` 而非两个 tarball 的字节（结论等价、少一倍下载）。JS 侧 `auto.npm.onWarning` 的 `kind` 联合与 `InstallEvent.Kind` 五值逐字对齐。**生产投递走事件拉取口**（`drainEvents` → JS 轮询泵 → 过 `feedWarning` 做 kind 校验；`feedWarning` 自身降为注入缝供装配/测试直调，2026-09-26 起不再是唯一投递方 —— 在那之前它零生产调用者）；
   未知 kind 抛错而非静默丢弃（契约漂移即响亮错误）。诚实边界：**不**回答「镜像 hardcode 的摘要是否真由上游产生」——那要 sigstore/官方签名端点，记为未决项。
2. **审批 = 人的动作（人机分离）**（整改自批判「程序化绕过」）：
   - `approveScript`/`runScript`/`exec` **不允许脚本直调**——脚本只能发出 `ApprovalRequest` 排队，等 UI 弹卡人工二次确认（可配生物特征），脚本侧限流 + 全量审计。
   - 审批记录绑定 `pkg+版本+脚本内容哈希`，版本升级必须重新审批；审计日志（approve/registry 变更/lock 重签）落 App 且可导出。
     **落地现状（2026-10-09 批 85）**：「落 App」自始成立（`InstallHistory` → `files/.autojs/install-history.jsonl`，只追加、失败也记），但**读侧此前零消费方** —— 本批补上 `PackageManagerFacade.history()` → `HostSummary.npmHistory()` → `:ui` `AuditScreen`（入口在依赖管理页顶栏），见 §10.9 第 2 条。**「可导出」仍未落**：SAF 选目录 + 写文件那条通道没接，故界面上**不画导出按钮**（按下去什么都不发生的按钮比不画更糟）。
3. **恶意包防线（缺省启用）**：
   - 默认**拒绝全部 install 脚本**（对操纵无障碍/root 的自动化脚本是最大投毒面）；postinstall 包装完即出「脚本未运行」显式警告，**禁止静默**。
   - 在线 `npm audit` + `audit signatures`（ECDSA）；离线捆绑 OSV 库 + `osv-scanner --offline`；签名端点不可用**绝不静默降级**。
4. **低信任边界**：T1 脚本执行会话一律**独立最小 CapabilityMask**（仅 npm 目录 fs+network，无 a11y/shell/root），与用户脚本会话物理区分；UI 明示「审批 postinstall ≠ 授权自动化能力」；高信任须**可验证签名 + 用户显式升级**（不用软签名）。
5. ~~**QuickJS 白名单库独立 vendored**~~ **已裁（2026-09-26，§18 第 1 项）**：沙箱不在排期，白名单库不复存在。（"共享 store 要持 `store 哈希 == 各项目 lock 哈希` 的加签映射校验"那半句是 store 的纪律，随 P3 跨项目共享 store 保留。）

### 10.6 轻/重操作拆分与资源预算（整改自批判「过度设计+欠定义」）

- **重操作**（全局安装会话互斥排队）：`install / ci / uninstall / audit / importOfflineBundle` 等变更+网络操作。
- **轻操作**（`:main` Kotlin 直读实现，零 Node 进程、零全局互斥）：`list/ls / config / storage / prune 报告`——目录遍历算尺寸（不用 `du`），读 `.npmrc`。
- **内存预算协商化**（不按固定 384MB 封顶）：npm 会话 `--max-old-space-size` 从框架池预算反推（常规 192MB / 低内存 96MB）；packument 解析**流式化**（大 lockfile 依赖图不全量驻留 JS 堆）；会话 RSS 记账，超阈值先降载再放弃。
- **准入/驱逐优先级**：脚本槽优先；安装会话可排队且 UI 展示 ETA；**reify 中禁止被自适应内存回收**；quiesce 只在命令间界发生；池满或预算不足 → 显式 `ERR_NPM_LOWMEM` + 引导离线 bundle/桌面打包（替代被内核杀）。
- 全局同时至多一个 npm 安装会话；per-project 串行；安装期间对项目 node_modules 加写锁 + 默认建议「脚本结束后安装」（用户强制时显式风险确认）。

### 10.7 PackageManagerFacade（`:domain` 纯 Kotlin 接口）

```kotlin
interface PackageManager {
  suspend fun install(projectId, specs, flags): InstallHandle   // 排队→门禁→起会话→执行→post-check→归档
  suspend fun ci(projectId, offline = true)                    // lock 严格重建；市场脚本唯一入口
  suspend fun update(projectId, spec?) / uninstall(projectId, name)
  suspend fun list(projectId, depth): PkgNode[]                // 轻：Kotlin 直读
  suspend fun dedupe(projectId) / prune(projectId)             // 变更走安装会话
  suspend fun audit(projectId, offline): AuditReport           // 在线 audit(+签名) / 离线 OSV
  suspend fun offlineGap(projectId): List<MissingPkg>          // lock 闭包 − 缓存 的缺失清单(名+尺寸)
  suspend fun approveScript(pkg:, versionHash:, action)        // 仅提交人工确认队列
  suspend fun runScript(projectId, name, args) / exec(bin, args, env)   // P1 T1：宿主重算内容哈希 → ledger 命中才放行，纯 JS bin 白名单（已落 2026-09-29，见 §10.3 T1 落地追记）
  suspend fun importOfflineBundle(uri) / importTarball(path)   // 验签→校验→入缓存→ci
  suspend fun config(projectId?, key, value)                   // .npmrc 层；registry 变更经 :main 卡可配列表+审计
  suspend fun globalRegistry(): NpmRegistrySnapshot            // 全局镜像源读数（configured/defaultRegistry/secondaryRegistry；2026-10-09 批 83）
  suspend fun setGlobalRegistry(raw: String?)                  // 设/清全局镜像源；null 或空白 = 恢复出厂官方；校验不过抛原文
  suspend fun runConsoleCommand(projectId, line): NpmConsoleHandle  // 控制台命令面（2026-10-09 批 84）：解析→回显→派发；解析不过抛原文
  suspend fun consoleOutput(projectId, sinceSeq, maxLines=256): NpmConsoleSnapshot  // 控制台输出读数（seq 游标拉取，同 drainEvents 的形）
  suspend fun consoleHistory(projectId): List<String>              // 控制台命令历史读数（2026-10-10 批 90）：**按项目**、最近在最前、已去重
  suspend fun recordConsoleHistory(projectId, line)                // 同上写口：由 :ui 在**派发点**调（那里才有用户敲的原文，见下）
  fun progress(projectId): Flow<InstallEvent>              // :main 订阅用（Flow 无重放）
  fun approvals(projectId): Flow<ApprovalRequest>
  suspend fun drainEvents(projectId, sinceSeq, batch=32): InstallEventBatch   // 脚本侧拉取口（§7.5 桥无宿主→脚本推送面）
  suspend fun drainApprovals(projectId, sinceSeq, batch=32): ApprovalBatch    // 同上；游标由调用方持有
  suspend fun storage(): Map<ProjectId, NodeModulesStats>   // 每项目 node_modules 体积
  suspend fun cacheStorage(): NodeModulesStats             // npm 缓存体积（**全机一份**；2026-10-09 批 87，§10.9 第 5 条的 npm-cache 尺寸栏）
  suspend fun exportSnapshot(uri): SnapshotRef                 // node_modules.zip+lock+ledger→SAF；高信任通道
  suspend fun cancel(handle: InstallHandle)               // TTL/取消 → quiesce 安装会话
  suspend fun history(): List<InstallHistoryEntry>         // 审计史（2026-10-09 批 85）：append-only 历史，与 snapshot() 的「当前事实」是两件事
  suspend fun reclaimCache(): NpmCacheReclaimReport        // 缓存按 lock 闭包回收（2026-10-09 批 86）：**不是** npm 的 cache clean，见 §10.9 第 5 条
}
```

**事件面是拉取不是推送（2026-09-26 收口的第三处落差）**：桥的入站面只有按 requestId 结算的 ok/err（§7.5），宿主没有任何主动推给脚本的通道 —— 在此之前 JS 的 `onProgress`/
`onWarning`/`onApproval` **双侧都没有投递方**（订阅了但生产永远不响：`progress`/`approvals` 两个 SharedFlow 零订阅、`feedWarning` 零生产调用者、`InstallFailure` 连订阅口都没有），
正是 `feedWarning` KDoc 自己写的「比没有这个 API 更糟」。接法沿用仓库既有的游标拉取（`a11y.events`/传感器批次/`startHeartbeat`）：`InstallCoordinator` 两条有界环（`SeqRing`，
512、DROP_OLDEST、单调 seq）由 `emit()`/`requestApprove` 唯一投递，`drainEvents`/`drainApprovals` 按调用方游标取批；回包 `{first,last,items}`，空增量 `first=last=sinceSeq`，
环丢过最旧时 `first > sinceSeq+1` 即空洞可见（进度是可丢数据面，如实露洞不补造）。四个 DTO（`InstallEventBatch`/`SequencedInstallEvent`/`ApprovalBatch`/`SequencedApproval`）与两个新方法由 `PackageManagerFacadeContractTest` 冻结，
JS 侧 `npm-events.test.cjs` 逐字复刻同一套回包语义。

**审计读口（2026-10-09 批 85，§10.5-2「审计日志落 App 且可导出」）**：`history()` 是
`install-history.jsonl` 的只读投影，**无参**（与 `pendingApprovals` 同一取舍）—— 按项目筛会让
`Op.REGISTRY` 那条（`projectId` 空串，改的是 `files/.npmrc`，不属于任何项目）从任何一次筛选里
掉出去，而它恰恰是审计最该看见的。**它为什么不并进 `snapshot()`**：两者问的是两件事 ——
`snapshot()` 答「此刻装了什么」（当前事实，每次现算），`history()` 答「过去发生过什么」（历史事实，
落盘即定）；并进去等于每刷一次依赖面板就把全部历史重读一遍，而依赖面板根本不显示它。
`InstallHistoryEntry` 五字段（`op`/`projectId`/`success`/`detail`/`atMillis`）**由落盘格式决定**，
不由界面想显示什么决定 —— 加字段要动那份审计文件的形状，等于让历史行与将来行不可比。
`InstallHistoryOp` 是已知操作名的**唯一一份**（落盘侧 `InstallHistory.Op` 改为指向它的别名；
`:ui` 看不见 `:app-service:npm`），而 `InstallHistoryEntry.op` 仍是 `String`：取值域**开放**
（`opName(args)` 直取 argv 首词、T1 动作名来自 `ApprovalAction.name.lowercase()`），落盘侧刻意
不因枚举不全丢事件，读侧也不该把不认识的行藏掉。**读口不经桥**（同控制台/镜像源）。
**同批补记**：`enqueueHeavy` 的磁盘/配额**预检拒绝**原先不入史（`crossCheckRegistry` 的拒绝一直在记），
已补 —— 否则用户在审计页上看到的是「什么都没发生」，与屏幕上的报错对不上。

**控制台两条读口（2026-10-09 批 84，§10.9 第 3 条）**：`runConsoleCommand`/`consoleOutput` 与上面两条
`drain*` **同形不同面** —— `drain*` 是**脚本侧**的游标拉取口（过桥，`bridge/js` 消费），这两条是
**宿主自己界面**的读口（经 `HostSummary` 现取，**不经桥**，§7.3 方法表一条不加）。四个新 DTO
（`NpmConsoleHandle`/`NpmConsoleSnapshot`/`SequencedConsoleLine`/`NpmConsoleLine` 与枚举
`NpmConsoleLineKind`）住 `:domain`，由 `PackageManagerFacadeContractTest` 的方法面冻结一并覆盖。
`NpmConsoleHandle` 与 `InstallHandle` **刻意分开**：那个是「一次安装会话」的账，这个是「用户敲了一行」
的账 —— 一行 `npm run build` 走 T1 通道（无事务），一行 `npm ls` 甚至不起进程，塞进同一个句柄类型
会让 `cancel()`/journal 的语义含糊。输出环是 `InstallCoordinator` 里**另一个** `SeqRing`（有界 512），
与 `InstallEvent` 那条环互不干扰（前者给界面看，后者给脚本拉）。

**控制台命令历史读写口（2026-10-10 批 90，§10.9 第 3 条）**：与 `consoleOutput` 的分工是
**环 vs 盘** —— 输出环（`SeqRing<NpmConsoleLine>`，有界 512）随进程消失，而"关掉控制台再进来
还能翻回上次敲的那条"要靠落盘（`files/.autojs/console-history.jsonl`）。三条口径：
**按项目分开读**（与 `history()` 的无参全量**刻意相反**：控制台的命令跑在某个项目上，
在 A 项目敲的 `npm install axios` 翻到 B 项目去点，落的是 B 的 `node_modules`，而按钮上那行字
一模一样。两问不同 —— 那边问"这个宿主发生过什么"（审计），这边问"我在这个项目里敲过什么"）；
**与 `InstallHistory` 纪律相反**（那个只追加永不清理，这个允许修剪 —— 它是便利缓存，
丢掉的只是"很久以前敲过的一条命令"，没有任何事实随之消失）；**带凭据形态的行整条不记**
（`_auth`/`_password`/`--otp`/`npm_token`；`npm install --//registry.example.com/:_authToken=…`
是**能敲进控制台的**，而这份历史落盘跨重启还在。**不打码后记录** —— 打码后的历史看起来是
一条能跑的命令，点它填进输入框得到「认证失败」，那是历史自己造出来的假故障）。
**写口在调用方（`:ui` 的派发点）而不在执行入口**：历史要记的是**用户敲的那行原文**，
而执行入口拿到的是已定形的东西（shell 面那条只收得到剥掉入口词的正文，`su id` 变成 `id`，
记下来再点一次就会在默认模式里被当成 npm bin 解析）。副作用是「拒收的行与进/退模式不进历史」
成了**自然结果**而不是特判。

**缓存体积读口（2026-10-09 批 87，§10.9 第 5 条「per-project `node_modules` + `npm-cache` 尺寸」的后半截）**：
`cacheStorage()` 与 `storage()` **分开而不是并进去** —— 两者的键空间不同：`storage()` 是「每个项目各占
多大」（`Map<projectId, …>`），而缓存按内容寻址、**全机只有一份**（§10.2），按项目铺开就是同一个数字
抄 N 份，而那 N 份会让人以为「删掉这个项目的缓存」说得通。`projectId` 传空串（全局读数没有项目，与
`InstallHistoryEntry` 里 `registry` 那条同手法）。只报 `content-v2` 的体积：这个数字的用途是回答
「回收缓存能腾出多少」，而回收动的正是 content-v2 —— 把 `index-v5`（几 KB 级索引）算进来，
配额条上的数字就会与回收回执里的删/留对不上，而那两个数字摆在同一个屏幕上。量不到（缓存目录还不
存在）报 0 而**不是** null：目录不存在就是「这个缓存是空的」。

**缓存回收读口（2026-10-09 批 86，§10.9 第 5 条那颗 cache clean）**：`reclaimCache()` 返回
`NpmCacheReclaimReport`（六字段：`removedEntries`/`removedBytes`/`keptEntries`/`keptBytes`/
`keepCount`/`indexRebuilt`）。**刻意不叫 `cache clean`**（审计 op 名是 `InstallHistoryOp.CACHE_RECLAIM`
= `"cache_reclaim"`）：npm 的 `cache clean` 是整目录全清，而本动作的语义是「删掉没有任何项目 lock
需要的那些」（用户 2026-10-09 裁定）—— §10 整卷的离线能力（`--prefer-offline`、精选种子首装、
`offlineGap` 体检）全建在这个缓存上，全清等于把紧挨着的「按 lock 重装」那颗按钮变成**必须联网**。
名字跟着语义走：审计页上两件事长得一样的话，将来真接了全清就分不出来了。
**它是一份读数不是一个句柄**（与 `install`/`prune`/`dedupe`/`ci` 那四条返回 `InstallHandle` 的不同）：
不动依赖树、不占安装会话、跑完即出账，故不塞进 `NpmMaintenanceAction` 那个枚举。
**`keptEntries` 会大于 lock 闭包条目数**，这不是漏删：认不出形状的条目（非 sha512 目录、路径段数不对、
大写 hex）一律保留 —— 本方法的职责是回收缓存，不是打扫看不懂的东西，删一个读不懂的文件是「猜」，
猜错的代价是别人的离线能力，收益只是几个字节。

**同批修的一件事（不是附赠）**：回收**必须同时摘掉指向已消失 content 的 index-v5 行**。实测
（2026-10-09，npm 10.9.8）：`npm ci --offline` 走 `cacache.get.stream.byDigest`、**不看 index**
（content 在就命中，缺就 `ENOTCACHED`）；但**在线**路径读 index，index 指向一份已消失的 content 时
`npm install` 报 `ENOENT … Invalid response body while trying to fetch`（**不是**回源重下）——
即**悬空 index 会让缓存从「没用」变成「有害」，且界面上看不出来**。所以只删 content 不修 index
是半件事；`indexRebuilt` 报的就是这次有没有修到。`integrity` 为 null 的行是 cacache 的**删除标记**
（`compact` 里的记法），保留 —— 它不是悬空引用。

### 10.8 JS API —— `auto.npm`

Promise 优先 + EventEmitter；npm 操作一律跨进程路由到全局安装会话、TTL 绑定，**绝不阻塞脚本事件循环**；脚本内不直接 `require('child_process')`。

```ts
// 安装（P0）
const handle = await auto.npm.install('axios', { save: true, offline: false, timeout: 60_000 });
//     → { handleId, projectId, enqueuedAtMillis }   // :domain InstallHandle 上桥（只代表已入队）
const list = await auto.npm.list();                 // 装了什么：直读 lockfile（权威）
//     → [{ name:'axios', version:'1.20.0' }]        // 无 sizeBytes（lockfile 量不到尺寸）
const gap = await auto.npm.offlineGap();
//     → [{ name:'…', version:'…', size:1234 }]      // 尺寸在这条路（缺失清单）
await auto.npm.onProgress(e => console.log(e.phase, e.name, e.percent));   // phase: queued/resolve/download/reify/post-check/done
await auto.npm.remove('axios');
await auto.npm.ci({ offline: true });                          // lockfile v3 严格重建（验签后）
const list = await auto.npm.list({ depth: 0 });                // 轻操作，Kotlin 直读
await auto.npm.prune(); await auto.npm.dedupe();
const gap = await auto.npm.offlineGap();                       // 离线闭包差距（缺哪些包、共多大）
const report = await auto.npm.audit({ offline: true });        // { vulns:[{id,severity,name}], level, offline }
//                                                            // 键名是 vulns（不是 vulnerabilities）

// 配置/离线（P0）
await auto.npm.setRegistry('https://registry.npmmirror.com', { scope: '@my' });
await auto.npm.importOfflineBundle('/sdcard/Download/baseBundle.zip');   // SAF uri 亦可
await auto.npm.importTarball('/sdcard/Download/pkg.tgz');

// 审批（人机分离：只能发起请求，人工在 UI 弹卡确认）
await auto.npm.requestApprove('esbuild', { scripts: ['postinstall'] });  // 不直接 approve；不 await 的话被拒会成 unhandled rejection

// 事件（四个独立方法，**不是** `on('progress')` —— 那种写法在 facade 上会 TypeError，见 §12.3.2 第 6 条）
// 实现是**带游标拉取轮询**（§7.5 桥没有宿主→脚本的推送面，§10.7 drainEvents/drainApprovals）：
// 首订立拉一轮、之后按周期补拉、退订干净自停（定时器 unref 不保活事件循环，同 startHeartbeat）；
// 宿主没实现 events/approvals → 响亮 ERR_NOT_IMPLEMENTED（「订阅了却永远收不到」禁静默），瞬时错误吞掉走下一拍。
const offP = auto.npm.onProgress(e => console.log(e.phase, e.name, e.percent));
const offA = auto.npm.onApproval(req => notify('需人工确认', req.pkg));   // ApprovalRequest 六字段，无 scripts
const offW = auto.npm.onWarning(e => console.log(e.kind, e.pkgs, e.message));
const offF = auto.npm.onFinished(f => f.success ? done() : fail(f.detail)); // 成功**和**失败都发（install 回包只是已入队）
// kind: scripts-skipped/trust-downgraded/registry-fallback/low-memory/disk-quota（:domain InstallEvent.Kind 同集）

// 错误码新增：ERR_NPM_*（安装失败/审批被拒/SPAWN_BLOCKED）、ERR_NOT_SUPPORTED（git:依赖）、
// ERR_REGISTRY_UNAVAILABLE（网络/镜像可诊断）、ERR_DISK_FULL、ERR_NPM_LOWMEM、ERR_NOT_IMPLEMENTED（node-gyp/exec）
```

### 10.9 UX 流程

1. **依赖面板**（IDE 项目页）：搜索 / `npm install <spec>` 输入行 + 旗标（`-D`/`--offline`/registry 选择器）→ 阶段进度条（packument→下载→解包→链接，job 数）→ 完成横幅；hasInstallScript 包显式警告。
2. **脚本审批卡**：带 install/postinstall 脚本的包 → 卡片列表（可展开「脚本=任意代码」风险说明）→ per-package 人工批准/拒绝 / 「全局禁止脚本」（出厂默认）→ 批准记录入审计页。
   **落地现状（2026-10-09 批 81）**：第 1 条的**只读半边**已落 —— 管理面板 → 依赖管理（`NpmScreen`）
   显示已装清单（`list()` 直读 lockfile）+ 尺寸/配额条（`storage()` + `InstallConfig` 的 512MB/80% 判据）；
   第 2 条的审批卡已落（待审队列 → 批准/拒绝 → `resolveApproval`，§10.5-2 人机分离的**唯一**生产落点）。
   两条共用一个读口 `PackageManagerFacade.snapshot()`（`:domain` `NpmPanelSnapshot`），经 `HostSummary`
   现取、**不经桥**（桥面是脚本侧的面，依赖面板是宿主自己的界面）。
   **审计页已落地（2026-10-09 批 85）**：第 2 条末段那句「批准记录入审计页」原先只有前半截 ——
   `InstallHistory` 一直在写（approve / registry 变更 / lock 重签 / 每次安装的成败），但那条读口
   在**生产里零消费方**。本批补上：`PackageManagerFacade.history()` → `HostSummary.npmHistory()` →
   `:ui` `AuditScreen`/`AuditState`/`AuditOps`，入口在**依赖管理页顶栏**那颗「审计」（不另立管理面板
   第五项 —— 它记的就是依赖面那些操作，从面板直进会让人以为它与依赖管理是并列的另一件事）。
   呈现三条：**失败行不藏**（审计要能回答「用户当时看到成功了吗」）、**未知 op 原样显示**（不编
   「未知操作」—— 那是把「还不认识」说成「记录有问题」）、**筛选只影响显示**（宿主读口无参全量，
   筛掉失败行时状态栏说明「全部记录里共 N 条失败」）。**未落**：**导出**（§10.5-2 原话是
   「落 App 且**可导出**」，可导出那条通道 = SAF 选目录 + 写文件，本批没接 —— 故不画按钮）。
   **变更半边已落（2026-10-09 批 87）**：安装输入行 + 两颗旗标（`-D` / 「离线优先」）+ 六档**阶段条**
   + 清单行上的「卸载」。五条口径写死在这里，免得被"统一"掉：
   - **走的是与控制台同一条宿主口**（`HostSummary.runNpmPanelCommand` → 同一个
     `InstallCoordinator.runConsoleCommand`）：同一份判据（`NpmConsoleKeys.parse`）、同一套装前
     多镜像交叉校验、同一道磁盘/配额预检、同一把项目锁与全局安装会话。**门禁强度不取决于用户从
     哪个界面按下去** —— 新开一条「按 spec 装」的宿主口就是第二份安装入口，而两份入口的差别
     只在「谁先忘了加某道门」上体现出来。「卸载」同理走 `npm uninstall <name>` 命令通道。
   - **进度是阶段不是百分比**：`InstallEvent.Progress.percent` 全仓**从无赋值**，
     `HostNodeExecutor` 起的是 vendored npm CLI 进程、reify 在它进程内是黑盒，宿主只在前后发得出
     三枚粗标记。故画的是 `InstallEvent.Phase` 六档的**阶段条**；原文那句「job 数」同样拿不到。
     画一条会动的百分比条就是编一个拿不到的数 —— 用户看着它停在 90%，比看着它诚实地停在
     「写入 node_modules」更糟。**这条口径是对 §10.9 第 1 条原文的收窄，不是实现偷懒**。
   - **「离线优先」不是「仅离线」**：这颗旗标拼的是 `--prefer-offline`（先查缓存、缺了仍联网）。
     真正断网也要装上，靠的是缓存里恰好有全部闭包（`offlineGap` 答的就是这个）。
     界面文案与旗标名都不许把一个词当两件事用。
   - **草稿失败不清**：宿主是先落 ECHO 行**再抛**的（预检/验签拒绝都发生在发出去之后），
     清早了用户看到的是「一句失败 + 一个空输入框」，还得把包名重打一遍。
   - **换项目要把阶段条与拉取游标一起归零**（`NpmState.withProject`）：`seq` 是**环内全局
     单调**的、`drain` 才按 `projectId` 过滤，沿用上一个项目的游标会**漏掉**新项目 seq
     更小的那些事件（它们对新项目是新的、对那个游标却不是）—— 包括 `Finished`，于是阶段条
     永远停在「进行中」。归零不是"重头开始"，是"把这个项目还留在环里的那些事件全取回来"
     （与 `ConsoleCmdState.withProject` 同一条纪律）。归零的只有项目域那两样：草稿与旗标
     是用户的输入，`installing` 记的是全局在途。
   **未落**：依赖**树**（`list(depth)` 宿主侧只用 0，画树就是把平铺清单伪装成树）、
   `hasInstallScript` **前置**告警（要 packument 解析面；装完之后的 `SCRIPTS_SKIPPED` 警告
   已经在事件流里，那是**事后**的，界面不假装自己知道装之前该警告什么）、
   「全局禁止脚本」开关（出厂默认已是禁止，反向的**白名单放行**通道属 T1 之后）、
   真流式 stdout（同第 3 条的边界）。
   ~~**另**：第 5 条（包大小管理页）的「一键 prune/dedupe/ci 重装/cache clean」按钮**仍未落** ——
   配额满时那句提示今天把用户指去**控制台**敲 `npm prune`（可操作，但不是一键）。~~
   **该行已落（2026-10-09 批 86）**：四颗按钮进依赖管理页（见第 5 条），配额满那句提示改为
   指向本页的「清理多余包」（控制台那条路仍然通，第 3 条的白名单没动）。
3. **npm 终端视图**（P1）：项目内终端 `npm install axios` / `npm ls`，stdout/stderr 流式输出 + exit code；与依赖面板同一安装会话队列。
   **落地现状（2026-10-09 批 84）**：控制台页从「日志屏」改成**命令面**（用户口径：「控制台不是放系统日志的地方，
   是用来执行命令的，比如 npm」）—— 管理面板 → 控制台，选项目 + 敲一行 + 看输出；原控制台的日志内容
   **一行不少地**搬去管理面板 → 日志管理（§7.3 的消费侧随之改名）。四件：
   - **判据唯一一份住 `:domain`**（`NpmConsoleKeys`，与批 82 `ScriptEnvKeys`、批 83 `NpmRegistryKeys` 同形）：
     `parse`（一行命令 → `Npm`/`Run`/`Exec`/`Rejected` 四形态）、`SUBCOMMANDS` 白名单、
     `HEAVY_SUBCOMMANDS`/`LIGHT_SUBCOMMANDS`（§10.6 的轻/重拆分）、`packageSpecsIn`、`gitSpecIn`、
     `rejectProjectId`。宿主侧执行入口与 `:ui` 的输入行读的是**同一个结论** —— 界面当场拒的话术
     就是宿主抛出来的那句原文。
   - **白名单**（不在表内即拒，并把整张表念给用户听）：`install / uninstall / ci / ls / list / prune / dedupe / audit`，
     外加 `npm run`（`run-script` 同义）与 `npx` / `npm exec` 两种执行入口。**不是黑名单**：npm 有 60+ 子命令，
     `publish`/`login`/`token`/`owner`/`config`/`init`/`link`/`cache` 要么改宿主全局状态、要么要凭据、
     要么在本平台没有意义，黑名单漏一个就是一条没人守的路。**只认 `npm` 与 `npx` 两个入口**
     （用户口径 2026-10-09：「不用加 sh 啊」）：本仓没有 shell，任意命令走脚本的 `auto.shell` 桥面，
     那是另一个面；假装支持 `sh -c` 只会让「看起来能跑、实际没人守」的输入进来。
   - **分词按空白切，不做 shell 引号解析**（`parse` 的 KDoc 写死了这条）：本仓没有 shell，假装支持引号
     会让 `npm install "a b"` 产生「看起来对、实际是另一个包名」的结果 —— 静默错比报错难查得多。
     同理 `packageSpecsIn` 只处理裸包名，**旗标带值的形态（`--registry <url>`）会被误读成包名**；
     这条边界如实写在契约里，且**只影响装前的多镜像交叉校验**（结论是「未校验」不是「不一致」，不误拦）——
     **真 argv 一个字不改地透传**给 npm，装的东西与手敲完全一致。
   - **输出粒度 = 事件流 + npm 输出尾部**（用户 2026-10-09 裁定，否掉「新开真流式 stdout 事件」那案）：
     `InstallEvent` → `NpmConsoleLine` 的投影在宿主侧一处发生（`InstallCoordinator.projectConsoleLine`），
     四类 `NpmConsoleLineKind`（`ECHO`/`PHASE`/`OUTPUT`/`WARNING`/`RESULT`）；npm 自己的输出取
     `HeavyOpOutcome.outputTail` 作 `OUTPUT` 行。**不新开桥面事件** —— 脚本侧的事件契约
     （`bridge/js` 的 `onProgress` 等）不能因为宿主多了一个界面而改形状。
     `NpmConsoleLine.ok: Boolean` 是终态行的**成败位**：失败标红由它决定，呈现层**不按文本猜**
     （`startsWith("失败：")` 那种写法会在宿主改措辞时静默失效）。
   - **依赖提供的命令照实接线**（用户 2026-10-09 裁定「照实接线」）：`npm run <script>` 与 `npx <bin>`
     走 §10.3 T1 的 `runScript`/`exec` 门禁。**未获批** → `ERR_PERMISSION_DENIED` 且审批请求已入队
     （回执话术指向**真的那一页**：管理面板 → 依赖管理的审批卡）；**获批但 T1 spawn 桥未接** →
     `ERR_NOT_IMPLEMENTED`。两条都如实，且都在 ECHO 行之后抛出 —— 用户看得见自己敲的那行。
     **审批 ≠ 执行**：门禁只入队不排队命令，批完要**重敲那一行**（这条写进了 `runScriptOps` 的 KDoc
     与界面回执，别让用户以为批完就会自己跑）。
   - **门禁强度不取决于入口**：控制台敲 `npm install axios` 与依赖面板装同一个包，走的是**同一套**
     装前多镜像交叉校验（§10.5-1）、磁盘预检/配额/项目锁/全局安装会话。入史那一栏只记**子命令**
     而不是完整 argv —— 审计表要长期留存，而用户手敲的 argv 可能夹着凭据形态的参数
     （`--//registry/:_authToken=…`、`--otp`），存原文等于把用户手滑敲进来的东西永久写进盘。
   - **读口不经桥**：`HostSummary.runNpmCommand(projectId, line)` / `consoleOutput(projectId, sinceSeq, maxLines)`
     —— 控制台是**宿主自己的界面**，桥面是脚本侧的面，故 §7.3 的方法表**一条不加**。
     输出走 `InstallCoordinator` 的 `SeqRing<NpmConsoleLine>`（有界 512、DROP_OLDEST、单调 seq、
     `drain(projectId, sinceSeq, batch)` 非破坏拉取），空洞判据（`first > sinceSeq + 1`）由**呈现层**
     用发请求前的游标判 —— 与 `ConsoleSnapshot` 把 `droppedTotal`/`pageFull` 原样带上、由呈现层下结论同一条分工。
     `NpmConsoleSnapshot.running` 的判据是**句柄账**（不是「有没有新行」）：正在排队的重操作也算在跑。
   - **真流式 stdout 已落（2026-10-10 批 90）**：执行体**边读边报**（`HostNodeExecutor`
     专门的读流线程），经 `OutputSink` 缝（`:app-service:npm` `InstallSeams.kt`）逐行落控制台环。
     与 `ProgressSink` **刻意分开**：那条装的是 `InstallEvent`（**脚本侧的契约形状**，
     `bridge/js` 的 `onProgress` 逐字对齐），这条装 npm 自己吐的原文 —— 把后者塞进事件契约
     等于为一个宿主界面去改脚本侧的面。与 `HeavyOpOutcome.outputTail` 的分工是**过程面 vs
     摘要面**：尾部是"跑完之后"的有界截断（谁都要得到，进审计与终态行），流是"跑的当中"
     逐行报（尽力而为，拿不到也不该让安装失败）。**流真报过时不再补 `outputTail` 行**
     （否则同一段文本显示两遍，用户以为 npm 跑了两回）；空行**不报**（真实 npm 输出以空行
     结尾，`takeLast(8000)` 取到的正是那些空行）。
   - **命令历史持久化已落（2026-10-10 批 90）**：`ConsoleHistory`（`files/.autojs/console-history.jsonl`）
     + `PackageManagerFacade.consoleHistory`/`recordConsoleHistory` → `HostSummary` 同两口 →
     `:ui` 输入行上方的 `HistoryRow`（横向滚动，点一下**填进输入框而不直接执行** ——
     历史里那条多半要改一改再跑）。口径见上面那一段与 `design-decisions.md` 第 60 项。
   - **输出"活着"已落（2026-10-10 批 90）**：`:ui` 新增 `LivePoll.pollWhile`，提交之后跟到
     跑完、子页在前台时一直跟 —— 原先每个读口都是"进页面 / 手动刷新时取一次"，而宿主是
     入队即返回的，两者合起来就是"敲完什么都不动"。**刻意不用**宿主的 `progress` SharedFlow：
     它没有重放，晚到的订阅者永久丢那一批，而"切进来时命令已经跑了一半"正是常态。
   - **未落**：shell 引号解析、`npm config`/`publish` 一类子命令（白名单外）、T1 spawn 桥本体
     （`scriptExecutor` 仍缺，§11.2 T2）、命令的**取消**（`NpmConsoleHandle` 与 `InstallHandle`
     刻意分开，故没有 `cancel()`）、`HistoryRow` 的**上下键翻历史**（本批只做了点选 ——
     触屏上没有"上下键"，真要做的是长按/滑动，那是另一件事）。
4. **离线包导入**：SAF 选择（tarball / lock+cacache bundle / 快照 node_modules.zip）→ 验签 → 队列安装；另提供「从内置精选缓存离线装 axios/dayjs/…」。`node_modules.zip` 导入**仅限高信任项目**，签名锚定 `HMAC(应用密钥, lock.sig + zip.sha256)`；市场脚本一律拒绝该格式（走 reify 产出 integrity）。
   **体积上限（2026-10-06，backlog B12）**：cacache bundle 解包前先卡**整包** 512 MiB（判据是文件系统上的字节数，不是 zip 声明的数），解包中逐条目卡 64 MiB —— 单条目读到顶即停、不入缓存。上限**整包拒收**（不截断、不返回部分结果）并回 `ERR_INVALID_PARAM`（「选错了文件」是可诊断的参数问题，不是 `ERR_FILE_NOT_FOUND`）。理由：这条路径收的是用户从 SAF 递进来的外部文件，**用户可能只是选错了**（视频、系统镜像、整个 Downloads 打成的一个包），而解包器在读到顶之前没有任何自然的停止点。
5. **包大小管理页**：per-project `node_modules` + `npm-cache` 尺寸（Kotlin 遍历）+ 配额条（80%黄/100%拦）→ 一键 prune/dedupe/ci 重装/cache clean；明确标注 node_modules 计入系统「App 数据」。
   **落地现状（2026-10-09 批 81 尺寸条 + 批 85 措辞订正 + 批 86 动作半边）**：尺寸/配额条与判据已落
   （`NpmScreen` 的 `QuotaCard`，读 `storage()` + `InstallConfig` 的 512MB/80%，呈现层不写死）。
   ~~**动作半边未落** —— 今天配额满时那句提示把用户指去**控制台**敲 `npm prune`（那条路是真的通的，
   见第 3 条），而不是假装有一颗「一键清理」的按钮。~~
   **动作半边已落（2026-10-09 批 86）**：依赖管理页配额条下面多一行 `MaintenanceCard`，四颗按钮
   —— `清理多余包`(prune) / `依赖去重`(dedupe) / `按 lock 重装`(ci) / `回收缓存`(reclaimCache)。
   三条口径写死在这里，免得被"统一"掉：
   - **前三颗走 `NpmMaintenanceAction` 枚举**（`PRUNE`/`DEDUPE`/`CI`）→ `HostSummary.runNpmMaintenance`
     → `facade.prune/dedupe/ci`：它们形状相同（都是一次安装会话、都返回 `InstallHandle`、都要
     per-project 互斥锁与磁盘/配额预检），界面只需要知道"哪一颗在跑"。`CI` 走的是 `facade.ci(offline = true)`
     —— **`lockSigner.verifyOrThrow` 照旧先跑**，按钮不绕过任何门禁。
   - **`回收缓存` 不在那个枚举里**：它返回的是一份读数而不是句柄、也不占安装会话，塞进去会让
     「跑一次 npm 会话」与「删几个缓存文件」在界面上共用一套进度语义（时长差两个量级）。
     它走自己的 `HostSummary.reclaimNpmCache()` + 自己的 `reclaimingCache` 忙碌位。
   - **回执不替用户宣布结果**：三颗按钮是**入队**（几十秒量级），回执说"已入队…完成后清单会更新"；
     回收是**一次算出来的读数**，回执带删/留两侧数字（用户按下去就是为了看那个数字变没变）。
   **失败原文原样透传**（`ci` 的验签拒绝那句里已经写清了为什么拒，界面再译一遍就是第二份判据）。
   **`npm-cache` 那一栏已落（2026-10-09 批 87）**：~~`QuotaCard` 今天只画 `node_modules`~~
   —— 现由 `QuotaCard` 一并画出（`cacheStorage()` 读口，只算 `content-v2`；与 node_modules
   那行**各自独立判空** —— 量不到项目尺寸不该把缓存那行也吞掉，反之亦然）。
   回收回执里的删/留数字**仍**取自 `NpmCacheReclaimReport` 本身，不取自配额条：两者量的是同一棵树，
   但一个是「这一刻有多大」、一个是「这次删了多少」，拿后者去对前者只会让人以为对不上。
6. **首启引导**：原子部署 assets/npm CLI + 播种精选缓存 → registry ping 探测 → 选镜像（**默认官方 npmjs**，§18 第 7 项；镜像是加速选项不是开箱前提）与配置代理（能力中心网络项）。
7. **打包向导联动**：node_modules 默认入 APK + `.autojs.build.ignore` 排除规则 + 「完全离线变体」（宿主预装 node_modules.zip）+ 项目 lock 签名生成。
8. **镜像源管理**（2026-10-09 批 83）：管理面板 → 镜像源管理（`RegistryScreen`），编的是 **npm registry 全局缺省**，**粒度全局一份**（用户 2026-10-09 裁定；不做「全局缺省 + 项目覆盖」的编辑面 —— 项目那层仍可手编 `.npmrc`，界面不代管）。
   - **归属 `:app-service:npm`**：它本来就在管这件事（`NpmConfigKey.REGISTRY`、项目 `.npmrc`、`InstallHistory.Op.REGISTRY` 审计键、交叉校验首选），搬去别处就是第二份 registry 判据。
   - **判据唯一一份住 `:domain`**（`NpmRegistryKeys`，与批 82 `ScriptEnvKeys` 同形）：`canonicalize`（规整化）与 `reject`（拒收原文）同时被 `NpmRegistryVerifier` 的缝边界与 `:ui` 的输入校验调用 —— 界面另判一遍必然漂移，漂移方向最坏（界面放行的串在写入侧被拒）。`NpmRegistryVerifier.OFFICIAL`/`MIRROR` 改为指向它的别名，URL 字面量从此只有一份。
   - **写入侧存原样（只 trim）不规整化**：规整化会丢 query，自建网关用 `?token=…` 的凭据会被静默剥掉 —— 表现为「保存成功」之后永久 401。
   - **校验不过抛原文**、`null`/空白 = 删键（恢复出厂，不是写一个空值行 —— 后者让 npm 拿到空 registry 而每次安装都失败）；变更入 `InstallHistory.Op.REGISTRY`，审计行 `projectId` 传空串（全局变更没有项目；改行格式会动审计契约）。
   - **未落**：首启引导的 ping 探测与镜像候选表（第 6 条）、`proxy`/`cache-retention` 两个 `NpmConfigKey`（桥面本来就没有入口）。
   - ~~审计页（`InstallHistory` 仍无 UI 消费方）~~ **已落地（2026-10-09 批 85）**：见第 2 条末段。

### 10.10 与既有机制的关系

- 桥/进程/看门狗/quiesce/原子部署/来源分级全部复用既有框架；npm 不建平行体系。
- `assets/npm` 原子部署复用 script-repo 的 tmp+sha256+rename；安装会话复用 EnginePool acquire/quiesce 四步与 TSF 双队列。
- 运行中脚本的 node_modules 被重装/删包 → 懒加载 ENOENT；per-project 互斥锁 + 默认「脚本结束后安装」+ 强制时显式风险确认。

### 10.11 优先级落定（npm 相关增补到 §14）

- **P0**：vendored npm CLI + 专用安装会话进程；零 spawn 主路径（install/ci/ls/uninstall/prune/dedupe）；T0 拦截 shim 硬失败；精选缓存种子 + 离线首装 + `--prefer-offline`；镜像/代理三路径 + replace-registry-host；事务化安装 + journal 自愈；磁盘/配额预检；hasInstallScript 前置告警 + 审批卡 UI（仅请求）；lock v3 + `npm ci` 强制 + 带外信任锚 + 多镜像交叉校验；
  依赖面板 + `auto.npm` 核心 API；打包向导 node_modules 入包。
- **P1**：spawn 桥完整 polyfill（stdio 假管道 + pgrp 杀树 + detached 拒绝）+ **lifecycle 脚本真实执行**（§18 第 7 项 2026-09-26 口径：安装时让用户自己选跑不跑，不设出厂卡口，也**不是**"审批通过才跑"的流）+ `npm run/exec`（纯 JS bin 白名单）；node-shim PIE + PATH 注入（2–3 台 ROM 红测）；~~npm 终端视图~~ **已落地（2026-10-09 批 84；2026-10-10 批 90 补齐"活着"三件）**：控制台改做命令面，白名单子命令 + `npm run`/`npx` 照实接线到 T1 门禁；输出粒度 = **真流式 stdout（批 90 已落）** + npm 输出尾部（摘要面，两条并存且流报过就不补尾部）+ 事件流；批 90 同批补上常驻进度推送与命令历史落盘 —— 见 §10.9 第 3 条；
  在线 audit + audit signatures + OSV 离线；`offlineGap` + 种子金标准测试。（原「QuickJS 白名单库独立 vendored」随第 1 项裁掉。）
- **P2**：离线 bundle 打包器（desktop `npm ci` 物化 + cacache 复制体交付）+ 增量更新 + 导入 UX；「完全离线变体」打磨；native 依赖 **wasm 方案**（2026-09 拍板）：
  优先取上游 wasm 构建（`esbuild-wasm`、`argon2-wasm`、sql.js 等——Node 内置 `WebAssembly`，无 ABI/无 dlopen、一份全平台、随 bundle 离线送达），无 wasm 产物的回落纯 JS 替代/
  内置（sharp→jimp 或平台图像桥、bcrypt→bcryptjs、better-sqlite3→`node:sqlite`——Node 24 官方标 **STABILITY 1.2 Release-candidate**，随 libnode 钉版即锁 API，保守备选 sql.js-wasm）；
  安装期检测 `binding.gyp`/平台 optionalDeps 点名引导，不静默半装。**对标 AutoX-v7（研究笔记，2026-09）**：它**不需要**这条管线 —— 运行时是 Javet（`com.caoccao.javet:javet-node-android:5.0.2`，
  进程内 `NodeRuntime`）而非真 libnode，全仓零 node-gyp/prebuild/`NODE_MODULE_VERSION`/`.node` dlopen 痕迹；模块解析是自研 `NodeModuleResolver`（`createRequire` + package.json 走查 + ESM），
  常用包以**已物化的纯 JS 树**预置在 `assets/modules/npm`（buffer/stream/process/events + lodash/cheerio/bluebird/rxjs），原生能力全走 Java↔V8 绑定（`NativeApiManager` → `Autox.*`），
  paddle OCR 等 `.so` 只经 Java `System.loadLibrary`、与 JS 引擎无关。即：没有 N-API 加载面就没有 `.node` 交付问题。本仓 §7 桥本体就是 N-API addon（真 libnode 不能换），
  故 native 依赖的策略已定为 **wasm 优先、纯 JS 兜底**——他们 assets 全纯 JS 是兜底可行的实证；**prebuild `.node` 小工具已移出排期**（需要时再立需求），ABI/
  `--dest-os` 断言届时随需求一起复活。
- **P3**：跨项目共享 store 去重（pnpm 式，须 store↔lock 加签映射）；程序化安装服务化；ECDSA 签名强制；vendored npm 自动升级（仅通过零 spawn 金标准闸门）；esbuild 类**代码签名原生 exec** 独立通道。

### 10.12 npm 特有风险与缓解

| 风险 | 缓解 |
|---|---|
| `--ignore-scripts` 的「假装成功」（postinstall 下载二进制/自检、真原生包装上才炸） | packument `hasInstallScript` 前置扫描 + 显式 warning + 人工审批升级通道，**禁止静默** |
| 第三方 `.node` V8 ABI 稀缺且难匹配（Node24 `NODE_MODULE_VERSION`=137 与 libnode 快照不一致则 dlopen 崩）；`process.platform` 非 android 会让平台探测失真 | 当前策略**不引入第三方 `.node`**（wasm 优先、纯 JS 兜底；prebuild 工具已移出排期，需要时再立）；自建 libnode 必须 `--dest-os=android` + CI `process.platform/arch` 断言 + 16KB 双门禁（本仓构建线照旧） |
| vendored npm 12 要求 Node≥24.15，降级 npm11 会恢复「脚本默认执行」使护栏静默消失 | `:node-runtime-build` 钉版本下限。**该落差已发生（2026-10-01）**：素材取自 Node 24.21.0 的 `deps/npm` = **npm 11.19.0**，npm 12 的 `allowScripts=none` 默认语义**不在位**。三条补偿：① **主控与版本无关** —— `HostNodeExecutor` 对每条命令硬编码 `--ignore-scripts`（§11.1 T1 的零 spawn 主路径），"脚本默认执行"这条恢复不了它；② 版本钉死 + 断言 —— `NPM_CLI_VERSION` 与素材树 `package.json` 逐字比对，漂移即 `fetch-and-build.sh` §9 当场红（`npm install` 的 lifecycle 面不会静默换版）；③ 落差登记在 backlog（升级 = 换素材来源；「等 Node 线携带」已实测否掉 —— `nodejs.org/dist/index.json` 的 868 条发布里没有一条带 npm 12.x；改 `NPM_CLI_VERSION` 即触发全链回归）。**残余**：①只是"不跑脚本"，npm 11 与 12 在**非脚本** spawn 路径上的差异没有第二条兜底 —— §10.12 末行的 child_process 拦截 shim 仍未落（P0 未排）。~~**该落差已发生（2026-10-01）**~~ **已消解（2026-10-02 A6 落地）**：素材换 registry `npm@12.2.0`，npm 12 官方默认（依赖 lifecycle 拒 + allow-git/remote=none，§10.1 实测注）回到在位；①的主控角色不变、②③照旧。~~残余（child_process shim 未落）不变~~ **残余已收口（2026-10-09）**：该 shim 已落地，见 §10.3「已落」段 |
| 设备端 100 依赖安装 15–60s（eMMC/f2fs 更差），非「秒级」 | 独立会话 + 分级超时 + FGS + 熄屏仅物化；进度如实展示 |
| 锁 TOFU；缓存条目与 lock 版本绑定（更新依赖后旧 tarball EINTEGRITY） | 带外信任锚 + 多镜像交叉校验 + 设备端锁降信任标记；提示联网/升级包 |
| **零 spawn 不变量漂移**（npm 升级引入新 spawn 路径，allowScripts 拦不住非脚本 spawn） | 安装会话**强制注入 child_process 拦截 shim**（非批准 spawn 硬失败 ERR_NPM_SPAWN_BLOCKED）；桌面 CI 金标准：child_process 替换为 throw 的 harness 里跑全命令矩阵必须全绿；vendored npm 升级只准通过此闸。**已落地（2026-10-09）**：见 §10.3「已落」段（`NpmSpawnGate` + `NpmSpawnGateMatrixTest`，后者已进 `check-e2e-ran.sh`） |
| 离线 bundle 与 lock 闭包不匹配（盯顶层包，锁含的传递依赖不在种子内 → ENOTCACHED） | `offlineGap` 返回缺失清单（名+尺寸）；导入先按当前 lock 校验；「仅凭种子 npm ci --offline」金标准 |
| 审批/ledger 被已批准脚本改写（自批+改 registry） | ledger 迁 App 私有只读目录 + 条目绑定版本+脚本哈希 + post-check 防篡改比对 + .npmrc 变更经 :main 卡控审计 |
| 数据被清（clear data/卸载重装）导致依赖与审批记录蒸发 | npm-cache/seed → cacheDir（可重建）；node_modules/ledger/lock → filesDir；导出/导入 SAF 快照；清后强制重审批并明示 |
| esbuild 类原生 bin「install 成功、build 一律 W^X 拒绝」的过度承诺 | 审批卡标注是否需要原生 exec；run/exec 首版纯 JS bin 白名单（eslint/prettier/esbuild-wasm）并钱袋承诺，原生 exec 留代码签名通道 |

---

