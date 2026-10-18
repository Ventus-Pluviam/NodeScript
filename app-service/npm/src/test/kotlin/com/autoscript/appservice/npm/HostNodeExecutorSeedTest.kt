package com.autoscript.appservice.npm

import com.autoscript.domain.json.DomainJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 播种工作目录时「项目缺 `package.json` 怎么办」（2026-10-17 用户口径
 * 「应该让它缺的时候自动创建呀」）。
 *
 * 修之前这里是一句 `require`：`项目 demo 缺 package.json（…），无法执行 npm install`
 * —— 而"首页根层建文件夹 = 建项目"造出来的是一个**空目录**（`package.json` 此前只有
 * 模板部署才长得出），于是"自己建的项目装不了依赖"，报的还是用户无从下手的一句。
 *
 * 造出来的那份**只落 workDir**：跑完由 `harvest` 写回项目根（与 npm 自己重写 manifest
 * 走同一条路）。故这里同时钉住反向的那一条 —— **失败/未落位时项目根一个字都不变**。
 */
class HostNodeExecutorSeedTest {

    @TempDir
    lateinit var dir: Path

    /** 只用来跑 [HostNodeExecutor.prepareWorkDir]（不 execute，故 `npmCliJs` 只需存在）。 */
    private fun executor(): HostNodeExecutor {
        val cli = dir.resolve("npm-cli.js")
        Files.write(cli, "// stub".toByteArray())
        return HostNodeExecutor(cli, dir.resolve("cache"), nodeBin = "node")
    }

    private fun op(projectId: String = "demo"): HeavyOp {
        val projectRoot = dir.resolve("scripts").resolve(projectId)
        Files.createDirectories(projectRoot)
        return HeavyOp(
            nonce = "n1", projectId = projectId, args = listOf("install", "axios"),
            projectRoot = projectRoot, stageDir = dir.resolve("stage"), timeoutMillis = 30_000,
        )
    }

    @Test
    fun `项目缺 package json 时造一份最小骨架，并如实报「新建了」`() {
        val op = op()
        val workDir = dir.resolve("work")
        val seeded = executor().prepareWorkDir(op, workDir)

        assertTrue(seeded, "项目根原本没有 package.json ⇒ 必须报「新建了」（终态摘要要据此说一句）")
        val written = workDir.resolve(ProjectManifest.FILE_NAME)
        assertTrue(Files.isRegularFile(written), "workDir 里必须有一份 —— npm 的 --prefix 只认那里")
        val fields = DomainJson.decodeObject(Files.readAllBytes(written).toString(StandardCharsets.UTF_8))
        assertEquals("demo", (fields["name"] as DomainJson.Value.S).v)
        assertEquals(true, (fields["private"] as DomainJson.Value.B).v)
        assertFalse(fields.containsKey("main"), "不写 main：入口判据留给 ProjectIndex 的回落")
    }

    @Test
    fun `自动造出来的清单不落项目根——harvest 没跑到（失败或未落位）时项目根一个字都不变`() {
        val op = op()
        executor().prepareWorkDir(op, dir.resolve("work"))
        assertFalse(
            Files.exists(op.projectRoot.resolve(ProjectManifest.FILE_NAME)),
            "造出来的那份只落 workDir：一个失败的操作不该在用户的项目里留下新文件",
        )
        assertEquals(
            listOf<Path>(),
            Files.list(op.projectRoot).use { it.toList() },
            "项目根必须还是空目录",
        )
    }

    @Test
    fun `项目已有 package json 时原样拷过去，报「没新建」`() {
        val op = op()
        val original = """{"name":"mine","version":"2.0.0","main":"app.js"}"""
        Files.write(op.projectRoot.resolve(ProjectManifest.FILE_NAME), original.toByteArray())

        val workDir = dir.resolve("work")
        val seeded = executor().prepareWorkDir(op, workDir)

        assertFalse(seeded, "本来就有 ⇒ 不许报成新建（那句话是给用户看的，不能编）")
        assertEquals(
            original,
            Files.readAllBytes(workDir.resolve(ProjectManifest.FILE_NAME)).toString(StandardCharsets.UTF_8),
            "已有的清单必须**逐字**拷过去：手搓一份会把用户的字段（main/scripts/…）全丢掉",
        )
    }

    @Test
    fun `中文项目号也能过——名字洗过，且不因名字非法而中止`() {
        val op = op(projectId = "我的项目")
        val workDir = dir.resolve("work")
        assertTrue(executor().prepareWorkDir(op, workDir))
        val fields = DomainJson.decodeObject(
            Files.readAllBytes(workDir.resolve(ProjectManifest.FILE_NAME)).toString(StandardCharsets.UTF_8),
        )
        assertEquals("autoscript-project", (fields["name"] as DomainJson.Value.S).v)
        assertEquals("我的项目", op.projectId, "洗的是清单里的名字，项目号（寻址）一个字没动")
    }
}
