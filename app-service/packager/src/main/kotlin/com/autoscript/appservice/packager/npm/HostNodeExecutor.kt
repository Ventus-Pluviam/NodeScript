package com.autoscript.appservice.packager.npm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * 主机 Node 执行体（P0 验证切片/桌面兜底）：
 * 用宿主机 node 进程跑 npm-cli.js 完成真实 install/ci。
 *
 * 对应 docs §10.2 调用链末段「顶层脚本执行 node <filesDir>/npm/bin/npm-cli.js」的
 * **主机形态**；Android 形态（EnginePool slotTag='npm' 会话进程内 dlopen libnode 执行
 * 同一 npm-cli.js）复用同一接口（[InstallCoordinator.HeavyOpExecutor]），两形态只是
 * 执行宿主不同：工作目录约定一致——
 *
 * **work-prefix 模型**（npm 语义要求 prefix = 项目根，不能是 node_modules 本身）：
 * 1. 在 stageDir 旁开 `npmwork-<nonce>` 工作目录，写入项目 package.json + 现有 lockfile；
 * 2. `npm <args> --prefix <workDir>`（cwd=workDir），reify 目标 = `<workDir>/node_modules`；
 * 3. 成功后把 `<workDir>/node_modules/＊` 提升进 stageDir（stageDir 即未来 node_modules），
 *    把 `<workDir>/package-lock.json` 原子写回项目根（lockfile 随成功事务更新，失败不污染）；
 * 4. 工作目录用完即删。
 *
 * 零 spawn 主路径（§10.1/§10.3 T0）在这里落地为参数面：
 * `--ignore-scripts`（拒绝全部 lifecycle）+ `--no-audit --no-fund` +
 * `--cache <cacheDir>`。
 */
class HostNodeExecutor(
    private val npmCliJs: Path,
    private val cacheDir: Path,
    private val nodeBin: String = "node",
    private val env: Map<String, String> = emptyMap(),
    // 与 NpmRegistryVerifier 的首选同源（交叉校验要比的就是实际安装用的那一家）：
    // 出厂官方，§18 第 7 项 2026-09-26 拍板。
    private val registry: String = NpmRegistryVerifier.OFFICIAL,
) : InstallCoordinator.HeavyOpExecutor {

    init {
        require(Files.isRegularFile(npmCliJs)) { "npm-cli.js 不存在: $npmCliJs" }
    }

    override suspend fun execute(op: InstallCoordinator.HeavyOp, sink: InstallCoordinator.ProgressSink): String =
        withContext(Dispatchers.IO) {
            sink.emit(
                com.autoscript.domain.npm.InstallEvent.Progress(
                    op.projectId, op.nonce,
                    com.autoscript.domain.npm.InstallEvent.Phase.DOWNLOAD,
                ),
            )
            val workDir = op.stageDir.resolveSibling("npmwork-" + op.nonce)
            try {
                prepareWorkDir(op, workDir)
                // REIFY/DONE：npm 进程内 reify 是黑盒，主机形态只能粗粒度标注阶段
                sink.emit(
                    com.autoscript.domain.npm.InstallEvent.Progress(
                        op.projectId, op.nonce,
                        com.autoscript.domain.npm.InstallEvent.Phase.REIFY,
                    ),
                )
                runNpm(op, workDir)
                harvest(op, workDir)
                sink.emit(
                    com.autoscript.domain.npm.InstallEvent.Progress(
                        op.projectId, op.nonce,
                        com.autoscript.domain.npm.InstallEvent.Phase.DONE,
                    ),
                )
                "npm ${op.args.first()} 完成"
            } finally {
                workDir.toFile().deleteRecursively()
            }
        }

    /** 播种工作目录：项目 package.json（必须存在）+ 现有 lockfile（有则带上，保住已装依赖闭包）。 */
    private fun prepareWorkDir(op: InstallCoordinator.HeavyOp, workDir: Path) {
        val pkgJson = op.projectRoot.resolve("package.json")
        require(Files.isRegularFile(pkgJson)) {
            "项目 ${op.projectId} 缺 package.json（$pkgJson），无法执行 npm ${op.args.first()}"
        }
        workDir.toFile().deleteRecursively()
        Files.createDirectories(workDir)
        Files.copy(pkgJson, workDir.resolve("package.json"))
        val lock = op.projectRoot.resolve("package-lock.json")
        if (Files.isRegularFile(lock)) {
            Files.copy(lock, workDir.resolve("package-lock.json"))
        }
    }

    private fun runNpm(op: InstallCoordinator.HeavyOp, workDir: Path): String {
        val cmd = buildList {
            add(nodeBin)
            add(npmCliJs.toAbsolutePath().toString())
            addAll(op.args)
            add("--ignore-scripts"); add("--no-audit"); add("--no-fund")
            add("--cache"); add(cacheDir.toAbsolutePath().toString())
            add("--prefix"); add(workDir.toAbsolutePath().toString())
            add("--registry"); add(registry)
            add("--loglevel"); add("error")
        }
        val pb = ProcessBuilder(cmd)
        pb.directory(workDir.toFile())
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        val exited = proc.waitFor(op.timeoutMillis, TimeUnit.MILLISECONDS)
        if (!exited) {
            proc.destroyForcibly()
            throw RuntimeException("npm ${op.args.first()} 超时（${op.timeoutMillis}ms）")
        }
        if (proc.exitValue() != 0) {
            throw RuntimeException("npm 退出码 ${proc.exitValue()}: ${output.takeLast(500)}")
        }
        return output
    }

    /**
     * 收割：node_modules/＊ 提升进 stageDir；npm 重写后的 package.json + package-lock.json
     * 写回项目根——manifest 链持久化在项目根（npm install <pkg> 会把新依赖写进 package.json，
     * 不写回则下次安装因 package.json 缺旧依赖而把已装包 prune 掉）。
     */
    private fun harvest(op: InstallCoordinator.HeavyOp, workDir: Path) {
        val nm = workDir.resolve("node_modules")
        if (Files.isDirectory(nm)) {
            Files.createDirectories(op.stageDir)
            Files.list(nm).use { s ->
                s.forEach { entry ->
                    val target = op.stageDir.resolve(entry.fileName.toString())
                    if (Files.isDirectory(target)) target.toFile().deleteRecursively()
                    Files.move(entry, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        Files.createDirectories(op.projectRoot)
        val newLock = workDir.resolve("package-lock.json")
        if (Files.isRegularFile(newLock)) {
            Files.copy(
                newLock, op.projectRoot.resolve("package-lock.json"),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        val newPkgJson = workDir.resolve("package.json")
        if (Files.isRegularFile(newPkgJson)) {
            Files.copy(
                newPkgJson, op.projectRoot.resolve("package.json"),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
