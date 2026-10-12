package com.autoscript.appservice.scriptrepo.assets

import android.content.res.AssetManager
import com.autoscript.domain.scripts.ProjectId
import com.autoscript.domain.scripts.RelPath
import com.autoscript.domain.scripts.ScriptPaths

/**
 * Android 资产源（docs §9.6）：把 assets/scripts/<projectId>/ 下的
 * 原始资源读出为 Map<String, ByteArray>，交由 core 的 [ProjectDeployer] 原子部署。
 * 本类只做 read-only 的资产枚举；写路径全部在 core（纯 JVM），保证可单测。
 *
 * 递归逻辑本体不在这里：见 [AssetsWalk]（独立文件，本机无 SDK 也能单测）。
 * 本类退化为按 [AssetManager] 转接的薄壳。
 *
 * **assets 下的根名不再硬编码 `scripts/`**（批 109）：那字面量与
 * [ScriptPaths.PROJECTS_DIR] 是同一件事，抄两份就会漂 —— 而漂的后果是「部署落点」与
 * 「约定落点」分家，本类读到的文件永远部署不出去，且没有任何报错。
 */
class AndroidAssetsSource(
    private val assets: AssetManager,
    private val projectId: ProjectId,
) {
    fun readScripts(): Map<RelPath, ByteArray> = AssetsWalk.walk(
        root = "${ScriptPaths.PROJECTS_DIR}/$projectId",
        list = { dir -> assets.list(dir) },
        open = { path -> assets.open(path) },
    )
}
