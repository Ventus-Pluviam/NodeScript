package com.autoscript.appservice.npm

import com.autoscript.domain.json.DomainJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 自动补出来的 `package.json`（2026-10-17 用户口径「应该让它缺的时候自动创建呀」）。
 *
 * 两件事各钉一处：
 * - [ProjectManifest.npmNameOf]：项目号判据 2026-10-16 放宽到"拒分隔符与点"（中文、大写、
 *   空格、`@` 都合法），而 npm 的包名只认小写 URL-safe —— 名字没洗好就是
 *   `EINVALIDPACKAGENAME`，等于把"缺清单"换成"清单不合法"，用户照样装不上；
 * - [ProjectManifest.minimal]：形状（三个字段 + **不写 `main`**）。
 */
class ProjectManifestTest {

    /** npm 认的包名形态（`validate-npm-package-name` 那套的保守子集）。 */
    private val npmNameOk = Regex("^[a-z0-9][a-z0-9-]*$")

    @Test
    fun `名字洗成 npm 认的形态`() {
        // 左边是项目号（判据允许的形态），右边是洗完的名字。
        val cases = mapOf(
            "demo" to "demo",
            "v1-2" to "v1-2",
            "My App" to "my-app",
            "A@B" to "a-b",
            "--a--b--" to "a-b",
            "  spaced  " to "spaced",
            // 中文项目号：一个合法字符都留不下 → 兜底。名字在这里只有展示意义
            // （private 项目，不发布、不参与解析），数据仍在项目号上。
            "我的项目" to "autoscript-project",
            "项目-demo" to "demo",
            "" to "autoscript-project",
        )
        for ((projectId, expected) in cases) {
            assertEquals(expected, ProjectManifest.npmNameOf(projectId), "项目号「$projectId」")
        }
    }

    @Test
    fun `洗出来的名字一律是 npm 认的形态`() {
        // 判据放宽后的真实输入面：中文、大写、空格、`@`、`#`、emoji、超长、纯符号。
        val wide = listOf(
            "demo", "我的项目", "My App", "a@b", "x#y", "🚀发射", "-", "--", "__",
            "a".repeat(300), "中文".repeat(100), "0", "9lives",
        )
        for (projectId in wide) {
            val name = ProjectManifest.npmNameOf(projectId)
            assertTrue(npmNameOk.matches(name), "项目号「$projectId」洗出了 npm 不认的名字「$name」")
            assertTrue(name.length <= 214, "名字超了 npm 的 214 字符上限：${name.length}")
        }
    }

    @Test
    fun `缺省清单是能解析的 JSON，三个字段就位`() {
        val fields = DomainJson.decodeObject(ProjectManifest.minimal("demo"))
        assertEquals("demo", (fields["name"] as DomainJson.Value.S).v)
        assertEquals("1.0.0", (fields["version"] as DomainJson.Value.S).v)
        assertEquals(true, (fields["private"] as DomainJson.Value.B).v, "private: true 挡住误发布")
    }

    @Test
    fun `缺省清单刻意不写 main——入口判据原样留给 ProjectIndex`() {
        // `ProjectIndex.resolveMain`：有 package.json 就找 `main`，找不到退 `main.js`/`index.js`。
        // 不写 `main` ⇒ 这份自动生成的清单对入口判断零影响，用户原有的脚本照旧找得到。
        val fields = DomainJson.decodeObject(ProjectManifest.minimal("demo"))
        assertFalse(fields.containsKey("main"), "写了 main 就得替用户答一个我们并不知道的入口：$fields")
    }

    @Test
    fun `正文末尾带换行`() {
        assertTrue(ProjectManifest.minimal("demo").endsWith("\n"), "落盘的文件该像个文本文件")
    }
}
