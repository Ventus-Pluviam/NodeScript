package com.autoscript.appservice.packager.npm

import com.autoscript.domain.npm.PackageSpec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 真实 npm 端到端（§10 P0 实证切片）：
 * HostNodeExecutor 在暂存目录跑真 `npm install --prefer-offline --ignore-scripts`，
 * 产物经 InstallCoordinator 事务链（journal begin/commit + ATOMIC_MOVE 落位）进 node_modules。
 *
 * 运行条件：宿主机存在 node + npm-cli.js（CI ubuntu-latest 自带；无则跳过）。
 * 网络：默认 registry.npmmirror.com；离线 CI 可在首次跑通后靠 _cacache 复跑。
 */
class HostNodeNpmE2ETest {

    @TempDir
    lateinit var dir: Path

    companion object {
        private val npmCli: Path? = sequenceOf(
            "/usr/lib/node_modules/npm/bin/npm-cli.js",
            "/usr/local/lib/node_modules/npm/bin/npm-cli.js",
        ).map { Path.of(it) }.firstOrNull { Files.isRegularFile(it) }

        @JvmStatic
        @BeforeAll
        fun assumeNode() {
            assumeTrue(npmCli != null, "宿主机无 npm-cli.js，跳过真实 npm e2e")
        }
    }

    private fun coordinatorAt(root: Path, cacheDir: Path): InstallCoordinator {
        val layout = NpmProjectLayout(root.resolve("scripts"))
        return InstallCoordinator(
            services = NpmServices(
                layout = layout,
                journal = InstallJournal(root.resolve(".autojs")),
                staging = InstallStaging(layout),
                ledger = ApprovalLedger(),
                cacheIndex = CacheIndex { false },
            ),
            executor = HostNodeExecutor(npmCli!!, cacheDir),
        )
    }

    @Test
    fun `真实安装 lodash 进事务化 node_modules`() = runBlocking {
        val root = dir
        val c = coordinatorAt(root, root.resolve("npm-cache"))
        val layout = NpmProjectLayout(root.resolve("scripts"))

        // 项目根需要 package.json（npm install 的前置）
        Files.createDirectories(layout.projectRoot("e2e"))
        Files.writeString(layout.projectRoot("e2e").resolve("package.json"), """{"name":"e2e","version":"0.0.1"}""")

        val handle = c.install("e2e", listOf(PackageSpec("lodash", "4.17.21")))
        // 产物落位
        val nm = layout.nodeModules("e2e")
        assertTrue(Files.isDirectory(nm.resolve("lodash")), "node_modules/lodash 必须存在")
        // journal begin+commit 成对
        val journal = InstallJournal(root.resolve(".autojs"))
        val states = journal.all().map { it.state }
        assertTrue(
            InstallJournal.State.COMMIT in states,
            "journal 必须含 COMMIT（实为 $states）",
        )
        assertTrue(journal.unfinished().isEmpty())
        // lockfile 由执行体随成功事务写回项目根（失败不污染）
        val lockInProject = layout.lockfile("e2e")
        assertTrue(Files.exists(lockInProject), "package-lock.json 必须生成于项目根")
        // 轻操作直读：list 应能看到 lodash（lockfile v3 被 LockfileReader 解析）
        assertTrue(c.list("e2e", 0).any { it.name == "lodash" && it.version == "4.17.21" })
    }

    @Test
    fun `两次安装同事务链互不污染（第二包安装不丢第一包）`() = runBlocking {
        val root = dir
        val c = coordinatorAt(root, root.resolve("npm-cache"))
        val layout = NpmProjectLayout(root.resolve("scripts"))
        Files.createDirectories(layout.projectRoot("e2e2"))
        Files.writeString(layout.projectRoot("e2e2").resolve("package.json"), """{"name":"e2e2","version":"0.0.1"}""")

        c.install("e2e2", listOf(PackageSpec("lodash", "4.17.21")))
        c.install("e2e2", listOf(PackageSpec("dayjs", "1.11.13")))

        val nm = layout.nodeModules("e2e2")
        assertTrue(Files.isDirectory(nm.resolve("lodash")), "第一次安装产物必须保留")
        assertTrue(Files.isDirectory(nm.resolve("dayjs")), "第二次安装产物必须落位")
    }
}
