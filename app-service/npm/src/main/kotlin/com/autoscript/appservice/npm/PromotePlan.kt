package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.json.DomainJson.Value
import com.autoscript.domain.npm.PackageSpec
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** 项目和全局两棵树的迁移判据。只在协调器持有安装会话时现取，不用 UI 快照作写操作依据。 */
internal class PromotePlan(private val project: PromoteTree, private val global: PromoteTree) {
    val packages: Map<String, PromotePackage> = project.directNames.sorted().mapNotNull { name ->
        val locked = project.locked[name]?.takeIf { project.registryDeclaration(name) && it.registryPackage }
            ?: return@mapNotNull null
        if (!global.canShare(name, locked)) return@mapNotNull null
        if (!locked.matches(project.installed[name], name)) {
            promotionError("项目 $name 的物化版本与 package-lock 不一致，未执行迁移")
        }
        name to locked
    }.toMap()

    /** 同版本传递包尚未声明为全局直依赖，也要登记；否则下次 prune 可能把它删掉。 */
    val additions: List<PackageSpec> = packages.mapNotNull { (name, pkg) ->
        if (global.registryDeclaration(name) && pkg.matches(global.installed[name], name)) null
        else PackageSpec(name, pkg.version)
    }

    fun verifyGlobal(tree: PromoteTree) {
        if (!tree.keeps(global, global.locked.keys + global.installed.keys)) {
            promotionError("全局已有包的版本或来源发生变化，未移除项目依赖")
        }
        if (tree.declarations.without(packages.keys) != global.declarations.without(packages.keys)) {
            promotionError("全局其他依赖的声明发生变化，未移除项目依赖")
        }
        val invalid = packages.filterNot { (name, pkg) ->
            tree.registryDeclaration(name) && pkg.sameRelease(tree.locked[name]) && pkg.matches(tree.installed[name], name)
        }.keys
        if (invalid.isNotEmpty()) promotionError("全局依赖未按项目锁定版本就位：${invalid.joinToString()}，未移除项目依赖")
    }

    fun verifySource(tree: PromoteTree) {
        if (tree.declarations != project.declarations || !tree.keeps(project, project.directNames)) {
            promotionError("项目依赖在迁移准备期间发生变化，未执行卸载")
        }
    }

    fun verifyProject(tree: PromoteTree) {
        // npm 仍可能需要某个包作为保留项的传递依赖。顶层还在就不能冒充去重，更不能强删。
        val retained = packages.keys.intersect(tree.locked.keys + tree.installed.keys + tree.declaredNames)
        if (retained.isNotEmpty()) promotionError("项目顶层仍需要这些依赖，无法迁移：${retained.joinToString()}")
        val keep = project.declaredNames - packages.keys
        if (!tree.keeps(project, keep) || tree.declarations != project.declarations.without(packages.keys)) {
            promotionError("卸载会改变项目中其他直接依赖，无法迁移")
        }
    }

    fun summary(): String = buildString {
        if (packages.isEmpty()) append("没有可迁移的直接依赖")
        else append("兼容依赖迁移完成：迁移 ${packages.size} 项")
        val retained = project.directNames - packages.keys
        if (retained.isNotEmpty()) append("；留在项目：").append(retained.sorted().joinToString())
    }
}

internal data class PromotePackage(
    val version: String?,
    val integrity: String?,
    val resolved: String?,
    val link: Boolean,
) {
    private val registryResolution: Boolean get() = resolved == null || resolved.startsWith("https://") || resolved.startsWith("http://")
    val registryPackage: Boolean get() = !link && version != null && EXACT_VERSION.matches(version) && registryResolution

    fun sameRelease(other: PromotePackage?): Boolean {
        if (other == null || !other.registryPackage) return false
        return version == other.version && sameIntegrity(other)
    }

    fun unchanged(other: PromotePackage?): Boolean {
        if (other == null) return false
        return version == other.version && link == other.link && sameIntegrity(other)
    }

    private fun sameIntegrity(other: PromotePackage): Boolean = integrity == null || other.integrity == integrity

    fun matches(actual: MaterializedPackage?, name: String): Boolean =
        actual != null && !actual.link && actual.name == name && actual.version == version
}

internal data class MaterializedPackage(val name: String?, val version: String?, val link: Boolean = false)

/** 对修改使用严格读口：坏锁不等于空锁，根层键也不能和同名嵌套包混到一起。 */
internal class PromoteTree private constructor(
    val declarations: Map<String, Map<String, Value>>,
    val locked: Map<String, PromotePackage>,
    val installed: Map<String, MaterializedPackage>,
) {
    val directNames: Set<String> get() = MIGRATABLE_SECTIONS.flatMap { declarations.getValue(it).keys }.toSet()
    val declaredNames: Set<String> get() = declarations.values.flatMap { it.keys }.toSet()

    fun registryDeclaration(name: String): Boolean {
        val sections = declarations.filterValues { name in it }
        return sections.isNotEmpty() && sections.all { (section, values) ->
            section in MIGRATABLE_SECTIONS && (values[name] as? Value.S)?.v?.let(::isRegistrySpec) == true
        }
    }

    fun canShare(name: String, source: PromotePackage): Boolean {
        if (name in declaredNames && !registryDeclaration(name)) return false
        val existing = locked[name] ?: return name !in installed && name !in declaredNames
        return source.sameRelease(existing) && (name !in installed || source.matches(installed[name], name))
    }

    fun keeps(before: PromoteTree, names: Set<String>): Boolean = names.all { name ->
        val old = before.locked[name]
        val was = before.installed[name]
        val lockKept = old == null || old.unchanged(locked[name])
        val treeKept = was == null || was == installed[name]
        lockKept && treeKept
    }

    companion object {
        fun read(root: Path, allowEmpty: Boolean = false, nodeModules: Path = root.resolve("node_modules")): PromoteTree {
            val manifest = readObject(root.resolve(ProjectManifest.FILE_NAME))
            if (manifest == null && !allowEmpty) {
                throw AutojsException(ErrorCode.ERR_FILE_NOT_FOUND, "项目没有 package.json，未执行迁移：$root")
            }
            val declarations = readDeclarations(manifest.orEmpty())
            val installed = readInstalled(nodeModules)
            val lock = readObject(root.resolve("package-lock.json"))
            if (lock == null && (declarations.values.any { it.isNotEmpty() } || installed.isNotEmpty())) {
                throw AutojsException(ErrorCode.ERR_FILE_NOT_FOUND, "已有依赖但缺少 package-lock.json，未执行迁移：$root")
            }
            return PromoteTree(declarations, lock?.let(::readTopLevelLock).orEmpty(), installed)
        }
    }
}

private val MIGRATABLE_SECTIONS = listOf("dependencies", "devDependencies")
private val DEPENDENCY_SECTIONS = MIGRATABLE_SECTIONS + listOf("optionalDependencies", "peerDependencies")
private val PACKAGE_NAME = Regex("""^(@[A-Za-z0-9][A-Za-z0-9._-]*/)?[A-Za-z0-9][A-Za-z0-9._-]*$""")
private val EXACT_VERSION = Regex("""^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$""")
private val REGISTRY_SPEC = Regex("""[A-Za-z0-9_.*~^<>=|+\t -]*""")

/** 只筛来源，不求解 semver；具体要装的版本由包锁定值给出。 */
private fun isRegistrySpec(spec: String): Boolean {
    val value = spec.trim().lowercase()
    return REGISTRY_SPEC.matches(value) && !value.startsWith('.') &&
        listOf(".tgz", ".tar.gz", ".tar").none { value.endsWith(it) }
}

private fun readDeclarations(manifest: Map<String, Value>): Map<String, Map<String, Value>> =
    DEPENDENCY_SECTIONS.associateWith { key ->
        manifest[key]?.let {
            (it as? Value.Obj)?.fields ?: promotionError("package.json 的 $key 不是对象")
        }.orEmpty()
    }

private fun Map<String, Map<String, Value>>.without(names: Set<String>): Map<String, Map<String, Value>> =
    mapValues { (_, declarations) -> declarations.filterKeys { it !in names } }

private fun readTopLevelLock(lock: Map<String, Value>): Map<String, PromotePackage> {
    val prefix = "node_modules/"
    val entries = (lock["packages"] as? Value.Obj)?.fields ?: promotionError("package-lock.json 缺少 packages 对象")
    return entries.mapNotNull { (location, raw) ->
        val name = location.removePrefix(prefix)
        if (name == location || !PACKAGE_NAME.matches(name)) return@mapNotNull null
        val fields = (raw as? Value.Obj)?.fields ?: promotionError("package-lock 条目不是对象：$location")
        name to PromotePackage(fields.text("version"), fields.text("integrity"), fields.text("resolved"), fields["link"] == Value.B(true))
    }.toMap()
}

private fun readInstalled(modules: Path): Map<String, MaterializedPackage> {
    if (Files.notExists(modules, NOFOLLOW_LINKS)) return emptyMap()
    if (!Files.isDirectory(modules, NOFOLLOW_LINKS)) promotionError("node_modules 不是普通目录：$modules")
    return childrenOf(modules).flatMap { entry ->
        val name = entry.fileName.toString()
        if (name.startsWith('@') && Files.isDirectory(entry, NOFOLLOW_LINKS)) {
            childrenOf(entry).map { pkg -> "$name/${pkg.fileName}" to pkg }
        } else listOf(name to entry)
    }.filter { (name, _) -> PACKAGE_NAME.matches(name) }.associate { (name, pkg) ->
        val linked = Files.isSymbolicLink(pkg) || Files.isSymbolicLink(pkg.resolve(ProjectManifest.FILE_NAME))
        val manifest = if (linked || !Files.isDirectory(pkg, NOFOLLOW_LINKS)) null else readObject(pkg.resolve(ProjectManifest.FILE_NAME))
        name to MaterializedPackage(manifest?.text("name"), manifest?.text("version"), linked)
    }
}

private fun childrenOf(path: Path): List<Path> = Files.newDirectoryStream(path).use { it.toList() }

private fun Map<String, Value>.text(key: String): String? = (get(key) as? Value.S)?.v

private fun readObject(path: Path): Map<String, Value>? {
    if (Files.notExists(path, NOFOLLOW_LINKS)) return null
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) promotionError("不是普通文件：$path")
    return try {
        DomainJson.decodeObject(String(Files.readAllBytes(path), StandardCharsets.UTF_8))
    } catch (e: IOException) {
        throw AutojsException(ErrorCode.ERR_IO, "无法读取 $path：${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw AutojsException(ErrorCode.ERR_IO, "无法解析 $path：${e.message}", e)
    }
}

private fun promotionError(detail: String): Nothing = throw AutojsException(ErrorCode.ERR_IO, detail)
