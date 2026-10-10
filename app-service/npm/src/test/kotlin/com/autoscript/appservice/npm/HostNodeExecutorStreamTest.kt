package com.autoscript.appservice.npm

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Collections

/**
 * [HostNodeExecutor] 交给控制台的**过程面**：真流式 stdout（§10.9 第 3 条，2026-10-10 批 90）。
 *
 * 与 [HostNodeExecutorOutcomeTest] 的分工：那条钉的是**结果面**（跑完之后 `outputTail`
 * 里有没有 npm 说的那段话），这条钉的是**过程面** —— 输出是**边跑边到**的，
 * 而不是跑完一次性倒出来。两者是两件事，一条绿不蕴含另一条绿。
 *
 * **为什么可以零真 node**：`nodeBin` 可注入，而执行体对子进程只做三件事 ——
 * 读它的 stdout、看退出码、把读到的行报给 [OutputSink]。于是一个会「说一句、停一下、
 * 再说一句」的桩进程就足以钉住"到得早不早"这件事。
 */
class HostNodeExecutorStreamTest {

    @TempDir
    lateinit var dir: Path

    private fun stubNode(body: String): String {
        val p = dir.resolve("stub-node-${body.hashCode()}")
        Files.write(p, ("#!/bin/sh\n" + body + "\n").toByteArray())
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

    /**
     * 线程安全的收集器：报行发生在读流那条线程上，断言发生在测试线程上。
     *
     * 同时记**每行到达的时刻**（相对执行开始）—— 「流式」这个词的判据只能是时间：
     * 内容与顺序都对的实现完全可以是"跑完一次性倒出来"，那种实现过得了除时刻以外的一切断言。
     */
    private class Recorder(private val startedAtMillis: Long = System.currentTimeMillis()) : OutputSink {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val arrivals = Collections.synchronizedList(mutableListOf<Long>())
        override fun line(text: String) {
            lines += text
            arrivals += System.currentTimeMillis() - startedAtMillis
        }
    }

    @Test
    fun `输出是边跑边报的，不是跑完一次性倒出来`() = runBlocking {
        val root = project("s1")
        val recorder = Recorder()
        // 桩：先说一句，停 400ms（比读流线程的启动慢得多），再说一句，再停，然后退出。
        // 「停」是这条用例的判据所在 —— 见下面的时刻断言。
        val node = stubNode(
            """echo first-line; sleep 0.4; echo second-line; sleep 0.4; echo third-line""",
        )
        val outcome = executor(node).execute(op("s1", root), {}, recorder)

        assertEquals(
            listOf("first-line", "second-line", "third-line"),
            recorder.lines.toList(),
            "三行都要报到，且顺序与 npm 说的一致",
        )
        // **时刻**才是「流式」的判据：三行到齐的时间必须**摊开**（桩自己在中间停了两次）。
        // 一次性倒出来的实现（`readBytes()` 后再报）会让三行几乎同一毫秒到 —— 那条路
        // 过得了上面那条顺序断言，过不了这一条。
        val arrivals = recorder.arrivals.toList()
        assertEquals(3, arrivals.size)
        assertTrue(
            arrivals[0] < 300,
            "第一行必须在进程还没跑完时就到（实为 ${arrivals[0]}ms）—— 到得晚说明是攒完才倒的",
        )
        assertTrue(
            arrivals[2] - arrivals[0] >= 600,
            "三行的时间跨度要盖住桩的两次 sleep（实为 ${arrivals}）—— 跨度小说明是一次性倒出来的",
        )
        // 结果面不受影响：全量输出照样带回去（`outputTail` 与 `failureOf` 都要它）。
        assertEquals("first-line\nsecond-line\nthird-line", outcome.outputTail)
    }

    @Test
    fun `没人订阅流时输出照样被排空（管道反压会卡死 npm）`() = runBlocking {
        val root = project("s2")
        // 1000 行 ≈ 20KB：远超管道缓冲区（Linux 默认 64KB，但这里要的是"读完"这件事
        // 本身成立）。若没人排空，进程会卡在写、我们卡在 waitFor —— 谁也没动。
        val node = stubNode("""i=0; while [ ${'$'}i -lt 1000 ]; do echo "line-${'$'}i-padding-pad"; i=${'$'}((i+1)); done""")
        val outcome = executor(node).execute(op("s2", root), {}, OutputSink.None)
        assertTrue(
            outcome.outputTail!!.endsWith("line-999-padding-pad"),
            "没人看也要读完（反压会把 npm 卡死在写上）：实为「${outcome.outputTail!!.takeLast(60)}」",
        )
    }

    @Test
    fun `空行不报给控制台（一行一条的账里空行不表示任何东西）`() = runBlocking {
        val root = project("s3")
        val recorder = Recorder()
        val node = stubNode("""echo a; echo ""; echo b""")
        val outcome = executor(node).execute(op("s3", root), {}, recorder)
        assertEquals(listOf("a", "b"), recorder.lines.toList(), "空行跳过")
        assertTrue(outcome.outputTail!!.contains("a\n\nb"), "但原样文本里空行还在：${outcome.outputTail}")
    }

    @Test
    fun `报行的实现抛异常不会打死读流线程`() = runBlocking {
        val root = project("s4")
        val seen = Collections.synchronizedList(mutableListOf<String>())
        // 第一次报就抛：读流线程若被它带走，管道就没人排空 —— 一次界面故障
        // 会升级成一次安装超时（`outputTail` 也会是半截）。
        val exploding = OutputSink { text ->
            seen += text
            error("显示面炸了")
        }
        val node = stubNode("""echo one; echo two; echo three""")
        val outcome = executor(node).execute(op("s4", root), {}, exploding)
        assertEquals("one\ntwo\nthree", outcome.outputTail, "读流不受影响，输出仍然完整")
        assertFalse(seen.isEmpty(), "抛之前那行已经报出去了（说明它确实在报）")
    }
}
