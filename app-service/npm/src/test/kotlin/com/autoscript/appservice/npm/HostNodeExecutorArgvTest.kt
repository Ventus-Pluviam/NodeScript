package com.autoscript.appservice.npm

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [HostNodeExecutor.npmArgv] 的不变量（§10.2；2026-10-09 批 83）。
 *
 * 这一条是本批修掉的 bug 的**回归钉**：此前 argv 无条件带 `--registry <官方>`，
 * 而唯一的生产构造点从不传这个参数 —— 用户设的镜像源对真实安装毫无影响。
 * 提成纯函数后零 node、零文件系统即可断言（真起 node 的用例在 `NpmSpawnGateTest` /
 * `HostNodeNpmE2ETest`，那两条管的是「跑得起来」，不是「argv 对不对」）。
 */
class HostNodeExecutorArgvTest {

    private val op = HeavyOp(
        nonce = "n1",
        projectId = "p1",
        args = listOf("install", "axios@1.7.0"),
        projectRoot = Path.of("/tmp/proj"),
        stageDir = Path.of("/tmp/proj/node_modules.part-n1"),
        timeoutMillis = 30_000,
    )

    private val workDir = Path.of("/tmp/work")

    /** 构造一个只用来问 argv 的执行体（不 execute，故 `npmCliJs` 只需存在）。 */
    private fun executor(@TempDir dir: Path, override: String? = null, userConfig: Path? = null): HostNodeExecutor {
        val cli = dir.resolve("npm-cli.js")
        java.nio.file.Files.write(cli, "// stub".toByteArray())
        return HostNodeExecutor(cli, dir.resolve("cache"), nodeBin = "node", registryOverride = override, userConfig = userConfig)
    }

    private fun flagValue(argv: List<String>, flag: String): String? {
        val i = argv.indexOf(flag)
        return if (i >= 0 && i + 1 < argv.size) argv[i + 1] else null
    }

    @Test
    fun `缺省不注入 --registry——让 npm 自己解析三层链`(@TempDir dir: Path) {
        val argv = executor(dir).npmArgv(op, workDir)
        assertFalse(argv.contains("--registry"), "缺省必须不钉死注册表，否则用户设的镜像源永远不生效：$argv")
        assertTrue(argv.contains("--prefix"), "prefix 必须给（项目 .npmrc 就靠它被读到）")
    }

    @Test
    fun `显式给了才注入 --registry`(@TempDir dir: Path) {
        val argv = executor(dir, override = "https://harbor.example.com/registry").npmArgv(op, workDir)
        assertEquals("https://harbor.example.com/registry", flagValue(argv, "--registry"))
    }

    @Test
    fun `userconfig 与 registry 可以同时给——两者不是二选一`(@TempDir dir: Path) {
        val rc = dir.resolve(".npmrc")
        val argv = executor(dir, override = "https://x.example.com", userConfig = rc).npmArgv(op, workDir)
        assertEquals(rc.toAbsolutePath().toString(), flagValue(argv, "--userconfig"))
        assertEquals("https://x.example.com", flagValue(argv, "--registry"))
    }

    @Test
    fun `缺省不给 --userconfig`(@TempDir dir: Path) {
        assertFalse(executor(dir).npmArgv(op, workDir).contains("--userconfig"))
    }

    @Test
    fun `用户参数与既有旗标一个不少`(@TempDir dir: Path) {
        val argv = executor(dir).npmArgv(op, workDir)
        assertTrue(argv.containsAll(listOf("install", "axios@1.7.0")), "op.args 原样透传：$argv")
        for (f in listOf("--ignore-scripts", "--no-audit", "--no-fund", "--cache", "--prefix", "--loglevel")) {
            assertTrue(argv.contains(f), "既有旗标 $f 不得因本批改动而消失：$argv")
        }
        assertEquals(workDir.toAbsolutePath().toString(), flagValue(argv, "--prefix"))
    }
}
