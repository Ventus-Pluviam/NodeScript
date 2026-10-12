package com.autoscript.shell

import android.content.res.AssetManager
import com.autoscript.domain.scripts.ProjectId
import com.autoscript.domain.scripts.RelPath
import com.autoscript.domain.scripts.ScriptPaths
import com.autoscript.appservice.npm.AssetTreeCliSource
import com.autoscript.appservice.scriptrepo.assets.AndroidAssetsSource

/**
 * APK 资产的四条读口（2026-10-07 backlog D7 自 `AppShellApplication` 外迁）。
 *
 * **为什么单独一个文件**：四条读法各自带一条**降级口径**（枚举失败算不算"没货"、
 * 读失败与缺件同不同形），那是判断不是搬运 —— 埋在 `AppShellKit.assemble` 那个九十行的
 * 具名参数表里，读者只会看到四段 `try/catch` 而看不到"为什么这条这么吞"。
 * 抽出来之后 `AppShellApplication.installWithFiles` 每类资产只剩一行。
 *
 * **诚实边界**：本文件**不可单测**（参数是 `AssetManager`，JVM 上造不出真实例），
 * 这一点与 `CapabilityCenterRead` 不同 —— 那里吃的是 `:domain` 的接口，能注入假实现。
 * 抽出来的收益是"口径集中且可读"，不是"可测"；要测这四条得连 `AssetManager` 一起假造，
 * 那是另一件事（本仓目前没做）。
 *
 * 四条口径刻意不同，逐条写在各自 KDoc 里 —— 共同点是**没有一条把失败粉饰成"有货"**：
 * - 枚举/读失败一律退回"本次没货"，由下游如实记账（空报告 / 空映射 / null），
 *   绝不半量落（半量 + 孤儿清理会把"读失败那个文件"当成旧版删掉）。
 */
object AssetsRead {

    /**
     * 首批内置脚本的**清单**（§9.6 `assets/scripts/<projectId>/`）：`assets/scripts` 下的
     * projectId 枚举。枚举失败 = 空表（无资产来源），不炸装配 —— 补部署报告如实为空，
     * 不会被读成"已恢复"。
     */
    fun scriptProjects(assets: AssetManager): List<ProjectId> = try {
        assets.list(ScriptPaths.PROJECTS_DIR)
            ?.mapNotNull { ProjectId.createOrNull(it.trimEnd('/')) }
            .orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * 单个项目的脚本素材（相对路径 → 字节；§9.6 补部署的读侧）。
     *
     * 递归本体在 `script-repo` 的 `AssetsWalk`（纯逻辑、JVM 可测）；本函数只做
     * "projectId → 资产根" 的转接。读失败由 `AssetsWalk` 自己按"该文件读不到"处理
     * （`open` 抛则整次补部署失败并如实记账），这里不吞。
     */
    fun scriptsOf(assets: AssetManager, projectId: ProjectId): Map<RelPath, ByteArray> =
        AndroidAssetsSource(assets, projectId).readScripts()

    /**
     * facade dist（§12.4 资产交付轨）：`assets/bridge-dist/` 全量读成扁平 map。
     * **枚举或任一读失败 = 整体空 map**（宁可这次不落，不可半量落：半量 + 孤儿清理会把
     * "读失败那个文件"当成旧版删掉）—— `bridgeDistReport` 如实为空。
     */
    fun bridgeDist(assets: AssetManager): Map<String, ByteArray> = try {
        val names = assets.list("bridge-dist")?.toList() ?: emptyList()
        names.associateWith { name -> assets.open("bridge-dist/$name").use { it.readBytes() } }
    } catch (_: Exception) {
        emptyMap()
    }

    /**
     * bridge addon（§19 交付轨）：单文件资产。没货 = null（不注入的诚实缺省，
     * **不是**"空文件注入"）。读失败与没货同形 —— 引擎侧缺文件即降级，不半装。
     */
    fun bridgeAddon(assets: AssetManager): ByteArray? = try {
        assets.open("bridge-addon/bridge_native.node").use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    /**
     * vendored npm CLI 素材源（§10.2 调用链首段）：源 = `assets/npm/` 树，键形状 `npm/<rel>`
     * （随包任务 `prepareNpmCliAssets` 产出）。本函数只递 Android 侧的读口，
     * **落位与执行体注入都在 `AppShellKit`**（源在不在、锚齐没齐、有没有 Node 宿主，
     * 一处判完；诊断原文见 `AssembledShell.npmCliFailure`）。
     */
    fun npmCliSource(assets: AssetManager): AssetTreeCliSource = AssetTreeCliSource(
        root = "npm",
        listDir = { dir -> assets.list(dir) },
        openManifest = { assets.open("npm-manifest.json") },
        openFile = { path -> assets.open(path) },
    )
}
