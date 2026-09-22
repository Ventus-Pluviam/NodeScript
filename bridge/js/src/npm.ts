/**
 * npm 依赖管理命名空间（docs/framework-design.md §10.8 / §12.3 auto.npm）。
 * P0：install/remove/ci/list/prune/dedupe/offlineGap/audit、registry 配置、离线导入、
 * approval 只提交请求（人机分离：绝不脚本直调 approve）、progress/approval/warning 事件流。
 *
 * 全部操作跨进程路由到全局安装会话（:app-service:packager InstallCoordinator），TTL 绑定，
 * 绝不阻塞脚本事件循环；脚本内不直接 require('child_process')。
 * 事件流在 bootstrap loader 层经 RuntimeChannel 注入（见 runtime.ts handleResponse 注释）。
 */

import { runtimeBridge } from './runtime'

/**
 * 排队结果（宿主 `install` 的回包）。
 *
 * 宿主实现是「enqueue + 协程内联执行」：回包只代表**已入队**，不代表装完。
 * 真结果走 [InstallFailure] 的 `finished` 事件（失败也在同一处，detail 带得上），
 * 进度走 [InstallEvent]。所以这里**没有** `name/version/integrity` —— 宿主此刻还不知道
 * 会装出什么版本（要等 npm 解析），回一个猜的版本号就是伪造（§1 诚实原则）。
 *
 * 想知道装了什么：`list()` 直读 lockfile（权威），或订阅 `finished` 事件。
 */
export interface InstallQueued {
  /** 安装句柄（传给 cancel / 与事件流的 handleId 对账）。 */
  readonly handleId: string
  readonly projectId: string
  readonly enqueuedAtMillis: number
}

/** 单包安装结果（`InstallCoordinator` 装完后由宿主/UI 侧构造；脚本面不直接拿到）。 */
export interface InstallResult {
  readonly name: string
  readonly version: string
  readonly integrity: string | null
  readonly linkedBins: readonly string[]
}

/**
 * 目录树节点（npm ls 深度的轻量形态）。
 *
 * 刻意**没有** sizeBytes：宿主 `list` 直读 lockfile，量不到字节数。这里若声明了它，
 * `node.sizeBytes` 在运行期恒 undefined，而 TS 会让人以为能拿来算「还要下多少」——
 * 尺寸的两条真来源是 `offlineGap`（缺失清单）与 `storage`（目录实测）。
 */
export interface PkgNode {
  readonly name: string
  readonly version: string
  readonly dependencies?: ReadonlyArray<PkgNode>
}

/**
 * 离线闭包差距（lock 闭包 − 缓存 的缺失清单）。
 *
 * [version] 是宿主一直发、而这里漏声明的字段：`gap[0].version` 原本恒 undefined，
 * 脚本想提示「缺 axios 1.20.0」只能自己拼 lock 再查一遍。
 */
export interface MissingPkg {
  readonly name: string
  readonly version: string
  /** 缺失尺寸（字节）。 */
  readonly size: number
}

/**
 * 审计报告（在线 audit / 离线 OSV）。
 *
 * 键名 **vulns**（不是 vulnerabilities）是 §10.8 与宿主 `NpmBridgeHandler` 的共同键名；
 * 宿主侧有一次真实漂移（发 `vulnerabilities`，让 `report.vulns` 恒 undefined），
 * 现由 Kotlin `NpmBridgeHandlerTest` + 本文件下面的 mock 双向钉住。
 */
export interface AuditReport {
  readonly vulns: ReadonlyArray<{
    id: string
    severity: 'low' | 'moderate' | 'high' | 'critical'
    name: string
  }>
  readonly level: 'none' | 'low' | 'moderate' | 'high' | 'critical'
}

/**
 * 审批请求（宿主经 approvals Flow 推过来；人工在 UI 卡确认）。
 *
 * 与 :domain `ApprovalRequest` 逐字段对齐：`{id, projectId, pkg, versionHash, action,
 * requestedAtMillis}`。刻意**没有** `scripts` —— 那是 §10.8 示例里 `requestApprove`
 * 的**入参**（脚本自己声明的），不是宿主审批流的字段；两边都写会让人以为宿主会回显它。
 * 入参侧的 scripts 由 `requestApprove` 的回包显式回显（见该方法注释）。
 */
export interface ApprovalRequest {
  readonly id: string
  readonly projectId: string
  readonly pkg: string
  readonly versionHash: string
  readonly action: 'install_script' | 'run_script' | 'exec'
  readonly requestedAtMillis: number
}

/**
 * 安装事件（progress 数据面，可丢包/背压，语义与 §7.3 tsf_data 对齐）。
 *
 * [phase] 与 :domain `InstallEvent.Phase` 六个枚举值逐字对齐（同 [InstallWarning] 的
 * kind 约定）：`queued/resolve/download/reify/post-check/done`。宿主发新阶段而这里没同步，
 * 就是「平台说过了但 facade 听不见」—— 脚本用 switch 分支时会整段落到 default。
 * 失败不在 phase 里：那由 [InstallFailure] 表达（`finished` 事件的 success=false）。
 */
export interface InstallEvent {
  readonly projectId: string
  readonly handleId: string
  readonly phase: 'queued' | 'resolve' | 'download' | 'reify' | 'post-check' | 'done'
  readonly name?: string | null
  readonly percent?: number | null
}

/** 安装终止事件（宿主 progress 流的 Finished 分支；成功/失败都发，detail 可空）。 */
export interface InstallFailure {
  readonly projectId: string
  readonly handleId: string
  readonly success: boolean
  readonly detail?: string | null
}

/** 审批票（宿主 `requestApprove` 的回包）。resolve 刻意不在桥面（§10.5 人机分离）。 */
export interface ApprovalTicket {
  readonly requestId: string
  /** `pending` 是脚本侧唯一能拿到的状态：APPROVED/REJECTED 只在 UI 审批卡回调后产生。 */
  readonly status: 'pending' | 'approved' | 'rejected' | 'expired'
  /** 入参 scripts 的回显（宿主校验过数组形态）。空表 = 调用方没声明脚本。 */
  readonly scripts: readonly string[]
}

/**
 * 安装警告（对偶 :domain:InstallEvent.Warning，§10.5-3 禁止静默）。
 *
 * [kind] 与 :domain `InstallEvent.Kind` 枚举逐字对齐：新增种类时两侧同改——
 * JS 侧写死字面量联合而 Kotlin 侧新增枚举值，宿主一发新种类这里就收不到，
 * 那种「平台说过了但 facade 听不见」正是静默漂移。
 * - `scripts-skipped`：`--ignore-scripts` 语义下 lifecycle 脚本没跑（最大投毒面，禁止静默）；
 * - `trust-downgraded`：多镜像交叉校验没验成（副镜像不可达/只在一侧/无 integrity），
 *   安装仍继续但与「两镜像声明一致」不是同一级保证（§10.5-1）；
 * - `registry-fallback`：走了离线 bundle/缓存兜底而非注册表；
 * - `low-memory` / `disk-quota`：资源分级告警（配额 80% 黄 / 磁盘逼近下限）。
 */
export interface InstallWarning {
  readonly projectId: string
  /** 安装会话句柄；预门禁类警告（交叉校验）尚无句柄 → 空串（不伪造 id）。 */
  readonly handleId: string
  readonly kind: 'scripts-skipped' | 'trust-downgraded' | 'registry-fallback' | 'low-memory' | 'disk-quota'
  /** 涉及的包名（只放名字，与 Kotlin pkgs 同口径；`name@version` 细节在 message 里）。 */
  readonly pkgs: readonly string[]
  readonly message: string
}

/** 避免运行时因并发 subscribe 丢失事件：宿主侧以 Flow 订阅；这里持有回调集。 */
type Listener<T> = (e: T) => void

class EventHub<T> {
  private readonly listeners = new Set<Listener<T>>()
  on(listener: Listener<T>): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }
  emit(e: T): void {
    for (const l of [...this.listeners]) l(e)
  }
}

const progress = new EventHub<InstallEvent>()
const approvals = new EventHub<ApprovalRequest>()
const warnings = new EventHub<InstallWarning>()

export const npm = {
  /** 安装（排队→门禁→起会话→执行→post-check→归档；P0）。回包 = 排队结果，非装完。 */
  async install(
    spec: string,
    opts: { save?: boolean; offline?: boolean; timeout?: number } = {},
  ): Promise<InstallQueued> {
    return (await runtimeBridge.invoke('npm', 'install', { spec, save: opts.save ?? true, offline: opts.offline ?? false }, {
      ttl: opts.timeout ?? 60_000,
    })) as InstallQueued
  },

  async remove(spec: string, opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke('npm', 'remove', { spec }, { ttl: opts.timeout ?? 60_000 })
  },

  /** lockfile v3 严格重建（验签后）；市场脚本唯一入口。 */
  async ci(opts: { offline?: boolean; timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke('npm', 'ci', { offline: opts.offline ?? true }, { ttl: opts.timeout ?? 120_000 })
  },

  /** 轻操作：Kotlin 直读，不依赖网络。 */
  async list(opts: { depth?: number; timeout?: number } = {}): Promise<PkgNode[]> {
    return (await runtimeBridge.invoke('npm', 'list', { depth: opts.depth ?? 0 }, { ttl: opts.timeout ?? 10_000 })) as PkgNode[]
  },

  async prune(opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke('npm', 'prune', null, { ttl: opts.timeout ?? 60_000 })
  },

  async dedupe(opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke('npm', 'dedupe', null, { ttl: opts.timeout ?? 60_000 })
  },

  /** 离线闭包差距（缺哪些包、共多大）。 */
  async offlineGap(opts: { timeout?: number } = {}): Promise<MissingPkg[]> {
    return (await runtimeBridge.invoke('npm', 'offlineGap', null, { ttl: opts.timeout ?? 30_000 })) as MissingPkg[]
  },

  async audit(opts: { offline?: boolean; timeout?: number } = {}): Promise<AuditReport> {
    return (await runtimeBridge.invoke('npm', 'audit', { offline: opts.offline ?? true }, {
      ttl: opts.timeout ?? 120_000,
    })) as AuditReport
  },

  async setRegistry(registry: string, opts: { scope?: string; timeout?: number } = {}): Promise<void> {
    // registry 变更经 :main 卡可配列表 + 审计
    await runtimeBridge.invoke('npm', 'setRegistry', { registry, scope: opts.scope ?? null }, { ttl: opts.timeout ?? 10_000 })
  },

  async importOfflineBundle(uri: string, opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke('npm', 'importOfflineBundle', { uri }, { ttl: opts.timeout ?? 120_000 })
  },

  async importTarball(path: string, opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke('npm', 'importTarball', { path }, { ttl: opts.timeout ?? 120_000 })
  },

  /**
   * 审批：只提交请求，绝不脚本直调（人机分离，UI 人工确认）。
   *
   * 回包 `{requestId, status, scripts}`：前两个是宿主票号与状态（`pending`），
   * [ApprovalRequest.scripts] 是**入参回显** —— 宿主校验了数组形态并原样带回，
   * 让脚本能确认「我声明的脚本清单宿主收到了」。不回显的话，宿主与脚本各持一份
   * scripts，改了哪一侧都看不出来（与 setRegistry 的 scope 同一条纪律）。
   *
   * 若宿主拒绝提交，会抛 ERR_PERMISSION_DENIED/ERR_NPM_* —— 如实上抛。
   */
  async requestApprove(
    pkg: string,
    opts: { scripts?: readonly string[]; versionHash?: string; timeout?: number } = {},
  ): Promise<ApprovalTicket> {
    return (await runtimeBridge.invoke(
      'npm',
      'requestApprove',
      { pkg, scripts: opts.scripts ?? [], versionHash: opts.versionHash ?? null },
      { ttl: opts.timeout ?? 10_000 },
    )) as ApprovalTicket
  },

  /** 进度事件（数据面，可丢包）。返回退订函数。 */
  onProgress(listener: (e: InstallEvent) => void): () => void {
    return progress.on(listener)
  },

  /** 审批请求事件（宿主经 approvals Flow 推过来）。 */
  onApproval(listener: (req: ApprovalRequest) => void): () => void {
    return approvals.on(listener)
  },

  /** 警告（此类不可恢复的静默漂移变响亮错误）。 */
  onWarning(listener: (e: InstallWarning) => void): () => void {
    return warnings.on(listener)
  },
}

/** 已知警告种类（与 :domain `InstallEvent.Kind` 五个枚举值一一对应；双断言防漂移）。 */
const WARNING_KINDS: ReadonlyArray<InstallWarning['kind']> = [
  'scripts-skipped',
  'trust-downgraded',
  'registry-fallback',
  'low-memory',
  'disk-quota',
]

/**
 * 宿主向 facade 喂安装警告（装配侧/N-API TSF 回调调用；桌面/测试可直接调）。
 *
 * 为什么必须有这个显式投递缝：桥的入站面只有「按 requestId 结算的 ok/err」
 * （§7.5），而 `InstallEvent.Warning` 不是任何 invoke 的应答——它是宿主
 * `progress(projectId)` Flow 的旁路推送（§7.3 tsf_data 数据面语义）。hub 没有
 * 投递缝的话，`onWarning` 就是「订阅了但永远不响」，比没有这个 API 更糟：
 * 脚本会以为平台不报警（§10.5-3 禁止的静默）。
 *
 * 形态对齐 engines.ts 的 [installHeartbeatPeriod]：宿主注入 + 模块内消费。
 * 与心跳不同，警告**不拉取**——它的价值就在「当下这一下」（丢包的进度事件可以
 * 补拉，而「来源未校验」这种降信任标记必须在安装当下让人看见）。
 */
export function feedWarning(e: InstallWarning): void {
  if (!WARNING_KINDS.includes(e.kind)) {
    // 未知种类 = 契约漂移（Kotlin 新增 InstallEvent.Kind 而这里没同步）。
    // 宁可炸，也不能「收不到还以为没有」——那正是 §10.5-3 要禁的静默面。
    throw new Error(
      `未知安装警告种类: ${String(e.kind)}（:domain InstallEvent.Kind 新增值时须同步 bridge/js/src/npm.ts）`,
    )
  }
  warnings.emit(e)
}
