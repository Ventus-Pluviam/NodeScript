package com.autoscript.domain.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 契约锚定测试（§10.7）：PackageManagerFacade 的方法面与 DTO 形状是 §10 的冻结点，
 * 任何签名变更都必须同步改本测试 + §10.7 文档。
 */
class PackageManagerFacadeContractTest {

    @Test
    fun `门面方法面冻结`() {
        val names = PackageManagerFacade::class.java.methods.map { it.name }.toSet() -
            setOf("equals", "hashCode", "toString", "wait", "notify", "notifyAll", "getClass", "getDeclaringClass")
        val expected = setOf(
            // 重操作
            "install", "ci", "update", "uninstall", "dedupe", "prune",
            "audit", "importOfflineBundle", "importTarball", "cancel",
            // 轻操作
            "list", "offlineGap", "config", "storage", "snapshot",
            // 全局镜像源（§10.9 第 8 条；读口 + 写口，界面不经桥）
            "globalRegistry", "setGlobalRegistry",
            // 控制台命令面（§10.9 第 3 条「npm 终端视图」；同样不经桥）
            "runConsoleCommand", "consoleOutput",
            // 审批（人机分离：requestApprove 仅入队 / resolveApproval 仅 UI 回调）
            "requestApprove", "resolveApproval", "pendingApprovals",
            // P1
            "runScript", "exec",
            // 事件流（Flow 供 :main 订阅；drain* 是脚本侧拉取口，§10.7）
            "progress", "approvals", "drainEvents", "drainApprovals",
            // 快照
            "exportSnapshot",
            // 审计史（§10.5-2；append-only 历史，与 snapshot() 的"当前事实"是两件事）
            "history",
            // 缓存回收（§10.9 第 5 条的动作半边；**不是** npm cache clean，见该方法的 KDoc）
            "reclaimCache",
        )
        assertEquals(expected, names, "门面方法面必须与 §10.7 冻结清单一致")
    }

    @Test
    fun `拉取批次 DTO 形状（first 与 last 与 seq 三件套，与 a11y events 同口径）`() {
        assertEquals(
            listOf("firstSeq", "lastSeq", "events"),
            InstallEventBatch::class.java.declaredFields.map { it.name },
        )
        assertEquals(
            listOf("firstSeq", "lastSeq", "requests"),
            ApprovalBatch::class.java.declaredFields.map { it.name },
        )
        assertEquals(listOf("seq", "event"), SequencedInstallEvent::class.java.declaredFields.map { it.name })
        assertEquals(listOf("seq", "request"), SequencedApproval::class.java.declaredFields.map { it.name })
    }

    @Test
    fun `全局镜像源读数三件套（生效值由 configured 决定，呈现层不写死 URL）`() {
        assertEquals(
            listOf("configured", "defaultRegistry", "secondaryRegistry"),
            NpmRegistrySnapshot::class.java.declaredFields.map { it.name },
        )
        assertEquals(
            NpmRegistryKeys.OFFICIAL,
            NpmRegistrySnapshot(null, NpmRegistryKeys.OFFICIAL, NpmRegistryKeys.MIRROR).effective,
            "没设过时生效的就是出厂缺省",
        )
    }

    @Test
    fun `审批 DTO 绑定 pkg 与版本哈希`() {
        val props = ApprovalRequest::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("versionHash" in props, "ApprovalRequest 必须携带 versionHash")
        assertTrue("pkg" in props)
        assertTrue("action" in props)
    }

    @Test
    fun `审批状态机四态`() {
        assertEquals(
            listOf("PENDING", "APPROVED", "REJECTED", "EXPIRED"),
            ApprovalStatus.entries.map { it.name },
        )
    }

    @Test
    fun `安装事件阶段覆盖安装全生命周期`() {
        assertEquals(
            listOf("QUEUED", "RESOLVE", "DOWNLOAD", "REIFY", "POST_CHECK", "DONE"),
            InstallEvent.Phase.entries.map { it.name },
        )
    }

    @Test
    fun `控制台输出批次三件套（first 与 last 与 lines，与 a11y events 同口径）`() {
        assertEquals(
            listOf("firstSeq", "lastSeq", "lines", "running"),
            NpmConsoleSnapshot::class.java.declaredFields.map { it.name },
        )
        assertEquals(listOf("seq", "line"), SequencedConsoleLine::class.java.declaredFields.map { it.name })
        assertEquals(
            listOf("kind", "text", "atMillis", "ok"),
            NpmConsoleLine::class.java.declaredFields.map { it.name },
            "ok 是终态行的成败位：呈现层按它着色，**不按文本猜**（宿主改措辞不该让颜色静默失效）",
        )
        assertEquals(
            listOf("ECHO", "PHASE", "OUTPUT", "WARNING", "RESULT"),
            NpmConsoleLineKind.entries.map { it.name },
            "呈现层按 kind 着色，宿主侧是唯一判读处 —— 枚举顺序变了要同步 §10.9 第 3 条",
        )
    }

    @Test
    fun `审计史条目五字段（形状由落盘格式决定，不由界面想显示什么决定）`() {
        assertEquals(
            listOf("op", "projectId", "success", "detail", "atMillis"),
            InstallHistoryEntry::class.java.declaredFields.map { it.name },
            "这五个字段就是 install-history.jsonl 的落盘字段：加字段要动落盘格式，" +
                "而那是审计 —— 改形状等于让历史行与将来行不可比",
        )
    }

    @Test
    fun `审计操作名的取值域是开放的，已知名有一个锚`() {
        // 未知 op 必须能构造出来（落盘侧刻意不因枚举不全丢事件，读侧不该把不认识的行藏掉）。
        val unknown = InstallHistoryEntry(op = "future-op", projectId = "p", success = true, atMillis = 0)
        assertEquals("future-op", unknown.op)

        // 已知名以 :domain 那份为准（`:ui` 审计页按它分组，看不见 :app-service:npm）。
        assertEquals(
            listOf("install", "ci", "uninstall", "prune", "dedupe", "registry", "import", "export"),
            listOf(
                InstallHistoryOp.INSTALL, InstallHistoryOp.CI, InstallHistoryOp.UNINSTALL,
                InstallHistoryOp.PRUNE, InstallHistoryOp.DEDUPE, InstallHistoryOp.REGISTRY,
                InstallHistoryOp.IMPORT, InstallHistoryOp.EXPORT,
            ),
            "前八个与 InstallHistory.Op 逐字同值（那边现在是别名）；改了要同步 :ui 的分组",
        )
        // 跑出来的取值：opName(args) 直取 argv 首词、T1 动作名来自 ApprovalAction.name.lowercase()。
        assertEquals("ls", InstallHistoryOp.LS)
        assertEquals("audit", InstallHistoryOp.AUDIT)
        assertEquals("update", InstallHistoryOp.UPDATE)
        assertEquals("run_script", InstallHistoryOp.RUN_SCRIPT)
        assertEquals("exec", InstallHistoryOp.EXEC)
        assertEquals("install_script", InstallHistoryOp.INSTALL_SCRIPT)
    }

    @Test
    fun `缓存回收报告六字段（回收后的账，不是"本来有多少"）`() {
        assertEquals(
            listOf("removedEntries", "removedBytes", "keptEntries", "keptBytes", "keepCount", "indexRebuilt"),
            NpmCacheReclaimReport::class.java.declaredFields.map { it.name },
            "界面要回答的是「点完了还占多大地方」，所以 kept* 是现状、removed* 是本次战果；" +
                "keepCount 单列是为了让「保留集为空」与「保留集很大」可区分",
        )
    }

    @Test
    fun `维护动作三态（cache 回收刻意不在这个枚举里）`() {
        assertEquals(
            listOf("PRUNE", "DEDUPE", "CI"),
            NpmMaintenanceAction.entries.map { it.name },
            "cache 回收返回的是一份读数而不是句柄、且不占安装会话 —— 塞进这里会让" +
                "「跑一次 npm 会话」与「删几个缓存文件」在界面上共用一套进度语义",
        )
    }

    @Test
    fun `审计报告区分在线签名与离线 OSV`() {
        val props = AuditReport::class.java.declaredFields.map { it.name }
        assertTrue("offline" in props, "AuditReport.offline 是签名端点不可用时显式降级的载体")
    }
}
