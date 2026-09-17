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
        files.forEach { (rel, bytes) -> deployer.finalize(deployer.stage(rel, bytes)) }
        return files.size
    }

    /** 启动恢复：补 journal 或回滚残留部署。返回回滚数（审计用）。 */
    fun recover(projectId: String): Int = deployerFor(projectId).recover()
}