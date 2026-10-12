package com.autoscript.shell

import com.autoscript.appservice.scriptrepo.core.DeployPath
import com.autoscript.domain.scripts.ScriptPaths
import java.io.IOException
import com.autoscript.domain.scripts.ProjectId
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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

    /**
     * 编辑器能打开的文本上限（2 MiB）：再大就不是"脚本"了，整篇塞进 Compose 的
     * 文本框会直接把 UI 拖死（每帧重排版几十万字符）。超限**抛**，由 UI 如实显示。
     */
    const val MAX_EDIT_BYTES: Long = 2L * 1024 * 1024

    /**
     * 读一个脚本文件的文本（项目页点文件进编辑）。
     *
     * 拒绝条件（都抛，原文给 UI）：
     * - 目标不是**已存在的普通文件**（目录/不存在）—— 编辑器只编辑文件；
     * - 超过 [MAX_EDIT_BYTES]；
     * - 内容含 NUL（二进制）—— 当文本读进来再存回去就是**损坏用户文件**，
     *   宁可在这里拒绝；
     * - 非法 UTF-8 字节：严格解码，不用替换字符冒充原文。
     */
    fun read(filesDir: Path, projectId: String, relPath: String): String {
        val target = resolveInProject(filesDir, projectId, relPath)
        require(Files.isRegularFile(target)) { "不是文件：$relPath" }
        val size = Files.size(target)
        require(size <= MAX_EDIT_BYTES) { "文件太大（$size 字节，上限 $MAX_EDIT_BYTES）：编辑器不打开" }
        val bytes = Files.readAllBytes(target)
        require(bytes.none { it == 0.toByte() }) {
            // 原文给 UI：这句会**逐字**显示在编辑器里，所以把"能开什么"也写进去 ——
            // 用户手里的 .js/.txt 打不开时，这句话本身就是答复。
            "这不是文本文件（含二进制字节）：$relPath —— 编辑器只开 UTF-8 文本（.js/.txt/.json/.md 等）"
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("这不是合法的 UTF-8 文本：$relPath —— 编辑器拒绝打开以免损坏原文件", e)
        }
    }

    /**
     * 覆盖写一个脚本文件的文本（编辑器「保存」）。
     *
     * **原子替换**：先写同目录临时文件，再 `ATOMIC_MOVE` 覆盖 —— 直接往原文件里写，
     * 写到一半失败（磁盘满/进程被杀）会留下一个语法坏掉的脚本，而用户以为"保存成功"。
     * 文件系统不支持原子移动时退一步用 `REPLACE_EXISTING`（同目录 rename 仍是替换语义，
     * 只是不保证断电下的原子性），仍好过就地写。
     *
     * **只覆盖已存在的文件**：不新建（新建走 [createFile]，撞名语义在那里）。
     */
    fun save(filesDir: Path, projectId: String, relPath: String, content: String) {
        val target = resolveInProject(filesDir, projectId, relPath)
        require(Files.isRegularFile(target)) { "文件不存在（不新建）：$relPath" }
        val tmp = Files.createTempFile(target.parent, ".edit-", ".tmp")
        try {
            Files.write(tmp, content.toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                // 该文件系统不支持原子移动 → 退一步用 REPLACE_EXISTING（同目录 rename 仍是
                // 替换语义，只是不保证断电下的原子性）。**原异常不吞**：兜底再失败时挂到
                // suppress 上一起抛，调用方拿到的原因链里两段都在。
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                } catch (io: IOException) {
                    io.addSuppressed(e)
                    throw io
                }
            }
        } finally {
            // move 成功时 tmp 已不在；失败时清掉，别在用户的项目目录里留垃圾。
            Files.deleteIfExists(tmp)
        }
    }

    /**
     * `relPath` → 绝对路径：**`files/scripts/` 之下的相对路径**（首段 = 项目名）。
     *
     * 为什么不是"项目根之下的相对路径"：呈现层手里那条是 [com.autoscript.domain.host.ScriptFileRow.relPath]，
     * 它由 `ScriptFilesRead` 从 `files/scripts/` 起算（`root.relativize(path)`），**首段就是**
     * `projectId`。契约按"列表给什么就收什么"写，实现按"首段必须等于 [projectId]"校验 ——
     * 两者说不到一块（比如传了项目内相对路径 `lib/main.js`）当场抛，而不是静默拼成
     * `files/scripts/demo/lib/main.js` 去读另一个文件。**2026-10-06 实测踩过这个坑**：
     * 编辑器报"不是文件：demo/main.js"，因为被拼成了 `files/scripts/demo/demo/main.js`。
     *
     * 三道校验：[DeployPath.resolveIn] 防 `..`/绝对路径/越界、首段与项目名一致、项目目录存在。
     *
     * （原文这里写着「[ScriptPaths.scriptFile] 刻意不做逃逸校验」，**2026-10-12 删掉**——
     * 那是批 7c0fec9 订正前的状态：外审 M1 收口时 `scriptFile` 已补上词法闸，理由与
     * 词法判据的边界见该方法 KDoc。这里仍用自己的 `resolveIn` 而不是复用 `scriptFile`：
     * 两条路径的入参形状不同（那边是项目内相对路径，这边首段就是 `projectId`），判据可共用、
     * 拼接不能共用。）
     */
    private fun resolveInProject(filesDir: Path, projectId: String, relPath: String): Path {
        // 目录行的 relPath 以 / 结尾（契约约定），读写只服务文件 —— 先削掉再校验，
        // 好让"目录不是文件"这句原文浮出来，而不是报一句路径非法。
        val rel = relPath.trimEnd('/')
        val base = ScriptPaths.projectsRoot(filesDir).toAbsolutePath().normalize()
        val target = DeployPath.resolveIn(base, rel)
        val owner = base.relativize(target).firstOrNull()?.toString()
        require(owner == projectId) {
            "relPath 与项目不一致：$relPath 不在项目 $projectId 下" +
                "（relPath 是 files/scripts/ 之下的相对路径，首段应为项目名）"
        }
        require(Files.isDirectory(ScriptPaths.projectRoot(filesDir, ProjectId(projectId)))) { "项目不存在: $projectId" }
        return target
    }

    /** 名字 → `files/scripts/<projectId>/<name>`：合法性裁决 + 落位唯一出口。 */
    private fun resolveName(filesDir: Path, projectId: String, name: String): Path {
        require(name.isNotBlank()) { "名字不能为空" }
        // 单段校验在前：isSafeRelPath 是部署路径的判据（多段相对路径对部署合法），
        // `lib/main.js` 会穿过去、到 Files.createFile 才炸出 NoSuchFileException。
        require('/' !in name) { "名字不能含 /（要进子文件夹先建文件夹）" }
        require(DeployPath.isSafeRelPath(name)) { "名字含非法字符（.. 不允许）" }
        val root = ScriptPaths.projectRoot(filesDir, ProjectId(projectId))
        require(Files.isDirectory(root)) { "项目不存在: $projectId" }
        return root.resolve(name)
    }
}
