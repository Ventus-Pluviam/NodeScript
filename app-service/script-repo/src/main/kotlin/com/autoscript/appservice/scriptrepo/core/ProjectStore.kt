package com.autoscript.appservice.scriptrepo.core

import com.autoscript.domain.scripts.ProjectId
import java.nio.file.Files
import java.nio.file.Path

/**
 * 项目仓库（docs §9）：id → 目录（files/scripts/<id>）。
 * id 白名单校验 + 目录解析二次校验，杜绝穿越仓库根（同 [DeployPath] 的防逃逸纪律）。
 *
 * **接口收 [ProjectId] 而不是裸 `String`**（2026-10-12 批 109，typed ids）：本仓此前
 * **第二份**项目号判据就住在 [FsProjectStore]（`[A-Za-z0-9][A-Za-z0-9._-]{0,63}` ——
 * 收 `.` 且限长 64），与 `ScriptPaths.PROJECT_ID` 不同。判据收 `:domain` 之后，
 * 实现类不再自带正则，本接口也就顺理成章收类型化的 id。
 */
interface ProjectStore {
    val base: Path
    fun listIds(): List<ProjectId>
    fun exists(id: ProjectId): Boolean
    fun rootFor(id: ProjectId): Path
    fun create(id: ProjectId): Path
    fun delete(id: ProjectId)
}

/** 基于文件系统的实现：每个项目一个目录，目录内含 .deploy.journal + .stage/（见 [AtomicDeployer]）。 */
class FsProjectStore(override val base: Path) : ProjectStore {

    /**
     * 目录名 → id：**盘上读回的值没经过 [ProjectId] 的判据**，故用 [ProjectId.createOrNull]
     * 逐个构造 —— 判不过的直接不列（静默跳过而不是抛，见下）。
     *
     * **静默跳过是既有行为，判据收窄只是让它多跳过几种**（原先那份 `idPattern` 也 filter）。
     * 要抛的是「用户敲了非法 id」，那是 `create`/`rootFor` 那一侧的事（构造 `ProjectId`
     * 就会抛）；这里面对的是**别人留下的目录**，抛异常会让「打开项目列表」整个失败 ——
     * 一个叫 `my.project` 的旧目录不该让整个列表打不开。
     */
    override fun listIds(): List<ProjectId> {
        if (!Files.isDirectory(base)) return emptyList()
        val out = ArrayList<ProjectId>()
        Files.list(base).use { s ->
            s.filter { Files.isDirectory(it) }
                .forEach { out += ProjectId.createOrNull(it.fileName.toString()) ?: return@forEach }
        }
        // 排序在收集之后做：`Stream.sorted` 配上传给 `mapNotNull` 的值类要过一次
        // 装箱歧义（编译器给不出唯一的重载），而这里排序的是几十个目录名，不是热路径。
        return out.sortedBy { it.value }
    }

    override fun rootFor(id: ProjectId): Path = base.resolve(id.value)

    override fun exists(id: ProjectId): Boolean = Files.isDirectory(rootFor(id))

    override fun create(id: ProjectId): Path {
        require(id.value.isNotEmpty()) { NOT_A_PROJECT }
        val root = rootFor(id)
        Files.createDirectories(root)
        return root
    }

    override fun delete(id: ProjectId) {
        require(id.value.isNotEmpty()) { NOT_A_PROJECT }
        val root = rootFor(id)
        if (!Files.isDirectory(root)) return
        Files.walk(root).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private companion object {
        /**
         * 闸在**写侧两个口**上（2026-10-16）：[ProjectId.ROOT]（根层哨兵，空串）自本轮起
         * 构造得出来，而 `rootFor(ROOT)` 就是 `base` 本身 —— `create` 是"建一个已经存在的
         * 根"（无害但没意义），`delete` 则是**把整个项目库连根删掉**。
         * 根层不是项目，没有"删根层"这个动作；把闸设在这里，而不是把哨兵从值类里禁掉
         * （引擎侧与寻址侧正要靠它走同一套路径拼接）。
         *
         * [listIds]/[exists]/[rootFor] 不设闸：它们是**读**，`exists(ROOT)` 问的是
         * "根存不存在"，答案本身没错。
         */
        const val NOT_A_PROJECT = "根层不是项目：不能建/删 files/scripts 本身"
    }
}