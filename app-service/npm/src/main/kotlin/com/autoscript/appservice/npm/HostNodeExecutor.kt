package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
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
 * 同一 npm-cli.js）复用同一接口（[HeavyOpExecutor]），两形态只是
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
 *
 * **2026-10-09 补上第二层（§10.12 末行「零 spawn 不变量漂移」）**：`--ignore-scripts`
 * 只挡 lifecycle 脚本，挡不住**非脚本** spawn（npm 自己新增一条 `execFile` 路径、或某包
 * 直接 `require('child_process')`），那类漂移在本平台上的表现是**静默失败**。
 * [spawnGateFile] 非 null 时经 `NODE_OPTIONS=--require=<shim>` 把
 * [NpmSpawnGate] 注入会话进程，`child_process` 七个入口一律抛错；被拦时本类把
 * shim 的播报**提成领域错误码**（`ERR_NPM_SPAWN_BLOCKED` / `ERR_PERMISSION_DENIED` /
 * `ERR_NOT_IMPLEMENTED`）而不是折成一句「退出码 1」—— 病因要能被脚本与 UI 机器判定。
 */
class HostNodeExecutor(
    private val npmCliJs: Path,
    private val cacheDir: Path,
    private val nodeBin: String = "node",
    private val env: Map<String, String> = emptyMap(),
    // 与 NpmRegistryVerifier 的首选同源（交叉校验要比的就是实际安装用的那一家）：
    // 出厂官方，§18 第 7 项拍板。
    private val registry: String = NpmRegistryVerifier.OFFICIAL,
    /**
     * `child_process` 拦截 shim 的落盘路径（[NpmSpawnGate.gateFile]）；null = 不注入门禁。
     *
     * **装配层不许传 null**：它是 §10.11 P0 承诺面，缺了就不是「少一层保险」而是
     * 「零 spawn 这条不变量没人守」。null 只留给两处 —— 单测（验参数面本身）与
     * 桌面 P0 切片（[com.autoscript.appservice.npm.NpmSpawnGate.deploy] 在那边由调用方
     * 自行决定装不装）。
     */
    private val spawnGateFile: Path? = null,
) : HeavyOpExecutor {

    init {
        require(Files.isRegularFile(npmCliJs)) { "npm-cli.js 不存在: $npmCliJs" }
    }

    override suspend fun execute(op: HeavyOp, sink: ProgressSink): String =
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
    private fun prepareWorkDir(op: HeavyOp, workDir: Path) {
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

    private fun runNpm(op: HeavyOp, workDir: Path): String {
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
        // 门禁注入走 NODE_OPTIONS（**追加不覆盖**父环境里那份别人的设置，见 mergeNodeOptions）：
        // `--require` 对 `-e`、脚本、以及 npm 自己 fork 的 node 子进程都生效，覆盖面比
        // 单点 argv 注入宽 —— 而 argv 那条路在这里本来也走不通（npm 的 argv 是它自己的）。
        spawnGateFile?.let { gate ->
            val key = NpmSpawnGate.ENV_NODE_OPTIONS
            pb.environment()[key] = NpmSpawnGate.mergeNodeOptions(pb.environment()[key], gate)
        }
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        val exited = proc.waitFor(op.timeoutMillis, TimeUnit.MILLISECONDS)
        if (!exited) {
            proc.destroyForcibly()
            throw RuntimeException("npm ${op.args.first()} 超时（${op.timeoutMillis}ms）")
        }
        if (proc.exitValue() != 0) throw failureOf(op, output, proc.exitValue())
        return output
    }

    /**
     * 非零退出的病因（**不折成一句「退出码 1」**）：门禁播报优先于退出码 —— 被拦时 npm
     * 只会笼统报一句失败，真病因是 shim 那条播报。码按 shim 给的折成领域码
     * （[NpmSpawnGate.errorCodeOf]，三个已知码一一对应），原文进 detail，
     * 于是脚本与 UI 能按码判定「是守卫拦的」还是「是 npm 自己失败的」。
     */
    private fun failureOf(op: HeavyOp, output: String, exitCode: Int): RuntimeException {
        val blocked = NpmSpawnGate.blockedIn(output)
        return if (blocked != null) {
            AutojsException(
                NpmSpawnGate.errorCodeOf(blocked.code),
                "npm ${op.args.first()} 被 child_process 门禁拦截（${blocked.code}）：${blocked.detail}",
            )
        } else {
            RuntimeException("npm 退出码 $exitCode: ${output.takeLast(500)}")
        }
    }

    /**
     * 收割：node_modules/＊ 提升进 stageDir；npm 重写后的 package.json + package-lock.json
     * 写回项目根——manifest 链持久化在项目根（npm install <pkg> 会把新依赖写进 package.json，
     * 不写回则下次安装因 package.json 缺旧依赖而把已装包 prune 掉）。
     */
    private fun harvest(op: HeavyOp, workDir: Path) {
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
