## 11. 安全模型（来源分级）

| 来源 | 信任级别 | 引擎 | 能力 | 说明 |
|---|---|---|---|---|
| 内置/作者签名模板 | 高 | Node | 全量（含 root） | 软签名验证 |
| 用户自写脚本 | 中 | Node | 全量 | 提示风险 |
| 第三方/市场脚本 | **低** | **Node（与自写脚本同进程、同权）** | **全量** —— **沙箱已裁，进程隔离这条防线不存在**（§18 第 1 项 2026-09-26） | 防线改为：**安装时按 `hasInstallScript` 如实告知并由用户当场选**（§18 第 7 项）+ 来源分级提示（本表）+ 运行期 TTL/看门狗；**文案不得让人误以为是沙箱跑的** |
| 打包分发脚本 | 中高 | Node | 打包时配置 | 走签名链 |

- RuntimeChannel/engines 通信也按来源分级（低信任不能给高信任发控制消息）。
- 桥的 `ModuleRegistry` 按 scripts 的 CapabilityMask 过滤 handler（非授权模块调用 → `ERR_PERMISSION_DENIED`，不是静默 no-op）。

---

### 11.1 资产与信任边界

先说三条不可从别处推出来的事实，后面所有防线都挂在它们上：

| # | 事实 | 出处 |
|---|---|---|
| 1 | **进程隔离这条防线不存在**。引擎只有 Node 一条轨，第三方脚本与用户自写脚本**同进程、同权**（无障碍读屏/点击/截屏/网络/文件全开）。原先「第三方 → QuickJS 白名单子集」的隔离随 QuickJS 整轨撤出而作废 | §18 第 1 项（[18-19-ledger.md](18-19-ledger.md)）+ [design-decisions.md](../design-decisions.md) 已拍板第 1 项 |
| 2 | **因此能力授予是唯一边界，不是进程边界**。给用户的提示必须直说「装来的脚本与自写脚本同权」，不能让文案读起来像沙箱 | 同上 |
| 3 | **桥面按来源与能力双重过滤**：RuntimeChannel/engines 通信按来源分级（低信任不能给高信任发控制消息）；`ModuleRegistry` 按脚本的 CapabilityMask 过滤 handler，未授权调用回 `ERR_PERMISSION_DENIED`（不是静默 no-op） | 本节上方两条 bullets + §7 |

**要守的资产**（按被攻破后的代价排序）：① 设备上的账号与凭据（脚本可读 `filesDir` 与已授权能力所见的一切）；② 宿主 App 自身的完整性（脚本能改自己的项目文件，从而改下一次启动时跑什么）；③ 已授予的自动化能力（点击、输入、截屏可被脚本用于对外操作）；④ 用户注意力（脚本可弹窗/发通知/驱动界面）。

**信任边界只有三条，其余都是边界内的**：脚本 ↔ 桥（跨进程异步，按 uid 与 CapabilityMask 校验）；脚本 ↔ npm 供应链（lock/registry/安装脚本）；宿主 ↔ 用户（权限授予与安装选择）。

### 11.2 威胁与防线对照

下表左列是本设计**认为会发生**的攻击面，右列是**当前实际生效**的防线与其落点。凡落点为空的行，见 §11.3 残余风险，不假装已覆盖。

| # | 威胁（攻击者能力） | 攻击面 | 现行防线（落点） |
|---|---|---|---|
| T1 | **恶意/被污染的包在安装时执行任意代码**（lifecycle 脚本） | npm 安装 | 零 spawn 主路径：T0 全程 `--ignore-scripts`，安装脚本一个都没跑过，回执 `scripts-skipped`（`InstallCoordinator`，§10.5-3）。**残余**：让脚本真跑的那条路（spawn 桥）尚未落地，见 §11.3 |
| T2 | **lockfile 投毒**（把包名指到别处，integrity 仍成立） | `npm ci` 重建 | `LockSigner` 对 lock 做 HMAC-SHA256 带外签名（`files/.autojs/lock.sig`，键绑 `projectId` 防跨项目搬锁），`ci` 前验签；缺签名/错签名一律 `ERR_PERMISSION_DENIED`（TOFU 自签不算通过）。**接线现状（2026-10-01 核实）**：防线代码与单测都在，但**生产装配没接** —— `NpmShellKit.assembleHandler` 的 `lockKey` 缺省 `null` → 既不签也不验，全仓无 `KeyProvider` 实现。见 §11.3 第 8 条 |
| T3 | **单一镜像/注册表投毒** | registry 响应 | `NpmRegistryVerifier` 双运营主体交叉校验（第二意见必须与首选**不同运营主体**，同站即自比、自比一律拒）；取不到/非 https/无 integrity 锚点一律 `Verdict.Unverifiable` 由调用方显式告知，不折成「通过」 |
| T4 | **冒名客户端抢绑桥 socket** | abstract unix socket | 桥监听与客户端 `main.cpp` 对称验 peer uid（`SO_PEERCRED`），凭据读不到即 fail-closed 拒收（`BridgeSocketListener`）；abstract 名带 uid 后缀，多实例互不抢绑 |
| T5 | **未授权能力被调用**（脚本绕过能力授予） | 桥面 | `ModuleRegistry` 按 CapabilityMask 过滤 handler → `ERR_PERMISSION_DENIED`；`PermissionCenter` 是唯一权限入口，读取异常诚实降级 `DEGRADED`（可用性未知即受限），绝不伪造 `GRANTED` |
| T6 | **脚本失控/僵死/赖活**（死循环、OOM、宿主与引擎状态分裂） | 引擎进程 | `EngineWatchdog` 按心跳/RSS/CPU 采样 + drift 连段裁决（`KillCause.DRIFT`）+ 执行期限（`KillCause.TIMEOUT`）；心跳必须由引擎进程自己打点，看门狗只问不记账 |
| T7 | **安装期互踩/打爆磁盘/恶意 git 依赖/无限挂起** | 安装会话 | per-project 锁 + 全局互斥、free ≥500MB 预检、项目 512MB 配额（80% 黄 / 100% 拦）、`git:` 依赖入口即拒 `ERR_NOT_SUPPORTED`、安装 TTL（`InstallCoordinator`） |
| T8 | **审批票重放**（拿旧版本 APPROVED 装新版本） | 审批账本 | 审批键绑 `pkg + versionHash`，宿主从盘上重算版本哈希，命中 `APPROVED` 才放行（`ApprovalLedger` + `requestApprove`/`requestGateApproval`）；未获批自请入队 `PENDING` |
| T9 | **本地状态被系统备份/换机迁移带到另一个设备上下文**（Auto Backup、D2D 迁移、`adb backup`） | 应用私有目录 `files/.autojs/` | `AndroidManifest` 置 `android:allowBackup="false"`（`:app`）：信任锚与审计面不进备份 —— `lock.sig` 到新机验不过（密钥不出本机），而审批台账/安装 history/意图日志会以「已完成/已批准」的姿态跟着走，恢复出来的状态不可信也不可解释。代价如实说：换机不自动带走过往审批与历史，需重装/重批 |

### 11.3 残余风险（诚实缺口，未被上面任何一条覆盖）

1. **进程隔离不存在** —— 见 §11.1 第 1 条。这是 §18 第 1 项拍板的直接后果，不是待修的 bug。
2. **安装脚本「一个都没真跑过」**：T0 全程 `--ignore-scripts`，回执 `scripts-skipped`。等 spawn 桥（P1）落地后，「用户选择跑」这条才有落点；**在那之前，安装脚本永不执行**（§18 第 7 项 + §10.5-3）。
3. **`lock.sig` 是本地信任锚，不是第三方可验证**：签名用应用私钥，只能证明「这份 lock 是本机签过的」，不构成跨设备/跨用户的可验证来源证明。应用私钥丢失 = 显式「安全降级」失败（`LockSigner` KDoc 口径），不静默放行。**密钥从哪来：目前没有实现** —— 接缝是 `LockSigner.KeyProvider`（2026-10-01 起形状为 `secretKey(): SecretKey`：给句柄而非字节，Keystore 密钥材料不出库也接得上；实现落点已拍板住 `:app` 装配层），设计口径是 Android Keystore 包装的应用密钥，但全仓没有任何 `KeyProvider` 实现、生产装配传 `null`（见第 8 条），所以「生产走 Keystore」这句现在是**目标形态**，不是现状。
4. **MediaProjection 高清会话未落**：授权 UI + FGS 那一档还没接，P0 由同一 a11y 帧源连续截图承接（§9.2）。这不是安全缺口，是能力边界，列此只为避免被当成「高清会话已有门禁」。
5. **16KB 页机未测**：真机红测只有 16KB 模拟器镜像或 Pixel 8+ 能给，SELinux enforcing 上下文与 targetSdk 提取策略同样待真机（design-status「仍未验」块）。
6. **审批卡呈现层未排期**：审批账本与桥面拉取口已通（`drainApprovals` → `NpmBridgeHandler` → JS `pumpApprovals`），但能力中心的审批卡不在当前排期内，期间审批只能靠脚本侧拉取。
7. **无上报时限承诺**：私密上报渠道已于 2026-10-01 开通（GitHub Security → Report a vulnerability，见根 [`SECURITY.md`](../../SECURITY.md)）—— 缺的从此不是渠道，而是**响应 / 修复时限**：单人维护的开发期项目不作承诺。（原条目「上报流程缺失」同日改写。）
8. **npm 生产装配：执行体已接线，签名与脚本门禁仍未落（2026-10-01 起分档）** —— `AppShellKit` 不再走全缺省：
   - **`executor` 已接线**：素材（`assets/npm/**`，vendored npm CLI）启动期幂等落位 `files/npm/`，
     注入 `HostNodeExecutor`（宿主 = `nativeLibraryDir/libnoden.so`）；**两条同时成立才注入**
     （落位就位 + 有宿主），否则保持 `HeavyOpExecutor.Unavailable` 并对 npm.* 如实回
     `ERR_NOT_IMPLEMENTED`，原因原文进 `AssembledShell.npmCliFailure`（不吞）。即 T1/T7 的
     安装路径**有执行体了**，但仍**依赖素材随包**：本机自建、没跑过 Node 构建线的 APK
     就是「无素材」那一档（警告 + 空产出，装配照过）。
   - **`lockKey` 仍 `null`**（T2 的签/验与快照导出都不发生；全仓无 `KeyProvider` 实现，
     接缝形状 2026-10-01 已就位 —— 见第 3 条）。
   - **`scriptExecutor` 仍 `Unavailable`**（T1 门禁过了也跑不起来，spawn 桥属 P1）。
   - ~~**素材版本落差**：vendored 的是 **npm 11.19.0**（Node 24.21.0 的 `deps/npm`），不是 §10.1
     脊梁写的 npm 12.x —— npm 12 的「拒绝全部 lifecycle」官方默认不在位，T1 的护栏当前**只由
     硬编码 `--ignore-scripts` 一层承担**（与版本无关），且**非脚本** spawn 路径的第二层兜底
     （§10.12 末行 child_process 拦截 shim）未落。**且这条落差短期消不掉**：2026-10-01 实测
     `nodejs.org/dist/index.json`（868 条官方发布）**没有一条携带 npm 12.x** —— 「等 Node 线携带」
     不成立，要 12.x 只能另找素材来源。~~ **作废（2026-10-02 批 9 换源，落差已消解）** ——
     素材来源换 **registry 发布态 tarball `npm@12.2.0`**（`VERSIONS.env` 钉版本 + sha1，
     `fetch-and-build.sh` §9 双闸），§10.1 脊梁满足。**实测的官方默认语义**（解包产物直跑）：
     **依赖** lifecycle 默认拒（`allow-scripts` 白名单缺省为空，拦时 `npm warn install-scripts`
     播报被拦包与脚本名，不静默；`npm install-scripts approve <pkg>` 放行）、
     `allow-git=none` / `allow-remote=none`；**项目自身** lifecycle 仍执行（历代如此）
     → 所以硬编码 `--ignore-scripts` 仍是主控的**一半**，不撤；**非脚本** spawn 路径的第二层
     兜底（§10.12 末行 child_process 拦截 shim）**仍未落**。口径见
     [`design-decisions.md`](../design-decisions.md) 第 26 项，实测注见
     [`10-npm.md`](10-npm.md) §10.1 与 §10.12 风险表。

   即：**设计上写着「已接线」的那几道 npm 防线，当前在生产路径上只接上了一道（执行体）**；这是接线缺口，不是设计缺口。

### 11.4 非目标

- 本节不引入新的防线，只**登记现有防线与已知缺口**；新增/加强防线走 §18 决策台账，不在设计文档里悄悄添。
- 不做「威胁模型的完备性」论证——上表是按攻击者可达路径枚举的，不是 STRIDE 全矩阵逐格推导。
- 打包（APK 重打包/签名）的密钥管理与签名向导随整轨移入后续版本（§13），其密钥口径不在本节展开。
