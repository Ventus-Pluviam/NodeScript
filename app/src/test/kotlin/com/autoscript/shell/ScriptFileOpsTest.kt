package com.autoscript.shell

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 新建文件/文件夹落盘（[ScriptFileOps]）的裁决面：
 * - 合法名字落到 `files/scripts/<projectId>/<dirRelPath>/<name>`（路径出处在
 *   [com.autoscript.domain.scripts.ScriptPaths]）；父文件夹由 `dirRelPath` 说（2026-10-16
 *   用户口径「在当前进入的文件夹中新建」），**必须已存在**、不许越界、不许跨项目；
 * - 非法名字（含 `/`、`..`、空）拒绝 —— 原文抛，呈现层不写第二套判据；
 * - **不覆盖已存在**（撞名抛 `FileAlreadyExistsException`）；
 * - 项目目录不存在拒绝（而不是静默建到别处）。
 *
 * 编辑面（[ScriptFileOps.read]/[ScriptFileOps.save]，项目页点文件进编辑）：
 * - 读回原样、存后读得到新内容，且**不留临时文件**（原子替换的副产物必须清掉）；
 * - **relPath 是 `files/scripts/` 之下的路径（首段 = 项目名）**，与 `ScriptFileRow.relPath` 同形；
 *   写成"项目内相对路径"当场抛 —— 2026-10-06 实机踩过（被拼成 `…/demo/demo/main.js`，编辑器报"不是文件"）；
 * - 越界 relPath（`..`/绝对路径）在**读取侧也拒绝**；
 * - 目录不是文件、保存**不新建**（新建归 createFile）；
 * - 二进制（含 NUL）与超限文件读时拒绝 —— 当文本打开再存回去就是损坏用户文件。
 *
 * 删除与绝对路径（[ScriptFileOps.delete]/[ScriptFileOps.absolutePath]，2026-10-17 用户口径
 * 「文件列表菜单加个删除 + 复制绝对路径」）：
 * - 删**文件夹递归整棵**（非空文件夹删不掉等于菜单里那格没做），删完项目本身留着；
 * - 删不存在的条目**拒**（静默成功会把"删错了对象"与"删掉了"画成同一件事）；
 * - 删除/绝对路径的**越界与归属判据与读写口同源**（同一个 `resolveInProject`）；
 * - 绝对路径**不查存在性**（刚删掉的行照样复制得出它原来在哪）。
 */
class ScriptFileOpsTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `新建文件与文件夹落位正确`() {
        Files.createDirectories(dir.resolve("scripts/demo"))
        ScriptFileOps.createFile(dir, "demo", "main.js")
        ScriptFileOps.createFolder(dir, "demo", "lib")
        assertTrue(Files.isRegularFile(dir.resolve("scripts/demo/main.js")))
        assertTrue(Files.isDirectory(dir.resolve("scripts/demo/lib")))
        // 新文件是空的（占位，不是模板）。
        assertEquals(0L, Files.size(dir.resolve("scripts/demo/main.js")))
    }

    @Test
    fun `指定父文件夹时落在那一层`() {
        Files.createDirectories(dir.resolve("scripts/demo/lib"))
        ScriptFileOps.createFile(dir, "demo", "main.js", "demo/lib")
        // 目录行的 relPath 带尾随 `/`（契约约定），新建那条链路原样传下来也要认。
        ScriptFileOps.createFolder(dir, "demo", "deep", "demo/lib/")
        assertTrue(Files.isRegularFile(dir.resolve("scripts/demo/lib/main.js")))
        assertTrue(Files.isDirectory(dir.resolve("scripts/demo/lib/deep")))
        // 项目根那一层**没有**多出来 —— "落错层"的典型形态就是文件出现在上一层。
        assertTrue(!Files.exists(dir.resolve("scripts/demo/main.js")))
        assertTrue(!Files.exists(dir.resolve("scripts/demo/deep")))
    }

    @Test
    fun `父文件夹不存在拒绝且不顺路建层`() {
        Files.createDirectories(dir.resolve("scripts/demo"))
        val e = assertThrows(IllegalArgumentException::class.java) {
            ScriptFileOps.createFile(dir, "demo", "main.js", "demo/nope")
        }
        // 原文给 UI：`Files.createFile` 自己抛的是一句机器话（NoSuchFileException）。
        assertTrue(e.message?.contains("文件夹不存在") == true, "实际：${e.message}")
        // 不用 createDirectories 顺路把中间层建出来（错字会静默变成一个目录层级）。
        assertTrue(!Files.exists(dir.resolve("scripts/demo/nope")))
    }

    @Test
    fun `父文件夹越界或跨项目拒绝`() {
        Files.createDirectories(dir.resolve("scripts/demo"))
        Files.createDirectories(dir.resolve("scripts/other"))
        // `..` 穿出去 → 落到别的项目；首段不是本项目 → 直接对不上号。两条都拒。
        assertThrows(IllegalArgumentException::class.java) {
            ScriptFileOps.createFile(dir, "demo", "x.js", "demo/../other")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptFileOps.createFile(dir, "demo", "x.js", "other")
        }
        assertTrue(!Files.exists(dir.resolve("scripts/other/x.js")), "越界的落点一个都不许被写出来")
    }

    @Test
    fun `父文件夹指向文件时拒绝`() {
        Files.createDirectories(dir.resolve("scripts/demo"))
        Files.write(dir.resolve("scripts/demo/main.js"), "x".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            ScriptFileOps.createFile(dir, "demo", "y.js", "demo/main.js")
        }
        assertTrue(!Files.exists(dir.resolve("scripts/demo/y.js")))
    }

    @Test
    fun `名字含路径段拒绝`() {
        Files.createDirectories(dir.resolve("scripts/demo"))
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.createFile(dir, "demo", "lib/main.js") }
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.createFile(dir, "demo", "../escape.js") }
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.createFile(dir, "demo", ".") }
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.createFile(dir, "demo", "  ") }
        // 什么都没落下来。
        assertEquals(0, Files.list(dir.resolve("scripts/demo")).use { it.count() })
    }

    @Test
    fun `撞名不覆盖`() {
        Files.createDirectories(dir.resolve("scripts/demo"))
        Files.write(dir.resolve("scripts/demo/main.js"), "keep".toByteArray())
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            ScriptFileOps.createFile(dir, "demo", "main.js")
        }
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            ScriptFileOps.createFolder(dir, "demo", "main.js")
        }
        // 原内容原样。
        assertEquals("keep", String(Files.readAllBytes(dir.resolve("scripts/demo/main.js"))))
    }

    @Test
    fun `项目不存在拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.createFile(dir, "nope", "x.js") }
        // 没有替用户建出 nope 目录（静默建目录 = 错字也建出空项目）。
        assertTrue(!Files.isDirectory(dir.resolve("scripts/nope")))
    }

    @Test
    fun `读回原样_保存覆盖且不留临时文件`() {
        val project = dir.resolve("scripts/demo")
        Files.createDirectories(project.resolve("lib"))
        Files.write(project.resolve("lib/main.js"), "const a = 1;\n".toByteArray(Charsets.UTF_8))
        // 子文件夹里的文件靠 relPath 定位（不是单段名字；首段是项目名）。
        assertEquals("const a = 1;\n", ScriptFileOps.read(dir, "demo", "demo/lib/main.js"))
        ScriptFileOps.save(dir, "demo", "demo/lib/main.js", "const a = 2;\n")
        assertEquals("const a = 2;\n", ScriptFileOps.read(dir, "demo", "demo/lib/main.js"))
        // 原子替换的临时文件必须清掉：项目目录里只剩用户那一个文件。
        assertEquals(listOf("main.js"), Files.list(project.resolve("lib")).use { s -> s.map { it.name }.sorted().toList() })
    }

    @Test
    fun `relPath 必须带项目名前缀`() {
        // 2026-10-06 实机踩过的坑：呈现层手里那条是 ScriptFileRow.relPath（首段 = 项目名），
        // 而实现按"项目根之下"去拼 —— 读成了 files/scripts/demo/demo/main.js，编辑器于是
        // 报"不是文件：demo/main.js"（点文件进不去编辑）。现在只有一种写法合法。
        val project = dir.resolve("scripts/demo")
        Files.createDirectories(project)
        Files.write(project.resolve("main.js"), "x".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.read(dir, "demo", "main.js") }
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.save(dir, "demo", "main.js", "pwn") }
        // 首段不是本项目也拒：别的项目里的同名文件不该从这个入口读到/写到。
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.read(dir, "demo", "other/main.js") }
        // 带前缀的写法读得到 —— 这才是呈现层实际传的那条。
        assertEquals("x", ScriptFileOps.read(dir, "demo", "demo/main.js"))
    }

    @Test
    fun `读写都拒绝越界路径`() {
        val project = dir.resolve("scripts/demo")
        Files.createDirectories(project)
        Files.write(project.resolve("main.js"), "x".toByteArray())
        for (bad in listOf("../escape.js", "/etc/passwd", "demo/lib/../../escape.js", "")) {
            assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.read(dir, "demo", bad) }
            assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.save(dir, "demo", bad, "pwn") }
        }
        // 项目根之外什么都没被写出来。
        assertTrue(!Files.exists(dir.resolve("scripts/escape.js")))
    }

    @Test
    fun `目录不是文件_保存不新建`() {
        val project = dir.resolve("scripts/demo")
        Files.createDirectories(project.resolve("lib"))
        // 目录行的 relPath 以 / 结尾（契约约定）：削掉结尾的 / 之后仍然是目录 → "目录不是文件"。
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.read(dir, "demo", "demo/lib/") }
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.save(dir, "demo", "demo/new.js", "x") }
        // 保存**不新建**（新建走 createFile，撞名/命名裁决在那里）。
        assertTrue(!Files.exists(project.resolve("new.js")))
    }

    @Test
    fun `二进制与超限文件读时拒绝`() {
        val project = dir.resolve("scripts/demo")
        Files.createDirectories(project)
        Files.write(project.resolve("bin.dat"), byteArrayOf(0x41, 0x00, 0x42))
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.read(dir, "demo", "demo/bin.dat") }
        Files.write(project.resolve("big.js"), ByteArray((ScriptFileOps.MAX_EDIT_BYTES + 1).toInt()))
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.read(dir, "demo", "demo/big.js") }
    }

    @Test
    fun `无 NUL 的非法 UTF8 拒绝且原始字节不变`() {
        val project = Files.createDirectories(dir.resolve("scripts/demo"))
        val invalid = listOf(
            byteArrayOf(0xC3.toByte(), 0x28),
            byteArrayOf(0xE4.toByte(), 0xB8.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
        )
        for (bytes in invalid) {
            val target = project.resolve("bad.js")
            Files.write(target, bytes)
            val error = assertThrows(IllegalArgumentException::class.java) {
                ScriptFileOps.read(dir, "demo", "demo/bad.js")
            }
            assertTrue(error.message!!.contains("UTF-8"))
            assertArrayEquals(bytes, Files.readAllBytes(target))
        }
    }

    // ── 根层（[com.autoscript.domain.scripts.ProjectId.ROOT] = 空串）：`files/scripts/` 本层 ──
    //
    // 2026-10-16 用户裁定「根层可写」：首页 FAB 建的文件落本层、建的文件夹就是一个新项目。
    // 下面这几条钉的是三条判据（列得出/打得开/跑得起来）里的**读与写两半**，
    // 跑那一半在 `ScriptPathsProjectInternalTest`（形状转换）与 `ScriptPathsScriptFileTest`
    // （绝对路径拼装）里钉。

    @Test
    fun `根层建文件与文件夹落在 files_scripts 本层`() {
        Files.createDirectories(dir.resolve("scripts"))
        ScriptFileOps.createFile(dir, "", "hello.js")
        ScriptFileOps.createFolder(dir, "", "我的脚本")
        assertTrue(Files.isRegularFile(dir.resolve("scripts/hello.js")))
        assertTrue(Files.isDirectory(dir.resolve("scripts/我的脚本")), "根层的文件夹就是一个项目目录")
        // 没有多出一层（"落到别处"的典型形态就是凭空多一个目录）。
        assertEquals(0, Files.list(dir.resolve("scripts/我的脚本")).use { it.count() })
    }

    @Test
    fun `根层文件读回_保存覆盖且不留临时文件`() {
        val scripts = Files.createDirectories(dir.resolve("scripts"))
        Files.write(scripts.resolve("hello.js"), "const a = 1;\n".toByteArray(Charsets.UTF_8))
        assertEquals("const a = 1;\n", ScriptFileOps.read(dir, "", "hello.js"))
        ScriptFileOps.save(dir, "", "hello.js", "const a = 2;\n")
        assertEquals("const a = 2;\n", ScriptFileOps.read(dir, "", "hello.js"))
        assertEquals(
            listOf("hello.js"),
            Files.list(scripts).use { s -> s.map { it.name }.sorted().toList() },
        )
    }

    @Test
    fun `根层 relPath 必须是单段`() {
        val scripts = Files.createDirectories(dir.resolve("scripts"))
        Files.createDirectories(scripts.resolve("proj"))
        Files.write(scripts.resolve("proj/x.js"), "y".toByteArray())
        // 根层只有一层：`proj/x.js` 是"项目 proj 下的文件"，不该从这个入口读到。
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.read(dir, "", "proj/x.js") }
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.save(dir, "", "proj/x.js", "pwn") }
        // 越界/空串照旧拒（防越界那一道与项目分支同源）。
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.read(dir, "", "") }
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.read(dir, "", "../escape.js") }
        assertEquals("y", String(Files.readAllBytes(scripts.resolve("proj/x.js"))), "越界的写一次都没落下来")
    }

    /**
     * 根层建**文件夹** = 建一个新项目，故多两条判据（名字得能当项目号）。
     *
     * 放行的代价是"进得去、里面每个文件都打不开"：项目号判据比文件名判据严一档
     * （文件名可以含 `.`，项目名不可以 —— 批 84：`.` 让 `projectsRoot.resolve("..")` 跳出项目根）。
     */
    @Test
    fun `根层建文件夹的名字要能当项目名`() {
        Files.createDirectories(dir.resolve("scripts"))
        val e = assertThrows(IllegalArgumentException::class.java) {
            ScriptFileOps.createFolder(dir, "", "v1.0")
        }
        assertTrue(e.message?.contains("项目名") == true, "原文要点破原因：${e.message}")
        // `node_modules`：文件列表里整棵不显示 → 建了等于消失。
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.createFolder(dir, "", "node_modules") }
        assertTrue(!Files.exists(dir.resolve("scripts/v1.0")))
        assertTrue(!Files.exists(dir.resolve("scripts/node_modules")))
        // **文件名不受这条限制**：`a.b.js` 里有 `.` 是常事 —— 两条判据分得开。
        ScriptFileOps.createFile(dir, "", "a.b.js")
        assertTrue(Files.isRegularFile(dir.resolve("scripts/a.b.js")))
    }

    /** 点开头与超长（255 字节）在落盘前拒 —— 否则是"建了但列表里看不见"/一句机器话。 */
    @Test
    fun `点开头与超长名字拒绝`() {
        Files.createDirectories(dir.resolve("scripts"))
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.createFile(dir, "", ".hidden.js") }
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.createFolder(dir, "", ".git") }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptFileOps.createFile(dir, "", "长".repeat(100) + ".js")
        }
        assertEquals(0, Files.list(dir.resolve("scripts")).use { it.count() })
    }

    /** 根目录都还没建（首装）时，根层新建拒绝而**不是**顺手把 `files/scripts/` 建出来。 */
    @Test
    fun `根目录不存在时根层新建拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.createFile(dir, "", "x.js") }
        assertTrue(!Files.exists(dir.resolve("scripts")))
    }

    @Test
    fun `合法中文补充字符与空文件都可读取`() {
        val project = Files.createDirectories(dir.resolve("scripts/demo"))
        val text = "中文脚本😀\n合法替换字符：�"
        Files.write(project.resolve("valid.js"), text.toByteArray(Charsets.UTF_8))
        Files.createFile(project.resolve("empty.js"))
        assertEquals(text, ScriptFileOps.read(dir, "demo", "demo/valid.js"))
        assertEquals("", ScriptFileOps.read(dir, "demo", "demo/empty.js"))
    }

    // ── 删除与绝对路径（2026-10-17 用户口径：菜单「复制绝对路径」/「删除」）──

    @Test
    fun `绝对路径按 files_scripts 之下那条算`() {
        Files.createDirectories(dir.resolve("scripts/demo/lib"))
        // 三档：根层单段 / 项目根 / 子层 —— 都是 `files/scripts/<relPath>`。
        assertEquals(dir.resolve("scripts/hello.js").toString(), ScriptFileOps.absolutePath(dir, "", "hello.js"))
        assertEquals(dir.resolve("scripts/demo/main.js").toString(), ScriptFileOps.absolutePath(dir, "demo", "demo/main.js"))
        // 目录行的 relPath 带尾随 `/`（契约形态）—— 复制出来的是目录本身，不带那个斜杠。
        assertEquals(dir.resolve("scripts/demo/lib").toString(), ScriptFileOps.absolutePath(dir, "demo", "demo/lib/"))
        // 判据与读写口**同源**：越界、首段不是本项目、根层多段一律拒。
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.absolutePath(dir, "demo", "../escape.js") }
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.absolutePath(dir, "demo", "other/main.js") }
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.absolutePath(dir, "", "demo/main.js") }
        // **不查存在性**：刚被删掉的行照样复制得出它原来在哪。
        assertEquals(dir.resolve("scripts/demo/gone.js").toString(), ScriptFileOps.absolutePath(dir, "demo", "demo/gone.js"))
    }

    @Test
    fun `删文件与空文件夹`() {
        val project = Files.createDirectories(dir.resolve("scripts/demo"))
        Files.write(project.resolve("a.js"), "x".toByteArray())
        Files.createDirectory(project.resolve("empty"))
        ScriptFileOps.delete(dir, "demo", "demo/a.js")
        // 目录行那条 relPath 带尾随 `/` —— 删除要照收（读写口也是先 trimEnd('/')）。
        ScriptFileOps.delete(dir, "demo", "demo/empty/")
        assertEquals(0, Files.list(project).use { it.count() })
        assertTrue(Files.isDirectory(project), "删的是条目，不是项目本身")
    }

    @Test
    fun `删文件夹递归整棵`() {
        val deep = Files.createDirectories(dir.resolve("scripts/demo/lib/deep"))
        Files.write(deep.resolve("x.js"), "x".toByteArray())
        Files.write(dir.resolve("scripts/demo/lib/y.js"), "y".toByteArray())
        ScriptFileOps.delete(dir, "demo", "demo/lib/")
        assertTrue(!Files.exists(dir.resolve("scripts/demo/lib")), "整棵都得没")
        assertTrue(Files.isDirectory(dir.resolve("scripts/demo")), "只删那一棵")
    }

    /** 根层的文件夹**就是项目本身**：删它 = 连同其中全部文件删掉一整个项目（用户 2026-10-17 裁定允许）。 */
    @Test
    fun `根层的文件夹就是项目_删它整棵没`() {
        val scripts = Files.createDirectories(dir.resolve("scripts"))
        val project = Files.createDirectories(scripts.resolve("我的脚本/sub"))
        Files.write(project.resolve("x.js"), "x".toByteArray())
        ScriptFileOps.delete(dir, "", "我的脚本/")
        assertTrue(!Files.exists(scripts.resolve("我的脚本")))
        assertEquals(0, Files.list(scripts).use { it.count() }, "同层别的条目一个都不许动")
    }

    @Test
    fun `删不存在的条目拒绝`() {
        Files.createDirectories(dir.resolve("scripts/demo"))
        val e = assertThrows(IllegalArgumentException::class.java) {
            ScriptFileOps.delete(dir, "demo", "demo/nope.js")
        }
        // 静默成功会把"删错了对象"与"删掉了"画成同一件事。
        assertTrue(e.message?.contains("不存在") == true, "实际：${e.message}")
    }

    @Test
    fun `删除的越界与归属判据与读写同源`() {
        Files.createDirectories(dir.resolve("scripts/demo"))
        Files.createDirectories(dir.resolve("scripts/other"))
        Files.write(dir.resolve("scripts/other/x.js"), "x".toByteArray())
        for (bad in listOf("../escape.js", "/etc/passwd", "other/x.js", "demo/../other", "")) {
            assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.delete(dir, "demo", bad) }
        }
        // 根层只认本层单段：`demo/x.js` 是"项目 demo 下的文件"，不该从这个入口删掉。
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.delete(dir, "", "demo/x.js") }
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.delete(dir, "", "") }
        assertTrue(Files.exists(dir.resolve("scripts/other/x.js")), "越界的一个都没被删")
    }

}
