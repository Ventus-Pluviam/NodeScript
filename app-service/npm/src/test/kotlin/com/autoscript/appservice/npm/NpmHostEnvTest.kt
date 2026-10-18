package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * `AUTOSCRIPT_LIBNODE` 这条必填件的回归闸（2026-10-17 设备实测 bug）。
 *
 * **病**（用户报的原文）：`npm 退出码 2: :nodeN 缺 AUTOSCRIPT_LIBNODE (libnode.so 绝对路径)`。
 * 脚本引擎那条路（`NodeProcessEngine`）一直把这条注下去，npm 这条路
 * （[HostNodeExecutor] / [NpmScriptExecutor]）**从来没注过** —— 两者 spawn 的是同一个
 * `noden`，而 `main.cpp` 头一件事就是读它去 `dlopen`。
 *
 * **为什么本机与 CI 都抓不到**：桌面测试跑的是系统 `node`，它不读这个变量。
 * 所以这里守两件事：① [NpmHostEnv] 自身的判据；② **路径真到子进程环境里了**
 * （真 spawn 一次桩进程，让它把变量打回来）—— 只测 ① 的话，"执行体忘了调它"
 * 这种病一条都拦不住。
 */
class NpmHostEnvTest {

    @TempDir
    lateinit var dir: Path

    /** 桩进程：把 libnode 那条变量原样打回 stdout，别的什么都不做。 */
    private fun stubNode(): String {
        val p = dir.resolve("stub-node")
        Files.write(p, "#!/bin/sh\necho \"LIB=[\$AUTOSCRIPT_LIBNODE]\"\n".toByteArray())
        Files.setPosixFilePermissions(
            p,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        return p.toAbsolutePath().toString()
    }

    private fun op(projectId: String): HeavyOp {
        val root = dir.resolve("projects").resolve(projectId)
        Files.createDirectories(root)
        Files.write(root.resolve("package.json"), """{"name":"$projectId","version":"1.0.0"}""".toByteArray())
        return HeavyOp(
            nonce = "n-$projectId",
            projectId = projectId,
            args = listOf("install", "axios"),
            projectRoot = root,
            stageDir = root.resolve("stage"),
            timeoutMillis = 30_000,
        )
    }

    private fun executor(libnode: Path?): HostNodeExecutor {
        val cli = dir.resolve("npm-cli.js")
        if (!Files.exists(cli)) Files.write(cli, "// stub".toByteArray())
        return HostNodeExecutor(
            cli,
            dir.resolve("cache"),
            nodeBin = stubNode(),
            libnodePath = libnode,
        )
    }

    // ── ① 判据本体 ────────────────────────────────────────────────────────────

    /**
     * 名字的锚（**跨语言，只能钉字面量**）：`main.cpp` 头注释 / `NodeProcessEngine.ENV_LIBNODE`
     * / docs §7.8 / 本处 —— 改名要四处同批，C++ 那边没有能被 Kotlin 引用的常量。
     */
    @Test
    fun `键名与 C++ 宿主那份逐字同名（改名要四处同批）`() {
        assertEquals("AUTOSCRIPT_LIBNODE", NpmHostEnv.ENV_LIBNODE)
    }

    @Test
    fun `宿主键后写：压过基础表里的同名键（顺序即契约）`() {
        val env = mutableMapOf(NpmHostEnv.ENV_LIBNODE to "/被人抢先设的/libnode.so", "PATH" to "/bin")
        NpmHostEnv.apply(env, dir.resolve("libnode.so"))

        assertEquals(dir.resolve("libnode.so").toString(), env[NpmHostEnv.ENV_LIBNODE], "宿主指的是哪一件就是哪一件")
        assertEquals("/bin", env["PATH"], "别的键一个不动")
    }

    @Test
    fun `没有 libnode 的宿主（桌面系统 node）：一个键都不动`() {
        val env = mutableMapOf("PATH" to "/bin")
        NpmHostEnv.apply(env, null)
        assertEquals(mapOf("PATH" to "/bin"), env.toMap())
    }

    @Test
    fun `预检：配了却在盘上找不到 = 打包缺件，当场点名`() {
        val missing = dir.resolve("no-such-libnode.so")
        val e = assertThrows(AutojsException::class.java) { NpmHostEnv.checkInstalled(missing) }
        assertEquals(ErrorCode.ERR_FILE_NOT_FOUND, e.error)
        assertTrue(
            e.message!!.contains(missing.toString()) && e.message!!.contains("libnode.so"),
            "点名缺的是哪个文件：${e.message}",
        )
    }

    @Test
    fun `预检：非绝对路径是 PATH 名（桌面形态），放行交给 spawn 报`() {
        NpmHostEnv.checkInstalled(Path.of("node"))
        NpmHostEnv.checkInstalled(null)
    }

    // ── ② 真的到了子进程环境里（不是"我们以为自己注了"）──────────────────────

    @Test
    fun `执行体把 libnode 路径送进了会话进程的环境`() = runBlocking {
        val lib = dir.resolve("libnode.so").also { Files.write(it, ByteArray(0)) }
        val outcome = executor(lib).execute(op("env1"), {}, OutputSink.None)
        assertTrue(
            outcome.outputTail!!.contains("LIB=[${lib.toAbsolutePath()}]"),
            "子进程环境里没有 AUTOSCRIPT_LIBNODE（设备上就是 exit 2）：实为「${outcome.outputTail}」",
        )
    }

    /** 反向：这条断言分辨得出"注了"与"没注"（没有它，上面那条可能只是环境里本来就有）。 */
    @Test
    fun `不给 libnode 时不注入（反向对照）`() = runBlocking {
        val outcome = executor(null).execute(op("env2"), {}, OutputSink.None)
        assertFalse(
            outcome.outputTail!!.contains("libnode.so"),
            "没给就一个字节都不该出现：实为「${outcome.outputTail}」",
        )
    }
}
