/**
 * npm 依赖管理命名空间（docs/framework-design.md §10.8 / §12.3 auto.npm）。
 * P0：install/remove/ci/list/prune/dedupe/offlineGap/audit、registry 配置、离线导入、
 * approval 只提交请求（人机分离：绝不脚本直调 approve）、progress/approval/warning 事件流。
 *
 * 全部操作跨进程路由到全局安装会话（:app-service:packager InstallCoordinator），TTL 绑定，
 * 绝不阻塞脚本事件循环；脚本内不直接 require('child_process')。
 * 事件流在 bootstrap loader 层经 RuntimeChannel 注入（见 runtime.ts handleResponse 注释）。
 */
/** 安装结果（对齐 §10.8：name/version/integrity/linkedBins）。 */
export interface InstallResult {
    readonly name: string;
    readonly version: string;
    readonly integrity: string;
    readonly linkedBins: readonly string[];
}
/** 目录树节点（npm ls 深度的轻量形态）。 */
export interface PkgNode {
    readonly name: string;
    readonly version: string;
    readonly dependencies?: ReadonlyArray<PkgNode>;
}
/** 离线闭包差距（lock 闭包 − 缓存 的缺失清单）。 */
export interface MissingPkg {
    readonly name: string;
    /** 缺失尺寸（字节）。 */
    readonly size: number;
}
/** 审计报告（在线 audit / 离线 OSV）。 */
export interface AuditReport {
    readonly vulns: ReadonlyArray<{
        id: string;
        severity: 'low' | 'moderate' | 'high' | 'critical';
        name: string;
    }>;
    readonly level: 'none' | 'low' | 'moderate' | 'high' | 'critical';
}
/** 审批请求（只可提交；人工在 UI 卡确认；与 §10.8 approvals Flow 对齐）。 */
export interface ApprovalRequest {
    readonly pkg: string;
    readonly scripts: readonly string[];
    readonly projectId: string;
}
/** 安装事件（progress 数据面，可丢包/背压，语义与 §7.3 tsf_data 对齐）。 */
export interface InstallEvent {
    readonly phase: 'resolve' | 'download' | 'unpack' | 'link' | 'done' | 'failed';
    readonly name?: string;
    readonly percent?: number;
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
    readonly projectId: string;
    /** 安装会话句柄；预门禁类警告（交叉校验）尚无句柄 → 空串（不伪造 id）。 */
    readonly handleId: string;
    readonly kind: 'scripts-skipped' | 'trust-downgraded' | 'registry-fallback' | 'low-memory' | 'disk-quota';
    /** 涉及的包名（只放名字，与 Kotlin pkgs 同口径；`name@version` 细节在 message 里）。 */
    readonly pkgs: readonly string[];
    readonly message: string;
}
export declare const npm: {
    /** 安装（排队→门禁→起会话→执行→post-check→归档；P0）。 */
    install(spec: string, opts?: {
        save?: boolean;
        offline?: boolean;
        timeout?: number;
    }): Promise<InstallResult>;
    remove(spec: string, opts?: {
        timeout?: number;
    }): Promise<void>;
    /** lockfile v3 严格重建（验签后）；市场脚本唯一入口。 */
    ci(opts?: {
        offline?: boolean;
        timeout?: number;
    }): Promise<void>;
    /** 轻操作：Kotlin 直读，不依赖网络。 */
    list(opts?: {
        depth?: number;
        timeout?: number;
    }): Promise<PkgNode[]>;
    prune(opts?: {
        timeout?: number;
    }): Promise<void>;
    dedupe(opts?: {
        timeout?: number;
    }): Promise<void>;
    /** 离线闭包差距（缺哪些包、共多大）。 */
    offlineGap(opts?: {
        timeout?: number;
    }): Promise<MissingPkg[]>;
    audit(opts?: {
        offline?: boolean;
        timeout?: number;
    }): Promise<AuditReport>;
    setRegistry(registry: string, opts?: {
        scope?: string;
        timeout?: number;
    }): Promise<void>;
    importOfflineBundle(uri: string, opts?: {
        timeout?: number;
    }): Promise<void>;
    importTarball(path: string, opts?: {
        timeout?: number;
    }): Promise<void>;
    /** 审批：只提交请求，绝不脚本直调（人机分离，UI 人工确认）。 */
    requestApprove(pkg: string, opts?: {
        scripts?: readonly string[];
        timeout?: number;
    }): Promise<void>;
    /** 进度事件（数据面，可丢包）。返回退订函数。 */
    onProgress(listener: (e: InstallEvent) => void): () => void;
    /** 审批请求事件（宿主经 approvals Flow 推过来）。 */
    onApproval(listener: (req: ApprovalRequest) => void): () => void;
    /** 警告（此类不可恢复的静默漂移变响亮错误）。 */
    onWarning(listener: (e: InstallWarning) => void): () => void;
};
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
export declare function feedWarning(e: InstallWarning): void;
