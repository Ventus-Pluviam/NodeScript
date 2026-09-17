package com.autoscript.appservice.scriptrepo.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * 原子部署器（docs/framework-design.md §9.6）：
 * stage（.stage/<nonce>/ 内写入 + sha256 登记）→ finalize（校验 + fsync + 同目录原子 rename）
 * → journal commit；任一步失败 abort（清理 + journal rollback）。
 *
 * 永不落半截文件到最终路径：写入/校验都在 stage 区，就位只用 ATOMIC_MOVE。
 */
class AtomicDeployer(
    private val root: Path,
    private val journal: DeployJournal,
) {

    data class Staged(
        val nonce: String,
        val relPath: String,
        val stageFile: Path,
        val target: Path,
        val sha256: String,
    )

    /** 阶段 1：写入 stage 区并登记 journal（STAGED）。 */
    fun stage(relPath: String, bytes: ByteArray): Staged {
        val target = DeployPath.resolveIn(root, relPath)
        val nonce = UUID.randomUUID().toString()
        val stageFile = root.resolve(".stage").resolve(nonce).resolve(relPath)
        Files.createDirectories(stageFile.parent)
        Files.write(stageFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        DeployPath.fsyncDirectory(stageFile.parent)
        val hash = DeployPath.sha256(bytes)
        journal.begin(nonce, relPath, hash)
        return Staged(nonce, relPath, stageFile, target, hash)
    }

    /** 阶段 2：校验 + fsync + 原子 rename 就位 + journal commit。 */
    fun finalize(staged: Staged) {
        val actual = DeployPath.sha256(staged.stageFile)
        require(actual == staged.sha256) { "部署校验失败：磁盘哈希不匹配（$staged.relPath）" }

        Files.createDirectories(staged.target.parent)
        // fsync 源文件与源目录，确保数据落盘后再 rename
        DeployPath.fsyncFile(staged.stageFile)
        DeployPath.fsyncDirectory(staged.stageFile.parent)
        Files.move(
            staged.stageFile, staged.target,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
        )
        DeployPath.fsyncDirectory(staged.target.parent)

        journal.commit(staged.nonce, staged.relPath, staged.sha256)
        cleanupStageDir(staged.nonce)
    }

    /** 阶段 2a：中止——清理 stage + journal rollback。 */
    fun abort(staged: Staged) {
        journal.rollback(staged.nonce, staged.relPath, staged.sha256)
        Files.deleteIfExists(staged.stageFile)
        cleanupStageDir(staged.nonce)
    }

    /**
     * 崩溃恢复：
     * - STAGED 记录但目标已就位且哈希相符 → 补 journal commit（finalize 半途崩溃）；
     * - 否则 → 清理 stage + journal rollback。
     * 返回已回滚的记录数（可审计）。
     */
    fun recover(): Int {
        var rolledBack = 0
        for (rec in journal.unfinished()) {
            val target = runCatching { DeployPath.resolveIn(root, rec.relPath) }.getOrNull()
            val targetHashOk = target?.let { Files.exists(it) && DeployPath.sha256(it) == rec.sha256 } == true
            if (targetHashOk) {
                journal.commit(rec.nonce, rec.relPath, rec.sha256)
            } else {
                val stageFile = root.resolve(".stage").resolve(rec.nonce).resolve(rec.relPath)
                Files.deleteIfExists(stageFile)
                cleanupStageDir(rec.nonce)
                journal.rollback(rec.nonce, rec.relPath, rec.sha256)
                rolledBack++
            }
        }
        return rolledBack
    }

    private fun cleanupStageDir(nonce: String) {
        val dir = root.resolve(".stage").resolve(nonce)
        runCatching {
            Files.list(dir).use { s -> if (!s.findAny().isPresent) Files.deleteIfExists(dir) }
        }
    }
}