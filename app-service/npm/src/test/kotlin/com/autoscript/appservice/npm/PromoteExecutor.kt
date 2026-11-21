package com.autoscript.appservice.npm

import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.json.DomainJson.Value
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** 按生产执行体的顺序：独立工作树 → 修改 → 验证 → harvest；失败不改原树。 */
internal class PromoteExecutor(
    private val beforeCheck: suspend (HeavyOp, Path) -> Unit = { _, _ -> },
) : HeavyOpExecutor {
    val calls = mutableListOf<HeavyOp>()

    override suspend fun execute(op: HeavyOp, sink: ProgressSink, output: OutputSink): HeavyOpOutcome {
        calls += op
        val work = op.stageDir.resolveSibling("fixture-${op.nonce}")
        Files.createDirectories(work)
        for (file in listOf("package.json", "package-lock.json")) {
            val source = op.projectRoot.resolve(file)
            if (Files.isRegularFile(source)) Files.copy(source, work.resolve(file))
        }
        copyNpmWorkTree(op.projectRoot.resolve("node_modules"), work.resolve("node_modules"))
        val manifest = if (Files.exists(work.resolve("package.json"))) readFixtureJson(work.resolve("package.json"))
            else DomainJson.decodeObject(ProjectManifest.minimal(op.projectId)).toMutableMap()
        val lock: MutableMap<String, Value> = if (Files.exists(work.resolve("package-lock.json"))) readFixtureJson(work.resolve("package-lock.json"))
            else linkedMapOf("lockfileVersion" to Value.N("3"), "packages" to Value.Obj(mapOf("" to Value.Obj(emptyMap()))))
        val packages = ((lock["packages"] as Value.Obj).fields).toMutableMap()
        val dependencies = ((manifest["dependencies"] as? Value.Obj)?.fields.orEmpty()).toMutableMap()
        val args = op.args.drop(1).filterNot { it.startsWith("--") }
        if (op.args.first() == "install") {
            args.forEach { spec ->
                val name = spec.substringBeforeLast('@')
                val version = spec.substringAfterLast('@')
                writeFixturePackage(work, name, version)
                dependencies[name] = Value.S(version)
                packages["node_modules/$name"] = fixtureLockPackage(version)
            }
        } else {
            args.forEach { name ->
                dependencies.remove(name)
                packages.remove("node_modules/$name")
                work.resolve("node_modules").resolve(name).toFile().deleteRecursively()
            }
            for (section in listOf("devDependencies", "optionalDependencies", "peerDependencies")) {
                val fields = (manifest[section] as? Value.Obj)?.fields ?: continue
                manifest[section] = Value.Obj(fields.filterKeys { it !in args })
            }
        }
        manifest["dependencies"] = Value.Obj(dependencies)
        packages[""] = Value.Obj(mapOf("dependencies" to Value.Obj(dependencies)))
        lock["packages"] = Value.Obj(packages)
        writeFixtureJson(work.resolve("package.json"), manifest)
        writeFixtureJson(work.resolve("package-lock.json"), lock)
        beforeCheck(op, work)
        op.checkWorkTree?.invoke(work, work.resolve("node_modules"))
        op.stageDir.toFile().deleteRecursively()
        copyNpmWorkTree(work.resolve("node_modules"), op.stageDir)
        Files.createDirectories(op.stageDir)
        for (file in listOf("package.json", "package-lock.json")) {
            Files.copy(work.resolve(file), op.projectRoot.resolve(file), REPLACE_EXISTING)
        }
        work.toFile().deleteRecursively()
        return HeavyOpOutcome("fixture ${op.args.first()}")
    }
}

internal fun seedPromoteTree(root: Path, vararg packages: Pair<String, String>) {
    Files.createDirectories(root)
    val dependencies = packages.associate { (name, _) -> name to Value.S("^1.0.0") }
    writeFixtureJson(root.resolve("package.json"), mapOf("name" to Value.S("demo"), "dependencies" to Value.Obj(dependencies)))
    val locked = linkedMapOf<String, Value>("" to Value.Obj(mapOf("dependencies" to Value.Obj(dependencies))))
    packages.forEach { (name, version) ->
        writeFixturePackage(root, name, version)
        locked["node_modules/$name"] = fixtureLockPackage(version)
    }
    writeFixtureJson(root.resolve("package-lock.json"), mapOf("lockfileVersion" to Value.N("3"), "packages" to Value.Obj(locked)))
}

internal fun writeFixturePackage(root: Path, name: String, version: String) {
    val pkg = root.resolve("node_modules").resolve(name)
    Files.createDirectories(pkg)
    writeFixtureJson(pkg.resolve("package.json"), mapOf("name" to Value.S(name), "version" to Value.S(version)))
}

internal fun fixtureLockPackage(version: String): Value.Obj = Value.Obj(
    mapOf("version" to Value.S(version), "integrity" to Value.S("sha512-fixture-$version")),
)

internal fun readFixtureJson(path: Path): MutableMap<String, Value> = DomainJson.decodeObject(Files.readString(path)).toMutableMap()

internal fun writeFixtureJson(path: Path, fields: Map<String, Value>) {
    Files.writeString(path, DomainJson.encodeParsed(Value.Obj(fields)))
}

internal fun changeFixtureDependency(root: Path, name: String, spec: String) {
    val path = root.resolve("package.json")
    val manifest = readFixtureJson(path)
    val deps = ((manifest["dependencies"] as? Value.Obj)?.fields.orEmpty()).toMutableMap()
    deps[name] = Value.S(spec)
    manifest["dependencies"] = Value.Obj(deps)
    writeFixtureJson(path, manifest)
}
