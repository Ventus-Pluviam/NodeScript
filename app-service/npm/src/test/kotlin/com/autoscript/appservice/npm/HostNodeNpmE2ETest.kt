package com.autoscript.appservice.npm

import com.autoscript.domain.scripts.ScriptPaths
import com.autoscript.domain.npm.NpmConsoleLineKind
import com.autoscript.domain.npm.PackageSpec
import kotlinx.coroutines.runBlocking
import com.autoscript.domain.scripts.ProjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import com.autoscript.testkit.HostNpm

/**
 * 真实 npm 端到端（§10 P0 实证切片）：
 * HostNodeExecutor 在暂存目录跑真 `npm install --prefer-offline --ignore-scripts`，
 * 产物经 InstallCoordinator 事务链（journal begin/commit + ATOMIC_MOVE 落位）进 node_modules。
 *
 * 运行条件：宿主机存在 node + npm-cli.js（[HostNpm] 现查，不写死发行版布局；无则跳过）。
 * 网络：默认 registry.npmjs.org（§18 第 7 项出厂官方）；离线 CI 可在首次跑通后靠 _cacache 复跑。
 */
class HostNodeNpmE2ETest {

    @TempDir
    lateinit var dir: Path

    companion object {
        private val npmCli: Path? = HostNpm.cliJs

        @JvmStatic
        @BeforeAll
        fun assumeNode() {
            assumeTrue(npmCli != null, "宿主机无 npm-cli.js，跳过真实 npm e2e")
        }
    }

    private fun coordinatorAt(root: Path, cacheDir: Path): InstallCoordinator {
        val layout = NpmProjectLayout(ScriptPaths.projectsRoot(root))
        return InstallCoordinator(
            services = NpmServices(
                layout = layout,
                journal = InstallJournal(root.resolve(".ns")),
                staging = InstallStaging(layout),
                cacheIndex = CacheIndex { false },
            ),
            executor = HostNodeExecutor(npmCli!!, cacheDir),
        )
    }

    @Test
    fun `真实安装 lodash 进事务化 node_modules`() = runBlocking {
        val root = dir
        val c = coordinatorAt(root, root.resolve("npm-cache"))
        val layout = NpmProjectLayout(ScriptPaths.projectsRoot(root))

        // 项目根需要 package.json（npm install 的前置）
        Files.createDirectories(layout.projectRoot(ProjectId("e2e")))
        Files.write(layout.projectRoot(ProjectId("e2e")).resolve("package.json"), ("""{"name":"e2e","version":"0.0.1"}""").toByteArray())

        val handle = c.install("e2e", listOf(PackageSpec("lodash", "4.17.21")))
        // 产物落位
        val nm = layout.nodeModules(ProjectId("e2e"))
        assertTrue(Files.isDirectory(nm.resolve("lodash")), "node_modules/lodash 必须存在")
        // journal begin+commit 成对
        val journal = InstallJournal(root.resolve(".ns"))
        val states = journal.all().map { it.state }
        assertTrue(
            InstallJournal.State.COMMIT in states,
            "journal 必须含 COMMIT（实为 $states）",
        )
        assertTrue(journal.unfinished().isEmpty())
        // lockfile 由执行体随成功事务写回项目根（失败不污染）
        val lockInProject = layout.lockfile(ProjectId("e2e"))
        assertTrue(Files.exists(lockInProject), "package-lock.json 必须生成于项目根")
        // 轻操作直读：list 应能看到 lodash（lockfile v3 被 LockfileReader 解析）
        assertTrue(c.list("e2e", 0).any { it.name == "lodash" && it.version == "4.17.21" })

        // 控制台那条链在**真 npm** 上也成立（§10.9 第 3 条，2026-10-09 批 84）。
        // 安装走的是 install()，故没有 ECHO 行（那是用户敲的那行，只有控制台入口才落）；
        // 但 PHASE/OUTPUT/RESULT 三类必须有，且 OUTPUT 是 **npm 自己说的话** ——
        // 这一条正是 `HeavyOpOutcome.outputTail` 那条链的端到端证据：没有它，
        // 控制台里就只有「npm install 完成」这句摘要，用户看不到 npm 到底报了什么。
        val installLines = c.consoleOutput("e2e", 0, 512).lines.map { it.line }
        val kinds = installLines.map { it.kind }.toSet()
        assertEquals(
            setOf(NpmConsoleLineKind.PHASE, NpmConsoleLineKind.OUTPUT, NpmConsoleLineKind.RESULT),
            kinds,
            "安装链必须投影出阶段/输出/终态三类行（实为 $kinds）",
        )
        val output = installLines.first { it.kind == NpmConsoleLineKind.OUTPUT }.text
        assertTrue(
            output.contains("added") || output.contains("up to date"),
            "OUTPUT 行必须是 npm 自己说的那段话（实为「${output.take(120)}」）",
        )

        // 控制台入口那一半：轻操作 `npm ls` 当场出结果，ECHO 行与结果行都在。
        val before = c.consoleOutput("e2e", 0, 512).lastSeq
        c.runConsoleCommand("e2e", "npm ls")
        val consoleLines = c.consoleOutput("e2e", before, 64).lines.map { it.line }
        assertEquals(
            listOf(NpmConsoleLineKind.ECHO, NpmConsoleLineKind.OUTPUT, NpmConsoleLineKind.RESULT),
            consoleLines.map { it.kind },
            "控制台敲一行 → 回显 / 结果 / 终态（实为 ${consoleLines.map { it.kind }}）",
        )
        assertTrue(consoleLines[1].text.contains("lodash@4.17.21"), "结果里要有真装上的那个包")
    }

    /**
     * 2026-10-29 修的实证切片：**卸载必须在真实树上生效**。
     *
     * 修之前 npm 跑在每次新建的空 workDir 上，`uninstall` 对空树执行 —— 设备上
     * 的观测是「卸载输出 install 风格的 `added 1 package in 2s` 且锁纹丝不动」
     * （项目根锁里 `extraneous` 孤儿 + `npmwork-<uuid>` 路径键就是那次设计的产物）。
     * 修之后 [prepareWorkDir] 把真实树搬进 workDir，卸载命中真包：输出
     * `removed N packages`、node_modules 与 lockfile 同步消失。两条断言各钉一端。
     */
    @Test
    fun `卸载在真实树上生效（removed 而非 added，锁与目录同步消失）`() = runBlocking {
        val root = dir
        val c = coordinatorAt(root, root.resolve("npm-cache"))
        val layout = NpmProjectLayout(ScriptPaths.projectsRoot(root))
        Files.createDirectories(layout.projectRoot(ProjectId("e2e3")))
        Files.write(layout.projectRoot(ProjectId("e2e3")).resolve("package.json"), ("""{"name":"e2e3","version":"0.0.1"}""").toByteArray())

        c.install("e2e3", listOf(PackageSpec("lodash", "4.17.21")))
        // 锁键形态：锚定真实树的 `node_modules/<pkg>`（不是 `npmwork-<uuid>` 墓碑路径）
        val lockText = Files.readString(layout.lockfile(ProjectId("e2e3")))
        assertTrue(
            lockText.contains(""node_modules/lodash"") && !lockText.contains("npmwork-"),
            "锁键必须是干净的 node_modules/lodash（实为：${lockText.lineSequence().first { it.contains("lodash") }?.trim()?.take(120)}）",
        )

        val nm = layout.nodeModules(ProjectId("e2e3"))
        assertTrue(Files.isDirectory(nm.resolve("lodash")), "前置：lodash 已装")
        c.uninstall("e2e3", "lodash")

        assertFalse(Files.exists(nm.resolve("lodash")), "node_modules/lodash 必须被卸载掉")
        assertFalse(c.list("e2e3", 0).any { it.name == "lodash" }, "lockfile 清单同步移除 lodash")
    }

    @Test
    fun `两次安装同事务链互不污染（第二包安装不丢第一包）`() = runBlocking {
        val root = dir
        val c = coordinatorAt(root, root.resolve("npm-cache"))
        val layout = NpmProjectLayout(ScriptPaths.projectsRoot(root))
        Files.createDirectories(layout.projectRoot(ProjectId("e2e2")))
        Files.write(layout.projectRoot(ProjectId("e2e2")).resolve("package.json"), ("""{"name":"e2e2","version":"0.0.1"}""").toByteArray())

        c.install("e2e2", listOf(PackageSpec("lodash", "4.17.21")))
        c.install("e2e2", listOf(PackageSpec("dayjs", "1.11.13")))

        val nm = layout.nodeModules(ProjectId("e2e2"))
        assertTrue(Files.isDirectory(nm.resolve("lodash")), "第一次安装产物必须保留")
        assertTrue(Files.isDirectory(nm.resolve("dayjs")), "第二次安装产物必须落位")
    }
}
