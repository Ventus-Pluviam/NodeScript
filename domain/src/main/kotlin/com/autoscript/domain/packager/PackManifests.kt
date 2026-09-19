package com.autoscript.domain.packager

import java.security.MessageDigest

/**
 * 清单装配（docs §14 P0 打包）：由 [PackSpec] + 已收集 [AssetEntry] 产出可校验的 [PackManifest]。
 * digest = sha256(按 relPath 排序后逐行 `"relPath␣sha256␣sizeBytes\n"`)。
 */
object PackManifests {

    fun build(spec: PackSpec, assets: List<AssetEntry>): PackManifest {
        val sorted = assets.sortedBy { it.relPath }
        val md = MessageDigest.getInstance("SHA-256")
        for (a in sorted) {
            md.update("${a.relPath} ${a.sha256} ${a.sizeBytes}\n".toByteArray(Charsets.UTF_8))
        }
        val digest = md.digest().joinToString("") { "%02x".format(it) }
        return PackManifest(spec, sorted, digest)
    }

    /** 校验清单自哈希（安装器/打包器双侧共用）。 */
    fun verify(manifest: PackManifest): Boolean =
        build(manifest.spec, manifest.assets).digest == manifest.digest
}
