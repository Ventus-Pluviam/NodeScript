package com.autoscript.appservice.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * [skippedLifecyclePkgs] 的判据（§10.5-3「禁止静默」的扫描面）。
 *
 * 每条用例都对着一个**真实失真**（2026-11-09 真机：装 axios 报「安装脚本未运行：axios」）：
 * 修前的扫描面是九个键的全文子串匹配、且只扫深度 1，于是同时有虚警与漏报。
 * 判据的唯一出处是素材 npm 12.2.0 的 `arborist/rebuild.js`（见被测文件头）。
 */
class SkippedLifecycleScanTest {

    @TempDir
    lateinit var stage: Path

    /** 往暂存树里放一个包（`@scope/name` 自动铺成两层）。 */
    private fun pkg(name: String, packageJson: String, extraFiles: List<String> = emptyList()) {
        val dir = stage.resolve(name)
        Files.createDirectories(dir)
        Files.write(dir.resolve("package.json"), packageJson.toByteArray())
        extraFiles.forEach { Files.write(dir.resolve(it), ByteArray(0)) }
    }

    // ═══ 该报的照报（不许漏）═══

    @Test
    fun `postinstall 声明了就报 —— 这本就是被 --ignore-scripts 跳掉的那条`() {
        pkg("esbuild", """{"name":"esbuild","scripts":{"postinstall":"node install.js"}}""")
        assertEquals(listOf("esbuild"), skippedLifecyclePkgs(stage))
    }

    @Test
    fun `install 与 preinstall 也算`() {
        pkg("a", """{"name":"a","scripts":{"install":"node-gyp rebuild"}}""")
        pkg("b", """{"name":"b","scripts":{"preinstall":"node setup.js"}}""")
        assertEquals(listOf("a", "b"), skippedLifecyclePkgs(stage))
    }

    /**
     * **漏报修**：原实现只扫深度 1，`@scope/` 那一层被当成包（`@scope/package.json`
     * 恒不存在）→ 所有带 scope 的包**从来没被扫过**。漏掉的正好是
     * `@parcel/watcher` 这类必带 postinstall 的原生包。
     */
    @Test
    fun `带 scope 的包也扫得到，名字报成 @scope_pkg`() {
        pkg("@parcel/watcher", """{"name":"@parcel/watcher","scripts":{"postinstall":"node build.js"}}""")
        pkg("@babel/core", """{"name":"@babel/core","scripts":{"test":"jest"}}""")
        assertEquals(
            listOf("@parcel/watcher"),
            skippedLifecyclePkgs(stage),
            "带 scope 的包必须被扫到（原实现一个都扫不到），且不相关的兄弟包不许捎上",
        )
    }

    /**
     * **漏报修 2**：node-gyp 那条**看不见于 package.json** ——
     * `rebuild.js#addToBuildSet` 会在「有 `binding.gyp` + 没声明 `install`/`preinstall`
     * + 没写 `gypfile:false`」时自己补一条 `install: node-gyp rebuild`。
     * 只读 scripts 段就漏掉整类原生包。
     */
    @Test
    fun `binding_gyp 在场且没声明 install —— npm 会自动注入一条，必须报`() {
        pkg("sqlite3", """{"name":"sqlite3","scripts":{"test":"mocha"}}""", extraFiles = listOf("binding.gyp"))
        assertEquals(listOf("sqlite3"), skippedLifecyclePkgs(stage))
    }

    @Test
    fun `gypfile false 明确关掉了自动探测 —— 不报`() {
        pkg("optout", """{"name":"optout","gypfile":false}""", extraFiles = listOf("binding.gyp"))
        assertEquals(emptyList<String>(), skippedLifecyclePkgs(stage), "npm 自己也不会注入（见 rebuild.js 的 `gypfile !== false`）")
    }

    @Test
    fun `已自己声明 install 的 gyp 包只报一次（不因为两条判据都在而重复点名）`() {
        pkg("dup", """{"name":"dup","scripts":{"install":"node-gyp rebuild"}}""", extraFiles = listOf("binding.gyp"))
        assertEquals(listOf("dup"), skippedLifecyclePkgs(stage))
    }

    // ═══ 不该报的一个不许报（虚警面）═══

    /**
     * **虚警修（真机那条）**：axios 只有 `prepare: husky`，而 `prepare` 对 registry
     * 装来的包**本来就不会跑**（`rebuild.js` 里它被圈在 `if (type === 'links')`）。
     * 报它 = 让用户去查一个不存在的构建步骤。
     */
    @Test
    fun `只有 prepare 的不报 —— registry 安装时它本来就不会跑`() {
        pkg(
            "axios",
            """{"name":"axios","scripts":{"prepare":"husky","version":"npm run build","preversion":"gulp version"}}""",
        )
        assertEquals(emptyList<String>(), skippedLifecyclePkgs(stage))
    }

    @Test
    fun `preprepare 与 preparePack 都不报 —— 前者只对项目自身跑，后者根本不是 npm 的名字`() {
        pkg("a", """{"name":"a","scripts":{"preprepare":"x","preparePack":"y"}}""")
        assertEquals(emptyList<String>(), skippedLifecyclePkgs(stage))
    }

    /**
     * **虚警修**：npm v7 起 `uninstall` 系 lifecycle **官方明写不实现**
     * （scripts.md：「not implemented and will not function」）。报它等于说
     * 「有个本来会跑的脚本被我拦了」—— 没有那回事。
     */
    @Test
    fun `uninstall 系三个都不报 —— npm v7 起根本不实现`() {
        pkg("a", """{"name":"a","scripts":{"preuninstall":"x","uninstall":"y","postuninstall":"z"}}""")
        assertEquals(emptyList<String>(), skippedLifecyclePkgs(stage))
    }

    /**
     * **虚警修**：原实现是全文子串匹配（`"\"install\"" in text`），
     * 于是 `bin` 里名叫 `install` 的命令、`devDependencies` 里名叫 `install` 的包都会命中。
     * 判据必须限定在 `scripts` 段的**键**上。
     */
    @Test
    fun `scripts 段之外的同名字段不算 —— 不是全文子串匹配`() {
        pkg(
            "a",
            """{"name":"a","bin":{"install":"./cli.js"},"devDependencies":{"install":"1.0.0"},"scripts":{"test":"jest"}}""",
        )
        assertEquals(emptyList<String>(), skippedLifecyclePkgs(stage), "命中必须限定在 scripts 段的键上")
    }

    @Test
    fun `只有 test 之类普通脚本的不报（既有判据不放宽）`() {
        pkg("lodash", """{"name":"lodash","scripts":{"test":"echo x"}}""")
        assertEquals(emptyList<String>(), skippedLifecyclePkgs(stage))
    }

    @Test
    fun `没有 scripts 段、没有清单、npm 自己的记账目录都不算包`() {
        pkg("bare", """{"name":"bare"}""")
        Files.createDirectories(stage.resolve(".bin"))
        Files.write(stage.resolve(".package-lock.json"), "{}".toByteArray())
        Files.createDirectories(stage.resolve("node_modules"))
        assertEquals(emptyList<String>(), skippedLifecyclePkgs(stage))
    }

    @Test
    fun `坏清单按「没有」处理 —— 信息面警告不该让一次已经跑完的安装失败`() {
        pkg("broken", """{"name":"broken","scripts":""")
        pkg("ok", """{"name":"ok","scripts":{"postinstall":"x"}}""")
        assertEquals(listOf("ok"), skippedLifecyclePkgs(stage), "坏的那个跳过，好的照报")
    }

    @Test
    fun `暂存树不存在 = 空清单（不抛）`() {
        assertEquals(emptyList<String>(), skippedLifecyclePkgs(stage.resolve("nope")))
    }

    @Test
    fun `报出来的名字是排序后的（UI 直接渲染，不必再排）`() {
        pkg("zeta", """{"name":"zeta","scripts":{"postinstall":"x"}}""")
        pkg("alpha", """{"name":"alpha","scripts":{"postinstall":"x"}}""")
        assertEquals(listOf("alpha", "zeta"), skippedLifecyclePkgs(stage))
    }

    /** 嵌套树里的包不归顶层那格管（`a/node_modules/b` 是 a 装进来的，按顶层汇总）。 */
    @Test
    fun `嵌套 node_modules 里的包不单独点名`() {
        val nested = stage.resolve("a/node_modules/b")
        Files.createDirectories(nested)
        Files.write(nested.resolve("package.json"), """{"name":"b","scripts":{"postinstall":"x"}}""".toByteArray())
        pkg("a", """{"name":"a"}""")
        assertEquals(emptyList<String>(), skippedLifecyclePkgs(stage))
    }

    /** 目录形态的自检：确认真的铺出了两层（否则上面那条 scope 用例是自欺）。 */
    @Test
    fun `scope 目录形态自检：@scope 下确实是一层子目录`() {
        pkg("@parcel/watcher", """{"name":"@parcel/watcher"}""")
        val scoped = stage.resolve("@parcel").resolve("watcher")
        assertTrue(Files.isDirectory(scoped), "带 scope 的包落在 @scope/pkg 两层里")
        assertTrue(Files.isRegularFile(scoped.resolve("package.json")))
        assertFalse(Files.exists(stage.resolve("@parcel").resolve("package.json")), "@scope 自己没有清单")
        Unit
    }

    /**
     * 两条判据合起来看：同一棵树里「真被跳掉的」与「本来就不会跑的」共存时，
     * 报出来的必须**恰好**是前者那三个（顺序 = 排序后），后者一个不沾。
     */
    @Test
    fun `混合树：只报真被跳掉的那几个`() {
        pkg("esbuild", """{"name":"esbuild","scripts":{"postinstall":"node install.js"}}""")
        pkg("sqlite3", """{"name":"sqlite3"}""", extraFiles = listOf("binding.gyp"))
        pkg("@parcel/watcher", """{"name":"@parcel/watcher","scripts":{"install":"node-gyp rebuild"}}""")
        pkg("axios", """{"name":"axios","scripts":{"prepare":"husky"}}""")
        pkg("lodash", """{"name":"lodash","scripts":{"test":"jest"}}""")
        pkg("legacy", """{"name":"legacy","scripts":{"uninstall":"cleanup.sh"}}""")
        assertEquals(
            listOf("@parcel/watcher", "esbuild", "sqlite3"),
            skippedLifecyclePkgs(stage),
        )
        Unit
    }
}
