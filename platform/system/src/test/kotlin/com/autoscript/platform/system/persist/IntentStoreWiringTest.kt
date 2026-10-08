package com.autoscript.platform.system.persist

import com.autoscript.domain.scripts.IntentStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * §8.5 **一次性迁移**（jsonl → SQLite）的验证：老设备升级后已提交的 nonce 与 runId 号段
 * 必须原样搬到新库（理由见 `IntentStoreWiring` 的 KDoc —— 不迁会让新旧记录同号异义）。
 *
 * 执行体是宿主 `sqlite3`（[CliSqlRunner]），与契约套件同一条路子；**未覆盖**
 * `AndroidSqliteRunner` 与 `IntentStoreWiring.open(context, …)` 的 Android 那一跳
 * （那半边只有真机能验，本轨未验）。
 */
class IntentStoreWiringTest {

    @TempDir
    lateinit var dir: Path

    private val sqliteAvailable: Boolean by lazy {
        try {
            val p = ProcessBuilder("sqlite3", "--version").redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    @BeforeEach
    fun requireSqlite() {
        assumeTrue(sqliteAvailable, "宿主没有 sqlite3 CLI，无法在本机跑 SQLite 语义（不假扮通过）")
    }

    private fun newStore() = SqliteIntentStore(CliSqlRunner(dir.resolve("intent-log.db")))

    private fun jsonl(vararg lines: String): Path {
        val f = dir.resolve("intent-log.jsonl")
        Files.write(f, lines.joinToString("").toByteArray())
        return f
    }

    /** 与已退役的 jsonl 存储落盘形态逐字一致的 start 行（老版本无 args/timeout 两键）。 */
    private fun startLine(
        runId: Long,
        nonce: String,
        withPayload: Boolean = true,
    ) = """{"op":"start","runId":$runId,"projectId":"p","scriptPath":"a.js","runNonce":"$nonce",""" +
        """"trigger":"TIMED","screen":"SCREEN_ON","scheduledAt":100,"startedAt":200,""" +
        """"deadlineAt":999""" +
        (if (withPayload) ""","args":["--fast","值,含逗号"],"timeoutMillis":7000""" else "") +
        "}\n"

    // 注意尾部的换行写成**普通字符串** `"\n"`：raw string 里 `\n` 是两个字符（反斜杠 + n），
    // 那样两行会粘成一行、解析当场报"尾部多余字符"（本测试初版踩过）。
    private fun sealLine(runId: Long, outcome: String, detail: String? = null) =
        """{"op":"seal","runId":$runId,"outcome":"$outcome",""" +
            """"detail":${detail?.let { "\"$it\"" } ?: "null"},"at":321}""" + "\n"

    @Test
    fun `迁移把历史带着原 runId 与已提交 nonce 一起搬进新库`() {
        val f = jsonl(startLine(3, "old-done"), sealLine(3, "SUCCEEDED"), startLine(7, "old-live"))
        val store = newStore()
        assertEquals(2, IntentStoreWiring.migrate(store, f))

        val rows = store.allRows()
        assertEquals(listOf(3L, 7L), rows.map { it.runId }, "原 runId 必须保留（与 run-archive 的 intentRunId 同号）")
        assertEquals("SUCCEEDED", rows.first { it.runId == 3L }.outcome?.name)
        assertEquals(321L, rows.first { it.runId == 3L }.committedAtMillis)
        assertTrue(store.hasCommittedNonce("old-done"), "已提交 nonce 是幂等锚点，必须搬过来")
        assertEquals(listOf(7L), store.liveRows().map { it.runId }, "存活行照样搬（下次启动按崩溃遗留重投）")
        assertEquals(listOf("--fast", "值,含逗号"), rows.first { it.runId == 3L }.start.args)
        assertEquals(7000L, rows.first { it.runId == 3L }.start.timeoutMillis)
        assertEquals(999L, rows.first { it.runId == 3L }.start.deadlineMillis)
        assertEquals("SCREEN_ON", rows.first { it.runId == 3L }.start.screen)
        store.close()
    }

    @Test
    fun `迁移后新号接在历史之后（不与 run-archive 的 intentRunId 撞号）`() {
        val f = jsonl(startLine(5, "old"))
        val store = newStore()
        IntentStoreWiring.migrate(store, f)
        val next = store.insertStart(
            IntentStore.StartRow("p", "a.js", "brand-new", "TIMED", "ANY", 1, 2, null),
        )
        assertTrue(next > 5, "迁移后自动分配必须接在历史之后，实际 $next")
        store.close()
    }

    @Test
    fun `迁移成功即把 jsonl 归档改名，重跑一次是 no-op`() {
        val f = jsonl(startLine(1, "n"))
        val store = newStore()
        assertEquals(1, IntentStoreWiring.migrate(store, f))
        assertFalse(Files.exists(f), "迁移后 jsonl 必须改名归档（否则两份日志各写各的）")
        assertTrue(Files.exists(dir.resolve(IntentStoreWiring.ARCHIVED_NAME)))
        assertEquals(0, IntentStoreWiring.migrate(store, f), "jsonl 已不在原处 → 无事可做")
        assertEquals(1, store.allRows().size, "重跑不许重复导入")
        store.close()
    }

    @Test
    fun `库非空时迁移整批跳过（幂等可重入）`() {
        val store = newStore()
        store.insertStart(IntentStore.StartRow("p", "a.js", "live", "TIMED", "ANY", 1, 2, null))
        val f = jsonl(startLine(9, "old"))
        assertEquals(0, IntentStoreWiring.migrate(store, f), "库非空 = 迁过了，不导")
        assertTrue(Files.exists(f), "跳过时不动 jsonl（下次真需要时还在）")
        assertEquals(1, store.allRows().size)
        store.close()
    }

    @Test
    fun `jsonl 不存在或为空时不动盘`() {
        val store = newStore()
        assertEquals(0, IntentStoreWiring.migrate(store, dir.resolve("nope.jsonl")))
        val empty = jsonl()
        assertEquals(0, IntentStoreWiring.migrate(store, empty))
        assertTrue(store.allRows().isEmpty())
        store.close()
    }

    @Test
    fun `尾部半行按写侧口径丢弃（崩溃截断不留脏历史）`() {
        val f = jsonl(startLine(1, "a"), """{"op":"seal","runId":1,"outcome":"SUC""")
        val store = newStore()
        assertEquals(1, IntentStoreWiring.migrate(store, f))
        assertEquals("", store.allRows().single().outcome?.name ?: "")
        assertFalse(store.hasCommittedNonce("a"), "半行没落完 = 没发生（与老写侧同口径）")
        store.close()
    }

    @Test
    fun `老版本行缺 args 与 timeout 两键按默认解析（升级兼容）`() {
        val f = jsonl(startLine(1, "legacy", withPayload = false))
        val store = newStore()
        IntentStoreWiring.migrate(store, f)
        val row = store.allRows().single()
        assertEquals(emptyList<String>(), row.start.args)
        assertEquals(null, row.start.timeoutMillis)
        store.close()
    }

    @Test
    fun `jsonl 行损坏时响亮失败且不动 jsonl（老日志原样留着）`() {
        val f = jsonl(startLine(1, "a"), "{\"op\":\"seal\",\"runId\":99,\"outcome\":\"SUCCEEDED\",\"detail\":null,\"at\":1}\n")
        val store = newStore()
        assertThrows(IllegalArgumentException::class.java) { IntentStoreWiring.migrate(store, f) }
        assertTrue(Files.exists(f), "导入失败不许把老日志搬走")
        assertTrue(store.allRows().isEmpty(), "失败即整批不落（事务）")
        store.close()
    }

    @Test
    fun `INTERRUPTED 历史行不占幂等锚点`() {
        val f = jsonl(startLine(1, "rn"), sealLine(1, "INTERRUPTED"))
        val store = newStore()
        IntentStoreWiring.migrate(store, f)
        assertFalse(store.hasCommittedNonce("rn"), "Interrupted 不构成完成的副作用")
        // 迁移过来的 nonce 仍可再投（与 jsonl 侧一致）
        store.insertStart(IntentStore.StartRow("p", "a.js", "rn", "TIMED", "ANY", 1, 2, null))
        store.close()
    }
}
