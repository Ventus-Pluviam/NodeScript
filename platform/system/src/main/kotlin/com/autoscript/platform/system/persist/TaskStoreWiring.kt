package com.autoscript.platform.system.persist

import android.content.Context
import com.autoscript.domain.scripts.TaskStore
import com.autoscript.domain.scripts.TaskStoreImport
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 任务注册表存储的**打开与一次性迁移**（§8.6；2026-10-11 批 102 P4 阶段 C）。
 *
 * 与 [IntentStoreWiring] **同一套口径**（不发明第二种迁移纪律）：
 * 1. **迁移**（[migrate]）：老设备上已有 `tasks.jsonl`。库为空且 jsonl 非空时把历史导进去
 *    （**连墓碑一起**），然后把 jsonl 改名归档（`tasks.jsonl.migrated`）；
 * 2. **打开**（[open]）：开 SQLite，**无回落** —— 打不开就是失败，异常原样抛给装配层。
 *
 * **为什么墓碑必须一起导**（本条最易漏的一半）：jsonl 的 `del` 是**墓碑行**不是删行。
 * 只导存活行 ⇒「历史上撤销过某个 id」在迁移后变成「那个任务复活了」—— 用户看到的是
 * 「我没登记过的任务自己开始跑」。故 [SqliteTaskStore.allWithRevoked] 把墓碑也读出来，
 * [TaskStoreImport] 也把它们解析出来。
 *
 * **可重入**：库非空即跳过（本类是这份库的唯一写者，读-判-写之间没有并发写者）；
 * 库空而 jsonl 已归档 → 无事可做。**失败响亮**：解析抛错时异常上抛，**老文件原样留着**
 * （改名在验完之后），不会出现「导了一半、老的又没了」。
 */
object TaskStoreWiring {

    /** jsonl 归档名（迁移成功后由 [migrate] 改名到此；不再被任何读者打开）。 */
    const val ARCHIVED_NAME: String = "tasks.jsonl.migrated"

    /** jsonl 侧的文件名（与 `FileTaskStore` 同一份约定，这里只读不写）。 */
    private const val JSONL_NAME: String = "tasks.jsonl"

    /**
     * 打开 Android 生产用的任务注册表存储，并在需要时先做一次性迁移。
     *
     * @param context Android 上下文（`SQLiteOpenHelper` 用）
     * @param nsDir `.ns` 目录（老 jsonl 就在它下面 —— 与既有 `FileTaskStore` 同一处）
     */
    fun open(context: Context, nsDir: Path): TaskStore =
        SqliteTaskStore(AndroidSqliteRunner(context, AndroidSqliteRunner.DB_NAME_TASK_REGISTRY)).also { store ->
            migrate(store, nsDir.resolve(JSONL_NAME))
        }

    /**
     * 一次性导入：库为空且 jsonl 非空 → 全量导入（含墓碑）+ 归档 jsonl。
     *
     * @return 导入的行数（0 = 无需导入：库非空，或 jsonl 不存在/为空）
     * @throws IOException jsonl 读失败
     * @throws IllegalArgumentException jsonl 行损坏（**响亮失败**，不静默丢历史）
     */
    fun migrate(store: SqliteTaskStore, jsonl: Path): Int {
        if (store.allWithRevoked().isNotEmpty()) return 0     // 库非空 = 迁过了
        if (!Files.isRegularFile(jsonl)) return 0
        val rows = TaskStoreImport.parse(readText(jsonl))
        if (rows.isEmpty()) return 0
        val imported = store.importTasks(rows.map { it.task to it.revokedAtMillis })
        // 归档：改名（不删）。改名前所有解析与导入都已完成 —— 导入抛错时老文件原样在原处。
        Files.move(
            jsonl,
            jsonl.resolveSibling(ARCHIVED_NAME),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        return imported
    }

    /**
     * 读 jsonl 文本：**尾部半行先截掉再解码**（与写路径的 force 组口径一致 ——
     * 没落完的行等于没发生）。按字节找最后一个 `\n`、再整体 UTF-8 解码：
     * 按块解码会把跨块的多字节字符解坏，而这里连「最后一行是半行」都要先判掉。
     */
    private fun readText(path: Path): String {
        val bytes = Files.readAllBytes(path)
        var end = bytes.size
        while (end > 0 && bytes[end - 1] != '\n'.code.toByte()) end--
        return String(bytes, 0, end, StandardCharsets.UTF_8)
    }
}
