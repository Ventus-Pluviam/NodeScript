package com.autoscript.appservice.packager.npm

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.core.ErrorCode

/**
 * `npm` 命名空间桥处理器（docs §10.8 / §12.3 `auto.npm` 的 Kotlin 对偶）。
 *
 * 归属：住 `:app-service:packager`（InstallCoordinator 所在；桥只透传 envelope，
 * 方法语义全在 [PackageManagerFacade] 面）。`:app` 装配层只拿 [mount] 返回的
 * [NamespaceHandler] 挂 Router（同 a11y/screen 注入缝，§4.1/§6）。
 *
 * 项目归属：payload `projectId` 显式指定（`auto.npm.install` 所在的脚本项目）；
 * 缺省走 [DEFAULT_PROJECT_ID]（单项目 IDE 场景）。空串/非字符串 → ERR_INVALID_PARAM，
 * 绝不静默套默认（把包装进别人项目比报错更糟）。
 *
 * 方法表（与 `bridge/js` npm.ts 一一对应，§10.8）：
 * - `install`：`{spec,save?,offline?,timeout?}` → `{handleId,projectId,enqueuedAtMillis}`
 *   （`:domain` 的 [InstallHandle] 原样上桥）。返回体**故意不含**装了什么：门面只交回句柄，
 *   `name/version/integrity` 要等 npm 解析完才有，此刻回一个猜的版本号就是伪造。
 *   想知道装了什么：`list()` 直读 lockfile（权威），或订阅 progress 流的 Finished 事件。
 *   （§10.8 文档里 `install → {name,version,integrity}` 的示例在门面形状下产不出来——
 *   `InstallResult`/`ResolvedPkg` 两个 DTO 至今没有任何实现方产出，别照文档当真）。
 * - `remove`：`{spec}` → 无参；
 * - `ci`：`{offline?}` → 无参；
 * - `list`：`{depth?}` → `[{name,version}]`（轻操作直读 lockfile；**不含** sizeBytes，
 *   lockfile 量不到尺寸，发一个恒 0 的字节数等于声称「该包 0 字节」——尺寸走
 *   `offlineGap`（缺失清单）/ `storage`（目录实测），两条路都真有数）；
 * - `prune`/`dedupe`：无参；
 * - `offlineGap`：无参 → `[{name,version,size}]`；
 * - `audit`：`{offline?}` → `{vulns:[],level,offline}`（P0 诚实空报告；键名是 **vulns**
 *   不是 vulnerabilities——§10.8 文档与 JS facade 的 `AuditReport.vulns` 都读这个键，
 *   回旧键名会让 `report.vulns` 恒 undefined）；
 * - `setRegistry`：`{registry,scope?}` → 无参（scope→npmrc `<scope>:registry`，§10.2 三层注册表配置；经 :main 可配列表 + 审计）；
 * - `importOfflineBundle`/`importTarball`：`{uri}`/`{path}`；
 * - `events`：`{sinceSeq?,batch?}` → `{first,last,events:[{seq,type,…}]}`（**拉取式**，§9.1 同形；
 *   见 [com.autoscript.domain.npm.InstallEventBatch]）。`type` ∈ `progress`/`warning`/`finished`——
 *   三种分别喂 JS 的 `onProgress`/`onWarning`/`onFinished`；空增量回 `{first:last:sinceSeq,events:[]}`。
 * - `approvals`：`{sinceSeq?,batch?}` → `{first,last,requests:[{seq,…}]}`（`onApproval` 的取数口）。
 * - `requestApprove`：`{pkg,versionHash?,action?,scripts?}` → `{requestId,status,scripts}`
 *   （**只入队**；脚本绝无 resolve 权。`scripts` 回显：JS facade 一直带着这个字段，
 *   宿主不校验也不回就是静默丢用户显式声明——与 `setRegistry` 的 scope 同一类问题）。
 */
class NpmBridgeHandler(private val facade: com.autoscript.domain.npm.PackageManagerFacade) {

    companion object {
        /** 单项目/IDE 直跑场景的缺省项目（多项目时 payload 必须显式带 projectId）。 */
        const val DEFAULT_PROJECT_ID = "main"
    }

    suspend fun handle(request: BridgeRequest): BridgeResponse = try {
        BridgeResponse.Ok(request.id, dispatch(request))
    } catch (e: com.autoscript.domain.core.AutojsException) {
        BridgeResponse.Err(request.id, e.error.code, e.message)
    } catch (e: IllegalArgumentException) {
        // 载荷/参数非法 → ERR_INVALID_PARAM（§7 诚实上报，不伪造成功）
        BridgeResponse.Err(request.id, ErrorCode.ERR_INVALID_PARAM.code, e.message)
    }

    /** 挂载为桥 NamespaceHandler（:app 装配层只拿这个，不 new 本类）。 */
    fun mount(): NamespaceHandler = NamespaceHandler { req -> handle(req) }

    private suspend fun dispatch(request: BridgeRequest): String? {
        val f = NpmBridgeJson.decodeObject(requirePayload(request))
        val projectId = when (val v = f["projectId"]) {
            null, is NpmBridgeJson.Value.Null -> DEFAULT_PROJECT_ID
            is NpmBridgeJson.Value.S -> v.v.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("projectId 不得为空串")
            else -> throw IllegalArgumentException("projectId 必须是字符串")
        }
        return when (request.method) {
            "install" -> {
                val spec = NpmBridgeJson.reqStr(f, "spec")
                val save = NpmBridgeJson.optBool(f, "save") ?: true
                val offline = NpmBridgeJson.optBool(f, "offline") ?: false
                val timeout = NpmBridgeJson.optLong(f, "timeout") ?: 60_000L
                val (name, version) = parseSpec(spec)
                val handle = facade.install(
                    projectId,
                    listOf(com.autoscript.domain.npm.PackageSpec(name, version)),
                    com.autoscript.domain.npm.InstallFlags(offline = offline, save = save, timeoutMillis = timeout),
                )
                // 上桥的是 :domain 的 InstallHandle 本身，不是 `true` 也不是包体：
                // 门面此刻只知道「这个句柄已入队」，装出什么要等解析。handleId 同时是
                // progress 流事件的关联键（脚本靠它把事件对回自己这次调用）。
                NpmBridgeJson.encode(
                    mapOf(
                        "handleId" to handle.id,
                        "projectId" to handle.projectId,
                        "enqueuedAtMillis" to handle.enqueuedAtMillis,
                    ),
                )
            }
            "remove" -> {
                val spec = NpmBridgeJson.reqStr(f, "spec")
                val (name, _) = parseSpec(spec)
                facade.uninstall(projectId, name)
                null
            }
            "ci" -> {
                val offline = NpmBridgeJson.optBool(f, "offline") ?: true
                facade.ci(projectId, offline)
                null
            }
            "list" -> {
                val depth = (NpmBridgeJson.optLong(f, "depth") ?: 0L).toInt()
                // 不发 sizeBytes：list 直读 lockfile，量不到尺寸（恒 0）。发一个永远是 0
                // 的字节数 = 声称「这个包占 0 字节」，比不给这个字段更糟——脚本会拿它算
                // 「还要下多少」然后得到 0。尺寸的两条真来源：offlineGap（缺失清单）、
                // storage（node_modules 目录实测）。
                NpmBridgeJson.encode(facade.list(projectId, depth).map {
                    mapOf("name" to it.name, "version" to it.version)
                })
            }
            "prune" -> { facade.prune(projectId); null }
            "dedupe" -> { facade.dedupe(projectId); null }
            "offlineGap" -> NpmBridgeJson.encode(
                facade.offlineGap(projectId).map { mapOf("name" to it.name, "version" to it.version, "size" to it.sizeBytes) },
            )
            "audit" -> {
                val offline = NpmBridgeJson.optBool(f, "offline") ?: true
                val r = facade.audit(projectId, offline)
                NpmBridgeJson.encode(
                    mapOf(
                        // 键名 vulns：JS facade 的 AuditReport.vulns 直接读它，§10.8 文档同形。
                        // 回 vulnerabilities 会让 report.vulns 恒 undefined（与 a11y.waitFor
                        // 那次同一类事故：facade 读一个宿主从不发的键）。
                        "vulns" to r.vulnerabilities.map {
                            mapOf("id" to it.id, "severity" to it.severity.name.lowercase(), "name" to it.pkgName)
                        },
                        "level" to r.level.name.lowercase(),
                        "offline" to r.offline,
                    ),
                )
            }
            "setRegistry" -> {
                val registry = NpmBridgeJson.reqStr(f, "registry")
                // scope（@my）→ npmrc 的 `<scope>:registry` 键（§10.2 registry 配置三层）。
                // JS facade 的 setRegistry(registry, {scope}) 会带此字段；不认就是静默丢弃
                // 用户显式声明的作用域（比报错更糟），故在此如实落地而非忽略。
                val scope = NpmBridgeJson.optStr(f, "scope")?.takeIf { it.isNotBlank() }
                facade.config(projectId, com.autoscript.domain.npm.NpmConfigKey.REGISTRY, registry, scope)
                null
            }
            "importOfflineBundle" -> {
                facade.importOfflineBundle(projectId, NpmBridgeJson.reqStr(f, "uri"))
                null
            }
            "importTarball" -> {
                facade.importTarball(projectId, NpmBridgeJson.reqStr(f, "path"))
                null
            }
            "events" -> {
                val sinceSeq = NpmBridgeJson.optLong(f, "sinceSeq") ?: 0L
                val batch = (NpmBridgeJson.optLong(f, "batch") ?: 32L).toInt()
                if (batch <= 0) throw IllegalArgumentException("batch 必须 > 0")
                val got = facade.drainEvents(projectId, sinceSeq, batch)
                NpmBridgeJson.encode(
                    mapOf(
                        "first" to got.firstSeq,
                        "last" to got.lastSeq,
                        "events" to got.events.map { encodeEvent(it.seq, it.event) },
                    ),
                )
            }
            "approvals" -> {
                val sinceSeq = NpmBridgeJson.optLong(f, "sinceSeq") ?: 0L
                val batch = (NpmBridgeJson.optLong(f, "batch") ?: 32L).toInt()
                if (batch <= 0) throw IllegalArgumentException("batch 必须 > 0")
                val got = facade.drainApprovals(projectId, sinceSeq, batch)
                NpmBridgeJson.encode(
                    mapOf(
                        "first" to got.firstSeq,
                        "last" to got.lastSeq,
                        "requests" to got.requests.map { r ->
                            mapOf(
                                "seq" to r.seq,
                                "id" to r.request.id,
                                "projectId" to r.request.projectId,
                                "pkg" to r.request.pkg,
                                "versionHash" to r.request.versionHash,
                                "action" to actionWire(r.request.action),
                                "requestedAtMillis" to r.request.requestedAtMillis,
                            )
                        },
                    ),
                )
            }
            "requestApprove" -> {
                val pkg = NpmBridgeJson.reqStr(f, "pkg")
                val versionHash = NpmBridgeJson.optStr(f, "versionHash") ?: ""
                // scripts：JS facade 的 requestApprove(pkg, {scripts}) 一直带着它（§10.8）。
                // 宿主既不校验也不回 = 静默丢弃用户显式声明，与 setRegistry 的 scope 同罪；
                // 形态不对就 ERR_INVALID_PARAM（响亮失败），对得上才回显「宿主收到了」。
                val scripts = NpmBridgeJson.optStrList(f, "scripts")
                val action = when (NpmBridgeJson.optStr(f, "action")?.lowercase()) {
                    "run_script", "runscript" -> com.autoscript.domain.npm.ApprovalAction.RUN_SCRIPT
                    "exec" -> com.autoscript.domain.npm.ApprovalAction.EXEC
                    else -> com.autoscript.domain.npm.ApprovalAction.INSTALL_SCRIPT
                }
                val t = facade.requestApprove(projectId, pkg, versionHash, action)
                NpmBridgeJson.encode(
                    mapOf(
                        "requestId" to t.requestId,
                        "status" to t.status.name.lowercase(),
                        "scripts" to scripts,
                    ),
                )
            }
            else -> throw com.autoscript.domain.core.AutojsException(
                ErrorCode.ERR_NOT_IMPLEMENTED,
                "未知 npm 方法: ${request.method}",
            )
        }
    }

    /**
     * 事件上桥的 wire 形状（JS 侧 `InstallEvent`/`InstallWarning`/`InstallFailure` 逐字对齐）。
     *
     * 枚举名**不走 `.name.lowercase()`**：`POST_CHECK` 折出来是 `post_check`，而 JS 的
     * phase 联合写的是 `post-check` —— 差一个连字符，脚本的 switch 就会整段落到 default
     * （与 `vulnerabilities`/`vulns` 同一类事故）。故 phase/kind/action 三处逐值显式映射，
     * 新增枚举值在 `when` 里缺分支 = 编译期红，不留到运行期。
     */
    internal fun encodeEvent(seq: Long, e: com.autoscript.domain.npm.InstallEvent): Map<String, Any?> = when (e) {
        is com.autoscript.domain.npm.InstallEvent.Progress -> mapOf(
            "seq" to seq, "type" to "progress",
            "projectId" to e.projectId, "handleId" to e.handleId,
            "phase" to phaseWire(e.phase), "name" to e.pkg, "percent" to e.percent,
        )
        is com.autoscript.domain.npm.InstallEvent.Warning -> mapOf(
            "seq" to seq, "type" to "warning",
            "projectId" to e.projectId, "handleId" to e.handleId,
            "kind" to kindWire(e.kind), "pkgs" to e.pkgs, "message" to e.message,
        )
        is com.autoscript.domain.npm.InstallEvent.Finished -> mapOf(
            "seq" to seq, "type" to "finished",
            "projectId" to e.projectId, "handleId" to e.handleId,
            "success" to e.success, "detail" to e.detail,
        )
    }

    /** 与 JS `InstallEvent['phase']` 联合逐字对齐（见 [encodeEvent] 的 KDoc）。 */
    internal fun phaseWire(p: com.autoscript.domain.npm.InstallEvent.Phase): String = when (p) {
        com.autoscript.domain.npm.InstallEvent.Phase.QUEUED -> "queued"
        com.autoscript.domain.npm.InstallEvent.Phase.RESOLVE -> "resolve"
        com.autoscript.domain.npm.InstallEvent.Phase.DOWNLOAD -> "download"
        com.autoscript.domain.npm.InstallEvent.Phase.REIFY -> "reify"
        com.autoscript.domain.npm.InstallEvent.Phase.POST_CHECK -> "post-check"
        com.autoscript.domain.npm.InstallEvent.Phase.DONE -> "done"
    }

    /** 与 JS `InstallWarning['kind']` 联合逐字对齐（feedWarning 认不出即抛，§10.5-3）。 */
    internal fun kindWire(k: com.autoscript.domain.npm.InstallEvent.Kind): String = when (k) {
        com.autoscript.domain.npm.InstallEvent.Kind.SCRIPTS_SKIPPED -> "scripts-skipped"
        com.autoscript.domain.npm.InstallEvent.Kind.TRUST_DOWNGRADED -> "trust-downgraded"
        com.autoscript.domain.npm.InstallEvent.Kind.LOW_MEMORY -> "low-memory"
        com.autoscript.domain.npm.InstallEvent.Kind.REGISTRY_FALLBACK -> "registry-fallback"
        com.autoscript.domain.npm.InstallEvent.Kind.DISK_QUOTA -> "disk-quota"
    }

    /** 与 JS `ApprovalRequest['action']` 联合逐字对齐。 */
    internal fun actionWire(a: com.autoscript.domain.npm.ApprovalAction): String = when (a) {
        com.autoscript.domain.npm.ApprovalAction.INSTALL_SCRIPT -> "install_script"
        com.autoscript.domain.npm.ApprovalAction.RUN_SCRIPT -> "run_script"
        com.autoscript.domain.npm.ApprovalAction.EXEC -> "exec"
    }

    private fun requirePayload(request: BridgeRequest): String =
        request.payload ?: throw IllegalArgumentException("npm.${request.method} 缺 payload")

    /** `name@version` 拆分：无 @ 或 @scope 名整体按裸名（version=null=latest）。 */
    private fun parseSpec(spec: String): Pair<String, String?> {
        val at = if (spec.startsWith("@")) spec.indexOf('@', 1) else spec.indexOf('@')
        return if (at <= 0 || at == spec.length - 1) spec to null
        else spec.substring(0, at) to spec.substring(at + 1)
    }
}
