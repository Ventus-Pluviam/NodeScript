package com.autoscript.appservice.scriptrepo.core

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
        val bad = listOf(
            "", "..", ".", "/etc", "a/b", "a\\b", "has space",
            ".hidden", "a".repeat(65), " a", "a\n",
        )
        for (id in bad) {
            assertThrows(IllegalArgumentException::class.java, { store.rootFor(id) }, "应拒绝 id: $id")
        }
    }

    @Test
    fun `创建-枚举-读取-删除 生命周期`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        assertTrue(store.listIds().isEmpty())

        store.create("demo")
        store.create("b-project")
        assertEquals(listOf("b-project", "demo"), store.listIds(), "id 枚举按字典序")

        assertTrue(store.exists("demo"))
        assertFalse(store.exists("nope"))
        assertTrue(store.rootFor("demo").startsWith(tmp), "root 必须落在仓库根下")

        store.delete("demo")
        assertFalse(store.exists("demo"))
        assertEquals(listOf("b-project"), store.listIds())
    }

    @Test
    fun `已存在则不重复创建`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        val p1 = store.create("demo")
        val p2 = store.create("demo")
        assertEquals(p1, p2)
    }
}