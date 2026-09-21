package com.autoscript.appservice.packager.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * vendored CLI 部署器单测（目录树契约）。
 * 素材源默认取本机 /usr/lib/node_modules/npm（CI ubuntu 同样有 node）；
 * 没有则整体跳过（本地只有 JVM 的机器不假扮通过）。
 */
class NpmCliDeployerTest {

    @TempDir
    lateinit var dir: Path

    private class DirSource(val root: Path) : NpmCliDeployer.CliSource {
        override fun read(relPath: String): ByteArray? {
            val f = root.resolve(relPath)
            return if (Files.isRegularFile(f)) Files.readAllBytes(f) else null
        }

        override fun list(): List<String> =
            if (Files.isDirectory(root)) {
                Files.walk(root).use { s ->
                    s.filter { Files.isRegularFile(it) }
                        .map { root.relativize(it).toString().replace('\\', '/') }
                        .sorted()
                        .toList()
                }
            } else emptyList()
    }

    private fun sourceOrSkip(): DirSource {
        val npm = Path.of("/usr/lib/node_modules/npm")
        assumeTrue(Files.isRegularFile(npm.resolve("bin/npm-cli.js")), "本机无 npm 安装，跳过")
        return DirSource(npm)
    }

    @Test
    fun `部署后 cli-js 就位可读`() {
        val src = sourceOrSkip()
        val r = NpmCliDeployer.deploy(dir, src) as NpmCliDeployer.Outcome.Ready
        assertTrue(Files.isRegularFile(r.cliJs))
        assertTrue(String(Files.readAllBytes(r.cliJs), Charsets.UTF_8).contains("cli.js"), "cli-js 内容应 require lib/cli.js")
        assertTrue(r.deployedFresh)
        assertTrue(Files.exists(dir.resolve("npm/.cli-manifest.sha256")), "manifest 锚必须落盘")
        // 全量树就位（不是只有 bin）
        assertTrue(Files.isDirectory(dir.resolve("npm/node_modules")), "依赖树必须整目录落位")
        assertTrue(Files.isDirectory(dir.resolve("npm/node_modules/semver")), "semver 依赖必须存在")
    }

    @Test
    fun `幂等：同源二次部署零重写`() {
        val src = sourceOrSkip()
        val first = NpmCliDeployer.deploy(dir, src) as NpmCliDeployer.Outcome.Ready
        val before = Files.getLastModifiedTime(first.cliJs)
        val second = NpmCliDeployer.deploy(dir, src) as NpmCliDeployer.Outcome.Ready
        assertFalse(second.deployedFresh, "同源二次部署必须幂等跳过")
        assertEquals(before, Files.getLastModifiedTime(second.cliJs), "幂等命中不得重写文件")
    }

    @Test
    fun `升级：源内容变化触发整体重部署`() {
        val src = sourceOrSkip()
        NpmCliDeployer.deploy(dir, src)
        // 升级场景：把源侧 npm-cli.js 换内容（= npm 版本变化）
        val fake = dir.resolve("fake-src")
        Files.walk(src.root).use { s ->
            s.filter { Files.isRegularFile(it) }.forEach { f ->
                val rel = src.root.relativize(f).toString().replace('\\', '/')
                val dest = fake.resolve(rel)
                Files.createDirectories(dest.parent)
                Files.write(dest, Files.readAllBytes(f))
            }
        }
        Files.write(fake.resolve("bin/npm-cli.js"), ("/* npm-cli.js v-next */").toByteArray())
        val after = NpmCliDeployer.deploy(dir, DirSource(fake)) as NpmCliDeployer.Outcome.Ready
        assertTrue(after.deployedFresh, "源哈希变化必须重部署")
        assertEquals("/* npm-cli.js v-next */", String(Files.readAllBytes(after.cliJs), Charsets.UTF_8))
    }

    @Test
    fun `缺锚文件整体失败（零半截 CLI）`() {
        val src = sourceOrSkip()
        val fake = dir.resolve("broken-src")
        Files.walk(src.root).use { s ->
            s.filter { Files.isRegularFile(it) }.forEach { f ->
                val rel = src.root.relativize(f).toString().replace('\\', '/')
                val dest = fake.resolve(rel)
                Files.createDirectories(dest.parent)
                Files.write(dest, Files.readAllBytes(f))
            }
        }
        Files.delete(fake.resolve("bin/npm-cli.js"))
        assertThrows(IllegalStateException::class.java) {
            NpmCliDeployer.deploy(dir, DirSource(fake))
        }
        assertFalse(Files.exists(dir.resolve("npm/bin/npm-cli.js")), "缺锚绝不能让 bin/npm-cli.js 就位")
    }

    @Test
    fun `stale 残骸被清扫`() {
        val src = sourceOrSkip()
        val stale = Files.createDirectories(dir.resolve(".npm-deploy-stale"))
        Files.write(stale.resolve("junk"), ("half-written").toByteArray())
        val tomb = Files.createDirectories(dir.resolve(".npm-tombstone-x"))
        Files.write(tomb.resolve("junk"), ("old").toByteArray())
        NpmCliDeployer.deploy(dir, src)
        assertFalse(Files.exists(stale), "半途 tmp 必须清扫")
        assertFalse(Files.exists(tomb), "旧墓碑必须清扫")
    }

    @Test
    fun `半途断电形态（目录在锚丢失）触发重部署`() {
        val src = sourceOrSkip()
        NpmCliDeployer.deploy(dir, src)
        Files.delete(dir.resolve("npm/.cli-manifest.sha256"))
        val again = NpmCliDeployer.deploy(dir, src) as NpmCliDeployer.Outcome.Ready
        assertTrue(again.deployedFresh, "无 manifest 锚 → 视为半途，重部署")
    }
}
