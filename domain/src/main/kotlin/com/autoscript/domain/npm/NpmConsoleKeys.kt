package com.autoscript.domain.npm

/**
 * 控制台命令行的**唯一一份**判据（docs §10.9 第 3 条「npm 终端视图」）。
 *
 * **为什么住 `:domain`**：判据有两个消费方 —— 宿主侧的执行入口（`InstallCoordinator`
 * 拿它决定这行能不能跑）与界面侧（`:ui` 的命令输入行，用户敲错要**当场**被告知，
 * 而不是往返一趟宿主才拿到同一句话）。抄两份必然漂，而漂的方向最坏：
 * 界面放行的行在宿主侧被拒，用户看到的是「执行失败」而不是「你这行写错了」。
 * 批 82 的 `ScriptEnvKeys`、批 83 的 `NpmRegistryKeys` 是同一条理由的先例。
 *
 * **本对象不依赖任何实现类**：白名单与拒收话术是**契约**，实现只能引用它。
 *
 * **只认 `npm` 与 `npx` 两个入口**（用户口径 2026-10-09：「不用加 sh 啊」）：
 * 本仓没有 shell，任意命令走 `auto.shell` 桥面（root/adb 三态门禁），那是脚本侧的面，
 * 与这里的命令面不是一回事。假装支持 `sh -c` 只会让「看起来能跑、实际没人守」
 * 的输入进来。
 */
object NpmConsoleKeys {

    /**
     * 允许的 npm 子命令（**白名单**：不在表内即拒，并把整张表念给用户听）。
     *
     * 为什么是白名单而不是黑名单：npm 有 60+ 个子命令，其中 `publish`/`login`/`token`/
     * `owner`/`config`/`init`/`link`/`cache` 这些要么改宿主全局状态、要么要凭据、
     * 要么在本平台上根本没有意义（§10.6 的轻/重操作拆分只管下面这几个）。
     * 黑名单漏一个就是一条没人守的路。
     */
    val SUBCOMMANDS: Set<String> = linkedSetOf(
        "install", "uninstall", "ci", "ls", "list", "prune", "dedupe", "audit",
    )

    /** 走**重操作**通道（安装会话，全局互斥 + 事务 + 落位）的子命令。其余是轻操作直读。 */
    val HEAVY_SUBCOMMANDS: Set<String> = linkedSetOf(
        "install", "uninstall", "ci", "prune", "dedupe",
    )

    /** 走**轻操作**通道（Kotlin 直读，零 Node 进程，§10.6）的子命令。 */
    val LIGHT_SUBCOMMANDS: Set<String> = linkedSetOf("ls", "list", "audit")

    /** `npm run` 的两种写法（npm 自己两个都认）。 */
    private val RUN_ALIASES = setOf("run", "run-script")

    /**
     * git 形态的依赖说明符（§10.3「明确不可行」：设备端无 git/编译器，**报可操作错误
     * 而非假成功**，引导本地 tarball 导入）。
     *
     * 这是**唯一一份**判据：`InstallCoordinator.install` 与 `runConsoleCommand` 都调它。
     */
    fun isGitSpec(spec: String): Boolean =
        spec.equals("git", ignoreCase = true) ||
            spec.startsWith("git:", ignoreCase = true) ||
            spec.startsWith("git@", ignoreCase = true) ||
            spec.startsWith("git+", ignoreCase = true) ||
            spec.startsWith("github:", ignoreCase = true) ||
            spec.startsWith("gitlab:", ignoreCase = true) ||
            spec.startsWith("bitbucket:", ignoreCase = true)

    /** 一行命令里的 git 依赖（null = 没有）。跳过 `-` 开头的旗标（`--registry` 等不是包名）。 */
    fun gitSpecIn(args: List<String>): String? =
        args.firstOrNull { !it.startsWith("-") && isGitSpec(it) }

    /**
     * 项目号合法性：合法 → null；不合法 → **拒收原文**。
     *
     * 判据在 [com.autoscript.domain.scripts.ScriptPaths.PROJECT_ID]（项目路径约定的
     * 单一事实来源），本函数只是把它翻成一句给用户看的话 —— 界面当场拒与落盘侧
     * `require` 因此同源。
     */
    fun rejectProjectId(projectId: String): String? =
        if (com.autoscript.domain.scripts.ScriptPaths.isValidProjectId(projectId)) null
        else "项目号不合法（只允许字母、数字、点、下划线、连字符）：$projectId"

    /**
     * 解析一行命令。
     *
     * **分词规则：按空白切分，不做 shell 引号解析。** 本仓没有 shell，假装支持引号
     * 只会让 `npm install "a b"` 这类输入产生「看起来对、实际是另一个包名」的结果 ——
     * 静默错比报错难查得多。规则写死在这里，界面与宿主读的是同一个结论。
     *
     * 认得的四种形态：
     * - `npm <sub> [args…]`，`sub` 在 [SUBCOMMANDS] 内；
     * - `npm run <script> [-- args…]`（`run-script` 同义）；
     * - `npx <bin> [args…]`；
     * - `npm exec <bin> [-- args…]`。
     *
     * 其余一律 [NpmConsoleCommand.Rejected]，理由**点名用户输入的那个串**。
     */
    fun parse(line: String): NpmConsoleCommand {
        val raw = line.trim()
        if (raw.isEmpty()) return NpmConsoleCommand.Rejected("命令为空：请写一行，例如 npm install axios")
        val t = raw.split(WHITESPACE)
        return when (t[0]) {
            "npm" -> parseNpm(raw, t)
            "npx" -> {
                val bin = t.getOrNull(1)
                    ?: return NpmConsoleCommand.Rejected("npx 后面要跟一个命令名：$raw（例如 npx esbuild --version）")
                NpmConsoleCommand.Exec(bin = bin, args = stripSeparator(t.drop(2)))
            }
            else -> NpmConsoleCommand.Rejected(
                "只认 npm 与 npx 两个入口（本平台没有 shell，任意命令走脚本的 auto.shell）：$raw",
            )
        }
    }

    private fun parseNpm(raw: String, t: List<String>): NpmConsoleCommand {
        val sub = t.getOrNull(1)
            ?: return NpmConsoleCommand.Rejected(
                "npm 后面要跟子命令：$raw（可用：${SUBCOMMANDS.joinToString(" / ")}）",
            )
        val rest = t.drop(2)
        if (sub in RUN_ALIASES) {
            val script = rest.firstOrNull()
                ?: return NpmConsoleCommand.Rejected(
                    "npm run 后面要跟脚本名：$raw（脚本名在项目 package.json 的 scripts 段）",
                )
            return NpmConsoleCommand.Run(script = script, args = stripSeparator(rest.drop(1)))
        }
        if (sub == "exec") {
            val bin = rest.firstOrNull()
                ?: return NpmConsoleCommand.Rejected(
                    "npm exec 后面要跟命令名：$raw（例如 npm exec esbuild -- --version）",
                )
            return NpmConsoleCommand.Exec(bin = bin, args = stripSeparator(rest.drop(1)))
        }
        if (sub !in SUBCOMMANDS) {
            return NpmConsoleCommand.Rejected(
                "子命令 $sub 不在控制台白名单内（可用：${SUBCOMMANDS.joinToString(" / ")}）：$raw",
            )
        }
        gitSpecIn(rest)?.let {
            return NpmConsoleCommand.Rejected(
                "git: 依赖不支持（$it）：设备端没有 git，请改用本地 tarball 导入（auto.npm.importTarball）",
            )
        }
        return NpmConsoleCommand.Npm(sub = sub, args = rest)
    }

    /** `--` 是 npm 的参数分隔符（`npm run foo -- --bar`），不是参数本身，剥掉。 */
    private fun stripSeparator(args: List<String>): List<String> =
        if (args.firstOrNull() == "--") args.drop(1) else args

    private val WHITESPACE = Regex("\\s+")
}

/**
 * 一行命令的解析结果（[NpmConsoleKeys.parse] 的返回）。
 *
 * `Rejected` 也在这条链上（而不是抛异常）：**界面与宿主读的是同一句话** ——
 * 界面侧当场显示它、宿主侧把它包成 `IllegalArgumentException` 抛出。
 * 两条路各写一份理由必然漂，而漂的那一份正好是用户看到的那一份。
 */
sealed interface NpmConsoleCommand {
    /** `npm <sub> [args…]`，`sub` 已在 [NpmConsoleKeys.SUBCOMMANDS] 内。 */
    data class Npm(val sub: String, val args: List<String>) : NpmConsoleCommand

    /** `npm run <script> [args…]` / `npm run-script <script>`。 */
    data class Run(val script: String, val args: List<String>) : NpmConsoleCommand

    /** `npx <bin> [args…]` / `npm exec <bin> [args…]`。 */
    data class Exec(val bin: String, val args: List<String>) : NpmConsoleCommand

    /** 拒收：`reason` 是给用户看的那句话（点名输入 + 说清为什么不收）。 */
    data class Rejected(val reason: String) : NpmConsoleCommand
}
