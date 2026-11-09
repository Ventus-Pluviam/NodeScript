package com.autoscript.appservice.npm

import com.autoscript.domain.npm.InstallEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Collections

/**
 * [HostNodeExecutor] 报的**阶段面**：它只报得到 `DOWNLOAD`/`REIFY`，`DONE` 归协调器。
 *
 * 与 [HostNodeExecutorStreamTest]（输出流）、[HostNodeExecutorOutcomeTest]（输出尾部）
 * 是同一个执行体的三个面；这条钉的是「执行体不许替会话宣布完成」。
 *
 * **为什么可以零真 node**：`nodeBin` 可注入，而本条断言与 npm 干了什么无关 ——
 * 要钉的只是「执行体经 [ProgressSink] 报了哪几格」。
 *
 * **回归钉（2026-11-09 真机日志）**：`DONE` 曾在 [HostNodeExecutor] 里发（harvest 之后），
 * 而协调器在执行体**返回之后**才发 `POST_CHECK`。于是事件环里的次序是
 * `… → REIFY → DONE → POST_CHECK`，与 [InstallEvent.Phase] 的声明序倒挂，两个具体后果：
 * ① UI 阶段条按 `InstallProgressState.ORDER` 点亮（`i <= reached`），六格先亮满再退一格；
 * ② 落位/签名失败时控制台先说「完成」再说「失败」，同一句柄两句互相矛盾的结论。
 */
class HostNodeExecutorPhaseTest {

    @TempDir
    lateinit var dir: Path

    private fun stubNode(): String {
        val p = dir.resolve("stub-node")
        Files.write(p, "#!/bin/sh\necho \"added 1 package in 1s\"\n".toByteArray())
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

    private fun executor(): HostNodeExecutor {
        val cli = dir.resolve("npm-cli.js")
        if (!Files.exists(cli)) Files.write(cli, "// stub".toByteArray())
        return HostNodeExecutor(cli, dir.resolve("cache"), nodeBin = stubNode())
    }

    @Test
    fun `执行体只报到 REIFY —— DONE 是会话结论，不是进程退出码`() = runBlocking {
        val root = project("p1")
        // 线程安全：`execute` 在 `Dispatchers.IO` 上跑，报阶段发生在那条线程上。
        val phases = Collections.synchronizedList(mutableListOf<InstallEvent.Phase>())

        executor().execute(
            op("p1", root),
            { ev -> (ev as? InstallEvent.Progress)?.let { phases += it.phase } },
            OutputSink.None,
        )

        assertEquals(
            listOf(InstallEvent.Phase.DOWNLOAD, InstallEvent.Phase.REIFY),
            phases.toList(),
            "执行体多报 DONE 就会把它压在协调器的 POST_CHECK 前面（次序倒挂，见类 KDoc）",
        )
        Unit
    }
}
