package com.autoscript.appservice.scriptrepo.core

import com.autoscript.domain.scripts.ProjectId
import com.autoscript.domain.scripts.RelPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ProjectStoreTest {

    @Test
    fun `非法 id 一律拒绝`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        // **判据自 2026-10-12 批 109 起是 `ScriptPaths.PROJECT_ID` 一份**（`[A-Za-z0-9_-]+`），
        // 本类原先那份 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}` 已删除。变化有两处要钉住：
        //   - `a.repeat(65)` **不再非法**（限长 64 是被删掉那份的规则，现在没有长度上限）；
        //   - `a.b` **新增为非法**（收 `.` 那条已从本类移到 `ScriptPaths` 侧并早就在那儿）。
        val bad = listOf(
            "", "..", ".", "/etc", "a/b", "a\\b", "has space",
            ".hidden", " a", "a\n", "p.1",
        )
        for (id in bad) {
            assertThrows(IllegalArgumentException::class.java, { store.rootFor(ProjectId(id)) }, "应拒绝 id: $id")
        }
    }

    /** 判据收窄后**仍然合法**的那一组（批 109 把它钉住，别再被顺手删掉某个）。 */
    @Test
    fun `合法 id 全放行`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        for (id in listOf("demo", "b-project", "a_b", "9lives", "A", "-lead", "under_9")) {
            assertTrue(store.create(ProjectId(id)).toString().endsWith("/$id"), "应放行 id: $id")
            assertTrue(store.exists(ProjectId(id)))
        }
        assertEquals(
            listOf("-lead", "9lives", "A", "a_b", "b-project", "demo", "under_9"),
            store.listIds().map { it.value },
            "枚举按字典序（'A' < 'a'）",
        )
    }

    @Test
    fun `创建-枚举-读取-删除 生命周期`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        assertTrue(store.listIds().isEmpty())

        store.create(ProjectId("demo"))
        store.create(ProjectId("b-project"))
        assertEquals(listOf("b-project", "demo"), store.listIds().map { it.value }, "id 枚举按字典序")

        assertTrue(store.exists(ProjectId("demo")))
        assertFalse(store.exists(ProjectId("nope")))
        assertTrue(store.rootFor(ProjectId("demo")).startsWith(tmp), "root 必须落在仓库根下")

        store.delete(ProjectId("demo"))
        assertFalse(store.exists(ProjectId("demo")))
        assertEquals(listOf("b-project"), store.listIds().map { it.value })
    }

    @Test
    fun `已存在则不重复创建`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        val p1 = store.create(ProjectId("demo"))
        val p2 = store.create(ProjectId("demo"))
        assertEquals(p1, p2)
    }
}