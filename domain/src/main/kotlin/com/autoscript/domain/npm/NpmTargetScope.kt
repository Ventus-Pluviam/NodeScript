package com.autoscript.domain.npm

import com.autoscript.domain.scripts.ProjectId

/**
 * 依赖**作用域**（2026-10-17 用户口径：「项目选择改成依赖作用域，然后下拉框加个全局」）。
 *
 * 一次安装（或一条 `npm` 命令）最后总得落在某一棵 `node_modules` 上，"落在哪一棵"就是作用域。
 * 今天两档：
 *
 * - **全局** —— `files/scripts/node_modules`；
 * - **某个项目** —— `files/scripts/<projectId>/node_modules`。
 *
 * ## 全局那一档为什么就是「根层哨兵」那个空串
 *
 * [GLOBAL] 取的是 [ProjectId.ROOT] 的编码，于是全局作用域走的是**与项目逐字相同**的那套
 * 寻址：`projectRoot(ROOT)` 天然等于 `files/scripts/`、`nodeModules(ROOT)` 天然等于
 * `files/scripts/node_modules`（`Path.resolve("")` 恒等自身）。管线其余各段
 * （staging / journal / lockfile / `list` / `offlineGap` / 签名 / 门禁）**一行都不用改** ——
 * 多出来的只有两件事：把 `node_modules` 这个目录名从项目枚举里排掉（它不是项目），
 * 以及回收缓存时把全局那份 lock 也算进保留集。
 *
 * ## 为什么全局必须落在 `files/scripts/` 这一层，而不是另开一个目录
 *
 * 用户口径：「项目依赖和全局依赖如果有重复的，则优先读取项目依赖」。Node 找包是**逐级向上**
 * 找 `node_modules` 的，而项目目录 `files/scripts/<projectId>/` 正压在 `files/scripts/`
 * 底下 —— 全局那份只要落在这儿，项目自己的 node_modules **天然更近、天然优先**，这条规则
 * 一行代码都不用写。换个位置（比如 `files/npm-global/`）它就不再是任何项目目录的祖先，
 * 那条优先规则立刻要靠 NODE_PATH 之类的额外机制去补；而 NODE_PATH 只对 CJS 的 `require`
 * 生效、还得改引擎 spawn —— 拿一个真实可用的行为去换一个更漂亮的目录名，不值。
 *
 * **代价如实记**：`files/scripts/` 本层从此会多出 `package.json` 与 `package-lock.json`
 * 两个文件（全局那份清单），它们会出现在文件列表的**根层**里。`node_modules/` 本身不会 ——
 * 文件列表早就把它整段滤掉了（`ScriptFilesRead` 的 `NODE_MODULES_DIR` 那一段）。
 *
 * @see NpmProjectSnapshot 面板上每个作用域一条（全局那条的 `projectId` 就是 [GLOBAL]）。
 */
object NpmTargetScope {

    /** 全局作用域的编码（= [ProjectId.ROOT]，空串）。 */
    val GLOBAL: String = ProjectId.ROOT.value

    fun isGlobal(projectId: String): Boolean = projectId == GLOBAL

    /**
     * 界面上怎么说这个作用域。
     *
     * 空串在屏幕上**不能显示成空**：「依赖作用域」那一行右边留白，用户读成"还没读到"，
     * 而它其实是一个明确的作用域（全局）。所以全局那条在界面上永远显示成「全局」。
     *
     * 措辞放在 `:domain` 而不是 `:ui`：宿主把事件、日志、去向上的 `projectId` 也写成空串，
     * 两边对同一个值的说法必须是一份（先例：`NpmConsoleKeys` 的拒收话术）。
     */
    fun label(projectId: String): String = if (isGlobal(projectId)) "全局" else projectId
}
