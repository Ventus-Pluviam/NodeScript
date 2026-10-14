package com.autoscript.domain.scripts

/**
 * 「这个脚本文件能不能被引擎跑」的**唯一一份**判据（2026-10-13 用户口径：项目页文件行尾那颗 ▶）。
 *
 * 为什么住 `:domain` 而不是各写一份：这条判据有**两个**读点 —— 呈现层（`ProjectScreen` 决定
 * 行尾画不画 ▶）与执行口（`AssembledShell.runScriptFile` 决定收不收这次请求）。抄两份必然漂，
 * 而漂的方向最坏：界面画了 ▶、点下去被执行口拒（用户看到的是"执行失败"而不是"这个文件不能跑"）。
 * 与 [ScriptPaths.PROJECT_ID] 同一条理由 —— 判据只有一份，其余引用点转调。
 *
 * **为什么只有 JS 家族**（`.js` / `.mjs` / `.cjs` / `.ts`）：引擎侧的 spawn 是
 * `node <脚本路径>`（§7.8 的 argv 契约，`NodeProcessEngine` 里拼的就是它），能被它跑起来的
 * 只有 JS 家族 —— Node 24 起 `.ts` 走内置的类型擦除，也在这一档里。
 *
 * **`.sh` / `.bash` / `.zsh` 刻意不在表内**（用户口径里点了 "sh"，这里是如实收窄）：引擎
 * 起的是 Node，`node x.sh` 只会得到一句 SyntaxError；shell 脚本唯一的执行面是宿主控制台的
 * shell 面（`HostSummary.runShellCommand`），而那条路**默认模式一律拒**（要 root 或 Shizuku，
 * 见 `ShellConsoleMode` 的 KDoc）。在行尾画一颗注定失败的 ▶ 比不画更糟 —— 所以不画，
 * 要跑 shell 脚本请去控制台显式进 `su` / `shizuku` 模式。
 *
 * **只判扩展名，不判存在性**：存在性是 `Files.isRegularFile` 的事（同 [ScriptPaths.scriptFile]
 * 那条"词法判据不查存在性"）。行里给出来的本来就是真实存在的条目。
 */
object ScriptRunnability {

    /** 引擎（`node <路径>`）能直接跑的扩展名（小写，不含点）。 */
    val EXECUTABLE_EXTENSIONS: Set<String> = linkedSetOf("js", "mjs", "cjs", "ts")

    /**
     * 这个项目内相对路径是不是可执行脚本。
     *
     * 取的是**最后一段的最后一个点之后**那截（`lib/a.b.js` → `js`）：与
     * `ScriptFilesRead` 里 `path.extension` 的口径一致（同一份行快照喂给两边，
     * 判据与显示必须看同一个扩展名）。大小写不敏感 —— `MAIN.JS` 与 `main.js` 是一类文件。
     */
    fun isRunnable(relPath: String): Boolean = extensionOf(relPath) in EXECUTABLE_EXTENSIONS

    /**
     * 项目内相对路径 → 小写扩展名（无扩展名 / 点开头无扩展名都给空串）。
     *
     * **`substringAfterLast('/')` 不带第二参数**（缺省 = 整串）：第二参数是
     * `missingDelimiterValue`，传 `""` 会让**没有斜杠**的路径整个变成空串 ——
     * `main.js` 于是没有扩展名，`isRunnable("main.js") == false`。原写法就是这个坑，
     * 而调用方传的 relPath 恰好**总带斜杠**（首段 = 项目名），所以界面上看不出来；
     * 只有单测直接传 `"main.js"` 才把它暴露出来。
     */
    private fun extensionOf(relPath: String): String =
        relPath.substringAfterLast('/').substringAfterLast('.', "").lowercase()
}
