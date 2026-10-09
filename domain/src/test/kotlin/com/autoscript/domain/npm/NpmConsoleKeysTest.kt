package com.autoscript.domain.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 控制台命令行判据的回归钉（§10.9 第 3 条）。
 *
 * 每条断言都在钉一件**反向变异能红**的事：放行一条本该拒的行 = 用户在控制台里
 * 敲出一个「看起来跑了、其实什么都没发生」的命令；拒掉一条本该放行的行 =
 * 白名单外的东西被静默吞掉。
 */
class NpmConsoleKeysTest {

    // —— 放行面 ——

    @Test
    fun `白名单内每条子命令都解析成 Npm 且参数原样带上`() {
        for (sub in NpmConsoleKeys.SUBCOMMANDS) {
            val parsed = NpmConsoleKeys.parse("npm $sub axios")
            assertEquals(NpmConsoleCommand.Npm(sub, listOf("axios")), parsed, "子命令 $sub 应当放行")
        }
    }

    @Test
    fun `无参数的子命令也放行（ls 不带包名是常态）`() {
        assertEquals(NpmConsoleCommand.Npm("ls", emptyList()), NpmConsoleKeys.parse("npm ls"))
    }

    @Test
    fun `多余空白被折叠（用户从别处粘一行带缩进的命令）`() {
        assertEquals(
            NpmConsoleCommand.Npm("install", listOf("axios", "--save-dev")),
            NpmConsoleKeys.parse("   npm   install   axios   --save-dev  "),
        )
    }

    @Test
    fun `npm run 与 run-script 同义，分隔符 -- 被剥掉`() {
        assertEquals(NpmConsoleCommand.Run("build", emptyList()), NpmConsoleKeys.parse("npm run build"))
        assertEquals(NpmConsoleCommand.Run("build", emptyList()), NpmConsoleKeys.parse("npm run-script build"))
        assertEquals(
            NpmConsoleCommand.Run("test", listOf("--watch")),
            NpmConsoleKeys.parse("npm run test -- --watch"),
        )
    }

    @Test
    fun `npx 与 npm exec 同义，分隔符 -- 被剥掉`() {
        assertEquals(NpmConsoleCommand.Exec("esbuild", emptyList()), NpmConsoleKeys.parse("npx esbuild"))
        assertEquals(
            NpmConsoleCommand.Exec("esbuild", listOf("--version")),
            NpmConsoleKeys.parse("npx esbuild --version"),
        )
        assertEquals(
            NpmConsoleCommand.Exec("esbuild", listOf("--version")),
            NpmConsoleKeys.parse("npm exec esbuild -- --version"),
        )
    }

    // —— 拒收面：每条都要「原文点名」——

    @Test
    fun `空行被拒且点名请写一行`() {
        val r = NpmConsoleKeys.parse("   ") as NpmConsoleCommand.Rejected
        assertTrue("请写一行" in r.reason, r.reason)
    }

    @Test
    fun `默认模式下裸首词按 npm bin 解析（装好的依赖提供的命令）`() {
        // 2026-10-09 二次裁定：控制台要能直接敲 tsc / eslint 这类命令。
        assertEquals(NpmConsoleCommand.Exec("tsc", emptyList()), NpmConsoleKeys.parse("tsc"))
        assertEquals(
            NpmConsoleCommand.Exec("tsc", listOf("--noEmit")),
            NpmConsoleKeys.parse("tsc --noEmit"),
        )
        // 存在性判不了（要看文件系统）—— 那是宿主的事，判据只负责把首词定形。
        assertEquals(NpmConsoleCommand.Exec("ls", listOf("-la")), NpmConsoleKeys.parse("ls -la"))
    }

    @Test
    fun `su 与 shizuku 单独一行 = 进模式，带参数 = 就地跑一条 shell 命令`() {
        assertEquals(NpmConsoleCommand.EnterMode(ShellConsoleMode.ROOT), NpmConsoleKeys.parse("su"))
        assertEquals(NpmConsoleCommand.EnterMode(ShellConsoleMode.ADB), NpmConsoleKeys.parse("shizuku"))
        assertEquals(
            NpmConsoleCommand.Shell("id", ShellConsoleMode.ROOT),
            NpmConsoleKeys.parse("su id"),
        )
        assertEquals(
            NpmConsoleCommand.Shell("ls -la /sdcard", ShellConsoleMode.ADB),
            NpmConsoleKeys.parse("shizuku ls -la /sdcard"),
        )
    }

    @Test
    fun `exit 退出特权模式，且不带参数`() {
        assertEquals(NpmConsoleCommand.ExitMode, NpmConsoleKeys.parse("exit"))
        val r = NpmConsoleKeys.parse("exit 1") as NpmConsoleCommand.Rejected
        assertTrue("exit" in r.reason, r.reason)
    }

    @Test
    fun `特权模式下裸首词是 shell 命令，且正文原样透传（不重新分词）`() {
        // 进了 root 之后敲 ls 要的是 shell 的 ls，不是某个恰好叫 ls 的包。
        assertEquals(
            NpmConsoleCommand.Shell("ls -la", ShellConsoleMode.ROOT),
            NpmConsoleKeys.parse("ls -la", ShellConsoleMode.ROOT),
        )
        // 双空格/引号原样带下去 —— 在这里切一遍再拼回去就是**改用户的命令**。
        assertEquals(
            NpmConsoleCommand.Shell("""echo "a  b"""", ShellConsoleMode.ROOT),
            NpmConsoleKeys.parse("""echo "a  b"""", ShellConsoleMode.ROOT),
        )
    }

    @Test
    fun `五个入口词在任何模式下都优先（否则进了 root 就退不出来）`() {
        assertEquals(
            NpmConsoleCommand.Npm("ls", emptyList()),
            NpmConsoleKeys.parse("npm ls", ShellConsoleMode.ROOT),
        )
        assertEquals(
            NpmConsoleCommand.Exec("esbuild", emptyList()),
            NpmConsoleKeys.parse("npx esbuild", ShellConsoleMode.ROOT),
        )
        assertEquals(NpmConsoleCommand.ExitMode, NpmConsoleKeys.parse("exit", ShellConsoleMode.ADB))
        assertEquals(
            NpmConsoleCommand.EnterMode(ShellConsoleMode.ADB),
            NpmConsoleKeys.parse("shizuku", ShellConsoleMode.ROOT),
        )
    }

    @Test
    fun `npm 后面没子命令被拒且念出白名单`() {
        val r = NpmConsoleKeys.parse("npm") as NpmConsoleCommand.Rejected
        assertTrue("子命令" in r.reason && "install" in r.reason, r.reason)
    }

    @Test
    fun `白名单外的子命令被拒且念出白名单全体`() {
        // config/publish/login 是三类典型：改宿主全局状态 / 要凭据 / 本平台无意义。
        for (sub in listOf("config", "publish", "login", "token", "owner", "init", "link", "cache", "update")) {
            val r = NpmConsoleKeys.parse("npm $sub foo") as NpmConsoleCommand.Rejected
            assertTrue(sub in r.reason, "理由要点名那个子命令：${r.reason}")
            assertTrue("install" in r.reason, "理由要念出白名单：${r.reason}")
        }
    }

    @Test
    fun `npm run 与 npm exec 缺名字被拒`() {
        assertTrue("脚本名" in (NpmConsoleKeys.parse("npm run") as NpmConsoleCommand.Rejected).reason)
        assertTrue("命令名" in (NpmConsoleKeys.parse("npm exec") as NpmConsoleCommand.Rejected).reason)
        assertTrue("命令名" in (NpmConsoleKeys.parse("npx") as NpmConsoleCommand.Rejected).reason)
    }

    @Test
    fun `git 依赖被拒且指向 tarball 导入（§10_3 明确不可行）`() {
        for (spec in listOf(
            "git+https://github.com/u/r.git",
            "git://github.com/u/r.git",
            "git@github.com:u/r.git",
            "github:u/r",
            "gitlab:u/r",
            "bitbucket:u/r",
        )) {
            val r = NpmConsoleKeys.parse("npm install $spec") as NpmConsoleCommand.Rejected
            assertTrue(spec in r.reason, "理由要点名那个 spec：${r.reason}")
            assertTrue("importTarball" in r.reason, "理由要给出可操作替代：${r.reason}")
        }
    }

    @Test
    fun `旗标不以 - 开头才当 spec —— --registry 不该被误判成 git 依赖`() {
        assertEquals(
            NpmConsoleCommand.Npm("install", listOf("--registry", "https://x.example")),
            NpmConsoleKeys.parse("npm install --registry https://x.example"),
        )
    }

    @Test
    fun `isGitSpec 覆盖 npm 认的六种 git 形态，但不误伤普通包名`() {
        for (s in listOf("git", "GIT", "git:", "git+ssh://h/r", "git@h:r", "github:u/r", "gitlab:u/r", "bitbucket:u/r")) {
            assertTrue(NpmConsoleKeys.isGitSpec(s), "「$s」应当判为 git 形态")
        }
        for (s in listOf("axios", "@scope/pkg", "gitignore-parser", "digit", "github-actions")) {
            assertTrue(!NpmConsoleKeys.isGitSpec(s), "「$s」不是 git 依赖，不该被拒")
        }
    }

    // —— 包说明符抽取（宿主侧装前预检用）——

    @Test
    fun `包说明符抽取：名字与范围分开，旗标不算包`() {
        assertEquals(
            listOf(PackageSpec("axios", "1.7.0"), PackageSpec("dayjs")),
            NpmConsoleKeys.packageSpecsIn(listOf("axios@1.7.0", "--save-dev", "dayjs")),
        )
    }

    @Test
    fun `scope 包名的 @ 不是版本分隔符（切第一个会把 @acme 切坏）`() {
        assertEquals(listOf(PackageSpec("@acme/pkg")), NpmConsoleKeys.packageSpecsIn(listOf("@acme/pkg")))
        assertEquals(
            listOf(PackageSpec("@acme/pkg", "2.0.0")),
            NpmConsoleKeys.packageSpecsIn(listOf("@acme/pkg@2.0.0")),
            "切点必须是最后一个 @：首位那个是 scope 的",
        )
    }

    @Test
    fun `没有包说明符时给空表（预检据此跳过，不拿空包名去问）`() {
        assertEquals(emptyList<PackageSpec>(), NpmConsoleKeys.packageSpecsIn(listOf("--offline", "--no-save")))
        assertEquals(emptyList<PackageSpec>(), NpmConsoleKeys.packageSpecsIn(emptyList()))
    }

    // —— 项目号判据（唯一一份在 ScriptPaths）——

    @Test
    fun `项目号判据与落盘侧同源`() {
        assertNull(NpmConsoleKeys.rejectProjectId("main"))
        assertNull(NpmConsoleKeys.rejectProjectId("my-proj_1-2"))
        // `.` 与 `..` 在名单里是刻意的：`resolve("..")` 正好跳出项目根，
        // 而原正则 `[A-Za-z0-9._-]+` 把它们放行了（批 84 收口）。
        for (bad in listOf("", ".", "..", "a/b", "a b", "a\\b")) {
            val why = NpmConsoleKeys.rejectProjectId(bad)
            assertNotNull(why, "「$bad」应当被拒")
            assertTrue(bad in why!!, "理由要点名原文：$why")
        }
        // 同源证明：`:domain` 的判据就是 `NpmProjectLayout` 用的那一条。
        assertEquals(
            com.autoscript.domain.scripts.ScriptPaths.isValidProjectId("main"),
            NpmConsoleKeys.rejectProjectId("main") == null,
        )
    }
}
