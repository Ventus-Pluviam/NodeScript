package com.autoscript.shell

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/**
 * `power_manager` 命名空间桥处理器（docs §8.7 保活与电源；JS `auto.power` 的 Kotlin 对偶）。
 *
 * 归属：住 `:app` 装配包（与 `WorkManagerNamespaceHandler` 同形）——它直驱本包自建的
 * [WakeLockLedger]（与框架保活同一本账），真实现不在 `:platform`，故**恒备、可注入**：
 * `AppShell.assemble` 经独立缝 `powerManagerHandler` 挂载（与 datastore/zip/settings/
 * notification 同一形态的独立缝 —— 电源面无共担门禁，`WAKE_LOCK` 是安装时授予的
 * normal 权限，判定在账本与系统侧，不入 [SystemHandlers] 束）。
 *
 * 线格式（与 `bridge/js` power.ts 逐字段对齐）：
 * - `acquire`：`{timeoutMillis}` → Ok `{"token":"script-…"}`。
 *   token **服务端分配**（`script-` 前缀 + 随机段）：脚本自带 token 会互撞/互释，
 *   分配制下释放只能放自己那一份（见 [WakeLockLedger.release] 幂等语义）；
 * - `release`：`{token}` → Ok `true`/`false` 裸布尔（false = 该 token 当时并未持有，
 *   如实回而不是假成功 —— 调用方据此发现账目错位，与 `workManager.cancel` 的 ghost-id
 *   照样 true 不同：取消是"它已经不在了"的幂等，释放是"我以为我持着"的对账）；
 * - `status`：无参 → Ok `{"held":…,"holders":…}`（`held` = 门禁读的
 *   [WakeLockLedger.isHeld]，`holders` = 账本 token 数；两者分歧时 held=false 而
 *   holders>0 —— 正是"锁被外部放掉"的如实表达，不折叠成一个布尔）；
 * - 未知方法 → ERR_NOT_IMPLEMENTED；非法载荷 → ERR_INVALID_PARAM。
 *
 * 三条诚实纪律：
 * - **脚本锁必须限时**：`timeoutMillis` 必填且 > 0 —— 无期限只属框架 token
 *   （[ForegroundKeeper.FRAMEWORK_TOKEN]），脚本无期限等于"卡死的持有方让 CPU
 *   永远不休眠"，正是 [WakeLockLedger] 超时自动释放要防的那条（§8.7 安全属性）；
 * - **直驱账本，不走 `ForegroundKeeper.start(token)`**：那个单槽只属框架
 *   （调两次会互踩 `frameworkToken`，框架 stop 会误放脚本的锁）—— 脚本侧持有方
 *   只进 [WakeLockLedger]，框架 stop 只放框架自己的那一份，两者引用计数共存；
 * - **取不到锁如实 ERR_SERVICE_DISABLED**（无 PowerManager / 系统拒绝 ——
 *   [WakeLockOps.acquire] 的 false 形状）：此时**未记账**，门禁据此拒绝 SCREEN_ON，
 *   而不是"以为有锁、跑在会休眠的 CPU 上"。
 *
 * FGS 补拉：`acquire` 成功后调一次 [ForegroundKeeper.renew] —— 生产路径框架保活
 * 常转（ticker 本来就在扫到期/补服务），这次调用只为兜"keeper 从未 start / 服务被
 * ROM 杀掉但进程还活着"的冷沿：renew 幂等（无动作即空列表），不记新账。
 */
class PowerManagerNamespaceHandler(
    private val ledger: WakeLockLedger,
    private val keepalive: ForegroundKeeper? = null,
) {

    suspend fun handle(request: BridgeRequest): BridgeResponse = try {
        BridgeResponse.Ok(request.id, dispatch(request))
    } catch (e: AutojsException) {
        BridgeResponse.Err(request.id, e.error.code, e.message)
    } catch (e: IllegalArgumentException) {
        BridgeResponse.Err(request.id, ErrorCode.ERR_INVALID_PARAM.code, e.message)
    }

    /** 挂载为桥 NamespaceHandler（`AppShell.assemble` 经独立缝直接挂，不经注入束）。 */
    fun mount(): NamespaceHandler = NamespaceHandler { req -> handle(req) }

    private fun dispatch(request: BridgeRequest): String? {
        return when (request.method) {
            "acquire" -> acquire(WmJson.decodeObject(requirePayload(request)))
            "release" -> release(WmJson.decodeObject(requirePayload(request)))
            "status" -> status()
            else -> throw AutojsException(
                ErrorCode.ERR_NOT_IMPLEMENTED, "未知 power_manager 方法: ${request.method}",
            )
        }
    }

    private fun acquire(f: Map<String, WmJson.Value>): String {
        val timeoutMillis = (f["timeoutMillis"] as? WmJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("acquire 需要正整数 timeoutMillis（脚本锁必须限时，无期限只属框架）")
        if (timeoutMillis <= 0) {
            throw IllegalArgumentException("timeoutMillis 必须 > 0（0/负数等于要求立刻过期，疑似漏配），实际 $timeoutMillis")
        }
        val token = "script-" + java.util.UUID.randomUUID().toString().substring(0, 8)
        if (!ledger.hold(token, timeoutMillis)) {
            throw AutojsException(
                ErrorCode.ERR_SERVICE_DISABLED,
                "唤醒锁取不到（无 PowerManager / 系统拒绝）：未记账，SCREEN_ON 任务将被如实拒绝",
            )
        }
        // 补拉/收敛幂等：生产 ticker 常转时本调用无动作；冷沿时把服务补起来。
        // 返回的动作只作诊断 —— acquire 的成败只由上面的 hold 决定。
        keepalive?.renew()
        return WmJson.encode(mapOf("token" to token))
    }

    private fun release(f: Map<String, WmJson.Value>): String {
        val token = WmJson.reqStr(f, "token").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("token 不得为空白（空白 token 找不到持有方）")
        return ledger.release(token).toString()
    }

    private fun status(): String = WmJson.encode(
        mapOf("held" to ledger.isHeld(), "holders" to ledger.heldTokens().size),
    )

    private fun requirePayload(request: BridgeRequest): String =
        request.payload ?: throw IllegalArgumentException("${request.method} 需要 payload 对象")
}
