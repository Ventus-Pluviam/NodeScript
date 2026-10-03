package com.autoscript.shell

import com.autoscript.appservice.scriptrepo.core.DeployPath
import com.autoscript.domain.scripts.ScriptPaths
import java.nio.file.Files
import java.nio.file.Path

/**
 * 项目页操作面的落盘（新建文件/新建文件夹；TG FAB 展开两项的对应位）。
 *
 * **为什么单独一个对象**（与 [ScriptFilesRead] 同一条理由）：`Application` 在 JVM
 * 单测里构造不出来，而"名字合不合法、路径怎么落、撞了已存在的怎么办"这段判断必须可测。
 *
 * 两条口径：
 * - **名字 = 单段相对路径**：用户输入的是名字，不是路径 —— 斜杠/`..`/空段直接拒绝
 *   （单段校验在前，[DeployPath.isSafeRelPath] 兜底 —— 后者是部署路径的判据，
 *   多段相对路径对它合法，拦不住 `lib/main.js` 穿到落盘才炸），要进文件夹先建文件夹。
 *   合法性在本层裁决（呈现层不写第二套判据），原文抛给 UI；
 * - **不覆盖已存在**（`CREATE_NEW` 语义）：TG 文件页新建撞名是报错不覆盖 ——
 *   静默覆盖会把用户的 `main.js` 换成空文件，比报错糟糕得多。
 */
object ScriptFileOps {

    /**
     * 新建文件（内容为空）。
     *
     * @param filesDir App 私有文件目录。
     * @param projectId 目标项目（`files/scripts/` 下第一级）。
     * @param name 文件名（单段；可带扩展名，扩展名决定列表里的类型图标）。
     * @throws IllegalArgumentException 名字非法（含 `/`、`..` 等）。
     * @throws java.nio.file.FileAlreadyExistsException 同名文件/文件夹已存在。
     */
    fun createFile(filesDir: Path, projectId: String, name: String) {
        val target = resolveName(filesDir, projectId, name)
        Files.createFile(target)
    }

    /**
     * 新建文件夹。
     *
     * @throws IllegalArgumentException 名字非法。
     * @throws java.nio.file.FileAlreadyExistsException 同名文件/文件夹已存在。
     */
    fun createFolder(filesDir: Path, projectId: String, name: String) {
        val target = resolveName(filesDir, projectId, name)
        Files.createDirectory(target)
    }

    /** 名字 → `files/scripts/<projectId>/<name>`：合法性裁决 + 落位唯一出口。 */
    private fun resolveName(filesDir: Path, projectId: String, name: String): Path {
        require(name.isNotBlank()) { "名字不能为空" }
        // 单段校验在前：isSafeRelPath 是部署路径的判据（多段相对路径对部署合法），
        // `lib/main.js` 会穿过去、到 Files.createFile 才炸出 NoSuchFileException。
        require('/' !in name) { "名字不能含 /（要进子文件夹先建文件夹）" }
        require(DeployPath.isSafeRelPath(name)) { "名字含非法字符（.. 不允许）" }
        val root = ScriptPaths.projectRoot(filesDir, projectId)
        require(Files.isDirectory(root)) { "项目不存在: $projectId" }
        return root.resolve(name)
    }
}
