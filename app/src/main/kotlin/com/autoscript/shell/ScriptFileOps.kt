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
import java.util.Comparator

/**
 * 项目页操作面的落盘（新建文件/新建文件夹；TG FAB 展开两项的对应位）。
 *
 * **为什么单独一个对象**（与 [ScriptFilesRead] 同一条理由）：`Application` 在 JVM
 * 单测里构造不出来，而"名字合不合法、路径怎么落、撞了已存在的怎么办"这段判断必须可测。
 *
 * **根层可写（2026-10-16 用户裁定「根层可写」，三条判据打通）**：`projectId` 空串 =
 * `files/scripts/` 本层（[com.autoscript.domain.scripts.ProjectId.ROOT]）。首页 FAB 建的**文件**
 * 落 `files/scripts/<名字>`、建的**文件夹**就是一个新项目（`files/scripts/<名字>/`）——
 * 后者多两条判据，见 [requireName] 的 `isProject` 档。六个口（[createFile]/[createFolder]/[read]/[save]/
 * [delete]/[absolutePath]）共用 [resolveInProject]，根层分支只改"归属怎么判"，不改"防不防越界"。
 * **删除与绝对路径（2026-10-17 用户口径）**：删文件夹是**递归整棵**（非空文件夹删不掉等于没做）；
 * 绝对路径就是 [resolveInProject] 的字符串形态（判据复用，不另写一套容错拼法）。
 *
 * 两条口径（原文口径不变，根层只是给它们各加一档）：
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
     * @param dirRelPath 目标文件夹（`files/scripts/` 之下相对路径，首段 = [projectId]，
     *   尾随 `/` 可有可无）；`null`/空 = 项目根。详见 [resolveName]。
     * @throws IllegalArgumentException 名字非法（含 `/`、`..` 等）/ 项目或文件夹不存在。
     * @throws java.nio.file.FileAlreadyExistsException 同名文件/文件夹已存在。
     */
    fun createFile(filesDir: Path, projectId: String, name: String, dirRelPath: String? = null) {
        val target = resolveName(filesDir, projectId, name, dirRelPath, isFolder = false)
        Files.createFile(target)
    }

    /**
     * 新建文件夹。
     *
     * @throws IllegalArgumentException 名字非法 / 项目或文件夹不存在。
     * @throws java.nio.file.FileAlreadyExistsException 同名文件/文件夹已存在。
     */
    fun createFolder(filesDir: Path, projectId: String, name: String, dirRelPath: String? = null) {
        val target = resolveName(filesDir, projectId, name, dirRelPath, isFolder = true)
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
     * 条目的绝对路径（文件列表菜单「复制绝对路径」）。
     *
     * 就是 [resolveInProject] 的字符串形态 —— **判据一字不差地复用**（防越界 + 首段与
     * [projectId] 一致 + 根层单段），不在这里重写一套"看着像就去掉前缀"的容错拼法：
     * 拼错的方向是"复制出去的路径指向另一个文件"，而用户拿这条路径去别处用（终端、
     * 编辑器、报错贴图）时**不会有人再校验一遍**。
     *
     * **不查存在性**（与 `ScriptPaths.scriptFile` 同一条分工）：约定只回答"应该在哪"。
     * 刚被删掉的行照样复制得出它曾有的路径。
     */
    fun absolutePath(filesDir: Path, projectId: String, relPath: String): String =
        resolveInProject(filesDir, projectId, relPath).toString()

    /**
     * 删一个条目（文件 / 文件夹；**文件夹递归整棵**）—— 文件列表菜单「删除」与批量删除。
     *
     * 三道闸，都在落盘前：
     * 1. [resolveInProject]：防 `..`/绝对路径/越界 + 首段与 [projectId] 一致（根层要求单段）；
     * 2. **目标必须存在**（原文「条目不存在」）—— 静默成功会把"删错了对象"与"删掉了"画成
     *    同一件事，而这两件事对用户完全不同；
     * 3. **不许是 `files/scripts/` 本身**：根层单段判据理论上已经挡掉（relPath 非空且单段
     *    ⇒ 至少深一层），但这条闸挡的是**删掉整个项目库**，值得自己有句话。
     *
     * **根层的文件夹就是项目本身**（`files/scripts/<名字>/`）：删它就是删掉一整个项目 ——
     * 用户 2026-10-17 裁定「允许」（见 `HostSummary.deleteEntry` 的 KDoc），故本函数不为它
     * 设闸，只保证"删干净、不留半个目录"。
     *
     * 删除用 `walk` 逆序（先文件后目录）+ `deleteIfExists`：`Files.delete` 对非空目录抛
     * `DirectoryNotEmptyException`，而"删文件夹"在用户眼里就是一个动作。
     */
    fun delete(filesDir: Path, projectId: String, relPath: String) {
        val target = resolveInProject(filesDir, projectId, relPath)
        val root = ScriptPaths.projectsRoot(filesDir).toAbsolutePath().normalize()
        require(target != root) { "根层不是条目：不能删 files/scripts 本身（那是整个项目库）" }
        require(Files.exists(target)) { "条目不存在：$relPath" }
        if (!Files.isDirectory(target)) {
            Files.delete(target)
            return
        }
        Files.walk(target).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
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
     * **根层分支（2026-10-16）**：`projectId` 空串时"首段与项目名一致"这条没有意义
     * （根层条目没有项目段），换成**"必须正好一层"** —— `base.relativize(target).nameCount == 1`。
     * 防越界那一道（[DeployPath.resolveIn]）一字不动：根层照样不许 `..`/绝对路径/穿层。
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
        if (projectId.isEmpty()) {
            require(base.relativize(target).nameCount == 1) {
                "relPath 与根层不一致：$relPath 不是 files/scripts/ 本层的条目" +
                    "（根层只有一层、没有项目名；子层请带项目名）"
            }
            require(Files.isDirectory(base)) { "根目录不存在：files/scripts（还没部署过任何项目）" }
            return target
        }
        val owner = base.relativize(target).firstOrNull()?.toString()
        require(owner == projectId) {
            "relPath 与项目不一致：$relPath 不在项目 $projectId 下" +
                "（relPath 是 files/scripts/ 之下的相对路径，首段应为项目名）"
        }
        require(Files.isDirectory(ScriptPaths.projectRoot(filesDir, ProjectId(projectId)))) { "项目不存在: $projectId" }
        return target
    }

    /**
     * 名字 → `files/scripts/<projectId>/<dirRelPath>/<name>`：合法性裁决 + 落位唯一出口。
     *
     * [dirRelPath] 非空时**复用 [resolveInProject] 那套越界/归属判据**（同一条路径不该有两套
     * 安全判断），只多一条"这一层得真在"：`Files.createFile` 对不存在的父目录抛的是
     * `NoSuchFileException`（一句机器话），这里换成点破"文件夹不存在"的原文 —— 呈现层原样显示。
     * **不顺路建中间层**（`createDirectories` 会把"名字打错一层"静默变成一个层级结构，
     * 用户以为建了个文件、其实建了一串目录）。
     *
     * [isFolder] 只影响**根层**那一档：根层建的文件夹落盘后就是 `files/scripts/<名字>/`，
     * 也就是一个项目，故多两条判据（[requireName] 的 `isProject` 档）。文件名/项目内的文件夹名不受它影响
     * —— 这里的"文件夹"与那里的"项目"是同一个目录的两个身份，判据只在根层这一档分开。
     */
    private fun resolveName(
        filesDir: Path,
        projectId: String,
        name: String,
        dirRelPath: String?,
        isFolder: Boolean,
    ): Path {
        // 根层 + 建文件夹 = 建项目（`files/scripts/<名字>/` 落盘后就是一个项目目录）——
        // 名字因此要多过两条判据（见 [requireName] 的 [isProject]）。
        requireName(name, isProject = isFolder && projectId.isEmpty() && dirRelPath.isNullOrBlank())
        val dir = targetDir(filesDir, projectId, dirRelPath)
        return dir.resolve(name)
    }

    /**
     * 目标文件夹（**必须已存在**）：根层 = `files/scripts/` 本层，项目内 = 项目根或子层。
     *
     * 两条"不存在"的原文分开写：根层缺的是 `files/scripts`（还没部署过任何项目），
     * 项目内缺的是那个项目/子目录 —— 合成一句会让用户按错误的方向去查。
     */
    private fun targetDir(filesDir: Path, projectId: String, dirRelPath: String?): Path {
        if (dirRelPath.isNullOrBlank()) {
            val root = if (projectId.isEmpty()) {
                ScriptPaths.projectsRoot(filesDir)
            } else {
                ScriptPaths.projectRoot(filesDir, ProjectId(projectId))
            }
            require(Files.isDirectory(root)) {
                if (projectId.isEmpty()) "根目录不存在：files/scripts（还没部署过任何项目）" else "项目不存在: $projectId"
            }
            return root
        }
        val dir = resolveInProject(filesDir, projectId, dirRelPath)
        require(Files.isDirectory(dir)) { "文件夹不存在：$dirRelPath" }
        return dir
    }

    /**
     * 名字判据（**不分层**，落哪一层由 `dirRelPath` 说）。
     *
     * 后两条是 2026-10-16 顺手堵的"建了但看不见"：
     * - **点开头**：`ScriptFilesRead` 把点开头条目当系统噪声排除，建出来的东西**不进列表**
     *   （项目根下的 `.npmrc` 那种内务文件正是靠这条隐身），用户只会读到"建了但没了"；
     * - **超长**：Linux/Android 文件名上限 255 **字节**（汉字 UTF-8 占 3 字节，约 85 个字），
     *   超了 `Files.createFile` 抛的是一句 `FileSystemException: File name too long`。
     *
     * **不 trim**：名字里前后空格照旧原样落盘（改动前后行为一致，不在这里悄悄替用户改名）。
     *
     * [isProject] 为真时 = 这个名字落盘后就是 `files/scripts/<名字>/`，**也就是一个项目**，
     * 故多两条判据。为什么非有不可（而不是"建了就建了，打不开再说"）：项目号判据比文件名判据
     * **严**一档 —— 文件名可以含 `.`（`a.b.js`），项目名不可以（批 84：`.` 让
     * `projectsRoot.resolve("..")` 跳出项目根）。放行的话，用户建出一个 `v1.0/`，进得去却
     * **里面每个文件都打不开**（`ProjectId("v1.0")` 当场抛），而错要等到那一刻才现形。
     * 第二条同理但更俗：叫 `node_modules` 的目录在文件列表里**整棵不显示**
     * （[ScriptPaths.NODE_MODULES_DIR]），建了就"消失"。
     *
     * **两条判据合成一个函数**是刻意的：它们判的是**同一个东西**（这个名字），只是"落盘后是不是
     * 项目目录"多一档 —— 拆两个函数会让调用方以为那是两件事，而 `ScriptFileOps` 这个对象
     * 已经贴到 detekt `TooManyFunctions` 的上限（11），多一个函数就多一条违规。
     */
    private fun requireName(name: String, isProject: Boolean) {
        require(name.isNotBlank()) { "名字不能为空" }
        // 单段校验在前：isSafeRelPath 是部署路径的判据（多段相对路径对部署合法），
        // `lib/main.js` 会穿过去、到 Files.createFile 才炸出 NoSuchFileException。
        require('/' !in name) { "名字不能含 /（要进子文件夹先在那一层新建）" }
        require(DeployPath.isSafeRelPath(name)) { "名字含非法字符（.. 不允许）" }
        require(!name.startsWith(".")) { "名字不能以点开头：$name（点开头是内务文件，文件列表里不显示）" }
        require(name.toByteArray(StandardCharsets.UTF_8).size <= MAX_NAME_BYTES) {
            "名字太长（最多 $MAX_NAME_BYTES 字节，一个汉字算 3 字节）：$name"
        }
        if (!isProject) return
        require(ScriptPaths.isValidProjectId(name)) {
            "这个名字不能当项目名：$name —— 根层的文件夹就是一个项目，名字不能含 `.`（也不能含 / 与 \\）"
        }
        require(name != ScriptPaths.NODE_MODULES_DIR) {
            "这个名字不能用：$name 是依赖缓存目录名，文件列表里不显示（换一个名字）"
        }
    }

    /**
     * 名字的字节上限（255 = Linux/Android 单段文件名上限，单位**字节**不是字符）。
     *
     * 存在这里而不是让 `Files` 抛：`FileSystemException: File name too long` 是一句机器话，
     * 而这句中文原文能直接告诉用户"为什么中文名字能写得更短"。
     */
    private const val MAX_NAME_BYTES: Int = 255
}
