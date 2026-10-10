package com.autoscript.appservice.npm

import com.autoscript.domain.core.ErrorCode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * `child_process` 拦截 shim 的落位与播报解析（docs §10.3 T0 末行 / §10.11 P0 / §10.12 末行）。
 *
 * **为什么它存在**（§10.12 末行那条「零 spawn 不变量漂移」）：`--ignore-scripts` 是主控，
 * 但它只挡 lifecycle 脚本；npm 自己新增一条 spawn 路径、或某包直接
 * `require('child_process')`，在本平台上的表现是**静默失败** —— 装完了但东西不对，
 * 而没有任何一处报错。本件把那条不变量变成**可判定**的：安装会话进程里
 * `child_process` 的七个入口全替换为抛错，非批准 spawn 一律
 * [com.autoscript.domain.core.ErrorCode.ERR_NPM_SPAWN_BLOCKED]。
 *
 * **为什么住本模块的 classpath 资源而不是随 npm 素材树**：素材树受
 * `npm-manifest.json` 逐件摘要对账（[NpmCliDeployer]），往里面塞一个宿主自己的文件会让
 * 「清单与 APK 文件集合不一致」**每次启动必红** —— 那是给别人的账本里记自己的账。
 * 本 shim 是宿主件，住 `filesDir/.autojs/`（宿主内务目录，与 journal / lock.sig 同处）。
 *
 * **为什么 shim 本体是 `.cjs` 资源而不是 Kotlin 字符串常量**：它是要被 Node 真加载的
 * 代码，写进 Kotlin 字符串里就没有语法高亮、没有 lint、diff 里是一坨转义；落成真文件
 * 还能被 `:app-service:npm` 的 JVM 单测**真跑一遍**（`NpmSpawnGateTest` 起真 node 验拦截），
 * 而不是只对着一份"我以为的 JS"断言。
 *
 * **失败即失败（fail closed，与批 78 删 jsonl 回落同一条口径）**：资源取不到或落盘失败
 * = [Deploy.Failed]，装配层据此**不注入安装执行体**（[NpmSpawnGate] 是 P0 承诺面，
 * 少一条防线不许静默降级成「装是能装、守卫没了」）。原因原文进
 * `AssembledShell.npmCliFailure`，不吞。
 *
 * **边界**：本 shim 是**不变量守卫**，不是安全边界（shim 自己 KDoc 里写死同一条）——
 * 已获批的脚本可以 `delete require.cache` 后重新 require 拿到未打补丁的模块。真正的
 * 对抗面是审批（§10.5-2）与 T1 会话的最小 CapabilityMask（§10.5-4）。
 */
object NpmSpawnGate {

    /** 落盘文件名（`filesDir/.autojs/` 下）。 */
    const val GATE_FILE_NAME: String = "npm-spawn-gate.cjs"

    /** classpath 资源路径（`src/main/resources/` 下，与包名同构）。 */
    const val RESOURCE_PATH: String = "/com/autoscript/appservice/npm/npm-spawn-gate.cjs"

    /** shim 打在 stderr 上的可识别前缀 —— 与 `npm-spawn-gate.cjs` 的 `MARKER` 逐字同值。 */
    const val MARKER: String = "[npm-spawn-gate]"

    /** 注入用的环境变量名（`NODE_OPTIONS` 对 `-e` / 脚本 / 子 node 都生效）。 */
    const val ENV_NODE_OPTIONS: String = "NODE_OPTIONS"

    /** 落位结果：要么给出可用路径，要么给出**原文**原因（不折叠成布尔）。 */
    sealed interface Deploy {
        data class Ready(val file: Path) : Deploy
        data class Failed(val reason: String) : Deploy
    }

    /** 门禁落点：`filesDir/.autojs/npm-spawn-gate.cjs`（宿主内务目录，见类 KDoc）。 */
    fun gateFile(filesDir: Path): Path = filesDir.resolve(".autojs").resolve(GATE_FILE_NAME)

    /**
     * 把 shim 落到 [gateFile]（字节一致则不动盘）。装配期调一次，不与运行期并发，故无锁；
     * 写入走临时文件 + 同目录原子 rename（半截 `.cjs` 被 `--require` 到是 SyntaxError，
     * 比缺文件难查得多 —— 与 [NpmCliDeployer] / `BridgeAddonDeploy` 同一手法）。
     */
    fun deploy(filesDir: Path): Deploy =
        deployResource(RESOURCE_PATH, gateFile(filesDir), "child_process 拦截 shim")

    /**
     * classpath 上的 `.cjs` 资源 → 落盘（**两个 shim 共用**：本门禁与 T1 桥，
     * 2026-10-10 批 91 自 [deploy] 提出）。
     *
     * 提出来的理由不是"少写几行"：两份 shim 的落位要求**逐字相同**（原子写、字节一致不动盘、
     * 空文件拒收、失败给原文），而其中任何一条漏在第二份上，症状都是「平时没事、某次更新后
     * Node 起不来」—— `--require` 到一个半截 `.cjs` 是 SyntaxError，比缺文件难查得多。
     * 两份各写一遍就迟早只改一份。
     *
     * @param what 失败原因里的人话主语（"child_process 拦截 shim" / "T1 桥 shim"）。
     */
    internal fun deployResource(resourcePath: String, target: Path, what: String): Deploy {
        val bytes = try {
            NpmSpawnGate::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
        } catch (t: Throwable) {
            return Deploy.Failed("$what 资源读取失败（$resourcePath）：${t.message}")
        } ?: return Deploy.Failed("$what 资源缺失（$resourcePath 不在 classpath 上）")
        // 空字节不落盘：0 字节 .cjs 被 --require 是 SyntaxError，把「没落上」变成「Node 起不来」。
        if (bytes.isEmpty()) return Deploy.Failed("$what 资源为空（$resourcePath）")

        return try {
            if (!Files.isRegularFile(target) || !Files.readAllBytes(target).contentEquals(bytes)) {
                writeAtomic(target, bytes)
            }
            Deploy.Ready(target)
        } catch (t: Throwable) {
            Deploy.Failed("$what 落盘失败（$target）：${t.message}")
        }
    }

    /**
     * 把 shim 的 `--require` 并进已有的 [ENV_NODE_OPTIONS]（**追加不覆盖**：父环境里那份
     * 是别人的设置，为装自己的守卫把别人抹掉是越权）。[gate] 为 null = 未装门禁，原样返回。
     */
    fun mergeNodeOptions(existing: String?, gate: Path): String {
        val flag = "--require=" + gate.toAbsolutePath()
        return if (existing.isNullOrBlank()) flag else existing.trim() + " " + flag
    }

    /** 门禁播报（从执行体输出里捞出来的那一条）。 */
    data class Blocked(val code: String, val detail: String)

    /**
     * 从安装会话的合并输出里提取门禁播报；没有则 null（= 这次失败与门禁无关，调用方按原样报）。
     *
     * 取**最后一条**：一次会话可能有多次拦截（npm 重试），杀死它的那条才是病因。
     * 行形状由 shim 写死：`[npm-spawn-gate] <CODE>: <detail>`。
     */
    fun blockedIn(output: String): Blocked? =
        output.lineSequence()
            .mapNotNull { parseLine(it) }
            .lastOrNull()

    /** 单行解析：不是门禁播报 / 形状不全（缺码缺详情）一律 null。 */
    private fun parseLine(raw: String): Blocked? {
        val line = raw.trim()
        if (!line.startsWith(MARKER)) return null
        val body = line.removePrefix(MARKER).trim()
        val colon = body.indexOf(": ")
        if (colon <= 0) return null
        val code = body.substring(0, colon).trim()
        val detail = body.substring(colon + 2).trim()
        if (code.isEmpty() || detail.isEmpty()) return null
        return Blocked(code, detail)
    }

    /**
     * 播报里的码 → 领域错误码。shim 只会发三个（见 `npm-spawn-gate.cjs` 的三个常量），
     * 认不出的码**折成** [ErrorCode.ERR_NPM_SPAWN_BLOCKED]：带 [MARKER] 前缀就一定是本
     * shim 发的，认不出只说明 shim 与这里漂了 —— 那时诚实的标题仍是「spawn 被拦了」，
     * 原文码由调用方放进 detail，不丢信息。
     */
    fun errorCodeOf(code: String): ErrorCode = when (code) {
        ErrorCode.ERR_PERMISSION_DENIED.code -> ErrorCode.ERR_PERMISSION_DENIED
        ErrorCode.ERR_NOT_IMPLEMENTED.code -> ErrorCode.ERR_NOT_IMPLEMENTED
        else -> ErrorCode.ERR_NPM_SPAWN_BLOCKED
    }

    /** 临时文件 + 同目录原子 rename（与 [NpmCliDeployer] 的落位手法同形，理由见 [deploy]）。 */
    private fun writeAtomic(target: Path, bytes: ByteArray) {
        val dir = target.parent
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".npm-spawn-gate-", ".tmp")
        try {
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            // 清临时文件本身失败不该掩盖真原因（同 NpmCliDeployer 的 finally 语义）。
            runCatching { Files.deleteIfExists(tmp) }
            throw t
        }
    }
}
