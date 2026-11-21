package com.autoscript.appservice.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 资产树 CliSource 的**纯逻辑**单测（§10.2 调用链首段）。
 *
 * 用 Map 建模 assets 树 + list/open 两个函数桩：不依赖 android.jar、不碰真 npm 树，
 * 故本机与 CI 同一条跑法、无 env 门（真 npm 树那半条在 [NpmCliDeployerTest] 的 env 门里，
 * 验的是"部署出来的树确实能跑"）。
 */
class AssetTreeCliSourceTest {

    /** assets 树建模：[list] 只返当前层，目录名带尾 '/'（真机行为之一）。 */
    private fun tree(
        vararg files: String,
        dirSuffix: Boolean = true,
    ): Triple<Map<String, ByteArray>, AssetTreeCliSource, () -> Int> {
        val map = files.associateWith { it.toByteArray() }
        var opens = 0
        val list: (String) -> Array<String>? = { dir ->
            if (dir.isEmpty()) null
            else {
                val prefix = "$dir/"
                val names = LinkedHashSet<String>()
                for (k in map.keys) {
                    if (k.startsWith(prefix)) {
                        val rest = k.removePrefix(prefix)
                        val slash = rest.indexOf('/')
                        names.add(
                            if (slash >= 0) rest.substring(0, slash + 1).let { if (dirSuffix) it else it.trimEnd('/') }
                            else rest,
                        )
                    }
                }
                names.toTypedArray()
            }
        }
        val manifest = com.autoscript.testkit.npmManifest(map.mapKeys { it.key.removePrefix("npm/") })
        val src = AssetTreeCliSource("npm", list, openManifest = { manifest.byteInputStream() }) { path ->
            opens++
            ByteArrayInputStream(map[path] ?: throw FileNotFoundException(path))
        }
        return Triple(map, src, { opens })
    }

    @Test
    fun `列举相对 root 的全部文件，路径用正斜杠`() {
        val (_, src, opens) = tree(
            "npm/bin/npm-cli.js",
            "npm/bin/npx-cli.js",
            "npm/lib/cli.js",
            "npm/node_modules/semver/index.js",
        )
        assertEquals(
            listOf("bin/npm-cli.js", "bin/npx-cli.js", "lib/cli.js", "node_modules/semver/index.js"),
            src.list().sorted(),
        )
        // 惰性：列举只走名字，不开任何文件（~1.5k 文件 / 近 10MB 的素材不在内存里压一份）
        assertEquals(0, opens(), "list() 不得读任何文件内容")
        assertEquals("npm/node_modules/semver/index.js", src.read("node_modules/semver/index.js")?.decodeToString())
    }

    @Test
    fun `目录名不带尾斜杠时靠 list 兜底判定`() {
        val (_, src, _) = tree("npm/lib/deep/a.js", dirSuffix = false)
        assertEquals(listOf("lib/deep/a.js"), src.list())
    }

    @Test
    fun `目录识别读到的子项直接复用每个路径每轮只枚举一次`() {
        val tree = mapOf(
            "npm" to arrayOf("bin", "node_modules", "empty/"),
            "npm/bin" to arrayOf("npm-cli.js", "npx-cli.js"),
            "npm/node_modules" to arrayOf("@scope"),
            "npm/node_modules/@scope" to arrayOf("pkg"),
            "npm/node_modules/@scope/pkg" to arrayOf("index.js"),
            "npm/empty" to emptyArray(),
        )
        val calls = linkedMapOf<String, Int>()
        val src = AssetTreeCliSource("npm", { path ->
            calls[path] = calls.getOrDefault(path, 0) + 1
            tree[path]
        }) { error("list 只枚举名字，不读文件") }
        val expected = listOf("bin/npm-cli.js", "bin/npx-cli.js", "node_modules/@scope/pkg/index.js")
        assertEquals(expected, src.list().sorted())
        assertEquals(
            tree.keys + expected.map { "npm/$it" },
            calls.keys,
            "仍完整枚举真实资产树，不能用清单推测目录内容而漏掉额外文件",
        )
        assertTrue(calls.values.all { it == 1 }, "不带尾斜杠的目录不能先为识别、再为展开而重复枚举：$calls")
        assertEquals(expected, src.list().sorted())
        assertTrue(calls.values.all { it == 2 }, "缓存只限一轮，下次启动检疫仍要现查：$calls")
    }

    @Test
    fun `重复枚举仍能看见新增或删除的资产不缓存旧文件集合`() {
        var names = arrayOf("old.js")
        val src = AssetTreeCliSource("npm", { path -> if (path == "npm") names else null }) {
            error("list 不读文件")
        }
        assertEquals(listOf("old.js"), src.list())
        names = arrayOf("new.js", "extra.js")
        assertEquals(listOf("extra.js", "new.js"), src.list().sorted())
    }

    @Test
    fun `root 不存在返回空表而非异常`() {
        val src = AssetTreeCliSource("npm", { null }) { ByteArrayInputStream(ByteArray(0)) }
        assertTrue(src.list().isEmpty())
    }

    @Test
    fun `缺件读回 null（部署侧按缺件处理），0 字节文件是合法内容`() {
        val (_, src, _) = tree("npm/bin/npm-cli.js")
        assertNull(src.read("bin/absent.js"))
        assertTrue(src.read("bin/npm-cli.js")!!.isNotEmpty())
    }

    @Test
    fun `喂给 deploy 即可就位：锚齐、全树落盘、幂等`(@TempDir dir: Path) {
        val (_, src, _) = tree(
            "npm/bin/npm-cli.js",
            "npm/bin/npx-cli.js",
            "npm/lib/cli.js",
            "npm/node_modules/@npmcli/arborist/package.json",
        )
        val first = NpmCliDeployer.deploy(dir, src) as NpmCliDeployer.Outcome.Ready
        assertTrue(first.deployedFresh)
        assertTrue(Files.isRegularFile(first.cliJs))
        assertTrue(Files.isRegularFile(dir.resolve("npm/node_modules/@npmcli/arborist/package.json")))
        assertTrue(Files.isRegularFile(dir.resolve("npm/.cli-manifest.sha256")))
        val second = NpmCliDeployer.deploy(dir, src) as NpmCliDeployer.Outcome.Ready
        assertEquals(false, second.deployedFresh, "同源二次部署必须幂等跳过")
    }

    @Test
    fun `缺锚文件的源在 deploy 处换来一声响`(@TempDir dir: Path) {
        val (_, src, _) = tree("npm/bin/npm-cli.js")   // 少了 npx-cli.js
        val e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            NpmCliDeployer.deploy(dir, src)
        }
        assertTrue(e.message!!.contains("npx-cli.js"), "错误信息要点名缺的锚：${e.message}")
    }
}
