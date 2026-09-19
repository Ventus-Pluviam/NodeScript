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
 * - `install`：`{spec,save?,offline?,timeout?}` → `true`（InstallHandle 语义：排队即返回；
 *   进度走 events 流，脚本用 onProgress 收）——返回体故意不含 result 明细：
 *   InstallCoordinator 的 install 是 enqueue+inline 执行，真实结果经 Finished 事件给；
 * - `remove`：`{spec}` → 无参；
 * - `ci`：`{offline?}` → 无参；
 * - `list`：`{depth?}` → `[PkgNodeJson...]`（轻操作直读 lockfile）；
 * - `prune`/`dedupe`：无参；
 * - `offlineGap`：无参 → `[{name,version,size}]`；
 * - `audit`：`{offline?}` → `{vulnerabilities:[],level,offline}`（P0 诚实空报告）；
 * - `setRegistry`：`{registry,scope?}` → 无参（scope→npmrc `<scope>:registry`，§10.2 三层注册表配置；经 :main 可配列表 + 审计）；
 * - `importOfflineBundle`/`importTarball`：`{uri}`/`{path}`；
 * - `requestApprove`：`{pkg,versionHash?,action?}` → 票 JSON（**只入队**；脚本绝无 resolve 权）。
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
                facade.install(
                    projectId,
                    listOf(com.autoscript.domain.npm.PackageSpec(name, version)),
                    com.autoscript.domain.npm.InstallFlags(offline = offline, save = save, timeoutMillis = timeout),
                )
                "true"
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
                NpmBridgeJson.encode(facade.list(projectId, depth).map {
                    mapOf("name" to it.name, "version" to it.version, "sizeBytes" to it.sizeBytes)
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
                        "vulnerabilities" to r.vulnerabilities.map {
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
            "requestApprove" -> {
                val pkg = NpmBridgeJson.reqStr(f, "pkg")
                val versionHash = NpmBridgeJson.optStr(f, "versionHash") ?: ""
                val action = when (NpmBridgeJson.optStr(f, "action")?.lowercase()) {
                    "run_script", "runscript" -> com.autoscript.domain.npm.ApprovalAction.RUN_SCRIPT
                    "exec" -> com.autoscript.domain.npm.ApprovalAction.EXEC
                    else -> com.autoscript.domain.npm.ApprovalAction.INSTALL_SCRIPT
                }
                val t = facade.requestApprove(projectId, pkg, versionHash, action)
                NpmBridgeJson.encode(mapOf("requestId" to t.requestId, "status" to t.status.name.lowercase()))
            }
            else -> throw com.autoscript.domain.core.AutojsException(
                ErrorCode.ERR_NOT_IMPLEMENTED,
                "未知 npm 方法: ${request.method}",
            )
        }
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
