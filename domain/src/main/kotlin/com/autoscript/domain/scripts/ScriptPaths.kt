package com.autoscript.domain.scripts

import java.nio.file.Path

/**
 * 脚本目录约定（docs/framework-design.md §9.6 / §10.2 的**单一事实来源**）。
 *
 * 为什么这条约定必须住 `:domain` 而不是各模块各写一份：项目根这一个字符串被四个地方读
 * —— `:app-service:script-repo`（部署/索引）、`:app-service:packager`（npm 项目布局）、
 * `:app-service:scheduler`（恢复期补部署）、`:app` 装配层（目录落位与壳读口）。
 * 任一处写错（`files/script`、`files/scripts/`、`Files/scripts`）都不会编译失败，
 * 只会表现为"文件写进去但引擎读不到"这种**没有报错**的故障。放进契约层，拼错即编译期可见。
 *
 * 纯路径计算：**不做 IO、不建目录、不校验存在性**（那是 [Path.exists] 的事，不是约定的）。
 * 需要落盘的一方自己 `Files.createDirectories` —— 本对象只回答"应该在哪"。
 */
object ScriptPaths {

    /** 项目根目录名（`filesDir` 下的一级目录）。 */
    const val PROJECTS_DIR: String = "scripts"

    /** App 私有文件目录下的项目根：`files/scripts`。 */
    fun projectsRoot(filesDir: Path): Path = filesDir.resolve(PROJECTS_DIR)

    /** 某项目的目录：`files/scripts/<projectId>`（不校验存在性，也不校验 id 合法性 —— 校验归各模块入口）。 */
    fun projectRoot(filesDir: Path, projectId: String): Path = projectsRoot(filesDir).resolve(projectId)

    /**
     * 脚本文件的绝对路径：`files/scripts/<projectId>/<scriptPath>`（§9.6 项目内相对路径）。
     *
     * 引擎宿主拿到的必须是绝对路径：引擎进程有自己的 cwd（native 侧由 `:engine:node-process`
     * 决定），相对路径会解析到别处。这里只做拼接，**不做逃逸校验** —— 调度侧早已把
     * `projectId`/`scriptPath` 当不透明字符串透传（见 `ScheduledTask`），
     * 要防逃逸的是**写入**侧（`DeployPath.resolveIn`），读取侧越界只会读不到文件。
     */
    fun scriptFile(filesDir: Path, projectId: String, scriptPath: String): Path =
        projectRoot(filesDir, projectId).resolve(scriptPath)
}
