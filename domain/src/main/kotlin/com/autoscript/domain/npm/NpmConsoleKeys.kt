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
 * **入口**（2026-10-09 二次裁定，用户口径：控制台要能执行 shell）：`npm` / `npx` 走
 * npm 命令面；`su` / `shizuku` 走 **shell 命令面**并进入对应的特权模式；`exit` 退出特权
 * 模式。默认（未进特权模式）的裸首词仍按 **npm bin** 解析 —— 那是「装好的依赖提供的
 * 命令」那条路（`tsc` / `eslint` / `prettier`），与 shell 面不共用判据。
 *
 * **特权模式改的是「裸首词是什么意思」**：进了 `su`/`shizuku` 之后，裸首词一律当
 * **shell 命令**（`ls -la` 就是 `ls -la`），而不是 npm bin —— 因为在特权模式里敲
 * `ls` 的人要的是 shell 的 `ls`，不是某个恰好叫 `ls` 的包。要跑 bin 请用 `npx <bin>`。
 *
 * **为什么必须有模式而不是「一律自动挑一个」**：root 与 Shizuku 是两条**不同身份**的
 * 通道（root uid vs shell uid），能做的事不同、留下的痕迹不同、失败话术也不同。
 * 静默替用户挑一条 = 让「我以为我在用 root」和「实际用的是 shell」不可分辨 ——
 * 与 §9.3「三通道必须显式指定」是同一条纪律。
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
     * 一行命令里的包说明符（`name[@range]`），供宿主侧的装前预检用。
     *
     * 切分点取**最后一个** `@` 且不在首位 —— 首位那个是 scope 的（`@acme/pkg`），
     * 切了会把 `@acme/pkg` 读成「空名字 + 范围 acme/pkg」。
     *
     * **已知边界（如实写在契约里，不假装没有）**：不处理「旗标带值」的形态 ——
     * `npm install --registry https://x axios` 里的那个 URL 会被当成包名。真 argv 是
     * **原样透传**给 npm 的（装的东西一点没错），受影响的只有装前的多镜像交叉校验
     * （它会拿一个不存在的包名去问，结论是「未校验」而不是「不一致」—— 不会误拦）。
     * 要修就得重写 npm 的参数文法，而半吊子重写正是「看起来对、实际是另一个包」
     * 这类静默错的来源，与 [parse] 拒绝 shell 引号解析是同一条理由。
     */
    fun packageSpecsIn(args: List<String>): List<PackageSpec> =
        args.filter { !it.startsWith("-") }.map { token ->
            val at = token.lastIndexOf('@')
            if (at > 0) PackageSpec(token.substring(0, at), token.substring(at + 1)) else PackageSpec(token)
        }

    /**
     * 项目号合法性：合法 → null；不合法 → **拒收原文**。
     *
     * 判据在 [com.autoscript.domain.scripts.ScriptPaths.PROJECT_ID]（项目路径约定的
     * 单一事实来源），本函数只是把它翻成一句给用户看的话 —— 界面当场拒与落盘侧
     * `require` 因此同源。
     *
     * 话术里**没有「点」**：`ScriptPaths.PROJECT_ID` 于 2026-10-09 批 84 收掉了 `.`
     * （它放行 `..`，而 `projectsRoot.resolve("..")` 正好跳出项目根）。话术跟着判据走，
     * 否则用户按话术写一个 `a.b` 却被拒 —— 那比不写还坏。
     */
    fun rejectProjectId(projectId: String): String? =
        if (com.autoscript.domain.scripts.ScriptPaths.isValidProjectId(projectId)) null
        else "项目号不合法（只允许字母、数字、下划线、连字符）：$projectId"

    /** 进入特权模式的入口词（用户口径 2026-10-09：`su` = root，`shizuku` = adb）。 */
    const val ENTER_ROOT = "su"
    const val ENTER_ADB = "shizuku"

    /** 退出特权模式回到默认。 */
    const val EXIT_MODE = "exit"

    /**
     * 解析一行命令。
     *
     * **分词规则：按空白切分，不做 shell 引号解析。** 认得的形态见类 KDoc；其余一律
     * [NpmConsoleCommand.Rejected]，理由**点名用户输入的那个串**。
     *
     * [mode] 是控制台**此刻**的特权模式（默认 [ShellConsoleMode.DEFAULT]）。它只改一件事：
     * **裸首词是什么意思** —— 默认模式下裸首词 = npm bin（[NpmConsoleCommand.Exec]），
     * 特权模式下裸首词 = shell 命令（[NpmConsoleCommand.Shell]）。`npm`/`npx`/`su`/
     * `shizuku`/`exit` 五个入口词**在任何模式下都优先**（否则进了 root 模式就再也退不出来
     * —— 那五个词会被当成 shell 命令发给 `/system/bin/sh`）。
     *
     * **shell 命令的正文原样透传**（`t.drop(1).joinToString(" ")` 而不是重新分词）：
     * shell 要的是它自己的分词与引号规则，宿主在这里切一遍再拼回去只会把
     * `echo "a  b"` 的双空格吃掉 —— 那是**改用户的命令**，比报错难查得多。
     */
    fun parse(line: String, mode: ShellConsoleMode = ShellConsoleMode.DEFAULT): NpmConsoleCommand {
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
            ENTER_ROOT -> modeEntry(raw, t, ShellConsoleMode.ROOT)
            ENTER_ADB -> modeEntry(raw, t, ShellConsoleMode.ADB)
            EXIT_MODE -> if (t.size == 1) NpmConsoleCommand.ExitMode
            else NpmConsoleCommand.Rejected("exit 不带参数（它只用来退出 su/shizuku 特权模式）：$raw")
            else -> if (mode == ShellConsoleMode.DEFAULT) {
                // 裸首词 = npm bin（装好的依赖提供的命令）。存在性由宿主查盘后答
                //（判据这里看不到文件系统），查不到时宿主给的是带 su/shizuku 指路的话术。
                NpmConsoleCommand.Exec(bin = t[0], args = t.drop(1))
            } else {
                NpmConsoleCommand.Shell(command = raw, mode = mode)
            }
        }
    }

    /**
     * `su` / `shizuku` 单独一行 = **进模式**；带参数 = **就地跑一条 shell 命令**。
     *
     * 两种形态都要，因为两种用法都自然：`su id` 是「用 root 跑这一条」，
     * 而 `su` 回车再连敲几条是「接下来都在 root 里」。前者不需要用户先切模式再切回来。
     */
    private fun modeEntry(raw: String, t: List<String>, mode: ShellConsoleMode): NpmConsoleCommand =
        if (t.size == 1) NpmConsoleCommand.EnterMode(mode)
        else NpmConsoleCommand.Shell(command = raw.substringAfter(' ').trim(), mode = mode)

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

    /** `npx <bin> [args…]` / `npm exec <bin> [args…]` / 默认模式下的裸首词。 */
    data class Exec(val bin: String, val args: List<String>) : NpmConsoleCommand

    /**
     * 一条 shell 命令（`su <cmd>` / `shizuku <cmd>` / 特权模式下的裸行）。
     *
     * [command] 是**原样正文**（不含入口词），交给实现侧起 `sh -c`。
     */
    data class Shell(val command: String, val mode: ShellConsoleMode) : NpmConsoleCommand

    /** 进入特权模式（`su` / `shizuku` 单独一行）—— **界面侧的会话状态**，不派发到宿主。 */
    data class EnterMode(val mode: ShellConsoleMode) : NpmConsoleCommand

    /** 退出特权模式（`exit`）—— 同上，界面侧状态。 */
    data object ExitMode : NpmConsoleCommand

    /** 拒收：`reason` 是给用户看的那句话（点名输入 + 说清为什么不收）。 */
    data class Rejected(val reason: String) : NpmConsoleCommand
}

/**
 * 控制台的特权模式（2026-10-09）。
 *
 * - [DEFAULT]：**未进特权模式**。shell 命令在这里一律拒（如实说「需要 root 或 Shizuku」），
 *   裸首词按 npm bin 解析。
 * - [ROOT]：`su -c`，root uid。
 * - [ADB]：Shizuku（`newProcess`），**shell uid**（不是「设备侧已在 adb shell 内」那层
 *   —— 那是 `:platform:system` 的 `ShellMode.ADB` 语义，本枚举刻意不复用那个类型：
 *   `:domain` 看不到 `:platform:*`，而且两者的语义确实不同，同名会让人以为是一条路）。
 */
enum class ShellConsoleMode { DEFAULT, ROOT, ADB }
