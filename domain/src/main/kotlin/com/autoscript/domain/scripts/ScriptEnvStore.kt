package com.autoscript.domain.scripts

/**
 * 脚本环境变量的领域契约（docs §8.1「脚本执行环境变量」）。
 *
 * 这是管理面板「环境变量」那一行底下的面：用户在界面里编一组全局 KV，
 * **每次脚本执行时注入到脚本进程的 `process.env`**。
 *
 * **为什么住 `:domain`**：注入方是 `:engine:node-process`（它已依赖 `:domain` 并实现
 * [com.autoscript.domain.engine.ScriptEngine]），读/写方是 `:app` 装配层与 `:ui`
 * 呈现层 —— 三处都要拿到同一份类型与同一份键名校验。放任何一个实现模块都会让另两处
 * 反向依赖它。`:domain` 零文件 IO（本包无一处 `java.nio.file`），故**接口在此、实现在
 * `:app-service:script-repo`**（[ScriptEnvStore] 与 [IntentStore] 同形）。
 *
 * **全局一份，不按项目分级**（2026-10-09 裁定）：这是"环境"而不是"项目配置"，
 * 用户口径就是全局。要按项目分级得先有项目级元数据面（今天没有）。
 */
interface ScriptEnvStore {

    /**
     * 当前全部条目，**按 key 升序**（排序归本层，呈现层不再排 —— 与
     * [com.autoscript.domain.host.ScriptFilesSnapshot] 的「读数与排序分家」相反，
     * 那条分家的理由是"排序档是用户现选的呈现偏好"，这里没有那种偏好）。
     *
     * 空列表 = 真的没设过任何变量，不是"读不到"。读不到由实现**抛**。
     */
    fun all(): List<ScriptEnvEntry>

    /** 写入/覆盖一条（后写胜）。[key] 不过 [ScriptEnvKeys.reject] 时**抛**。 */
    fun put(key: String, value: String)

    /**
     * 删除一条。**幂等**：从未设过的 key 照样返回（与
     * [com.autoscript.domain.host.HostSummary.cancelTask] 同口径 —— 重复删无副作用，
     * 静默吞掉"删了个不存在的"比报错更难查的是**相反**那种：删了却还在）。
     */
    fun remove(key: String)
}

/**
 * 一条脚本环境变量。
 *
 * @property key 变量名。**不可能是空串**（[ScriptEnvKeys.reject] 在写入侧拦掉了），
 *   但读侧不假设 —— 实现 replay 的是盘上已有的行，而盘上的行可能来自更宽松的旧版本。
 * @property value 变量值。**空串是合法值**，与"这个键没设"是两回事：脚本里
 *   `process.env.FOO === ''` 与 `process.env.FOO === undefined` 分得开，本层不得把
 *   两者折叠（与 `:domain` `StoredEntry.Json` 的「存 JSON `null` ≠ 键缺失」同一条纪律）。
 */
data class ScriptEnvEntry(val key: String, val value: String)

/**
 * 变量名校验的**唯一一份**（`:ui` 的即时校验与 `:app` 的写入闸门共用，不抄两份 ——
 * 抄两份必漂移，而漂移的后果是"界面放行、写入拒绝"或更糟的"界面拒绝、写入放行"）。
 *
 * 为什么必须有保留前缀这一条：宿主自己往脚本进程的 env 里塞了一批 `AUTOSCRIPT_*` 键
 * （见 `NodeProcessEngine` 的 env 合同：libnode/addon/socket/dist/runId/runNonce/token）。
 * 用户覆盖它们的后果不是"设了个没用"而是**真实的越权面**：
 * - `AUTOSCRIPT_HOST_SOCKET` 被改 → 脚本去连别的 socket（伪造/劫持桥身份）；
 * - `AUTOSCRIPT_BRIDGE_TOKEN` 被改 → 自断桥认证（`main.cpp` 用它做 hello/ACK，
 *   JS 侧 `bootstrap.ts` 认证成功后还专门 `delete process.env.AUTOSCRIPT_BRIDGE_TOKEN`
 *   —— 这条 `delete` 就是"这个键是活的、不是给人配的"的实证）。
 *
 * **两道闸**：写入侧拒（用户当场看到原因，不静默），spawn 侧宿主键再覆盖一次
 * （盘上若已有历史脏行，或将来有人绕过本闸直写 store，注入顺序仍保证宿主键胜出）。
 */
object ScriptEnvKeys {

    /** 宿主保留前缀。写入侧一律拒收，注入侧一律胜出。 */
    const val RESERVED_PREFIX: String = "AUTOSCRIPT_"

    /**
     * 校验一个变量名。
     *
     * @return null = 合法；否则是**拒收原文**（点名哪个键、为什么）——
     *   调用方原样交给用户，不加工成"参数非法"那种读不出所以然的措辞。
     */
    fun reject(key: String): String? {
        if (key.isEmpty()) return "变量名不得为空"
        if (key.startsWith(RESERVED_PREFIX)) {
            return "变量名不得以 $RESERVED_PREFIX 开头（宿主保留前缀，用于引擎与桥的内部约定）：$key"
        }
        if (key.contains('=')) return "变量名不得含 '='（那是 KEY=VALUE 的分隔符）：$key"
        if (key.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            return "变量名不得含 NUL 或换行：${key.replace("\n", "\\n").replace("\r", "\\r")}"
        }
        return null
    }
}
