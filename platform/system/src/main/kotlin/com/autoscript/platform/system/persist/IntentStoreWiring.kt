package com.autoscript.platform.system.persist

import android.content.Context
import com.autoscript.domain.scripts.IntentLogImport
import com.autoscript.domain.scripts.IntentStore
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 意图日志存储的**打开与一次性迁移**（§8.5「append-only（SQLite，启动即回放）」）。
 *
 * 两件事，都只在装配期跑一次：
 *
 * 1. **迁移**（[migrate]）：老设备上已有 `intent-log.jsonl`。SQLite 库为空且 jsonl 非空时
 *    把历史**带着原 runId** 导进去，然后把 jsonl 改名归档（`intent-log.jsonl.migrated`）。
 * 2. **打开**（[open]）：Android 生产开 SQLite。**没有回落**（2026-10-08 裁定）——
 *    打开失败就是失败，异常原样抛给装配层（`AppShellApplication.installWithFiles` 的
 *    失败分支：记日志、壳保持未就绪、闹钟走漏投记账）。原先那条「打不开就回落 jsonl」
 *    已删：回落目标的 runId 分配与幂等锚点都靠单写者假设撑着，那不是降级，是
 *    把「调度坏了」伪装成「调度还能用」。
 *
 * **为什么要迁移而不是不迁**（本轨的显式裁定）：
 * - **runId 与 `run-archive.jsonl` 的 `EngineRunLink.intentRunId` 是同一批号**。不迁 =
 *   新库从 1 发号，新意向会与历史 link **同号异义** —— `RunArchive.recordsOfIntent(3)`
 *   会把上辈子那次执行当成这次意向的历史（静默串档，比"丢数据"更难发现）。
 *   带着原 runId 导入（`sqlite_sequence` 随之上抬）之后，后续分配自然接在历史之后。
 * - **已提交的 nonce 是幂等锚点**。不迁 = 升级后老 nonce 全部消失 → 那些任务若被重投
 *   （`reopen` 之外的路径、或外部重放）会**重放副作用**，而 §8.5 的整个幂等机制就是为
 *   「只发一次」准备的。代价是：**存活行**（未 COMMIT 意向）导入后会在下次启动被判为
 *   崩溃遗留并重投 —— 这正是 jsonl 路径今天的行为（启动即回放），语义一致。
 * - **老库不再增长**：迁移后 jsonl 归档不再被读（改名前先验后写），老设备不会"两份日志各写各的"。
 *
 * **可重入**：库非空即跳过（本类是这份库的唯一写者，读-判-写之间没有并发写者）；
 * 库空而 jsonl 也已归档 → 无事可做。失败（jsonl 损坏）**响亮失败**：异常抛给装配层，
 * 老日志原样留着（改名在验完之后），不会出现"导了一半、老的又没了"。
 */
object IntentStoreWiring {

    /** jsonl 归档名（迁移成功后由 [migrate] 改名到此；不再被任何读者打开）。 */
    const val ARCHIVED_NAME: String = "intent-log.jsonl.migrated"

    /**
     * 打开 Android 生产用的意图日志存储，并在需要时先做一次性迁移。
     *
     * @param context Android 上下文（`SQLiteOpenHelper` 用）
     * @param autojsDir `.autojs` 目录（老 jsonl 就在它下面 —— 与已退役的 jsonl 存储同一处）
     * @return 存储实例（调用方负责 close，随壳收口）
     */
    fun open(context: Context, autojsDir: Path): IntentStore =
        SqliteIntentStore(AndroidSqliteRunner(context)).also { store ->
            migrate(store, autojsDir.resolve(JournalName.FILE))
        }

    /**
     * 一次性导入：库为空且 jsonl 非空 → 全量导入 + 归档 jsonl。
     *
     * @return 导入的行数（0 = 无需导入：库非空，或 jsonl 不存在/为空）
     * @throws IOException jsonl 读失败
     * @throws IllegalArgumentException jsonl 行损坏（**响亮失败**，不静默丢历史）
     */
    fun migrate(store: SqliteIntentStore, jsonl: Path): Int {
        if (store.allRows().isNotEmpty()) return 0          // 库非空 = 迁过了
        if (!Files.isRegularFile(jsonl)) return 0
        val text = readText(jsonl)
        val rows = IntentLogImport.parse(text).map {
            IntentStore.StoredRow(it.runId, it.start, it.outcome, it.committedAtMillis)
        }
        if (rows.isEmpty()) return 0
        val imported = store.importRows(rows)
        // 归档：改名（不删）。改名前所有解析与导入都已完成 —— 导入抛错时老日志原样在原处。
        Files.move(
            jsonl,
            jsonl.resolveSibling(ARCHIVED_NAME),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        return imported
    }

    /**
     * 读 jsonl 文本：**尾部半行先截掉再解码**（与写路径的 force 组口径一致 ——
     * 没落完的组等于没发生）。按字节找最后一个 `\n`、再整体 UTF-8 解码：
     * 按块解码的写法会把跨块的多字节字符解坏，而这里连"最后一行是半行"都要先判掉。
     */
    private fun readText(path: Path): String {
        val bytes = Files.readAllBytes(path)
        var end = bytes.size
        while (end > 0 && bytes[end - 1] != '\n'.code.toByte()) end--
        return String(bytes, 0, end, StandardCharsets.UTF_8)
    }

    /** jsonl 侧的文件名（与已退役的 jsonl 存储同一份约定，这里只读不写）。 */
    private object JournalName {
        const val FILE = "intent-log.jsonl"
    }
}
