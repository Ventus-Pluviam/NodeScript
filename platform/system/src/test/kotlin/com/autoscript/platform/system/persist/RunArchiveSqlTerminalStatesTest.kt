package com.autoscript.platform.system.persist

import com.autoscript.domain.scripts.RunState
import com.autoscript.domain.scripts.isTerminal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 终态集合的**单源**守卫（§8.2 终态不可改写/复活）。
 *
 * 为什么单独一个类、且**不继承契约套件**：契约套件那半边用 `sqlite3` CLI 跑真引擎、
 * 带 `assumeTrue` 环境门禁；而这里钉的是纯 Kotlin 的集合派生，不需要任何引擎，
 * 也不该因为宿主没有 `sqlite3` 就诚实跳过（那条会退化成"一片绿的假通过"）。
 *
 * 判据只有一份、且**从 `:domain` 派生**：
 * - Kotlin 侧（`SqliteRunArchive.put` 的 `check`）直接用 `RunState.isTerminal`；
 * - SQL 侧（`advanceState` 的 `WHERE state NOT IN (...)`、`Query.Unfinished` 的同一道闸）
 *   用 [RunArchiveSql.TERMINAL_STATES]，它由 `RunState.entries.filter { it.isTerminal }`
 *   生成 —— 此前那份是**手写列表**，注释写着「改一处必须看另一处」，而**没有守卫**。
 *   漂移的方向是坏的：`RunState` 加一个终态后 SQL 侧没跟上，那个状态会被当成中间态
 *   放行（终态记录被改写/复活），且**只有 SQLite 实现错**（另两个实现用的是 domain 判据）
 *   —— 这种只有一家实现错的分歧最难在契约套件里看出来。
 *
 * 本类断言的是「同一份判据的两个投影逐字相等」，不是"用了派生"这个实现细节：
 * 有人改回手写列表但抄对了，本类照旧绿（判据仍然一致）；抄漏一个就红。
 */
class RunArchiveSqlTerminalStatesTest {

    @Test
    fun `SQL 侧终态集合逐字等于 domain 判据选出的那些`() {
        assertEquals(
            RunState.entries.filter { it.isTerminal }.map { it.name }.sorted(),
            RunArchiveSql.TERMINAL_STATES.sorted(),
            "TERMINAL_STATES 必须与 domain 的 isTerminal 一致（漂了会让终态记录在 SQLite 侧复活）",
        )
    }

    @Test
    fun `IN 字面量逐条对上集合 —— 不多不少且都带引号`() {
        // 手写时代最容易在这里漏：集合加了一个而字面量没跟上；或者名字没带引号（被当成
        // 列名，整条语句在真机上炸而不是静默错 —— 那也是红的，只是晚一点、在别处）。
        val fromSql = RunArchiveSql.TERMINAL_IN.split(", ").map { it.removeSurrounding("'") }
        assertEquals(
            RunArchiveSql.TERMINAL_STATES.sorted(),
            fromSql.sorted(),
            "IN (...) 的字面量必须与集合逐条对上，实际：${RunArchiveSql.TERMINAL_IN}",
        )
        RunArchiveSql.TERMINAL_STATES.forEach { name ->
            assertTrue(
                RunArchiveSql.TERMINAL_IN.contains("'$name'"),
                "每个值都必须以单引号形式出现在 IN 里（否则被当成列名）：$name",
            )
        }
    }

    @Test
    fun `中间态不在终态集合里 —— 否则它们永远无法前进`() {
        // 反向的坏法：把 PENDING/RUNNING 混进终态集合，`advanceState` 的
        // `WHERE state NOT IN (...)` 会挡掉一切正常前进（每条 run 第一次推进就 0 行，
        // 被折成「已终态」）。派生实现下这条不可能红，但它同时钉住了 `isTerminal` 的
        // 语义边界 —— 有人把它改成 `state != PENDING` 之类，这里立刻红。
        val nonTerminal = RunState.entries.filterNot { it.isTerminal }.map { it.name }
        nonTerminal.forEach { name ->
            assertFalse(
                RunArchiveSql.TERMINAL_STATES.contains(name),
                "中间态 $name 不得进终态集合（会挡掉一切正常前进）",
            )
        }
        assertEquals(
            listOf("PENDING", "RUNNING"),
            nonTerminal.sorted(),
            "本仓的中间态只有这两个（§8.2）；多一个就意味着有一个状态既非终态又无出口",
        )
    }

    @Test
    fun `条件更新与在途查询用的都是这一份集合`() {
        // 集合派生对了、但语句没用它（各写一份字面量）同样会漂。这里钉住两处消费点：
        // 写口的终态闸（不可改写/复活）与读口的在途判据（sweep 靠它挑未完成的 run）。
        val update = RunArchiveSql.advanceState(
            RunArchiveRecord(1, "p1", "a.js", "n", RunState.RUNNING),
        ).dml.single()
        assertTrue(
            update.contains("state NOT IN (${RunArchiveSql.TERMINAL_IN})"),
            "advanceState 的终态闸必须来自 TERMINAL_IN，实际语句：$update",
        )
        val unfinished = RunArchiveSql.select(RunArchiveSql.Query.Unfinished).query
        assertTrue(
            unfinished.contains("state NOT IN (${RunArchiveSql.TERMINAL_IN})"),
            "在途查询必须用同一份集合，实际语句：$unfinished",
        )
    }

    @Test
    fun `每个领域终态都真出现在两条语句的闸里（含契约套件没走到的 CRASHED CANCELLED）`() {
        // 上面那条只钉住「语句用的是 TERMINAL_IN」，这条逐个点名 —— 因为**契约套件
        // 走不到这里**：`RunArchiveContract` 的终态用例只拿 `SUCCEEDED` 当既存终态、
        // 拿 `RUNNING`/`FAILED` 当改写尝试，所以「一条已经是 CRASHED/CANCELLED 的记录
        // 不许被推进」这件事在两个实现上都没有用例。手写列表漏掉 CRASHED 时，契约套件
        // 全绿，而真机上被看门狗判 CRASHED 的档案随后会被任何一次 `put` 覆盖。
        val update = RunArchiveSql.advanceState(
            RunArchiveRecord(1, "p1", "a.js", "n", RunState.RUNNING),
        ).dml.single()
        val unfinished = RunArchiveSql.select(RunArchiveSql.Query.Unfinished).query
        RunState.entries.filter { it.isTerminal }.forEach { state ->
            assertTrue(
                update.contains("'${state.name}'"),
                "${state.name} 必须被 advanceState 的终态闸点名（否则该状态可被改写）",
            )
            assertTrue(
                unfinished.contains("'${state.name}'"),
                "${state.name} 必须被在途查询排除（否则 sweep 会反复挑它、并按未完成投递）",
            )
        }
    }
}
