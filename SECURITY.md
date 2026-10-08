# 安全策略（Security Policy）

本文件面向**使用本项目的人**与**发现安全问题的人**。架构层面的威胁模型在
[`docs/design/11-security.md`](docs/design/11-security.md)，那里是防线与缺口的单一事实来源；
本文件只讲支持范围、怎么报告、密钥怎么管。

## 一句话现状（先读这条）

**本平台不提供脚本沙箱。** 每脚本独立 Node 进程，但第三方脚本与你自己写的脚本**同 UID、同权**——
无障碍读屏、点击、输入、截屏、网络、文件访问全部同权。这是 §18 第 1 项「不引入 QuickJS 沙箱」
的直接后果（引擎只有 Node 一条轨），不是待修的缺陷。**装入一个脚本，等于给它你这台设备上你已授予的全部能力。**
请只安装你读过源码、且确认来源可信的脚本。

## 支持版本

安全问题只在**当前开发分支（`main`）**上修复。本项目不发行正式版（§18 第 3 项「不发行」），
没有 LTS/ESM 支持窗口，因此不存在「请升级到受支持的补丁版本」这一说。

| 版本线 | 是否修复安全问题 |
|---|---|
| `main`（当前开发分支） | 是 |
| 任意历史提交 / 特性分支 | 否（不提供补丁，请切回 `main`） |

## 报告安全问题

**走私密渠道：[Security → Report a vulnerability](https://github.com/Ventus-Pluviam/NodeScript/security/advisories/new)**
（GitHub 私密漏洞上报，2026-10-01 开通）。报告只有维护者可见，可走 GitHub 的 security advisory 流程。

- **不要把漏洞细节发进公开 issue**，也不要发到任何公开渠道 —— 修好并发布 advisory 之前，公开等于给使用者一个没有补丁的靶子。
- 本仓没有安全邮箱，上面那个入口是**唯一**渠道；非安全类问题（功能缺陷 / 构建失败）走普通 issue，
  模板见 [`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE/)。
- **没有响应时限承诺**：单人维护的开发期项目，报告会被看到，时间不定（另见下方「已知限制」第 3 条）。

报告时请附：受影响模块（Gradle 模块名即可，如 `:app-service:npm`）、复现步骤、你观察到的行为与期望行为。
若涉及 npm 供应链（§11.2 的 T1–T3、T8），请一并说明你用的 registry 与 lock 状态。

## 密钥管理

| 密钥 | 用途 | 存放 | 丢失的后果 |
|---|---|---|---|
| 应用 HMAC 密钥 | 签 `files/.autojs/lock.sig`，钉「这份 lock 是本机认可的」（§10.5-1） | **当前未接线**：接缝 `LockSigner.KeyProvider` 是全仓唯一入口（2026-10-01 起形状为 `secretKey(): SecretKey` —— 给句柄而不是字节，Keystore 里**不出库**的密钥也接得上），但**没有任何实现**，生产装配（`NpmShellKit.assembleHandler`）的 `lockKey` 缺省 `null` → **既不签也不验**。设计口径的存放处是 Android Keystore（**目标形态，非现状**） | 未接线期间不适用。接上之后的口径：密钥丢失 = **显式的「安全降级」失败**（`verifyOrThrow` 抛 `ERR_PERMISSION_DENIED`），不静默放行，也不「没签就跳过」 |
| APK 发布密钥 | 打包链签名 | 随打包整轨移入后续版本（§13），当前不入 P0 | 不适用（整轨未启用） |

要点：

- **`lock.sig` 是本地信任锚，不是第三方可验证的来源证明**。它只能证明「这份 lock 是本机签过的」，
  不构成跨设备、跨用户的可审计来源链（§11.3 第 3 条）。
- **npm 那几道防线：执行体已接线（2026-10-01），密钥与脚本门禁仍未接** —— `executor` 现在真接上了：
  vendored npm CLI 随包（`assets/npm/**`）→ 启动期幂等落位 `files/npm/` → `HostNodeExecutor`
  （宿主 = `nativeLibraryDir/libnoden.so`），**两条同时成立才注入**（落位 + 有宿主），否则如实回
  `ERR_NOT_IMPLEMENTED` 并把原因留在 `AssembledShell.npmCliFailure`。`lockKey`（签名/快照）与
  `scriptExecutor`（spawn 桥，P1）仍走缺省。整组缺口登记在
  [`docs/design/11-security.md`](docs/design/11-security.md) §11.3 第 8 条；**没接上的那两道仍是
  「设计上有、当前没接」**，别把它读成「已有防线」。
- 口令只经环境变量传给 `apksigner`（`AUTOSCRIPT_KS_PASS`/`AUTOSCRIPT_KEY_PASS`），**不进参数表、不进日志**。
- 密钥库文件（`*.jks`/`*.keystore`）已列入 `.gitignore`，**不要提交进仓库**。若密钥已误提交，
  视为已泄漏：立即作废该密钥并重新生成，历史清除另需工具处理（git 历史不会因后续提交自动变干净）。

## 已知限制（诚实声明）

1. **无脚本权限隔离**：见上方「一句话现状」。桥已采用 UID 校验 + 宿主签发的一次性执行凭据，
   绑定连接身份，日志归属真实 engineRunId、心跳不能替其他执行打点；但 **CapabilityMask 与跨脚本
   控制授权尚未实现**，已认证脚本仍可使用现有命名空间。凭据通过环境传递、消费后清除且不写日志，
   仍不能保证对抗能窃取其他执行凭据的同 UID 代码；不要把连接认证当成沙箱或来源可信证明。
2. **npm 安装脚本（T0）一个都没真跑过**：全程 `--ignore-scripts`，回执 `scripts-skipped`；
   「让用户选择跑」的那条路（spawn 桥，P1）尚未落地，因此当前**安装脚本永不执行**。
3. **没有响应时限承诺**：私密上报渠道已于 2026-10-01 开通（见上节），但本项目不承诺响应 / 修复时限 ——
   单人维护的开发期项目，报告会被看到、时间不定。（此条原写「上报流程缺失」，随渠道开通改写：缺的从此是时限，不是渠道。）
4. **真机红测缺口**：SELinux enforcing、targetSdk 提取策略未在真机验证（§11.3 第 5 条）。
   16KB 页设备**已裁定不做真机复验**（2026-10-06）—— 装载风险由构建期 ELF 对齐门禁承接
   （`LOAD align >= 0x4000` 且 `p_offset ≡ p_vaddr (mod align)`，三个产物逐件断言），
   **残余面是「内核真按 16KB 基页映射时的装载行为」未被实测**，属**已知不测**而非待做。
5. **MediaProjection 高清会话未落**：P0 由同一无障碍帧源连续截图承接（§9.2）。
6. **npm 生产装配仅剩 T1 执行面未接**：执行体已接线（2026-10-01，且**依赖素材随包** —— 没跑过
   `node-runtime-build` 的 APK 就是「无素材」那档，npm.* 如实 `ERR_NOT_IMPLEMENTED`），
   签名/快照（`lockKey`）与 `child_process` 拦截 shim 已于 2026-10-08 / 2026-10-09 接线
   （`LockKeyStore.AndroidKeystore` / `NpmSpawnGate`），**脚本门禁（`scriptExecutor`，T1 执行面）
   仍未接** —— T1 lifecycle 脚本当前跑不起来（门禁过了也如实 `ERR_NOT_IMPLEMENTED`）。
   另：vendored 的是 registry `npm@12.2.0`（2026-10-02 换源，§10 脊梁 12.x 满足），
   实测官方默认 = **依赖** lifecycle 拒（白名单空 + 播报）+ `allow-git/remote=none` 在位，
   **项目自身**仍执行 → 硬编码 `--ignore-scripts` 仍是主控的一半；非脚本 spawn 的第二兜底
   child_process 拦截 shim 已落（§10.12 末行的零 spawn 金标准落成
   `NpmSpawnGateMatrixTest`，已进 nightly 验尸清单；见 §11.3 第 8 条、
   [`docs/design/10-npm.md`](docs/design/10-npm.md) §10.1 实测注与 §10.12 风险表）。

以上每一条在 `docs/design/11-security.md` §11.3 都有对应登记。两处若有出入，以设计文档为准。
