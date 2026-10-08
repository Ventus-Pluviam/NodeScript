## 11. 安全模型（来源分级）

| 来源 | 信任级别 | 引擎 | 能力 | 说明 |
|---|---|---|---|---|
| 内置/作者签名模板 | 高 | Node | 全量（含 root） | 软签名验证 |
| 用户自写脚本 | 中 | Node | 全量 | 提示风险 |
| 第三方/市场脚本 | **低** | **Node（每脚本独立进程、同 UID 同权）** | **全量** —— **沙箱已裁，进程分开不构成权限隔离**（§18 第 1 项 2026-09-26） | 防线改为：**安装时按 `hasInstallScript` 如实告知并由用户当场选**（§18 第 7 项）+ 来源分级提示（本表）+ 运行期 TTL/看门狗；**文案不得让人误以为是沙箱跑的** |
| 打包分发脚本 | 中高 | Node | 打包时配置 | 走签名链 |

- **已落地**：桥连接在 UID 门禁之后经宿主一次性凭据绑定执行身份（§7.5），日志按真实 engineRunId 归属，心跳只允许替自身打点。
- **已落地（2026-10-08，A5 第一阶段）**：按执行级 `CapabilityMask` 过滤 handler —— 桥路由在
  进 handler 之前先做 deny-by-default 判据（`BridgeCapabilityCatalog`），未覆盖即
  `ERR_PERMISSION_DENIED`，不进请求账、不留探测面。跨脚本控制/查询另经
  `CrossScriptAuthorizer`：触达他人执行要对应跨脚本位**且**调用方掩码覆盖目标掩码
  （低信任不得停高信任），`engines.exec` 派生不得提权，命名通道按发起执行私有。
  缺省（无来源元数据）取**保守档 A**：全量减跨脚本位与 `SCHEDULER_WRITE` —— 这是
  §11 矩阵之外新增的一档，不是把「第三方」改成窄掩码，理由见
  [`design-decisions.md`](../design-decisions.md) 第 43 项。
- **来源分级：经裁定不做**（2026-10-08，[`design-decisions.md`](../design-decisions.md) 第 45 项）。
  `TrustTier` 四档与 `TrustTierResolver` 注入缝保留，但**不接真来源元数据**，只作记账词汇；生产唯一可达档仍是 `UNKNOWN`（保守档 A）。**为什么不做**：同 UID 同权（§11.3 第 1 条）下，来源分级不产生防护 —— 掩码收窄只约束桥面，脚本绕开桥直接读写宿主私有目录即可达到同样效果，所以它挡住的是老实脚本而不是恶意脚本。它原本能买的「审计归因」已由 A10 的 `engineRunId → projectId` 覆盖。
  另：掩码**只约束桥面命名空间**，不限制 Node 内建 `fs`/`http`，**不是沙箱**（见下 §11.3 第 1 条）。

---

### 11.1 资产与信任边界

先说三条不可从别处推出来的事实，后面所有防线都挂在它们上：

| # | 事实 | 出处 |
|---|---|---|
| 1 | **权限隔离这条防线不存在**。引擎只有 Node 一条轨，每脚本一个进程，但第三方脚本与用户自写脚本**同 UID、同权**（无障碍读屏/点击/截屏/网络/文件全开）。原先「第三方 → QuickJS 白名单子集」的隔离随 QuickJS 整轨撤出而作废 | §18 第 1 项（[18-19-ledger.md](18-19-ledger.md)）+ [design-decisions.md](../design-decisions.md) 已拍板第 1 项 |
| 2 | **因此能力授予是唯一边界，不是进程边界**。给用户的提示必须直说「装来的脚本与自写脚本同权」，不能让文案读起来像沙箱 | 同上 |
| 3 | **桥面身份与执行级能力掩码已接；来源分级经裁定不做**：一次性凭据绑定 engineRunId；`CapabilityMask` 按执行过滤桥面（缺省保守档 A）。**「内置/自写/第三方/打包」四档不再接入真来源元数据**（2026-10-08 裁定，见 [`design-decisions.md`](../design-decisions.md) 第 45 项）—— 理由是它**不产生防护**：同 UID 同权（上表第 1 条）意味着脚本可绕开桥直接读写宿主私有目录与文件，任何只写在掩码上的「来源限制」都挡不住恶意脚本，只挡得住老实脚本。四档枚举与 `TrustTierResolver` 注入缝**保留**，但只作**记账词汇**（日志/审计里说明「这次执行有没有来源证据」），不接元数据、不产生授权差异 | 本节上方三条 bullets + §7 + [`design-decisions.md`](../design-decisions.md) 第 43/45 项 |

**要守的资产**（按被攻破后的代价排序）：① 设备上的账号与凭据（脚本可读 `filesDir` 与已授权能力所见的一切）；② 宿主 App 自身的完整性（脚本能改自己的项目文件，从而改下一次启动时跑什么）；③ 已授予的自动化能力（点击、输入、截屏可被脚本用于对外操作）；④ 用户注意力（脚本可弹窗/发通知/驱动界面）。

**信任边界只有三条，其余都是边界内的**：脚本 ↔ 桥（跨进程异步，按 UID + 一次性执行凭据认证 + 执行级 `CapabilityMask` 过滤桥面 —— 但**同 UID 同权**这条事实不变，掩码不是沙箱）；脚本 ↔ npm 供应链（lock/registry/安装脚本）；宿主 ↔ 用户（权限授予与安装选择）。

### 11.2 威胁与防线对照

下表左列是本设计**认为会发生**的攻击面，右列是**当前实际生效**的防线与其落点。凡落点为空的行，见 §11.3 残余风险，不假装已覆盖。

| # | 威胁（攻击者能力） | 攻击面 | 现行防线（落点） |
|---|---|---|---|
| T1 | **恶意/被污染的包在安装时执行任意代码**（lifecycle 脚本） | npm 安装 | 零 spawn 主路径：T0 全程 `--ignore-scripts`，安装脚本一个都没跑过，回执 `scripts-skipped`（`InstallCoordinator`，§10.5-3）。**残余**：让脚本真跑的那条路（spawn 桥）尚未落地，见 §11.3 |
| T2 | **lockfile 投毒**（把包名指到别处，integrity 仍成立） | `npm ci` 重建 | `LockSigner` 对 lock 做 HMAC-SHA256 带外签名（`files/.autojs/lock.sig`，键绑 `projectId` 防跨项目搬锁），`ci` 前验签；缺签名/错签名一律 `ERR_PERMISSION_DENIED`（TOFU 自签不算通过）。~~**接线现状（2026-10-01 核实）**：防线代码与单测都在，但**生产装配没接** —— `NpmShellKit.assembleHandler` 的 `lockKey` 缺省 `null` → 既不签也不验，全仓无 `KeyProvider` 实现。~~ **已接线（2026-10-08，批 79）**：`AppShellApplication` 递 `LockKeyStore.AndroidKeystore`（`AppShellKit` 经 `LockKeyStore.resolve` 做 get-or-create），`ci` 先验签、`install` 收尾重签、快照导出带 `snapshot.sig`。取钥失败**不外抛**：本次不装该防线、原因原文进 `AssembledShell.npmLockKeyFailure`（不吞）。见 §11.3 第 3/8 条 |
| T3 | **单一镜像/注册表投毒** | registry 响应 | `NpmRegistryVerifier` 双运营主体交叉校验（第二意见必须与首选**不同运营主体**，同站即自比、自比一律拒）；取不到/非 https/无 integrity 锚点一律 `Verdict.Unverifiable` 由调用方显式告知，不折成「通过」 |
| T4 | **冒名客户端抢绑桥 socket** | abstract unix socket | 桥监听与客户端 `main.cpp` 对称验 peer uid（`SO_PEERCRED`），凭据读不到即 fail-closed 拒收（`BridgeSocketListener`）；abstract 名带 uid 后缀，多实例互不抢绑。UID 通过后还须一次性 256-bit token 的 hello/ACK，绑定 engineRunId；双方 PID 可得时额外匹配。无票、重放、过期、已退出或撤销拒收；**同 UID 窃取其他执行凭据仍不在保证内** |
| T5 | **未授权能力被调用**（脚本绕过能力授予） | 桥面 | **执行级 `CapabilityMask` 已在桥路由做 deny-by-default 过滤**（2026-10-08，A5 第一阶段；缺省保守档 A）；设备能力门禁仍独立：`PermissionCenter` 是唯一权限入口，读取异常诚实降级 `DEGRADED`（可用性未知即受限），绝不伪造 `GRANTED`。**残余**：来源分级经裁定不做（第 45 项），且掩码不拦 Node 内建 `fs`/`http` |
| T6 | **脚本失控/僵死/赖活**（死循环、OOM、宿主与引擎状态分裂） | 引擎进程 | `EngineWatchdog` 按心跳/RSS/CPU 采样 + drift 连段裁决（`KillCause.DRIFT`）+ 执行期限（`KillCause.TIMEOUT`）；心跳 payload.runId 必须匹配认证连接，不能替其他执行刷新；看门狗只问不记账 |
| T7 | **安装期互踩/打爆磁盘/恶意 git 依赖/无限挂起** | 安装会话 | per-project 锁 + 全局互斥、free ≥500MB 预检、项目 512MB 配额（80% 黄 / 100% 拦）、`git:` 依赖入口即拒 `ERR_NOT_SUPPORTED`、安装 TTL（`InstallCoordinator`） |
| T8 | **审批票重放**（拿旧版本 APPROVED 装新版本） | 审批账本 | 审批键绑 `pkg + versionHash`，宿主从盘上重算版本哈希，命中 `APPROVED` 才放行（`ApprovalLedger` + `requestApprove`/`requestGateApproval`）；未获批自请入队 `PENDING` |
| T9 | **本地状态被系统备份/换机迁移带到另一个设备上下文**（Auto Backup、D2D 迁移、`adb backup`） | 应用私有目录 `files/.autojs/` | `AndroidManifest` 置 `android:allowBackup="false"`（`:app`）：信任锚与审计面不进备份 —— `lock.sig` 到新机验不过（密钥不出本机），而审批台账/安装 history/意图日志会以「已完成/已批准」的姿态跟着走，恢复出来的状态不可信也不可解释。代价如实说：换机不自动带走过往审批与历史，需重装/重批 |

### 11.3 残余风险（诚实缺口，未被上面任何一条覆盖）

1. **同 UID 同权，非沙箱** —— 见 §11.1 第 1 条。每脚本独立进程并不提供权限隔离，
   这是 §18 第 1 项不引入沙箱的直接后果。执行凭据只建立连接归属：同 UID 代码可能读取进程环境、
   访问内存或操纵其他进程；清 token 环境变量不构成抗冒用保证。**执行级 `CapabilityMask` 与跨脚本控制
   授权已于 2026-10-08 落地**（桥路由 deny-by-default 过滤 + `CrossScriptAuthorizer`，见 §11.1），
   但它**不改变这一条**：掩码只约束**桥面命名空间**，不拦 Node 内建 `fs`/`http`，同 UID 代码仍可
   绕开桥直连进程与文件。**来源分级经裁定不做**（第 45 项）：它不改变这一条，也不产生防护 —— 见 §11.1 事实 3。
2. **安装脚本「一个都没真跑过」**：T0 全程 `--ignore-scripts`，回执 `scripts-skipped`。等 spawn 桥（P1）落地后，「用户选择跑」这条才有落点；**在那之前，安装脚本永不执行**（§18 第 7 项 + §10.5-3）。
3. **`lock.sig` 是本地信任锚，不是第三方可验证**：签名用应用私钥，只能证明「这份 lock 是本机签过的」，不构成跨设备/跨用户的可验证来源证明。应用私钥丢失 = 显式「安全降级」失败（`LockSigner` KDoc 口径），不静默放行。**密钥从哪来：已落地（2026-10-08，批 79）** —— 接缝是 `LockSigner.KeyProvider`（2026-10-01 起形状为 `secretKey(): SecretKey`：给句柄而非字节，Keystore 密钥材料不出库也接得上；实现落点已拍板住 `:app` 装配层），实现是 `LockKeyStore.AndroidKeystore`（`AndroidKeyStore` 提供者，`HmacSHA256` / 256 位 / `PURPOSE_SIGN or PURPOSE_VERIFY` / `setUserAuthenticationRequired(false)`，别名 `autoscript.lock.hmac.v1`）。**取钥判定 = get-or-create 且「取不动」绝不静默重建**：只有「别名下没有这把钥匙」才新建，锁被换过/库失效/Keystore 整体不可用一律显式失败（重建会把「这份 lock 曾被换过」洗掉，或让所有项目已有签名一起验不过）。**仍不改变本条的性质**：它是本地锚，不是第三方可验证的来源证明。
4. ~~**MediaProjection 高清会话未落**：授权 UI + FGS 那一档还没接，P0 由同一 a11y 帧源连续截图承接（§9.2）。这不是安全缺口，是能力边界，列此只为避免被当成「高清会话已有门禁」。~~ **已落地（2026-10-08，批 75）**：授权 UI（`AndroidScreenConsentBroker` + `ScreenConsentRequests`）与 `foregroundServiceType="mediaProjection"` 的前台服务（`ProjectionForegroundService`）都已接，`MediaProjectionSource` 经 `PlatformWiring.screenHandler` 进 `screen` 命名空间。~~**仍缺的是录屏**（`MediaRecorder` 全仓零引用）~~ **录屏亦已落地（2026-10-08，批 77）**：`MediaProjectionRecorder` 与截屏腿并列、共用同一条会话账（同意 + FGS 同一条），输出汇是 `MediaRecorder` → 视频文件。
5. **16KB 页机不测（2026-10-06 拍板），SELinux enforcing 上下文与 targetSdk 提取策略仍待真机**：16KB 的装载风险由构建期机械门禁承接（§16：`LOAD align >= 0x4000` **且** `p_offset ≡ p_vaddr (mod align)`，三个产物 + `libc++_shared.so` 逐件在 CI 里断言，且该门禁被负向证伪过）—— **已知不测的残余面是「内核真按 16KB 基页映射时的装载行为」**，口径与理由见 `design-decisions.md` 第 34 项。后两项（SELinux enforcing、targetSdk 提取策略）同样只在特定设备上测得到，仍待真机（design-status「仍未验」块）。
6. **审批卡呈现层未排期**：审批账本与桥面拉取口已通（`drainApprovals` → `NpmBridgeHandler` → JS `pumpApprovals`），但能力中心的审批卡不在当前排期内，期间审批只能靠脚本侧拉取。
7. **无上报时限承诺**：私密上报渠道已于 2026-10-01 开通（GitHub Security → Report a vulnerability，见根 [`SECURITY.md`](../../SECURITY.md)）—— 缺的从此不是渠道，而是**响应 / 修复时限**：单人维护的开发期项目不作承诺。（原条目「上报流程缺失」同日改写。）
8. **npm 生产装配：执行体 / 签名 / 脚本门禁均已接线，只剩 T1 执行面（2026-10-01 起分档；标题于 2026-10-09 批 80 订正 —— 原写「签名与脚本门禁仍未落」，批 79 接了签名、批 80 接了门禁）** —— `AppShellKit` 不再走全缺省：
   - **`executor` 已接线**：素材（`assets/npm/**`，vendored npm CLI）启动期幂等落位 `files/npm/`，
     注入 `HostNodeExecutor`（宿主 = `nativeLibraryDir/libnoden.so`）；~~**两条同时成立才注入**
     （落位就位 + 有宿主）~~ **三条同时成立才注入（落位就位 + 有宿主 + `child_process` 拦截 shim
     落位，2026-10-09 批 80 —— shim 是 §10.11 P0 承诺面，落不上就不注入）**，否则保持
     `HeavyOpExecutor.Unavailable` 并对 npm.* 如实回
     `ERR_NOT_IMPLEMENTED`，原因原文进 `AssembledShell.npmCliFailure`（不吞）。即 T1/T7 的
     安装路径**有执行体了**，但仍**依赖素材随包**：本机自建、没跑过 Node 构建线的 APK
     就是「无素材」那一档（警告 + 空产出，装配照过）。
   - ~~**`lockKey` 仍 `null`**（T2 的签/验与快照导出都不发生；全仓无 `KeyProvider` 实现，
     接缝形状 2026-10-01 已就位 —— 见第 3 条）。~~ **已接线（2026-10-08，批 79）**：
     `AppShellApplication` → `AppShellKit(npmLockKeys = LockKeyStore.AndroidKeystore)` →
     `NpmShellKit.assembleHandler(lockKey = LockKeyStore.resolve(...))`。装配期**就取一次钥匙**
     （不把「Keystore 坏没坏」推到用户第一次 `npm ci` 才炸 —— 那时看到的是验签失败，
     分不清是 lock 被换了还是钥匙取不动）；取不到则本次不装该防线，原因原文进
     `AssembledShell.npmLockKeyFailure`，**不掀翻装配**（npm 只是能力之一）。
     接线后行为：`ci` 先验签（无签名/格式不识/不符/跨项目搬运一律 `ERR_PERMISSION_DENIED`）、
     `install` 收尾重签（失败即中止本次安装并如实报错）、`exportSnapshot` 带 `snapshot.sig`。
   - **`scriptExecutor` 仍 `Unavailable`**（T1 门禁过了也跑不起来，spawn 桥属 P1）。
   - **`child_process` 拦截 shim 已接线（2026-10-09）**：`NpmSpawnGate` 把
     `npm-spawn-gate.cjs`（classpath 资源）落到 `files/.autojs/`，`HostNodeExecutor` 经
     `NODE_OPTIONS=--require=<它>` 注入安装会话进程，`child_process` 七个入口一律抛错；
     被拦时按 shim 播报折成 `ERR_NPM_SPAWN_BLOCKED` / `ERR_PERMISSION_DENIED`（`detached:true`）/
     `ERR_NOT_IMPLEMENTED`（`fork`）。**落位失败 → 不注入安装执行体**（P0 承诺面，
     不许静默降级成「装是能装、守卫没了」）。零 spawn 金标准（§10.12 末行）落成
     `NpmSpawnGateMatrixTest`：门禁注入下跑 install/ls/dedupe/prune/uninstall/ci 全绿、
     不注入也全绿（反向变异）、`npm run` 在门禁下确实被拦（真产物判据），并已登记进
     `check-e2e-ran.sh` 的 nightly 验尸清单。**边界**：它是不变量守卫不是安全边界
     （已获批脚本可 `delete require.cache` 绕过）——对抗面仍是审批与最小掩码。
   - **素材版本落差已于 2026-10-02 消解**（换 registry 发布态 tarball `npm@12.2.0`，
     `VERSIONS.env` 钉版本 + sha1，`fetch-and-build.sh` §9 双闸），§10.1 脊梁满足。
     **实测的官方默认语义**（解包产物直跑）：**依赖** lifecycle 默认拒（`allow-scripts`
     白名单缺省为空，拦时 `npm warn install-scripts` 播报被拦包与脚本名，不静默；
     `npm install-scripts approve <pkg>` 放行）、`allow-git=none` / `allow-remote=none`；
     **项目自身** lifecycle 仍执行（历代如此）→ 所以硬编码 `--ignore-scripts` 仍是主控的
     **一半**，不撤；**非脚本** spawn 路径的第二层兜底（§10.12 末行 child_process 拦截
     shim）**已于 2026-10-09 落地**（见 §11.3 第 8 条末段：`NpmSpawnGate` + 零 spawn 金标准）。
     口径见 [`design-decisions.md`](../design-decisions.md) 第 26 项，
     实测注见 [`10-npm.md`](10-npm.md) §10.1 与 §10.12 风险表。

   即：**设计上写着「已接线」的那几道 npm 防线，截至 2026-10-09 只剩 `scriptExecutor`（T1
   执行面）未接**；这是接线缺口，不是设计缺口。

### 11.4 非目标

- 本节不引入新的防线，只**登记现有防线与已知缺口**；新增/加强防线走 §18 决策台账，不在设计文档里悄悄添。
- 不做「威胁模型的完备性」论证——上表是按攻击者可达路径枚举的，不是 STRIDE 全矩阵逐格推导。
- 打包（APK 重打包/签名）的密钥管理与签名向导随整轨移入后续版本（§13），其密钥口径不在本节展开。
