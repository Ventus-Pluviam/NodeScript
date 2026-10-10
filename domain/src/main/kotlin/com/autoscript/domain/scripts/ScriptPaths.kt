package com.autoscript.domain.scripts

import java.nio.file.Path

/**
 * 脚本目录约定（docs §9.6 / §10.2 的**单一事实来源**）。
 *
 * 为什么这条约定必须住 `:domain` 而不是各模块各写一份：项目根这一个字符串被四个地方读
 * —— `:app-service:script-repo`（部署/索引）、`:app-service:npm`（npm 项目布局）、
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
     * 项目号的合法形态（**唯一一份**判据）。
     *
     * 为什么住这里：这条正则被三个地方读 —— `:app-service:npm` 的 `NpmProjectLayout.projectRoot`
     * （防路径逃逸的 `require`）、界面侧的命令行校验（`NpmConsoleKeys.rejectProjectId`，
     * 用户敲错要当场被告知）、以及将来任何以 projectId 拼路径的地方。抄两份必然漂，
     * 而漂的方向最坏：界面放行的 id 在落盘侧被 `require` 拒（用户看到的是「执行失败」
     * 而不是「这个项目号不合法」）。与 `PROJECTS_DIR` 同一条理由 —— 拼错的路径不会
     * 编译失败，只会表现为「写进去了但读不到」这种**没有报错**的故障。
     *
     * 与 [com.autoscript.domain.npm.NpmConsoleKeys.rejectProjectId] 的分工：那是**话术**
     * （拒收原文，给用户看），这是**判据**（是/否）。
     */
    val PROJECT_ID: Regex = Regex("[A-Za-z0-9_-]+")

    /**
     * [PROJECT_ID] 的布尔投影（`NpmProjectLayout.projectRoot` 与界面校验共用一处）。
     *
     * 注意判据里**没有 `.`**：原正则 `[A-Za-z0-9._-]+` 收 `.` 与 `..`，而
     * `projectsRoot.resolve("..")` 正好跳出项目根 —— 那条 `require` 自称「防路径逃逸」，
     * 实际把最经典的一种逃逸放行了（`.` 本身则让 `files/scripts/.` 变成项目根，
     * 于是「项目 `.npmrc`」落在共享目录上）。`.` 在项目号里也没有真实用途：
     * 项目号来自目录名，而仓库自己的项目就叫 `main`。批 84 收口时一并钉住。
     */
    fun isValidProjectId(projectId: String): Boolean = PROJECT_ID.matches(projectId)

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

    /**
     * 录屏产物的落点目录：`files/scripts/<projectId>/.recordings`（§9.2 录屏腿）。
     *
     * **为什么是项目根下的点开头子目录**（三条都是既有约定，不是新发明的形状）：
     * 1. **项目私有**：[projectRoot] 就是「项目私有目录」这条约定的唯一出口
     *    （`files/scripts/<projectId>/` 被 script-repo 部署、npm 布局、调度补部署四处共用），
     *    产物落在它下面 = 与脚本、`package.json`、`node_modules` 同一个归属；
     * 2. **点开头**：项目根下已有的 `.npmrc` / `.autojs.build.ignore` 都是"项目内务文件"，
     *    点开头在脚本文件清单（`:app` 的 `ScriptFilesRead`）里**天然不入列表**
     *    （`isNoise`）—— 录屏是产物不是用户资产，不该混进编辑器的文件页；
     * 3. **不落 `files/.autojs`**：那是**宿主内务**（意图日志/档案/历史账本），
     *    按项目分账的东西混进去会让"清一个项目"变成"清宿主账"。
     *
     * 为什么这条约定必须住本对象：它是被**两处**读的路径 —— 语义层（`MediaProjectionRecorder`
     * 算落点）与设备层（`MediaRecorder.setOutputFile`）。任一处写错（`recordings` 少个点、
     * `scripts` 写成 `script`）都不会编译失败，只会表现为"录完了但文件找不到"这种**没有报错**
     * 的故障，同本对象 KDoc 开头那条教训。
     *
     * **已知后果（如实记账，不在本对象解决）**：`PackagerCollector` 收项目目录下全部常规文件
     * （只排 `node_modules` 与 ignore 规则），所以录屏产物**会被打进 APK**。要排除得改
     * `PackSpec.ignoreRules` 的缺省或打包器的排除面 —— 那是 `:app-service:packager` 的口径，
     * 不在本约定的管辖内。
     */
    fun recordingsDir(filesDir: Path, projectId: String): Path =
        projectRoot(filesDir, projectId).resolve(RECORDINGS_DIR)

    /** 录屏产物目录名（点开头 = 项目内务，见 [recordingsDir]）。 */
    const val RECORDINGS_DIR: String = ".recordings"

    /**
     * facade 包 `auto` 的落位根：`filesDir/node_modules/auto`（docs §12.4 资产交付轨）。
     *
     * 为什么住这里（与 [projectRoot] 同一条理由）：这个路径被两处拼 ——
     * `:app-service:script-repo` 的 `BridgeDistDeploy`（assets→落位）与 `:app` 装配层
     * （`AUTOSCRIPT_BRIDGE_DIST` 注入）。任一处写错不会编译失败，只会表现为
     * 「`require('auto')` 解析不到 / attachNative 没接上」这类**没有报错**的故障形态。
     *
     * 选这里的依据是 Node 的解析走位：脚本住 `files/scripts/<projectId>/x.js`，
     * `require('auto')` 沿目录树向上找 `node_modules/auto`，第 3 站正是 `filesDir/node_modules`
     * （脚本目录 → `files/scripts/<projectId>/node_modules` → `files/scripts/node_modules`
     * → `files/node_modules` = [autoModuleRoot] 的父级）。目录内**不需要 package.json**：
     * 无 package.json 时 Node 目录解析缺省落到 `index.js`，而 dist 的 `index.js` 就是
     * 命名空间根（§12.1 `require('auto')`）。此约定必须与部署侧同一出处 —— 换落位只改这里。
     */
    fun autoModuleRoot(filesDir: Path): Path =
        filesDir.resolve("node_modules").resolve("auto")

    /**
     * bridge addon 的装配期落位：`filesDir/lib/bridge_native.node`（docs §19
     * jniLibs 交付轨）。
     *
     * 为什么住这里：两个 Kotlin 读者 —— `:app-service:script-repo` 的
     * `BridgeAddonDeploy`（assets→落位）与 `:app` 装配层（`AUTOSCRIPT_BRIDGE_ADDON`
     * 注入的"应该在哪"）。任一处写错不会编译失败，只会表现为「addon 没接上、
     * 桥调用点 ERR_ENGINE_STOPPED」这类**没有报错**的故障形态。
     *
     * 为什么不放 `nativeLibraryDir`：APK 的 `lib/<abi>/` 只按 `*.so` 提取，
     * `.node` 进不去；而 main.cpp 的预载是 `require(env)`——Node 只认 `.node`
     * 扩展走 dlopen，`.so` 会被当 JS 解析当场炸。所以 addon 走 assets 随包
     * （与 facade dist 同一交付面），装配期落到这条路径；引擎侧
     * `addonPath` 缺文件即降级不注入（选填纪律，与 bridgeDistPath 同形）。
     * 换落位只改这里。
     */
    fun bridgeAddonFile(filesDir: Path): Path =
        filesDir.resolve("lib").resolve("bridge_native.node")
}
