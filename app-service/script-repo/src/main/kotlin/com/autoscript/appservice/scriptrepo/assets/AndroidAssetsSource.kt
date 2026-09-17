package com.autoscript.appservice.scriptrepo.assets

import android.content.res.AssetManager

/**
 * Android 资产源（docs/framework-design.md §9.6）：把 assets/scripts/<projectId>/ 下的
 * 原始资源读出为 Map<String, ByteArray>，交由 core 的 [ProjectDeployer] 原子部署。
 * 本类只做 read-only 的资产枚举；写路径全部在 core（纯 JVM），保证可单测。
 */
class AndroidAssetsSource(
    private val assets: AssetManager,
    private val projectId: String,
) {
    fun readScripts(): Map<String, ByteArray> {
        val dir = "scripts/$projectId"
        val names = assets.list(dir) ?: return emptyMap()
        return names
            .filter { it.isNotBlank() }
            .associateWith { name -> assets.open("$dir/$name").use { it.readBytes() } }
    }
}