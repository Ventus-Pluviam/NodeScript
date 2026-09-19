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
     * - 另清扫 .stage/ 下无 journal 记录的孤儿目录（stage 写盘完成、journal.begin 未落即崩溃的残留）。
     * 返回已回滚的记录数（可审计）。
     */
    fun recover(): Int {
        var rolledBack = 0
        val knownNonces = journal.all().map { it.nonce }.toSet()
        for (rec in journal.unfinished()) {
            val target = runCatching { DeployPath.resolveIn(root, rec.relPath) }.getOrNull()
            val targetHashOk = target?.let { Files.exists(it) && DeployPath.sha256(it) == rec.sha256 } == true
            if (targetHashOk) {
                journal.commit(rec.nonce, rec.relPath, rec.sha256)
                cleanupStageDir(rec.nonce)
            } else {
                val stageFile = root.resolve(".stage").resolve(rec.nonce).resolve(rec.relPath)
                Files.deleteIfExists(stageFile)
                cleanupStageDir(rec.nonce)
                journal.rollback(rec.nonce, rec.relPath, rec.sha256)
                rolledBack++
            }
        }
        // 孤儿 stage 目录：无任何 journal 记录（崩溃发生在 begin 之前），journal 路径永远看不到它们
        val stageRoot = root.resolve(".stage")
        if (Files.isDirectory(stageRoot)) {
            Files.list(stageRoot).use { dirs ->
                dirs.filter { Files.isDirectory(it) && it.fileName.toString() !in knownNonces }
                    .forEach { orphan ->
                        orphan.toFile().deleteRecursively()
                        rolledBack++
                    }
            }
        }
        return rolledBack
    }

    private fun cleanupStageDir(nonce: String) {
        val stageRoot = root.resolve(".stage")
        val dir = stageRoot.resolve(nonce)
        runCatching {
            // 递归清理空目录（finalize 移出文件后可能留下空子目录如 lib/）
            fun emptyIfEmpty(d: Path): Boolean {
                // 先快照目录内容（Files.list stream 在迭代中删除可能不反映变更）
                val entries = Files.list(d).toList()
                if (entries.isEmpty()) {
                    Files.deleteIfExists(d)
                    return true
                }
                // 递归清理子目录
                entries.filter { Files.isDirectory(it) }.forEach { child ->
                    emptyIfEmpty(child)
                }
                // 子目录清理后重新检查当前目录是否变空
                val remaining = Files.list(d).toList()
                if (remaining.isEmpty()) {
                    Files.deleteIfExists(d)
                    return true
                }
                return false
            }
            emptyIfEmpty(dir)
        }
        // <nonce> 清空后若 .stage 根也已空，一并抹除，避免遗留空 stage 目录（测试 & 审计要求）。
        runCatching {
            if (Files.exists(stageRoot)) {
                val any = Files.list(stageRoot).use { it.findAny().isPresent }
                if (!any) Files.deleteIfExists(stageRoot)
            }
        }
    }
}