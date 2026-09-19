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
    onWarning(listener: (e: {
        kind: "scripts-skipped";
        pkg: readonly string[];
    }) => void): () => void;
};
