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
        // **判据自 2026-10-12 批 109 起是 `ScriptPaths.PROJECT_ID` 一份**，本类原先那份
        // `[A-Za-z0-9][A-Za-z0-9._-]{0,63}` 已删除。变化有三处要钉住：
        //   - `a.repeat(65)` **不再非法**（限长 64 是被删掉那份的规则，现在没有长度上限）；
        //   - `a.b` **新增为非法**（收 `.` 那条已从本类移到 `ScriptPaths` 侧并早就在那儿）；
        //   - **2026-10-16 判据放宽成"排除分隔符与点"**（用户裁定"允许中文"）⇒
        //     `"has space"` 与 `" a"` 从本表**移出**（按新判据它们合法）；`""` 也不再是
        //     "构造得出但 rootFor 会拒" —— 它是根层哨兵，`rootFor(ROOT)` 就是库根本身，
        //     闸改设在**写侧两个口**（见 `根层哨兵不建不删`）。留下的都是字符集判据拒的。
        val bad = listOf("..", ".", "/etc", "a/b", "a\\b", ".hidden", "a\n", "p.1")
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

    /**
     * **根层哨兵（[ProjectId.ROOT] = 空串）不是项目**：`rootFor(ROOT)` 就是库根本身
     * （`Path.resolve("")` 恒等），所以它**读**得通（`exists(ROOT)` 问的是"库根在不在"，
     * 答案本身没错）；**写**的两个口都得拒 —— 2026-10-16 之前空串连构造都构造不出来，
     * 哨兵一放宽，`delete(ROOT)` 就成了"把整个项目库连根删掉"（本测试钉的就是这一条）。
     */
    @Test
    fun `根层哨兵不建不删`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        store.create(ProjectId("demo"))
        // 这条恒等式是"引擎侧零改动"的依据：哨兵拼出来的根就是库根。
        assertEquals(tmp, store.rootFor(ProjectId.ROOT), "根层哨兵的 root 是库根本身")
        assertThrows(IllegalArgumentException::class.java) { store.create(ProjectId.ROOT) }
        assertThrows(IllegalArgumentException::class.java) { store.delete(ProjectId.ROOT) }
        assertTrue(store.exists(ProjectId("demo")), "被拒的 delete 一个文件都不许删")
        assertTrue(store.exists(ProjectId.ROOT), "exists(ROOT) 是读口：库根在不在")
    }

    @Test
    fun `已存在则不重复创建`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        val p1 = store.create(ProjectId("demo"))
        val p2 = store.create(ProjectId("demo"))
        assertEquals(p1, p2)
    }
}