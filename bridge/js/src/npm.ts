/**
 * npm 依赖管理命名空间（docs/framework-design.md §10.8 / §12.3 auto.npm）。
 * P0：install/remove/ci/list/prune/dedupe/offlineGap/audit、registry 配置、离线导入、
 * approval 只提交请求（人机分离：绝不脚本直调 approve）、progress/approval/warning/finished
 * 事件流。宿主没有主动推给脚本的通道（§7.5 入站面只有按 requestId 结算的 ok/err），
 * 所以事件面是**带游标的拉取轮询**（§10.7 `drainEvents`/`drainApprovals`），
 * 不是推送：首订开定时器，退订干净自停。
 *
 * 全部操作跨进程路由到全局安装会话（:app-service:packager InstallCoordinator），TTL 绑定，
 * 绝不阻塞脚本事件循环；脚本内不直接 require('child_process')。
 * 事件不靠宿主推：脚本侧带游标拉 `events`/`approvals`（文件末 pumpInstallEvents/pumpApprovals），
 * 回包仍经 bootstrap 注入的 handleResponse 按 requestId 结算（见 runtime.ts 注释）。
 */

import { runtimeBridge } from './runtime'
import { AutojsError, ErrCode } from './errors'

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
 * 审批请求（脚本侧经 `onApproval` 收到 —— 底下是 approvals 拉取口的轮询投递，不是宿主推送；人工在 UI 卡确认）。
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
  /** 在场监听数：定时器靠它自停（没人听了就不该继续占着轮询）。 */
  get size(): number {
    return this.listeners.size
  }
}

const progress = new EventHub<InstallEvent>()
const approvals = new EventHub<ApprovalRequest>()
const warnings = new EventHub<InstallWarning>()
const finished = new EventHub<InstallFailure>()

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

  /** 进度事件（数据面，可丢包）。返回退订函数；首订即开拉取轮询。 */
  onProgress(listener: (e: InstallEvent) => void): () => void {
    ensureEventTimer()
    return progress.on(listener)
  },

  /** 审批请求事件（宿主 approvals 拉取口；自己的轮询与安装事件互不牵连）。 */
  onApproval(listener: (req: ApprovalRequest) => void): () => void {
    ensureApprovalTimer()
    return approvals.on(listener)
  },

  /** 警告（此类不可恢复的静默漂移变响亮错误）。 */
  onWarning(listener: (e: InstallWarning) => void): () => void {
    ensureEventTimer()
    return warnings.on(listener)
  },

  /**
   * 安装终止（成功**和**失败都发，detail 带失败原因）。
   *
   * `install()` 的回包只是「已入队」，装没装完只能听这里 —— 没有它，脚本要么
   * 轮询 `list()` 猜、要么干脆不知道失败（§1 诚实原则）。
   */
  onFinished(listener: (e: InstallFailure) => void): () => void {
    ensureEventTimer()
    return finished.on(listener)
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

// ══════════ 事件拉取（§10.7：宿主→脚本无推送面，带 seq 游标轮询） ══════════

/** 已知安装阶段（与 :domain `InstallEvent.Phase` 六个值逐字对齐；与 [WARNING_KINDS] 同纪律）。 */
const PHASES: ReadonlyArray<InstallEvent['phase']> = [
  'queued',
  'resolve',
  'download',
  'reify',
  'post-check',
  'done',
]

/** 已知审批动作（与 :domain `ApprovalAction` 对齐；`lowercase()` 会把 RUN_SCRIPT 折成 run_script 恰好撞上，纯属巧合）。 */
const APPROVAL_ACTIONS: ReadonlyArray<ApprovalRequest['action']> = ['install_script', 'run_script', 'exec']

/** 一轮拉多少（宿主侧环有界 512，32 足够一拍装完）。 */
const EVENT_BATCH = 32

/** 事件游标（已拉过的最大 seq，下次 `sinceSeq`）。只前进：退订再订不回退，漏掉的在环里还捞得到。 */
let eventSeq = 0
let approvalSeq = 0

let eventPollPeriodMillis = 250
let approvalPollPeriodMillis = 250
let eventTimer: ReturnType<typeof setInterval> | null = null
let approvalTimer: ReturnType<typeof setInterval> | null = null
let eventPumping = false
let approvalPumping = false

function checkPeriod(millis: number, what: string): void {
  if (!Number.isFinite(millis) || millis <= 0) throw new Error(`${what} 必须 > 0: ${millis}`)
}

/** 事件轮询周期注入缝（形态对齐 engines.installHeartbeatPeriod；改周期须在首订前生效）。 */
export function installEventPollPeriod(millis: number): void {
  checkPeriod(millis, 'eventPollPeriodMillis')
  eventPollPeriodMillis = millis
}

/** 审批轮询周期注入缝（独立于事件轮询：审批要等人，不必跟进度同拍）。 */
export function installApprovalPollPeriod(millis: number): void {
  checkPeriod(millis, 'approvalPollPeriodMillis')
  approvalPollPeriodMillis = millis
}

/** 宿主 `events`/`approvals` 拉取回包（{first,last,items}；空增量 first=last=sinceSeq）。 */
interface DrainBatch<T> {
  readonly first: number
  readonly last: number
  readonly events?: readonly T[]
  readonly requests?: readonly T[]
}

function drainBatchOf(payload: unknown, key: 'events' | 'requests'): { first: number; last: number; items: readonly Record<string, unknown>[] } {
  const b = payload as DrainBatch<Record<string, unknown>> | null
  if (!b || !Array.isArray(b[key]) || typeof b.first !== 'number' || typeof b.last !== 'number') {
    // 形状对不上 = 两侧契约漂移（宿主改了回包而这里没同步）。响亮炸：静默吞掉
    // 就是「拉回来一堆 undefined 却继续跑」，比没有这个 API 更糟。
    throw new Error(`npm 拉取回包形状不符（须 {first,last,${key}:[]}）: ${JSON.stringify(payload)}`)
  }
  return { first: b.first, last: b.last, items: b[key] as readonly Record<string, unknown>[] }
}

/**
 * 拉一轮安装事件（progress / warning / finished 三路共用一个游标与定时器）。
 *
 * 错误分两档，分界线是「能不能自己好」：
 * - `ERR_NOT_IMPLEMENTED` = 宿主没实现 `events` → **响亮上抛**。订阅了却永远收不到，
 *   正是 feedWarning KDoc 说的「比没有这个 API 更糟」，必须崩在脸上；
 * - 其余（超时/断链/引擎未就绪）= 瞬时 → 吞掉走下一拍，游标不动，不丢事件。
 *
 * 与 a11y.events 同口径：回包里的 `seq` 是宿主环的位置，游标取 `last`；
 * `first > eventSeq+1` 说明环有界丢过最旧的（进度是可丢数据面，如实跳过不补造）。
 */
export async function pumpInstallEvents(): Promise<void> {
  if (eventPumping) return
  eventPumping = true
  try {
    let payload: unknown
    try {
      payload = await runtimeBridge.invoke('npm', 'events', { sinceSeq: eventSeq, batch: EVENT_BATCH }, { ttl: 5_000 })
    } catch (e) {
      if (isNotImplemented(e)) throw e
      return
    }
    const { last, items } = drainBatchOf(payload, 'events')
    for (const w of items) routeInstallEvent(w)
    if (last > eventSeq) eventSeq = last
  } finally {
    eventPumping = false
  }
}

/** 拉一轮审批请求（独立游标：审批不必等安装事件那一拍）。 */
export async function pumpApprovals(): Promise<void> {
  if (approvalPumping) return
  approvalPumping = true
  try {
    let payload: unknown
    try {
      payload = await runtimeBridge.invoke('npm', 'approvals', { sinceSeq: approvalSeq, batch: EVENT_BATCH }, { ttl: 5_000 })
    } catch (e) {
      if (isNotImplemented(e)) throw e
      return
    }
    const { last, items } = drainBatchOf(payload, 'requests')
    for (const w of items) {
      const action = w.action
      if (!APPROVAL_ACTIONS.includes(action as ApprovalRequest['action'])) {
        throw new Error(`未知审批动作: ${String(action)}（:domain ApprovalAction 新增值时须同步 bridge/js/src/npm.ts）`)
      }
      approvals.emit({
        id: String(w.id),
        projectId: String(w.projectId),
        pkg: String(w.pkg),
        versionHash: String(w.versionHash),
        action: action as ApprovalRequest['action'],
        requestedAtMillis: Number(w.requestedAtMillis),
      })
    }
    if (last > approvalSeq) approvalSeq = last
  } finally {
    approvalPumping = false
  }
}

function isNotImplemented(e: unknown): boolean {
  if (e instanceof AutojsError) return e.is(ErrCode.NOT_IMPLEMENTED)
  return (e as { code?: unknown } | null)?.code === 'ERR_NOT_IMPLEMENTED' // N-API 形态的裸错误
}

/** 一条宿主事件 → 对应 hub；未知分支/未知取值一律响亮（平台说过了但 facade 听不见 = 静默漂移）。 */
function routeInstallEvent(w: Record<string, unknown>): void {
  switch (w.type) {
    case 'progress': {
      if (!PHASES.includes(w.phase as InstallEvent['phase'])) {
        throw new Error(`未知安装阶段: ${String(w.phase)}（:domain InstallEvent.Phase 新增值时须同步 bridge/js/src/npm.ts）`)
      }
      progress.emit({
        projectId: String(w.projectId),
        handleId: String(w.handleId),
        phase: w.phase as InstallEvent['phase'],
        name: (w.name as string | null | undefined) ?? null,
        percent: (w.percent as number | null | undefined) ?? null,
      })
      return
    }
    case 'warning':
      // 过 feedWarning 而不是直接 warnings.emit：kind 的逐字校验只写一处（双断言防漂移）。
      feedWarning({
        projectId: String(w.projectId),
        handleId: String(w.handleId),
        kind: w.kind as InstallWarning['kind'],
        pkgs: Array.isArray(w.pkgs) ? (w.pkgs as string[]) : [],
        message: String(w.message ?? ''),
      })
      return
    case 'finished': {
      if (typeof w.success !== 'boolean') {
        throw new Error(`finished 事件缺布尔字段 success: ${JSON.stringify(w)}`)
      }
      finished.emit({
        projectId: String(w.projectId),
        handleId: String(w.handleId),
        success: w.success,
        detail: (w.detail as string | null | undefined) ?? null,
      })
      return
    }
    default:
      throw new Error(`未知安装事件 type: ${String(w.type)}（:domain InstallEvent 上桥新增分支时须同步 bridge/js/src/npm.ts）`)
  }
}

function unref(t: ReturnType<typeof setInterval>): void {
  const u = (t as unknown as { unref?: () => void }).unref
  if (typeof u === 'function') u.call(t)
}

/** 响亮上抛：瞬时错已在 pump 内吞掉，走到这里的都是契约漂移类，不许静默成「收不到」。 */
function loud(p: Promise<void>): void {
  void p.catch((e) => {
    queueMicrotask(() => {
      throw e
    })
  })
}

function stopEventTimer(): void {
  if (eventTimer) clearInterval(eventTimer)
  eventTimer = null
}

function stopApprovalTimer(): void {
  if (approvalTimer) clearInterval(approvalTimer)
  approvalTimer = null
}

function ensureEventTimer(): void {
  if (!eventTimer) {
    eventTimer = setInterval(() => {
      if (progress.size + warnings.size + finished.size === 0) {
        stopEventTimer() // 没人听了就自停；游标保留，重订时从原位续拉
        return
      }
      loud(pumpInstallEvents())
    }, eventPollPeriodMillis)
    unref(eventTimer)
  }
  // 每次订阅都立拉一轮（不只定时器首建时）：退订到重订之间定时器可能还活着
  // 但一拍没到，只在首建时拉会让人以为「订了不响」。并发由 eventPumping 挡。
  loud(pumpInstallEvents())
}

function ensureApprovalTimer(): void {
  if (!approvalTimer) {
    approvalTimer = setInterval(() => {
      if (approvals.size === 0) {
        stopApprovalTimer()
        return
      }
      loud(pumpApprovals())
    }, approvalPollPeriodMillis)
    unref(approvalTimer)
  }
  loud(pumpApprovals())
}
