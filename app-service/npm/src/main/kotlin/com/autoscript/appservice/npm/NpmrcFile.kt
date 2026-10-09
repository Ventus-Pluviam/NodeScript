package com.autoscript.appservice.npm

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * `.npmrc` 的键级读写（§10.2）。
 *
 * **为什么需要它**：仓里有**两处**要写 `.npmrc` —— 项目级的 `InstallCoordinator.config`
 * （桥面 `setRegistry` 的落点）与全局级的 [NpmGlobalConfig]。两处若各写一份
 * 「读全行 → removeIf → 写回」，漂的方向是具体的：一处用 `startsWith("$k=")`、
 * 另一处用 `substringBefore('=').trim()`，于是带空格的 `registry = x` 一处认一处不认。
 *
 * **整文件重写，不做行级 append**：`.npmrc` 是用户会手编的文件，npm 自己也会改它
 * （`npm config set`），行级追加会让同一个键出现两次 —— 而 npm 的读法是「最后一个赢」，
 * 于是「删掉它」这个动作在行级 append 下根本表达不出来。
 *
 * 只认 `key=value` 这一种形状（与 npm 的实际读法一致）：`;`/`#` 注释行、`key = value`
 * 带空格的写法都**原样保留在文件里不动**，本对象不碰它们、也不假装能读它们 ——
 * 读键只认行首即 `key=` 的那些行，与 `InstallCoordinator.readRegistryFromNpmrc` 同口径。
 */
internal object NpmrcFile {

    /** 读某个键的值（**最后一行生效**，与 npm 的「后写赢」一致）；文件不存在/没这个键 → null。 */
    fun readKey(file: Path, key: String): String? {
        if (!Files.isRegularFile(file)) return null
        return Files.readAllLines(file, StandardCharsets.UTF_8).asReversed()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("$key=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * 写 / 删某个键：删掉文件里该键的**全部**行，`value` 非 null 时在末尾追加一行。
     *
     * `value == null` 即「删键」（恢复出厂），此时文件可能被写成空的 ——
     * **不删文件本身**：空 `.npmrc` 与不存在的 `.npmrc` 对 npm 是同一件事，
     * 而留着文件让「用户手动往里加了别的键」这个事实不会因为一次删键而消失。
     */
    fun writeKey(file: Path, key: String, value: String?) {
        val lines = if (Files.exists(file)) {
            Files.readAllLines(file, StandardCharsets.UTF_8).toMutableList()
        } else {
            mutableListOf()
        }
        lines.removeIf { it.startsWith("$key=") }
        if (value != null) lines.add("$key=$value")
        file.parent?.let { Files.createDirectories(it) }
        Files.write(file, lines, StandardCharsets.UTF_8)
    }
}

/**
 * 全局 npm 配置（§10.2 registry 三层链的 `files/.npmrc` = **userconfig** 那一层）。
 *
 * 这一层是 2026-10-09 批 83 新落的：此前全仓只有 [NpmProjectLayout.npmrc] 一个路径函数，
 * userconfig 层**零实现** —— 于是「镜像源」无处可存，管理面板那一行只能是个 toast 占位。
 *
 * 生产里它同时扮演两个角色，**两个角色不能互相顶掉**：
 * - 传给 npm 的 `--userconfig`（`HostNodeExecutor`）：npm 自己会读它，`@scope:registry`、
 *   proxy、cache 等键都靠这条通路；
 * - 本类读写的 `registry=` 键：解析链的第二层（项目 `.npmrc` → 全局 → 出厂官方）。
 *
 * **粒度是全局一份**（用户 2026-10-09 裁定）：所有项目共用一个缺省镜像，
 * 项目 `.npmrc` 仍可按项目覆盖（那一层一直存在，只是批 83 之前没被 npm 读到）。
 */
class NpmGlobalConfig(private val filesDir: Path) {

    /** `files/.npmrc`（`NpmCliDeployer` 的 `files/npm/` 是 CLI 素材，两者不同物，别混）。 */
    fun file(): Path = filesDir.resolve(FILE_NAME)

    /** 用户设过的镜像源；null = 没设过（走 [com.autoscript.domain.npm.NpmRegistryKeys.OFFICIAL]）。 */
    fun readRegistry(): String? = NpmrcFile.readKey(file(), REGISTRY_KEY)

    /** 写镜像源；`null` = 删键（恢复出厂缺省）。 */
    fun writeRegistry(value: String?) = NpmrcFile.writeKey(file(), REGISTRY_KEY, value)

    companion object {
        /** userconfig 文件名（`files/` 下；与项目级同名但不同目录）。 */
        const val FILE_NAME: String = ".npmrc"

        /** npm 的 registry 键名（非作用域形式）。 */
        const val REGISTRY_KEY: String = "registry"
    }
}
