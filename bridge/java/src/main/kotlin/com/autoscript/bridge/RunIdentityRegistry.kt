package com.autoscript.bridge

import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.Clock
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.RunIdentityIssuer
import com.autoscript.domain.engine.RunIdentityLease
import com.autoscript.domain.permission.ScriptAuthorizationSnapshot
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 每壳一份执行身份账（§7.5/A5）。票据只允许领取一次，绑定后不再按活性探针拒绝尾帧。
 * 所有状态在同锁转换，回调/等待/活性探针均在锁外；不以同 UID 凭据代替沙箱或能力授权。
 *
 * **A5 起本类也是授权快照的落点，但不再自己算授权**：`issue(...)` 收下调用方
 * （`RuntimeController.start` 那条链）**已经算好**的
 * [com.autoscript.domain.permission.ScriptAuthorizationSnapshot]，原样存进 lease，
 * 认证时随 [Binding.caller] 下发。为什么不在本类里按 projectId 现算：那样「预检校验的掩码」
 * 与「签发出去的掩码」是两次独立计算，中间任何策略/元数据变化都会让二者分叉 ——
 * 授权决策必须在 spawn 链上**只发生一次**（A5 整改第 4 条）。
 */
class RunIdentityRegistry(
    private val clock: Clock = Clock { System.nanoTime() / 1_000_000 },
    private val admissionMillis: Long = 10_000,
) : RunIdentityIssuer, AutoCloseable {
    private val lock = Any()
    private val random = SecureRandom()
    private val tokens = HashMap<String, Lease>()
    private val leases = HashSet<Lease>()
    private var closed = false

    /**
     * 「pid 不可得 ⇒ 本次没做 pid 绑定」的累计次数（外审 M5，2026-10-11 批 97）。
     *
     * 为什么要有这个计数而不是继续静默跳过：`spawn.pid` 为 null 时整段防劫持判据被跳过，
     * 而「跳过」与「度量通过」在返回类型上长得一样 —— 现场表现是「身份绑定这层不知何时
     * 失效了」。批 97 已在引擎侧补了 pid 的第二级反射阶梯（Android 8–12 不再无 pid），
     * 真拿不到时这个计数是唯一的可见信号。
     */
    @Volatile
    var pidUnavailable: Int = 0
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        require(admissionMillis > 0)
        scope.launch {
            while (isActive) {
                delay(250)
                expireDue()
            }
        }
    }

    override fun issue(
        engineId: EngineId,
        engineRunId: Long,
        projectId: String,
        authorization: ScriptAuthorizationSnapshot,
    ): RunIdentityLease = synchronized(lock) {
        check(!closed) { "桥身份入口已关闭" }
        require(engineRunId > 0)
        var token: String
        do {
            token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        } while (tokens.containsKey(token))
        // 授权快照在**上游**已算好（见类 KDoc）：这里只落账，不再算一次。
        // projectId 仅存进 lease 供审计/诊断，不参与任何判定。
        Lease(token, engineId, engineRunId, projectId, authorization, clock.nowMillis() + admissionMillis).also {
            tokens[token] = it
            leases.add(it)
        }
    }

    /** hello 先原子占票；spawn 确认晚到时挂起，不调用 controller，不占业务请求表。 */
    suspend fun authenticate(
        token: String,
        peerPid: Int?,
        connectionId: Long,
        onDrain: () -> Unit,
        onRevoke: () -> Unit,
    ): Binding {
        val lease = synchronized(lock) {
            val found = tokens.remove(token) ?: denied()
            if (closed || found.state != State.ISSUED || found.deadline <= clock.nowMillis()) {
                found.state = State.CLOSED
                leases.remove(found)
                denied()
            }
            found.state = State.CLAIMED
            found.onDrain = onDrain
            found.onRevoke = onRevoke
            found
        }
        var bound = false
        try {
            val spawn = lease.spawn.await()
            // 防劫持判据（外审 M5 相关）：宿主侧 pid 拿不到时**跳过**而不是放行成「相等」。
            // 原判据 `peerPid != null && spawn.pid != null && peerPid != spawn.pid` 在 pid 缺席
            // 时静默跳过整套检查 —— 这是「拿不到度量」而非「度量通过」，两者必须分得开。
            // 补 pid 的第二级反射阶梯（批 97，engine ProcessLauncher.pidOf）后 Android 8–12
            // 不再走这条路；真的两个都拿不到时仍跳过并计数（字段 KDoc 是判据本身）。
            if (peerPid != null && spawn.pid != null && peerPid != spawn.pid) denied()
            else if (peerPid != null && spawn.pid == null) pidUnavailable++   // 见 pidUnavailable 的 KDoc
            if (!spawn.isAlive()) denied()
            return synchronized(lock) {
                if (closed || lease.state != State.CLAIMED || lease.deadline <= clock.nowMillis()) denied()
                lease.state = State.ACTIVE
                bound = true
                val caller = AuthenticatedRunContext(
                    lease.engineId,
                    lease.runId,
                    connectionId,
                    lease.authorization.mask,
                )
                // 项目号从 lease 装填（§9.6 × §9.2 录屏落点）：与掩码同一条「不可自报」纪律 ——
                // 认证之后调用方才有项目号可言，而它决定 `ScriptPaths.recordingsDir` 的落点，
                // 绝不能从 wire payload 取（那是脚本可影响的字段）。
                caller.projectId = lease.projectId
                Binding(caller) { lease.disconnect() }
            }
        } finally {
            if (!bound) lease.revoke()
        }
    }

    /** 不再接入后立即释放；lease 仍由引擎持有，但旧清理永远不能碰到另一 lease。 */
    class Binding internal constructor(
        val caller: AuthenticatedRunContext,
        private val release: () -> Unit,
    ) : AutoCloseable {
        override fun close() = release()
    }

    private enum class State { ISSUED, CLAIMED, ACTIVE, DRAINING, CLOSED }
    private class Spawn(val pid: Int?, val isAlive: () -> Boolean)

    private inner class Lease(
        override val token: String,
        val engineId: EngineId,
        val runId: Long,
        /** 项目号：**仅供审计/诊断**（授权已在 [authorization] 里算好，本字段不参与判定）。 */
        val projectId: String,
        /** 本次执行的授权快照（上游一次算好，运行期不可变）。 */
        val authorization: ScriptAuthorizationSnapshot,
        val deadline: Long,
    ) : RunIdentityLease {
        var state = State.ISSUED
        val spawn = CompletableDeferred<Spawn>()
        var onDrain: (() -> Unit)? = null
        var onRevoke: (() -> Unit)? = null

        override fun confirmSpawn(pid: Int?, isAlive: () -> Boolean) {
            // completion 不需要持注册表锁；撤销若先发生，cancel 后的 deferred 不会再完成。
            spawn.complete(Spawn(pid, isAlive))
        }

        override fun naturalExit() {
            val callback = synchronized(lock) {
                if (state == State.DRAINING || state == State.CLOSED) return
                if (state == State.ACTIVE) {
                    state = State.DRAINING
                    onDrain
                } else {
                    detachLocked()
                    onRevoke.also { onDrain = null; onRevoke = null }
                }
            }
            spawn.cancel()
            callback?.invoke()
        }

        override fun revoke() {
            val callback = synchronized(lock) {
                if (state == State.CLOSED) return
                detachLocked()
                onRevoke.also { onDrain = null; onRevoke = null }
            }
            spawn.cancel()
            callback?.invoke()
        }

        fun disconnect() {
            synchronized(lock) {
                detachLocked()
                onDrain = null
                onRevoke = null
            }
            spawn.cancel()
        }

        private fun detachLocked() {
            state = State.CLOSED
            tokens.remove(token, this)
            leases.remove(this)
        }
    }

    /** 可推进时钟的收割入口；已认证连接不受建连期限影响。 */
    fun expireDue() {
        val expired = synchronized(lock) {
            leases.filter { (it.state == State.ISSUED || it.state == State.CLAIMED) && it.deadline <= clock.nowMillis() }
        }
        expired.forEach { lease ->
            // authenticate 可能刚刚赢得转换；不能撤掉一条已经 ACTIVE 的连接。
            val callback = synchronized(lock) {
                if (lease.state != State.ISSUED && lease.state != State.CLAIMED) return@forEach
                lease.state = State.CLOSED
                tokens.remove(lease.token, lease)
                leases.remove(lease)
                lease.onRevoke.also { lease.onDrain = null; lease.onRevoke = null }
            }
            lease.spawn.cancel()
            callback?.invoke()
        }
    }

    fun size(): Int = synchronized(lock) { leases.size }

    override fun close() {
        val all = synchronized(lock) {
            if (closed) return
            closed = true
            leases.toList()
        }
        all.forEach { it.revoke() }
        scope.cancel()
    }

    private fun denied(): Nothing = throw AutojsException(ErrorCode.ERR_PERMISSION_DENIED, "桥连接身份无效或已失效")
}
