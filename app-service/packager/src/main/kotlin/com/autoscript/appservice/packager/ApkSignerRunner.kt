package com.autoscript.appservice.packager

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.packager.ApkSignerArgs
import com.autoscript.domain.packager.SignRequest
import java.nio.file.Files
import java.nio.file.Path

/** 一次外部命令的结果：退出码 + 合并后的 stdout/stderr（失败时进错误详情，便于诊断）。 */
data class ProcessResult(val exitCode: Int, val output: String)

/**
 * 起进程的缝（对齐 [HostNodeExecutor] 的可注入惯例）：
 * 生产走 [ProcessBuilderLauncher]，测试注入记录型实现或假可执行体 ——
 * 于是**参数表 → 环境变量 → 退出码 → 产物存在性**这条链路在纯 JVM 上可闭环验证，
 * 不必真等到有 apksigner 的环境。
 */
fun interface ProcessLauncher {
    fun run(command: List<String>, env: Map<String, String>): ProcessResult
}

/** 真起进程：`redirectErrorStream(true)` 让失败输出完整进 [ProcessResult.output]。 */
class ProcessBuilderLauncher : ProcessLauncher {

    override fun run(command: List<String>, env: Map<String, String>): ProcessResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment().putAll(env) }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        return ProcessResult(exit, output)
    }
}

/**
 * apksigner 调用（docs §14 P0「签名向导」的**起进程**那一段）。
 *
 * 参数表来自领域层 [ApkSignerArgs]（已绑定 SignPlans 的计划摘要复验），本类只负责：
 * 拼可执行体前缀 → 注入口令环境变量 → 起进程 → **失败即失败**（非 0 退出码 / 没产出
 * 签名包都抛 [AutojsException]，不把"apksigner 说不行"翻译成成功）。
 *
 * 口令只进环境变量、不进 argv —— `ps` 看不到（[ApkSignerArgs] 侧同样不接受明文口令参数）。
 */
class ApkSignerRunner(
    /** 可执行体前缀，如 `["/path/apksigner"]` 或 `["java","-jar","/path/apksigner.jar"]`。 */
    private val apksignerCommand: List<String>,
    private val launcher: ProcessLauncher = ProcessBuilderLauncher(),
    /** 额外环境（PATH/HOME 之类）；口令两个变量由 [sign] 按参数注入，不在此处混放。 */
    private val baseEnv: Map<String, String> = emptyMap(),
) {
    init {
        require(apksignerCommand.isNotEmpty()) { "apksignerCommand 不得为空" }
    }

    /**
     * 签 [unsignedApk] 到 [signedApk]。
     * [request] 必须已过 `SignPlans.verify`（调用方在改写计划确定后组装）。
     */
    fun sign(
        request: SignRequest,
        unsignedApk: Path,
        signedApk: Path,
        keystorePath: String,
        ksPass: String,
        keyPass: String,
    ): Path {
        val args = ApkSignerArgs.build(
            request,
            unsignedApk.toString(),
            signedApk.toString(),
            keystorePath,
        )
        val command = apksignerCommand + args
        val env = baseEnv + mapOf(
            ApkSignerArgs.KS_PASS_ENV to ksPass,
            ApkSignerArgs.KEY_PASS_ENV to keyPass,
        )
        val result = launcher.run(command, env)
        if (result.exitCode != 0) {
            throw AutojsException(
                ErrorCode.ERR_IO,
                "apksigner 退出码 ${result.exitCode}：${result.output.trim().ifEmpty { "(无输出)" }}",
            )
        }
        if (!Files.isRegularFile(signedApk)) {
            throw AutojsException(ErrorCode.ERR_IO, "apksigner 报成功但没产出签名包：$signedApk")
        }
        return signedApk
    }
}
