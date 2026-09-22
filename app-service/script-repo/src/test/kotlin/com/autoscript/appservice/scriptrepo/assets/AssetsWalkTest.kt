package com.autoscript.appservice.scriptrepo.assets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 递归枚举的纯逻辑单测（docs/framework-design.md §9.6 assets → filesDir 原子部署）。
 * 走 [AssetsWalk.walk] 接缝，不依赖 android.jar，故可在本机 JVM 上跑。
 */
class AssetsWalkTest {

    /** 用 Map 建模 assets 树；[list] 只返当前层，目录名带尾 '/'（Android 真机行为）。 */
    private fun tree(vararg files: String): Pair<Map<String, ByteArray>, (String) -> Array<String>?> {
        val map = files.associateWith { it.toByteArray() }
        val list: (String) -> Array<String>? = { dir ->
            if (dir.isEmpty()) null
            else {
                val prefix = "$dir/"
                val names = LinkedHashSet<String>()
                for (k in map.keys) {
                    if (k.startsWith(prefix)) {
                        val rest = k.removePrefix(prefix)
                        val slash = rest.indexOf('/')
                        names.add(if (slash >= 0) rest.substring(0, slash + 1) else rest)
                    }
                }
                names.toTypedArray()
            }
        }
        return map to list
    }

    @Test
    fun `递归枚举含子目录，键为相对路径`() {
        val (map, list) = tree("scripts/demo/main.js", "scripts/demo/lib/util.js", "scripts/demo/lib/deep/deeper.js")
        val got = AssetsWalk.walk("scripts/demo", list) { ByteArrayInputStream(map[it]!!) as InputStream }
        assertEquals(
            listOf("lib/deep/deeper.js", "lib/util.js", "main.js"),
            got.keys.sorted(),
        )
        assertEquals("scripts/demo/lib/deep/deeper.js", got["lib/deep/deeper.js"]!!.decodeToString())
    }

    @Test
    fun `目录名不带尾斜杠时靠 list 兜底判定`() {
        val map = mapOf("scripts/d/lib/a.js" to "a".toByteArray())
        val list: (String) -> Array<String>? = { dir ->
            when (dir) {
                "scripts/d" -> arrayOf("lib")            // 不带 '/' 的目录名
                "scripts/d/lib" -> arrayOf("a.js")
                else -> null
            }
        }
        val got = AssetsWalk.walk("scripts/d", list) { ByteArrayInputStream(map[it]!!) }
        assertEquals(listOf("lib/a.js"), got.keys.toList())
    }

    @Test
    fun `空目录与空文件都不产生条目，空目录不无限递归`() {
        val map = mapOf("scripts/d/main.js" to "m".toByteArray(), "scripts/d/empty.txt" to ByteArray(0))
        val list: (String) -> Array<String>? = { dir ->
            when (dir) {
                "scripts/d" -> arrayOf("main.js", "empty.txt", "void/")
                else -> null                             // void/ list 出空 → 非目录
            }
        }
        val got = AssetsWalk.walk("scripts/d", list) { ByteArrayInputStream(map[it] ?: ByteArray(0)) }
        assertEquals(listOf("empty.txt", "main.js"), got.keys.sorted())
        assertTrue(got["empty.txt"]!!.isEmpty())
    }

    @Test
    fun `根目录不存在返回空而非异常`() {
        val got = AssetsWalk.walk("scripts/missing", { null }) { ByteArrayInputStream(ByteArray(0)) }
        assertTrue(got.isEmpty())
    }
}
