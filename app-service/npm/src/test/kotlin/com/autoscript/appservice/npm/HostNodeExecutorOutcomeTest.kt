package com.autoscript.appservice.npm

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * [HostNodeExecutor] 交给控制台的那一半：[HeavyOpOutcome.outputTail]（§10.9 第 3 条，2026-10-09 批 84）。
 *
 * **为什么可以零真 node**：[HostNodeExecutor] 的 `nodeBin` 是可注入的，而它对子进程只做两件事
 * —— 读它的 stdout、看它的退出码。于是「npm 说的最后那段话有没有被带出来」这条不变量
 * 用一个会说话的桩进程就能钉住，不必等 nightly 的 `HostNodeNpmE2ETest`（那条管的是
 * 「真 npm 跑得起来」，管不到「输出有没有被丢掉」）。
 */
class HostNodeExecutorOutcomeTest {

    @TempDir
    lateinit var dir: Path

    /** 一个只会把 argv 之后的固定文本吐到 stdout 的桩「node」。 */
    private fun stubNode(body: String): String {
        val p = dir.resolve("stub-node")
        Files.write(p, ("#!/bin/sh\n" + body + "\n").toByteArray())
        Files.setPosixFilePermissions(
            p,
            setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        return p.toAbsolutePath().toString()
    }

    private fun project(projectId: String): Path {
        val root = dir.resolve("projects").resolve(projectId)
        Files.createDirectories(root)
        Files.write(root.resolve("package.json"), """{"name":"$projectId","version":"1.0.0"}""".toByteArray())
        return root
    }

    private fun op(projectId: String, root: Path) = HeavyOp(
        nonce = "n-$projectId",
        projectId = projectId,
        args = listOf("install", "axios"),
        projectRoot = root,
        stageDir = root.resolve("node_modules.part-n-$projectId"),
        timeoutMillis = 30_000,
    )

    private fun executor(nodeBin: String): HostNodeExecutor {
        val cli = dir.resolve("npm-cli.js")
        if (!Files.exists(cli)) Files.write(cli, "// stub".toByteArray())
        return HostNodeExecutor(cli, dir.resolve("cache"), nodeBin = nodeBin)
    }

    @Test
    fun `执行体把 npm 的输出尾部原样带出（控制台要的是它说了什么，不是摘要）`() = runBlocking {
        val root = project("p1")
        val node = stubNode("""echo "added 1 package in 2s"; echo "found 0 vulnerabilities"""")
        val outcome = executor(node).execute(op("p1", root), {}, OutputSink.None)
        assertEquals("npm install 完成", outcome.summary)
        assertEquals(
            "added 1 package in 2s\nfound 0 vulnerabilities",
            outcome.outputTail,
            "输出必须原样带出（首尾空白已修）——摘要顶不了它",
        )
    }

    @Test
    fun `输出为空时给 null 而不是空串——由控制台如实说「没有捕获到」`() = runBlocking {
        val root = project("p2")
        val outcome = executor(stubNode("exit 0")).execute(op("p2", root), {}, OutputSink.None)
        assertNull(outcome.outputTail, "空输出 = null：控制台据此显示「本次没有捕获到命令输出」")
    }

    @Test
    fun `输出超上限时留尾部而不是开头（错误栈与 added N packages 都在末尾）`() = runBlocking {
        val root = project("p3")
        // 1000 行，每行 20 字符 ≈ 20000 字符 > 8000 上限
        val outcome = executor(stubNode("""i=0; while [ ${'$'}i -lt 1000 ]; do echo "line-${'$'}i-padding-pad"; i=${'$'}((i+1)); done"""))
            .execute(op("p3", root), {}, OutputSink.None)
        val tail = outcome.outputTail!!
        assertTrue(tail.length <= 8_000, "必须有界截断（实为 ${tail.length}）")
        assertTrue(tail.endsWith("line-999-padding-pad"), "留的是尾部：最旧的那些行才是可以丢的")
        assertTrue(!tail.startsWith("line-0-padding-pad"), "开头那些行已经被截掉了")
    }
}
