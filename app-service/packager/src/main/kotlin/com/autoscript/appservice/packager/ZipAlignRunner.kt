package com.autoscript.appservice.packager

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.nio.file.Files
import java.nio.file.Path

/**
 * `zipalign` 起进程（docs §14 P0 打包的独立 transform 步骤；[ApkRepacker] 诚实声明
 * "重写后不对齐"，对齐就发生在签名前的这里）。
 *
 * 参数表与进程缝与 [ApkSignerRunner] 同一惯例：可执行体前缀注入 + [ProcessLauncher]
 * 可替换 —— argv 形态、退出码、产物存在性在纯 JVM 上可闭环验证，不赌本机有没有二进制。
 *
 * 顺序铁律：**先对齐、再签名**（v2+ 签名覆盖整包字节，签完再动条目布局 = 验签必炸）。
 * 失败口径与 [ApkSignerRunner] 一致：非 0 退出码 / 没产出文件都抛 [AutojsException]，
 * 不把"zipalign 说不行"翻译成成功。
 *
 * `argv` 形态（对 build-tools 34 的 `zipalign` Usage 行逐字钉死）：
 * `<cmd> -f -p <align> <infile> <outfile>` —— `-f` 覆盖已存在输出，`-p` 页对齐未压缩
 * `.so`（模板带 `libnode.so`，缺页对齐在 16KB 页设备上 mmap 直接炸；`<align>` 缺省 4 =
 * 官方 32 位对齐值）。输出是**位置参数**（最后一个），没有 `--out` 这种选项。
 */
class ZipAlignRunner(
    /** 可执行体前缀，如 `["/path/zipalign"]`。 */
    private val zipalignCommand: List<String>,
    private val launcher: ProcessLauncher = ProcessBuilderLauncher(),
    /** 额外环境（PATH/HOME 之类）；zipalign 无口令，不存在 env 注入面。 */
    private val baseEnv: Map<String, String> = emptyMap(),
    /** 对齐字节数（4 = zipalign 官方示例值 / 32 位对齐）。 */
    private val alignment: Int = 4,
) {
    init {
        require(zipalignCommand.isNotEmpty()) { "zipalignCommand 不得为空" }
        require(alignment > 0) { "alignment 必须 > 0，实际 $alignment" }
    }

    /** 对齐 [input] 到 [output]；返回 [output]（存在性已验）。 */
    fun align(input: Path, output: Path): Path {
        val args = listOf("-f", "-p", alignment.toString(), input.toString(), output.toString())
        val result = launcher.run(zipalignCommand + args, baseEnv)
        if (result.exitCode != 0) {
            throw AutojsException(
                ErrorCode.ERR_IO,
                "zipalign 退出码 ${result.exitCode}：${result.output.trim().ifEmpty { "(无输出)" }}",
            )
        }
        if (!Files.isRegularFile(output)) {
            throw AutojsException(ErrorCode.ERR_IO, "zipalign 报成功但没产出对齐包：$output")
        }
        return output
    }
}
