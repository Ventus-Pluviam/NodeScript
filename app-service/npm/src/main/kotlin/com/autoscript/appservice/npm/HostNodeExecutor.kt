package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
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
 * 1. 在 stageDir（`node_modules.part-<nonce>`）旁开 `npmwork-<nonce>` 工作目录，
 *    写入项目 package.json（**缺则照 [ProjectManifest.minimal] 造一份**）+ 现有 lockfile
 *    +（有则）项目 `.npmrc`；
 * 2. **真实 `node_modules` 整体 rename 进 workDir**（同分区瞬时）——npm 永远在
 *    真实依赖树上 reify，`--prefix <workDir>`（cwd=workDir）只是把会话圈在工作目录里；
 * 3. `npm <args>` 增量 reify 真实树：卸载删真包（输出 `removed N packages`），
 *    安装只补 diff（不再每次整棵重建）；npm 重写后的锁键锚定真实树
 *    （`node_modules/<pkg>`，不再是带 `npmwork-<uuid>` 的墓碑路径）；
 * 4. 成功后把 `<workDir>/node_modules/＊` **提升进 stageDir**（stageDir 即未来
 *    node_modules，[harvest]），由 staging.commit 原子 rename 落位成项目根
 *    node_modules；`<workDir>/package-lock.json` 原子写回项目根
 *    （lockfile 随成功事务更新，失败不污染）；
 * 5. 失败/取消路径把 workDir 里的树**逆搬回**项目根（见 [rollbackTree] —— 真树唯一
 *    副本绝不跟着临时目录被删，即使它可能已被 npm 半改；journal fail + 下次
 *    `ci --offline` 自愈，§10.4 那条通道原样保留），工作目录随后删尽。
 *
 * **2026-10-29 修**：此前的 work-prefix 模型在**空屋**里全量重建 —— workDir 每次
 * 新建、`node_modules` 从不带进，产物靠 stageDir 头一次物化。这把卸载弄成了
 * 「对着一棵空树执行」：永远命中不了真实包（设备上项目根锁里 `extraneous` 孤儿 +
 * `npmwork-<uuid>` 路径键就是那次设计留下的证据）；且 npm 12 在空屋里写出的锁
 * 把 node_modules 锚定成 `../../…/npmwork-<uuid>/…` 墓碑路径，harvest 原样拷回
 * 项目根后整把锁指着一批已删除的临时目录。**修正 = 第 2 步把真实树带进门**：
 * npm 增量 reify 真树、锁键锚定 workDir 内真实树（`node_modules/<pkg>` 干净形态）；
 * `InstallStaging` 的 journal/sweep/commit 编排原样保留。
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
    /**
     * 显式钉死的注册表（argv 里的 `--registry`）。**缺省 null = 不注入**（2026-10-09 批 83）。
     *
     * 此前这里缺省 `NpmRegistryVerifier.OFFICIAL` 且**唯一的生产构造点从不传它**，
     * 于是每次安装都被强制钉在官方源上：用户经 `setRegistry` 写的项目 `.npmrc`
     * 对真实安装毫无影响（写了个寂寞），而交叉校验的首选却是从那份 `.npmrc` 读的
     * —— 校验的那家与实际装的那家可以不是同一家。
     *
     * 现在缺省不注入，让 npm 自己按 `--prefix`（= workDir，见 [prepareWorkDir] 会把
     * 项目 `.npmrc` 拷进去）→ `--userconfig`（[userConfig]）→ 出厂解析，
     * 与 [InstallCoordinator.resolveRegistry] 的两层链**同源**。
     * 非 null 只留给「必须钉死某一家」的场合（测试、诊断）。
     */
    private val registryOverride: String? = null,
    /**
     * 传给 npm 的 `--userconfig`（§10.2 的 `files/.npmrc` 那一层）。
     *
     * **不能省**：`--registry` 与 userconfig 不是二选一 —— 实测 `--registry` 只赢
     * `registry=` 这一个键，文件里的 `@scope:registry`、proxy、cache 等键仍靠这条通路。
     * null = 不给这个参数（npm 用自己的缺省 userconfig，即宿主 HOME 下那份）。
     */
    private val userConfig: Path? = null,
    /**
     * `child_process` 拦截 shim 的落盘路径（[NpmSpawnGate.gateFile]）；null = 不注入门禁。
     *
     * **装配层不许传 null**：它是 §10.11 P0 承诺面，缺了就不是「少一层保险」而是
     * 「零 spawn 这条不变量没人守」。null 只留给两处 —— 单测（验参数面本身）与
     * 桌面 P0 切片（[com.autoscript.appservice.npm.NpmSpawnGate.deploy] 在那边由调用方
     * 自行决定装不装）。
     */
    private val spawnGateFile: Path? = null,
    /**
     * noden 要 `dlopen` 的 `libnode.so` 绝对路径（设备 = `nativeLibraryDir/libnode.so`）。
     *
     * `main.cpp` **必填**，缺则 exit 2（§7.8）—— 2026-10-17 设备实测 bug：这条路
     * 从来没注过它，于是设备上点安装得到的是一句 `:nodeN 缺 AUTOSCRIPT_LIBNODE`，
     * 而宿主 JVM 测试用的是系统 `node`（不读这个变量），复现不出。
     *
     * null = 这次用的不是需要 libnode 的宿主（桌面系统 `node`）→ 不注入。
     * **生产装配不许传 null**（`:app` 的 `wireNpmExecutor` 在 `host != null` 时
     * 强制要求这一件，见那边的 fail-closed 判据）。
     */
    private val libnodePath: Path? = null,
) : HeavyOpExecutor {

    init {
        require(Files.isRegularFile(npmCliJs)) { "npm-cli.js 不存在: $npmCliJs" }
    }

    override suspend fun execute(
        op: HeavyOp,
        sink: ProgressSink,
        output: OutputSink,
    ): HeavyOpOutcome =
        withContext(Dispatchers.IO) {
            // 必填件预检摆在最前：报进度之前先确认这次真跑得起来 ——
            // 缺件是**装配/打包**问题，不该画成一次跑到一半的安装。
            NpmHostEnv.checkInstalled(libnodePath)
            sink.emit(
                com.autoscript.domain.npm.InstallEvent.Progress(
                    op.projectId, op.nonce,
                    com.autoscript.domain.npm.InstallEvent.Phase.DOWNLOAD,
                ),
            )
            val workDir = op.stageDir.resolveSibling("npmwork-" + op.nonce)
            try {
                val seededManifest = prepareWorkDir(op, workDir)
                // REIFY/DONE：npm 进程内 reify 是黑盒，主机形态只能粗粒度标注阶段
                sink.emit(
                    com.autoscript.domain.npm.InstallEvent.Progress(
                        op.projectId, op.nonce,
                        com.autoscript.domain.npm.InstallEvent.Phase.REIFY,
                    ),
                )
                val captured = runNpm(op, workDir, output)
                harvest(op, workDir)
                sink.emit(
                    com.autoscript.domain.npm.InstallEvent.Progress(
                        op.projectId, op.nonce,
                        com.autoscript.domain.npm.InstallEvent.Phase.DONE,
                    ),
                )
                HeavyOpOutcome(
                    // 自动补出来的清单要在**终态摘要**里说一句（走 `InstallEvent.Finished.detail`
                    // 与控制台 RESULT 行，与"完成/失败"同一处）：不静默 —— 用户的项目里
                    // 凭空多了个 package.json，他得知道是谁放的。**不走 [OutputSink]** 是刻意的：
                    // 那条缝一被碰，`runHeavy` 的 `streamed` 就为真，npm 的输出尾部会被当成
                    // "已经报过流"而不再补 —— 一句话换掉一段真输出，不值。
                    summary = buildString {
                        append("npm ").append(op.args.first()).append(" 完成")
                        if (seededManifest) append("（项目原本没有 package.json，已自动建一份最小骨架）")
                    },
                    // 尾部截断（见 [HeavyOpOutcome.outputTail] 的 KDoc）：控制台要的是
                    // 「npm 最后说了什么」，不是几万行安装日志。
                    outputTail = captured.trim().takeLast(OUTPUT_TAIL_CHARS).ifBlank { null },
                )
            } finally {
                // 失败/取消路径先把真实树移回项目根（若 harvest 还没落位）——见
                // [rollbackTree] 的 KDoc：真树唯一副本不能跟着临时目录一起被删。
                val treeSafe = rollbackTree(op, workDir)
                // 回滚成功（或树本就没搬来过）才删临时目录；回滚失败时整目录保留
                // （UUID 每次会话换新，不会撞上）——宁可留残骸，不可丢真树。
                if (treeSafe) workDir.toFile().deleteRecursively()
            }
        }

    /**
     * 播种工作目录：项目 `package.json`（**缺了就照 [ProjectManifest.minimal] 造一份**）
     * + 现有 lockfile（有则带上，保住已装依赖闭包）+ 项目 `.npmrc`（有则带上，见下方注释）。
     *
     * **缺清单不再拒绝**（2026-10-17 用户口径「应该让它缺的时候自动创建呀」）：此前这里
     * 一句 `require` 把"自己建的项目"整个挡在门外 —— 而"首页根层建文件夹 = 建项目"
     * （2026-10-16 用户裁定）造出来的正是一**空目录**，于是用户看到的是
     * `项目 demo 缺 package.json（…）`，既不是他的错，他也无从下手。
     * 造出来的那份只落在 workDir，**跑完由 [harvest] 写回项目根**（与 npm 自己重写
     * manifest 走同一条路，不新开一条落盘路径）；本次操作失败则项目根**一个字都不变**
     * —— 一个失败的操作不该在用户的项目里留下新文件。
     *
     * `public` 与 [npmArgv] 同一条理由：Kotlin 的 `internal` 跨模块不可见，而
     * 「项目 `.npmrc` 到底有没有进 workDir」这条不变量最该被 `:app` 的装配测试钉住
     * （它一旦失守，`setRegistry` 就重新变成空转，而症状只在真机上表现为「装的还是官方源」）。
     *
     * **2026-10-29 起开门先搬真树**：真实 `node_modules` 整体 ATOMIC_MOVE 进
     * workDir/nod_modules（同分区瞬时）——npm 在真实依赖树上增量 reify，卸载才
     * 命中真包、写回的锁键才锚定这颗树（类头 work-prefix 模型第 2 步）。会话期间
     * 项目根没有 node_modules；任何失败由 execute 的 finally 走 [rollbackTree] 移回。
     *
     * @return 本次是不是**新建**了那份清单（项目根此前没有）—— 调用方据此在终态摘要里
     *   如实说一句：用户的项目里会凭空多出一个文件，不能让他自己猜是哪儿来的。
     */
    fun prepareWorkDir(op: HeavyOp, workDir: Path): Boolean {
        val pkgJson = op.projectRoot.resolve(ProjectManifest.FILE_NAME)
        workDir.toFile().deleteRecursively()
        Files.createDirectories(workDir)
        // 真实树**搬**进 workDir（同分区瞬时 rename，2026-10-29 修）——npm 必须在
        // 真实依赖树上 reify，否则 `uninstall` 对着一棵"空树"执行（项目根锁里那些
        // extraneous 孤儿就是早期 `--no-save` 重装丢下的，卸载永远删不到真东西），
        // 且每把写回的锁都带着 `npmwork-<uuid>` 的临时目录路径、指着一批已删除的墓碑。
        // 搬走之后项目根在会话期间没有 node_modules，崩溃时由 execute 的 finally
        // 走 [rollbackTree] 移回 —— 真树唯一的副本绝不留在会被 deleteRecursively 的目录里。
        val realTree = op.projectRoot.resolve("node_modules")
        if (Files.isDirectory(realTree)) {
            Files.move(
                realTree, workDir.resolve("node_modules"),
                StandardCopyOption.ATOMIC_MOVE,
            )
        }
        val seeded = !Files.isRegularFile(pkgJson)
        if (seeded) {
            Files.write(
                workDir.resolve(ProjectManifest.FILE_NAME),
                ProjectManifest.minimal(op.projectId).toByteArray(StandardCharsets.UTF_8),
            )
        } else {
            Files.copy(pkgJson, workDir.resolve(ProjectManifest.FILE_NAME))
        }
        val lock = op.projectRoot.resolve("package-lock.json")
        if (Files.isRegularFile(lock)) {
            Files.copy(lock, workDir.resolve("package-lock.json"))
        }
        // 项目 `.npmrc` 必须跟着走（2026-10-09 批 83）：`--prefix` 一旦给出，npm 的
        // 项目级配置就**只看 prefix/.npmrc**（cwd 不再参与，本机 npm 12.2.0 实测），
        // 而 workDir 就是 prefix。不拷的话项目 `.npmrc` 是死配置 ——
        // `setRegistry` 写得再对，npm 也一个字都读不到。
        val npmrc = op.projectRoot.resolve(NpmGlobalConfig.FILE_NAME)
        if (Files.isRegularFile(npmrc)) {
            Files.copy(npmrc, workDir.resolve(NpmGlobalConfig.FILE_NAME))
        }
        return seeded
    }

    /**
     * npm 会话进程的完整 argv（**纯函数**，2026-10-09 批 83 从 [runNpm] 提出来）。
     *
     * 为什么提出来：本类一构造就 `require(Files.isRegularFile(npmCliJs))`，
     * 于是「argv 里到底有没有 `--registry`」这条不变量此前**只能靠真起 node 才验得到**
     * —— 而它正是「用户设的镜像源不生效」那个 bug 的所在地。提成纯函数后，
     * 零 node、零文件系统即可断言（`:app` 的装配测试也走它）。
     *
     * `public` 而非 `internal`：Kotlin 的 `internal` **跨模块不可见**，而最重要的
     * 消费方正是 `:app` 的装配测试。纯函数扩大可见性不带任何状态风险。
     */
    fun npmArgv(op: HeavyOp, workDir: Path): List<String> = buildList {
        add(nodeBin)
        add(npmCliJs.toAbsolutePath().toString())
        addAll(op.args)
        add("--ignore-scripts"); add("--no-audit"); add("--no-fund")
        add("--cache"); add(cacheDir.toAbsolutePath().toString())
        add("--prefix"); add(workDir.toAbsolutePath().toString())
        // 只有显式给了才注入：缺省让 npm 按 prefix/.npmrc → userconfig → 出厂自己解析
        // （见 [registryOverride] 的 KDoc —— 无条件注入正是那个「写了不生效」的病因）。
        registryOverride?.let { add("--registry"); add(it) }
        userConfig?.let { add("--userconfig"); add(it.toAbsolutePath().toString()) }
        add("--loglevel"); add("error")
    }

    /**
     * 跑 npm 并**边读边报**（2026-10-10 批 90：真流式）。
     *
     * 为什么读流必须是**另一条线程**（而不是原来的「先 `readBytes()` 再 `waitFor`」）：
     * 那样写要等流到 EOF 才去看退出码，两者被串成一条线；而 stdout 管道写满会**反压**，
     * 进程卡在写、我们卡在读 —— 谁也没错，谁也没动。原来那条路之所以没炸，只是因为
     * 它把整条流读完才等到退出，顺序上避开了这个窗口；一旦要在读的过程里做别的事
     * （这里就是报行给控制台），就必须拆成两条线程。
     *
     * 排空**不能省**（哪怕没人看）：管道没人读，npm 会卡在写。
     *
     * [OutputSink.line] 是**非挂起**且被 `runCatching` 包住的 —— 显示面出问题绝不能
     * 把读流线程打死（打死了管道就没人排空，一次界面故障会升级成一次安装超时）。
     *
     * 返回值仍是**全量输出**（与批 84 之前逐字相同）：`failureOf` 要按门禁播报定位病因、
     * `outputTail` 要取尾部 8000 字符。流是过程面，这个返回值是结果面，两条并存。
     */
    private fun runNpm(op: HeavyOp, workDir: Path, output: OutputSink): String {
        val pb = ProcessBuilder(npmArgv(op, workDir))
        pb.directory(workDir.toFile())
        pb.environment().putAll(env)
        // 宿主键在用户/基础键**之后**写（§8.1 顺序即契约）：`libnode.so` 的路径由宿主指，
        // 不由调用方给的那张表覆盖。缺了它 main.cpp 直接 exit 2，见 [NpmHostEnv]。
        NpmHostEnv.apply(pb.environment(), libnodePath)
        // 门禁注入走 NODE_OPTIONS（**追加不覆盖**父环境里那份别人的设置，见 mergeNodeOptions）：
        // `--require` 对 `-e`、脚本、以及 npm 自己 fork 的 node 子进程都生效，覆盖面比
        // 单点 argv 注入宽 —— 而 argv 那条路在这里本来也走不通（npm 的 argv 是它自己的）。
        spawnGateFile?.let { gate ->
            val key = NpmSpawnGate.ENV_NODE_OPTIONS
            pb.environment()[key] = NpmSpawnGate.mergeNodeOptions(pb.environment()[key], gate)
        }
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val collected = StringBuilder()
        val reader = Thread(
            {
                try {
                    proc.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            synchronized(collected) { collected.append(line).append('\n') }
                            // 空行**不报**：控制台是「一行一条、各自带时刻」的账，
                            // 一条空行在那里不表示任何东西，只是白占一格环容量
                            // （环是全局 512 格，见 InstallCoordinator.RING_CAPACITY）。
                            // 原样文本不受影响：上面那行已经把空行收进 `collected`，
                            // `outputTail` 仍是 npm 说的原话。
                            if (line.isNotBlank()) runCatching { output.line(line) }
                        }
                    }
                } catch (_: IOException) {
                    // 进程被超时强杀时这条流会以 IOException 收尾 —— 那是**预期**的结束方式，
                    // 不是病因。真病因（超时 / 退出码）由下面那条路给出，不在这里编一句。
                }
            },
            "npm-output-reader",
        )
        reader.isDaemon = true
        reader.start()
        val exited = proc.waitFor(op.timeoutMillis, TimeUnit.MILLISECONDS)
        if (!exited) {
            proc.destroyForcibly()
            // 给读流线程一点时间收尾（进程已死，EOF 马上到）：拿不到完整的最后几行也认，
            // 因为这条路本来就是异常收尾，输出只用于报错。
            reader.join(READER_JOIN_MILLIS)
            throw RuntimeException("npm ${op.args.first()} 超时（${op.timeoutMillis}ms）")
        }
        reader.join(READER_JOIN_MILLIS)
        val text = synchronized(collected) { collected.toString() }
        if (proc.exitValue() != 0) throw failureOf(op, text, proc.exitValue())
        return text
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
        // 产物提升进 stageDir（§10.4 落位语义不变，2026-10-29 修后 stageDir 里装的是
        // 「真实树增量后的**新树全集**」）：prepareWorkDir 已把真实树搬进 workDir，
        // npm 在真树上增量 reify 完，stageDir 由 staging.commit 原子 rename 成
        // projectRoot/node_modules。npm 写的锁键此时锚定 workDir 内的真树
        // （`node_modules/<pkg>` 干净形态），harvest 原样拷回项目根后语义依然正确
        // —— 不再带 `npmwork-<uuid>` 墓碑路径。
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

    /**
     * 失败/取消路径的真树回滚（2026-10-29 修，见 [harvest] 与类头 work-prefix 模型）：
     *
     * prepareWorkDir 把真实 `node_modules` 搬进了 workDir —— 会话失败时它是**唯一副本**，
     * 绝不能跟着 `npmwork-<nonce>` 一起被 deleteRecursively 销毁。本函数只在
     * `workDir/node_modules` 仍在（harvest 还没把它搬回）且项目根尚无树时把它移回。
     *
     * **如实记账的半改问题**：npm 在真实树上增量 reify，中断时树可能已被部分修改
     * （npm 生态的标准行为 —— 本地 `npm install` 失败同样留下半改树，重跑可续）。
     * 移回的是"半改树"而不是"旧树"；§10.4 的 journal fail + 下次会话 `ci --offline`
     * 一键重建通道原样保留，兜住半改态。牺牲"失败零污染"换「卸载/增量真正生效」，
     * 这笔账写在类头 —— 两个目标在真实树语义下不可兼得。
     */
    private fun rollbackTree(op: HeavyOp, workDir: Path): Boolean {
        val nm = workDir.resolve("node_modules")
        val realTree = op.projectRoot.resolve("node_modules")
        if (!Files.isDirectory(nm) || Files.isDirectory(realTree)) {
            // 树要么已被 harvest 搬回（无需回滚）、要么本就没搬来过
            return true
        }
        return try {
            Files.move(nm, realTree, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: IOException) {
            // 回滚失败（跨挂载/IO 错误）：调用方收到 false 后**保留整个 workDir** ——
            // 真树唯一副本不能进 deleteRecursively。残骸由 UUID 天然隔离，
            // 下次会话不会碰到；数据在盘上，用户/诊断还有救。
            false
        }
    }
}

/** 控制台回显用的命令输出尾部长度上限（[HeavyOpOutcome.outputTail]）。 */
private const val OUTPUT_TAIL_CHARS = 8_000

/**
 * 等读流线程收尾的上限（毫秒）。
 *
 * 进程已经退出（或已被强杀），管道那一端必然关闭，读线程只差把缓冲区里剩的几行吐完
 * —— 正常情况下是微秒级。给 2 秒是**防呆**：真卡住时宁可丢掉最后几行输出，
 * 也不能让一次已经超时的安装再挂在这里。
 */
private const val READER_JOIN_MILLIS = 2_000L
