package com.autoscript.platform.system.persist

import android.content.Context
import com.autoscript.domain.scripts.RunArchive
import com.autoscript.domain.scripts.RunArchiveImport
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 运行档案存储的**打开与一次性迁移**（§8.5；2026-10-11 批 102 P4）。
 *
 * 与 [IntentStoreWiring] / [TaskStoreWiring] **同一套口径**（三份存储一套纪律）：
 * 1. **迁移**（[migrate]）：老设备上已有 `run-archive.jsonl`。库为空且 jsonl 非空时把历史
 *    **带着原 engineRunId** 导进去（**关联随行一起导**），然后 jsonl 改名归档。
 * 2. **打开**（[open]）：开 SQLite，**无回落** —— 打不开就是失败，异常原样抛给装配层。
 *
 * **为什么「带原 id」是硬要求**（与意图日志迁移同一条）：`EngineRunLink.intentRunId` 指向的
 * 就是档案里的 `engineRunId`。不迁 = 新库从 1 重新发号，新执行与历史 link **同号异义** ——
 * `recordsOfIntent(3)` 会把上次那次执行当成这次意向的历史（静默串档，比丢数据更难发现）。
 *
 * **关联必须一起导**：否则迁移后任务中心按意向追溯**查不到历史**，而那正是「带原 id」
 * 要保的东西的一半。
 *
 * **可重入**：库非空即跳过；**失败响亮**：解析抛错时老文件原样留着（改名在验完之后）。
 */
object RunArchiveWiring {

    /** jsonl 归档名（迁移成功后由 [migrate] 改名到此；不再被任何读者打开）。 */
    const val ARCHIVED_NAME: String = "run-archive.jsonl.migrated"

    /** jsonl 侧的文件名（与 `FileRunArchive` 同一份约定，这里只读不写）。 */
    private const val JSONL_NAME: String = "run-archive.jsonl"

    fun open(context: Context, nsDir: Path): RunArchive =
        SqliteRunArchive(AndroidSqliteRunner(context, AndroidSqliteRunner.DB_NAME_RUN_ARCHIVE)).also { store ->
            migrate(store, nsDir.resolve(JSONL_NAME))
        }

    /**
     * 一次性导入：库为空且 jsonl 非空 → 全量导入（带原 id + 关联）+ 归档 jsonl。
     *
     * @return 导入的行数（0 = 无需导入：库非空，或 jsonl 不存在/为空）
     * @throws java.io.IOException jsonl 读失败
     * @throws IllegalArgumentException jsonl 行损坏（**响亮失败**，不静默丢历史）
     */
    fun migrate(store: SqliteRunArchive, jsonl: Path): Int = kotlinx.coroutines.runBlocking {
        if (store.records().isNotEmpty()) return@runBlocking 0   // 库非空 = 迁过了
        if (!Files.isRegularFile(jsonl)) return@runBlocking 0
        val rows = RunArchiveImport.parse(readText(jsonl))
        if (rows.isEmpty()) return@runBlocking 0
        val imported = store.importRecords(rows.map { with(store) { it.record.toFlat() } to it.intentRunId })
        // 归档：改名（不删）。改名前所有解析与导入都已完成 —— 导入抛错时老文件原样在原处。
        Files.move(
            jsonl,
            jsonl.resolveSibling(ARCHIVED_NAME),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        imported
    }

    /** 读 jsonl 文本：**尾部半行先截掉再解码**（与写路径的 force 组口径一致）。 */
    private fun readText(path: Path): String {
        val bytes = Files.readAllBytes(path)
        var end = bytes.size
        while (end > 0 && bytes[end - 1] != '\n'.code.toByte()) end--
        return String(bytes, 0, end, StandardCharsets.UTF_8)
    }
}
