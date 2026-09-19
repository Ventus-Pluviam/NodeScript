package com.autoscript.appservice.scriptrepo.core

import java.nio.file.Path

/**
 * 项目部署门面（docs/framework-design.md §9.6）：把若干相对路径文件原子写入项目 root。
 * 每个项目一个 [AtomicDeployer]（journal 随项目目录走，移动/删除自洽），启动时先 [recover]。
 */
class ProjectDeployer(private val store: ProjectStore) {

    private fun deployerFor(projectId: String): AtomicDeployer {
        val root = store.rootFor(projectId)
        return AtomicDeployer(root, DeployJournal(root.resolve(".deploy.journal")))
    }

    /** 部署一组文件；返回部署成功的文件数。任一文件非法（路径逃逸/哈希不符）即抛。 */
    fun deploy(projectId: String, files: Map<String, ByteArray>): Int {
        if (!store.exists(projectId)) store.create(projectId)
        val deployer = deployerFor(projectId)
        // 两阶段提交：全部 stage 成功（写 stage 区 + sha256 登记）后才逐条 finalize。
        // 若 stage 中途失败，已 stage 的全部 abort 回滚 —— 绝不把「半组文件」留进 finalize，
        // 否则项目目录会处于跨版本混合态（a.js 新版 + b.js 旧版），recover 也无从识别。
        val staged = mutableListOf<AtomicDeployer.Staged>()
        try {
            files.forEach { (rel, bytes) -> staged += deployer.stage(rel, bytes) }
        } catch (t: Throwable) {
            staged.forEach { s -> runCatching { deployer.abort(s) } }
            throw t
        }
        staged.forEach { deployer.finalize(it) }
        return files.size
    }

    /** 启动恢复：补 journal 或回滚残留部署。返回回滚数（审计用）。 */
    fun recover(projectId: String): Int = deployerFor(projectId).recover()
}