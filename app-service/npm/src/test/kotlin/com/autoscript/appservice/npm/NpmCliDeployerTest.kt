package com.autoscript.appservice.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import com.autoscript.testkit.HostNpm

/**
 * vendored CLI 部署器单测（目录树契约）。
 * 素材源 = 宿主 npm 安装树（[HostNpm] 现查：`npm root -g` → node prefix → 老静态位；
 * 不写死 `/usr/lib/node_modules/npm`，理由见 HostNpm KDoc）；
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
        val npm = HostNpm.root
        assumeTrue(npm != null, "本机无 npm 安装，跳过（HostNpm 三来源都没探到）")
        return DirSource(npm!!)
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

    /**
     * AssetManager 口径的 list（只返当前层、目录名带尾 '/'、**点条目不可见**）。
     * 点条目不可见是真机行为：随包素材因此一律不带点条目（node-runtime-build 的 §9
     * 剪裁与 build-logic 的 prepareNpmCliAssets 都按这条口径走），本测试就是验
     * "按这条口径剪过的树，部署出来还跑得起来"。
     */
    private fun assetList(root: Path, dir: String): Array<String>? {
        val d = if (dir.isEmpty()) root else root.resolve(dir)
        if (!Files.isDirectory(d)) return null
        return Files.list(d).use { s ->
            s.map { it.fileName.toString() }
                .filter { !it.startsWith(".") }
                .map { if (Files.isDirectory(d.resolve(it))) "$it/" else it }
                .sorted()
                .toList()
                .toTypedArray()
        }
    }

    @Test
    fun `资产源部署出的 CLI 真能跑起来（点条目不可见的 AssetManager 口径）`() {
        val npm = HostNpm.root
        assumeTrue(npm != null, "本机无 npm 安装，跳过（HostNpm 三来源都没探到）")
        assumeTrue(HostNpm.hasNode, "PATH 里没有 node，跳过")
        // 本机 npm 树**就是**素材根：把资产前缀 "npm" 摘掉映射回文件系统
        val root = npm!!
        fun fsRel(p: String) = p.removePrefix("npm").trimStart('/')
        val src = AssetTreeCliSource("npm", { assetList(root, fsRel(it)) }) { path ->
            Files.newInputStream(root.resolve(fsRel(path)))
        }
        val r = NpmCliDeployer.deploy(dir, src) as NpmCliDeployer.Outcome.Ready
        // 真起一次：部署出来的树缺一个 require 得到的东西就当场炸（比"文件都在"强）
        val out = runCli(r, "--version")
        assertTrue(out.trim().isNotEmpty(), "npm --version 应有版本输出")
        // 再打一记重的：`npm ls` 要**装进 @npmcli/arborist**（整棵依赖树的真载入），
        // 而剪裁口径恰恰丢掉了 npm 自己树里的点条目（node_modules/.bin、
        // node_modules/.package-lock.json）。2026-10-01 在本机实测过：这条路是通的
        // （还跑通了真 install），这条断言是给"以后有人改剪裁口径"留的回归哨。
        val ls = runCli(r, "ls", "--json", workDir = dir.resolve("probe"))
        assertTrue(ls.contains("\"probe\"") || ls.trim().startsWith("{"), "npm ls 应有 JSON 输出：$ls")
    }

    /** 起一次部署出来的 CLI，返回合并后的输出；非 0 退出码即失败。 */
    private fun runCli(r: NpmCliDeployer.Outcome.Ready, vararg args: String, workDir: Path? = null): String {
        val pb = ProcessBuilder(listOf("node", r.cliJs.toAbsolutePath().toString()) + args)
            .redirectErrorStream(true)
        workDir?.let {
            Files.createDirectories(it)
            Files.write(it.resolve("package.json"), """{"name":"probe","version":"0.0.1"}""".toByteArray())
            pb.directory(it.toFile())
        }
        val proc = pb.start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "npm ${args.first()} 不得挂死")
        assertEquals(0, proc.exitValue(), "npm ${args.first()} 退出码非 0：$out")
        return out
    }
}
