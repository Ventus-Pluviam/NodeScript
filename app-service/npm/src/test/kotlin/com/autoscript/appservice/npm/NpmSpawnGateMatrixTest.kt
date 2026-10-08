package com.autoscript.appservice.npm

import com.autoscript.testkit.HostNpm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * **零 spawn 金标准**（§10.12 末行：「桌面 CI 金标准：child_process 替换为 throw 的 harness
 * 里跑全命令矩阵必须全绿；vendored npm 升级只准通过此闸」）。
 *
 * 与 [NpmSpawnGateTest] 的分工：那边钉 shim 自己的行为（七个入口、码、注入串、播报解析，
 * 用一段探针脚本）；这边钉**真 npm 在门禁下的表现** —— 前者全绿而真 npm 被门禁弄瘸，
 * 是完全可能的（比如拦了某个 npm 内部真会用的入口），而那才是用户看得见的那一面。
 *
 * 三组断言，缺一不可：
 * 1. **P0 命令矩阵在门禁下全绿**（install/ls/dedupe/prune/uninstall/ci）—— 这是 §10.11 P0
 *    承诺面的可证伪形态：零 spawn 不是"我们觉得 npm 不 spawn"，是"注入了 throw 之后
 *    这七条命令照样跑通"；
 * 2. **同样的矩阵不注入门禁也全绿** —— 反向变异：少了它，「矩阵全绿」可能只是因为矩阵
 *    本来就没跑起来（例如 lock 不匹配导致每条都早退）；
 * 3. **门禁在真 npm 上确实拦得住** —— `npm run <script>` 是 npm 真会 spawn 的一条路径
 *    （§10.3 T1：带 `--ignore-scripts` 也照跑，那是 lifecycle 闸不是 run 闸），
 *    注入后必须非零退出且输出里带 `[npm-spawn-gate]` 播报。少了它，前两条在
 *    「shim 其实没被加载」时也会绿。
 *
 * **依赖只有本地 `file:` 依赖**（不碰网络）—— 所以它能进 PR 门（jvm-tests 有 node+npm），
 * 不必等 nightly。真 registry 那部分由 `HostNodeNpmE2ETest` 覆盖。
 *
 * 宿主无 npm 时 `assumeTrue` 诚实跳过（[com.autoscript.build.TestGuard.ENV_GATED] 登记）：
 * 这是"环境不齐不假扮通过"，而 CI 上 node+npm 恒在，这道门在那边**一定真跑**。
 */
class NpmSpawnGateMatrixTest {

    @TempDir
    lateinit var dir: Path

    companion object {
        private val npmCli: Path? = HostNpm.cliJs

        @JvmStatic
        @BeforeAll
        fun assumeHostNpm() {
            assumeTrue(npmCli != null, "宿主机无 npm-cli.js，跳过零 spawn 金标准矩阵")
            assumeTrue(HostNpm.hasNode, "宿主机无 node，跳过零 spawn 金标准矩阵")
        }
    }

    /** 一次 npm 调用的结果（合并输出 + 退出码）。 */
    private data class Run(val exit: Int, val output: String)

    /** 与 `HostNodeExecutor.runNpm` 逐字同形的参数面（cwd = prefix = [work]）。 */
    private fun npm(work: Path, gate: Path?, vararg args: String): Run {
        val cmd = buildList {
            add("node")
            add(npmCli!!.toAbsolutePath().toString())
            addAll(args)
            add("--ignore-scripts"); add("--no-audit"); add("--no-fund")
            add("--cache"); add(dir.resolve("cache").toAbsolutePath().toString())
            add("--prefix"); add(work.toAbsolutePath().toString())
            add("--loglevel"); add("error")
        }
        val pb = ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(true)
        if (gate != null) {
            pb.environment()[NpmSpawnGate.ENV_NODE_OPTIONS] =
                NpmSpawnGate.mergeNodeOptions(pb.environment()[NpmSpawnGate.ENV_NODE_OPTIONS], gate)
        }
        val proc = pb.start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(proc.waitFor(120, TimeUnit.SECONDS), "npm ${args.first()} 未在 120s 内退出")
        return Run(proc.exitValue(), out)
    }

    /**
     * 造一个只有本地 `file:` 依赖的项目（**零网络**：金标准要能在 PR 门里跑）。
     *
     * `probe` 脚本**写一个文件**而不是打一行字：npm 的输出里会回显脚本文本本身，
     * 「输出里有没有出现某个词」判不出子进程到底跑没跑 —— 文件在不在才是硬判据。
     */
    private fun project(name: String): Path {
        val root = dir.resolve(name)
        val dep = root.resolve("dep")
        Files.createDirectories(dep)
        Files.write(dep.resolve("package.json"), """{"name":"dep","version":"1.0.0"}""".toByteArray())
        val probe = "node -e \"require('fs').writeFileSync('ran.txt','yes')\""
        val pkg = """{"name":"$name","version":"0.0.1","dependencies":{"dep":"file:./dep"},""" +
            """"scripts":{"probe":${jsonStr(probe)}}}"""
        Files.write(root.resolve("package.json"), pkg.toByteArray())
        return root
    }

    /** 把任意文本编成 JSON 字符串字面量（手写 package.json 时唯一的转义点）。 */
    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun gate(): Path = assertInstanceOf(
        NpmSpawnGate.Deploy.Ready::class.java,
        NpmSpawnGate.deploy(dir.resolve("files")),
    ).file

    /** 跑一串 npm 命令并逐条要求成功 + 无门禁播报（"照常跑通"的可证伪形态）。 */
    private fun expectAllGreen(work: Path, gate: Path?, vararg steps: List<String>) {
        for (args in steps) {
            val r = npm(work, gate, *args.toTypedArray())
            assertEquals(0, r.exit, "npm ${args.joinToString(" ")} 必须照常跑通；输出：\n${r.output.takeLast(800)}")
            assertTrue(
                NpmSpawnGate.blockedIn(r.output) == null,
                "跑通了就不该有门禁播报（说明拦错了东西）：\n${r.output.takeLast(800)}",
            )
        }
    }

    @Test
    fun `金标准：门禁注入下 P0 命令矩阵全绿`() {
        val g = gate()

        // ① 安装面 + 只读面 + 变更面（dedupe/prune 都是"已经对就什么都不做"）。
        val root = project("main")
        expectAllGreen(
            root, g,
            listOf("install"),
            listOf("ls"),
            listOf("ls", "--depth", "0"),
            listOf("dedupe"),
            listOf("prune"),
        )
        assertTrue(Files.isRegularFile(root.resolve("node_modules/dep/package.json")), "依赖必须真装上")

        // ② 卸载面：单开一个项目 —— `npm uninstall` 会顺手把 package.json 里的依赖项也删掉，
        //    与 ① 共用一棵树的话"再 install 一次就该回来"是错的假设。
        val rm = project("rm")
        expectAllGreen(rm, g, listOf("install"), listOf("uninstall", "dep"))
        assertTrue(!Files.exists(rm.resolve("node_modules/dep")), "uninstall 必须真删掉")

        // ③ ci：要求 lock 与 package.json 严格一致，故**就在 ① 那棵树上跑**（lock 已由 install
        //    生成）。不搬到另一个目录：`file:` 依赖在 lock 里是相对路径，搬走就成了悬空引用 ——
        //    那种情况 npm 退出码仍是 0（link 不上但也不算失败），断言会以"没重建出树"的形式
        //    红，而红的原因是搬运而不是门禁。ci 自己会先删 node_modules，故顺序上安全。
        assertTrue(Files.isRegularFile(root.resolve("package-lock.json")), "install 必须生成 lockfile")
        expectAllGreen(root, g, listOf("ci"))
        assertTrue(Files.isRegularFile(root.resolve("node_modules/dep/package.json")), "ci 必须真重建出依赖树")
    }

    @Test
    fun `反向变异：同一条矩阵不注入门禁也全绿（矩阵本身没被门禁弄绿）`() {
        val root = project("plain")
        for (args in listOf(listOf("install"), listOf("ls"), listOf("dedupe"), listOf("prune"))) {
            val r = npm(root, null, *args.toTypedArray())
            assertEquals(0, r.exit, "无门禁时 npm ${args.joinToString(" ")} 必须全绿；输出：\n${r.output.takeLast(800)}")
        }
    }

    @Test
    fun `真 npm 上确实拦得住：npm run 是 npm 真会 spawn 的一条路径`() {
        // 判据是**产物文件在不在**（脚本正文会回显在 npm 输出里，拿词表判会自己骗自己）。
        val open = project("run-open")
        val ok = npm(open, null, "run", "probe")
        assertEquals(0, ok.exit, "npm run 无门禁时必须真跑起来；输出：\n${ok.output.takeLast(800)}")
        assertTrue(Files.isRegularFile(open.resolve("ran.txt")), "脚本子进程必须真执行过（产物在）")

        // 再证明门禁拦得住它（有门禁 → 非零退出 + 可识别播报 + **没有产物**）。
        // 注意 `--ignore-scripts` 拦不住这一条：那是 lifecycle 闸，`npm run` 是用户显式发起的。
        val shut = project("run-shut")
        val blocked = npm(shut, gate(), "run", "probe")
        assertTrue(blocked.exit != 0, "npm run 在门禁下必须失败；输出：\n${blocked.output.takeLast(800)}")
        val b = NpmSpawnGate.blockedIn(blocked.output)
        assertTrue(
            b != null && b.code == "ERR_NPM_SPAWN_BLOCKED",
            "失败必须是门禁播报（不是别的原因）；实为 ${b?.code}；输出：\n${blocked.output.takeLast(800)}",
        )
        assertTrue(!Files.exists(shut.resolve("ran.txt")), "被拦了就不该有子进程跑过的痕迹（无产物）")
    }
}
