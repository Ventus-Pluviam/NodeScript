package com.autoscript.appservice.scriptrepo.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * 项目仓库（docs/framework-design.md §9）：id → 目录（files/scripts/<id>）。
 * id 白名单校验 + 目录解析二次校验，杜绝穿越仓库根（同 [DeployPath] 的防逃逸纪律）。
 */
interface ProjectStore {
    val base: Path
    fun listIds(): List<String>
    fun exists(id: String): Boolean
    fun rootFor(id: String): Path
    fun create(id: String): Path
    fun delete(id: String)
}

/** 基于文件系统的实现：每个项目一个目录，目录内含 .deploy.journal + .stage/（见 [AtomicDeployer]）。 */
class FsProjectStore(override val base: Path) : ProjectStore {

    // 首字符必须字母数字：拒绝 "." / ".." / ".foo" 这类与 .stage、.deploy.journal 冲突且可逃逸的形态
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    private fun safeId(id: String): String {
        require(idPattern.matches(id)) { "非法项目 id: $id" }
        return id
    }

    override fun rootFor(id: String): Path = base.resolve(safeId(id))

    override fun listIds(): List<String> {
        if (!Files.isDirectory(base)) return emptyList()
        return Files.list(base).use { s ->
            s.filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .filter { idPattern.matches(it) }
                .sorted()
                .collect(Collectors.toList())
        }
    }

    override fun exists(id: String): Boolean = Files.isDirectory(rootFor(id))

    override fun create(id: String): Path {
        val root = rootFor(id)
        Files.createDirectories(root)
        return root
    }

    override fun delete(id: String) {
        val root = rootFor(id)
        if (!Files.isDirectory(root)) return
        Files.walk(root).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}