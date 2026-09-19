package com.autoscript.appservice.scheduler.core

import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.RunArchive
import com.autoscript.domain.scripts.RunRecord
import com.autoscript.domain.scripts.isTerminal

/**
 * 内存引擎运行档案（JVM 单测 / 非持久原型用；生产替换为 SQLite 实现，:app 装配层注入）。
 *
 * 语义与 [InMemoryIntentLog] 对齐：**线程安全 + append-only 状态机**。
 * 关键不变量（:domain [RunArchive] 契约）：
 * 1. 终态落地即不可改写、不可复活 —— [RunRecord.state] 终态后再 [put] 同 id 一律 [IllegalStateException]；
 * 2. 双 id 关联一旦成立不允许改写 —— 同 engineRunId 携带不同 [EngineRunLink] 一律拒绝；
 * 3. 反向索引按关联推导，与记录同事务更新，绝不出现「记录在、关联不在」的孤儿。
 */
class InMemoryRunArchive : RunArchive {

    private val lock = Any()
    private val records = hashMapOf<Long, RunRecord>()              // engineRunId → record
    private val linkOf = hashMapOf<Long, EngineRunLink>()           // engineRunId → 双 id 关联

    override suspend fun put(record: RunRecord, link: EngineRunLink?): RunRecord = synchronized(lock) {
        if (link != null && link.engineRunId != record.id) {
            throw IllegalArgumentException(
                "EngineRunLink.engineRunId=${link.engineRunId} 与 RunRecord.id=${record.id} 不一致",
            )
        }
        val existing = records[record.id]
        if (existing != null) {
            if (existing.state.isTerminal) {
                throw IllegalStateException(
                    "终态记录不可改写/复活: engineRunId=${record.id} state=${existing.state}",
                )
            }
            val existingLink = linkOf[record.id]
            if (link != null && existingLink != null && existingLink != link) {
                throw IllegalStateException(
                    "双 id 关联已成立，不允许改写: $existingLink → $link",
                )
            }
        }
        records[record.id] = record
        if (link != null) linkOf[record.id] = link
        record
    }

    override suspend fun record(engineRunId: Long): RunRecord? = synchronized(lock) {
        records[engineRunId]
    }

    override suspend fun link(engineRunId: Long): EngineRunLink? = synchronized(lock) {
        linkOf[engineRunId]
    }

    override suspend fun recordsOfIntent(intentRunId: Long): List<RunRecord> = synchronized(lock) {
        linkOf.values.filter { it.intentRunId == intentRunId }
            .mapNotNull { records[it.engineRunId] }
            .sortedBy { it.id }
    }

    override suspend fun recordsOfProject(projectId: String): List<RunRecord> = synchronized(lock) {
        records.values.filter { it.projectId == projectId }.sortedBy { it.id }
    }

    override suspend fun unfinished(): List<RunRecord> = synchronized(lock) {
        records.values.filter { !it.state.isTerminal }.sortedBy { it.id }
    }
}
