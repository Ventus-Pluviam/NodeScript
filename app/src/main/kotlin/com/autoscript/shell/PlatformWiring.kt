package com.autoscript.shell

import android.content.Context
import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.scripts.IntentStore
import com.autoscript.platform.system.persist.IntentStoreWiring
import com.autoscript.domain.automation.InputChannel
import com.autoscript.domain.automation.MediaProjectionSessions
import com.autoscript.domain.automation.ScreenConsentBroker
import com.autoscript.domain.automation.ScreenRecordingSessions
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.system.DialogHost
import com.autoscript.platform.capabilities.device.AndroidMediaProjectionSessions
import com.autoscript.platform.capabilities.dialogs.AndroidDialogHost
import com.autoscript.platform.capabilities.screen.AndroidFrameProducer
import com.autoscript.platform.capabilities.screen.AndroidGestureInput
import com.autoscript.platform.capabilities.screen.MediaProjectionRecorder
import com.autoscript.platform.capabilities.screen.MediaProjectionSource
import com.autoscript.platform.capabilities.a11y.AndroidUiTree
import com.autoscript.platform.capabilities.CapabilityNamespaces
import com.autoscript.platform.capabilities.screen.ScreenshotSource
import com.autoscript.platform.capabilities.device.ShizukuInput
import com.autoscript.platform.capabilities.device.SystemDialogOps
import com.autoscript.platform.system.power.AndroidWakeLockOps
import com.autoscript.platform.system.images.JniOps
import com.autoscript.platform.system.images.NativeImageAnalyzer
import com.autoscript.platform.system.SystemNamespaces
import com.autoscript.platform.system.power.PowerManagerNamespaceHandler
import com.autoscript.platform.system.SystemSpis
import com.autoscript.platform.system.shell.ShellExecutor
import com.autoscript.domain.npm.ShellConsoleMode
import com.autoscript.domain.host.ShellConsoleResult
import com.autoscript.appservice.npm.ShellOpExecutor
import com.autoscript.platform.system.shell.ShellMode
import com.autoscript.platform.system.shell.ShellInputProvider
import com.autoscript.platform.system.shell.ShellResult
import com.autoscript.platform.system.power.WakeLockLedger
import com.autoscript.platform.capabilities.a11y.A11yEventRing
import com.autoscript.platform.capabilities.a11y.InMemoryUiTree
import com.autoscript.platform.capabilities.a11y.SystemA11yBridge
import com.autoscript.platform.editor.EditorHighlighters
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 生产能力装配：`SystemSpis` + `CapabilityNamespaces` → `AppShellKit.assemble` 的注入束
 * （docs §12.2「分两层」的接线落点 + §6 **包级例外二**）。
 *
 * 为什么住 `com.autoscript.shell`：§6 只放行这一个包依赖 `:platform:*`（与 `:bridge:java`
 * 例外同形）——`AppShellApplication`（根包）只调本类、不 import 任何 `com.autoscript.platform..`，
 * `ArchitectureTest` 的「平台实现只许装配包碰」把这条钉死。
 *
 * 分两步是刻意的：
 * - [of] 是**唯一碰 Android 的一步**（`Context` → `SystemSpis.of` 九件 SPI）；
 * - [inject] 是纯转接（SPI 束 → handler 束，零 Android 触点）→ JVM 可单测，
 *   真假实现共用同一条拼装路径，不给"测试走另一套装配"留门。
 *
 * **a11y/screen 生产已接**（同一座无障碍服务做底）：
 * - `a11y` = `AndroidUiTree`（树+动作一体，句柄注册表共享）+ `AndroidGestureInput`；
 * - `screen` = `ScreenshotSource(AndroidFrameProducer(), analyzer = images)`（§9.2 a11y
 *   截图路径：333ms 节流 + §8.8 策略预检/回调分类；MediaProjection 高清会话是后续升级，
 *   换 producer 即插）。**analyzer 一并喂进去**（§18-8(b) 发号侧归一）：截屏帧经
 *   `ImageAnalyzer.ingest` 进 `images` 那张帧表，两 namespace 句柄同号段互认；
 *   analyzer 为 null（so 缺位）时 ScreenshotSource 退回本地帧表 —— 此时 `images`
 *   根本没注册，两个号段不可能相撞；
 * 二者都走 `SystemA11yBridge` —— 装配期即可注入（连接态在调用期判定），服务未连 =
 * 桥如实 `ERR_SERVICE_DISABLED`（不伪造可用，也不必等 `onServiceConnected` 才装壳）。
 *
 * **`images` 生产已接**：[of] 构造 `NativeImageAnalyzer.of(JniOps.loadOrNull())`
 * —— `libopencv.so`（`:bridge:image`，OpenCV 4.14 静态链接）缺位即整条不接，
 * 与 dialogs 同一条"缺件不伪造"纪律。
 *
 * **`dialogs` 生产已接**：[of] 用同一 `overlayAvailable` 构造
 * `AndroidDialogHost(SystemDialogOps(...))`（实现住 :platform:capabilities ——
 * domain KDoc 约定 + 平台模块间无依赖边，构造只能在本类）；[inject] 的
 * `dialogs` 参数缺省 null（单测不传 → 仍如实 `ERR_NOT_IMPLEMENTED`，测试走
 * 另一套装配不留门靠的是"同函数可注入真/假"，不是绑死构造）。
 */
object PlatformWiring {

    /**
     * `AppShellKit.assemble` 的能力注入束：七条独立缝（存储/通知/剪贴板/传感器/图像面，
     * §12.2 接线表）+ 五命名空间束（共担门禁的系统面）。形状与 assemble 的参数一一对应，
     * 少一层猜。
     */
    data class Injection(
        val a11yHandler: NamespaceHandler,
        val screenHandler: NamespaceHandler,
        val systemHandlers: SystemHandlers,
        val datastoreHandler: NamespaceHandler,
        val zipHandler: NamespaceHandler,
        val settingsHandler: NamespaceHandler,
        val notificationHandler: NamespaceHandler,
        val clipboardHandler: NamespaceHandler,
        val sensorsHandler: NamespaceHandler,
        /**
         * `images` 独立缝（§9.2）：[ImageAnalyzer] 的真实现 = `:bridge:image` 的 native
         * 管线（`libopencv.so`，OpenCV 静态链接）+ 本侧 `NativeImageAnalyzer`
         * （`:platform:system`，so 缺位即不构造）。**这里刻意缺省 null**：so 不在
         * （未跑 `build-opencv.sh` 的设备/CI JVM）时桥回 `ERR_NOT_IMPLEMENTED`，
         * 脚本拿不到一个看不见像素的假分析器。字段在束里与其余六条同形（图像面是第七条）。
         */
        val imagesHandler: NamespaceHandler? = null,
        /**
         * 投屏会话的设备面（§9.2）：能力中心「屏幕采集」三态与 `screen.startCapturer`
         * 的判据源。**缺省 null** = 本进程没有投屏通道（单测/JVM 装配）——
         * 探针如实报"没有活动会话"，`startCapturer` 走兼容路径或如实不可用。
         */
        val projectionSessions: MediaProjectionSessions? = null,
        /**
         * 控制台 shell 面的执行体（2026-10-09）。
         *
         * **为什么要有这一条**：控制台挂在 npm 模块上，而 `:app-service:npm` 的
         * `ArchitectureTest` 禁 `com.autoscript.platform..` —— 它编译期看不到
         * `ShellExecutor`。转接放在本类（`:app` 的 shell 装配包，包级例外二，本来就
         * 同时看得见两个平台模块），npm 侧只认 `:domain` 的 `HostSummary.runShellCommand`。
         *
         * **`adb` 档在这里被换掉**：`AndroidShellExecutor` 的 `ShellMode.ADB` 与 `DEFAULT`
         * 是同一行（`sh -c`，应用 uid）—— 那是它自己的口径，不是控制台要的。
         * 控制台的 `adb` 档 = **Shizuku**（远端进程的身份由 Shizuku 服务进程决定 ——
         * 服务以 adb 启动时是 shell uid，以 root 启动时是 root，故这里**不承诺具体 uid**），
         * 故本类把 ADB 档换成 Shizuku 实现，
         * ROOT/DEFAULT 原样转给 [AndroidShellExecutor]。缺省 null = 未接线（JVM 装配）。
         */
        val shellExecutor: ShellExecutor? = null,
    )

    /**
     * SPI 束 → 注入束（纯转接：不解释 payload、不吞错误、不做权限判断）。
     * [dialogs] 缺省 null = 未提供（桥如实 ERR_NOT_IMPLEMENTED）；生产由 [of] 传真宿主。
     */
    fun inject(
        spis: SystemSpis.Bundle,
        dialogs: DialogHost? = null,
        images: ImageAnalyzer? = null,
        projection: MediaProjectionSessions? = null,
        /**
         * App 私有文件目录（录屏产物的落点根，§9.2 录屏腿）。**缺省 null = 不接录屏腿**
         * （单测/JVM 装配）—— 那种装配下 `startRecording` 如实 `ERR_NOT_IMPLEMENTED`。
         * 为什么不给一个像 `/tmp` 的缺省：那会让"忘了喂目录"变成"产物悄悄落到别处"，
         * 而落点是契约的一部分（`ScriptPaths.recordingsDir`）。
         */
        filesDir: Path? = null,
        /**
         * 投屏同意口（§9.2）**替换用**：缺省 null = 生产实现 [AndroidScreenConsentBroker]
         * （只在投屏接线时接）。JVM 上它必然拉不起系统对话框（`ScreenConsentHolder.host`
         * 为 null），所以单测要验"录屏腿接通"就得能换一个恒同意的替身 ——
         * 与 `images`/`projection` 同一条"同一函数真假可注入"纪律，不给测试另开装配路径。
         */
        consent: ScreenConsentBroker? = null,
    ): Injection = Injection(
        // 树+动作同一个实例（句柄注册表共享，同 InMemoryUiTree 双身份形态）；
        // 事件流缺省 A11yEventRing.shared（服务 push / 树读同一环）。
        a11yHandler = a11yHandler(spis.shell),
        screenHandler = screenHandler(images, projection, filesDir, consent),
        systemHandlers = SystemHandlers(
            // 缺省 null（未提供）→ 如实 ERR_NOT_IMPLEMENTED；生产由 of() 传真宿主。
            dialogs = dialogs?.let { CapabilityNamespaces.dialogs(it) },
            shell = SystemNamespaces.shell(spis.shell),
            device = SystemNamespaces.device(spis.device),
            app = SystemNamespaces.app(spis.app),
            floatingWindow = SystemNamespaces.floatingWindow(spis.floatingWindow),
        ),
        datastoreHandler = SystemNamespaces.datastore(spis.datastore),
        zipHandler = SystemNamespaces.zip(spis.zip),
        settingsHandler = SystemNamespaces.settings(spis.settings),
        notificationHandler = SystemNamespaces.notification(spis.notification),
        clipboardHandler = SystemNamespaces.clipboard(spis.clipboard),
        sensorsHandler = SystemNamespaces.sensors(spis.sensors),
        // §9.2 图像面：生产侧由 [of] 喂 NativeImageAnalyzer（:bridge:image 的
        // libopencv.so 到位后）；单测/无 native 时不喂 —— 桥对 images.* 如实
        // ERR_NOT_IMPLEMENTED，绝不塞一个看不见像素的假分析器。
        imagesHandler = images?.let { SystemNamespaces.images(it) },
        projectionSessions = projection,
        // ADB 档 = Shizuku（见 Injection.shellExecutor 的 KDoc）。Shizuku 没装/服务没活
        // 时不换 —— 让调用方拿到的失败话术指向「去启动 Shizuku」（`ShizukuInput.exec`
        // 自己那句），而不是「命令跑不起来」。
        shellExecutor = ConsoleShellExecutor(spis.shell),
    )

    /**
     * a11y 装配（[CapabilityNamespaces.a11y] 形状转接；实现在 :platform:capabilities）。
     *
     * **三通道登记（§9.3，2026-10-06）**：`auto` 恒在（无障碍原生）；`root` 与 `adb`
     * **各自按可用性接线**，不可用就**不登记** —— 调用方指定它时拿到 handler 的
     * `ERR_PERMISSION_DENIED` + 引导文案，而不是「命令跑不起来」。这不是降级：
     * 登记与否只决定「这条通道现在有没有」，**绝不改变调用方选的那条**。
     */
    private fun a11yHandler(shell: ShellExecutor): NamespaceHandler {
        val tree = AndroidUiTree()
        val channels = buildMap {
            put(InputChannel.AUTO, AndroidGestureInput())
            // ROOT：走既有的 `su -c`（ShellExecutor 的 ShellMode.ROOT）。
            put(InputChannel.ROOT, ShellInputProvider.root(shell))
            // ADB：Shizuku。装没装 + 服务活没活都要问过才登记（见 ShizukuInput.isAvailable）。
            if (ShizukuInput.isAvailable()) {
                // 反射调 Shizuku 的 `waitForTimeout` 是**阻塞**调用，`withTimeoutOrNull`
                // 拦不住它；不切线程就会把**调用方所在的那个线程**钉住整个超时窗口
                // （脚本桥那条链的调度器本包不掌握，故统一在此切走，不赌调用方是谁）。
                put(
                    InputChannel.ADB,
                    ShellInputProvider.adb { cmd ->
                        withContext(Dispatchers.IO) { ShizukuInput.run(cmd).toShellResult() }
                    },
                )
            }
        }
        return CapabilityNamespaces.a11y(
            tree = tree,
            actions = tree,
            input = AndroidGestureInput(),
            channels = channels,
        )
    }

    /** Shizuku 的 `(exitCode, stderr)` → [ShellResult]（stdout 不取：`input` 成功时无输出）。 */
    private fun Pair<Int, String?>.toShellResult(): ShellResult =
        ShellResult(code = first, stdout = null, stderr = second)

    /**
     * screen 装配（§9.2 a11y 截图路径：语义节流/策略在 ScreenshotSource，设备面在 producer）。
     * [analyzer] 与 `images` 缝**同一个实例**（§18-8(b)）：截屏帧与 decode 帧同表同号段，
     * `images.findImage(screenFrame, decodeFrame)` 才成立；null 即退回本地帧表。
     *
     * **投屏会话**（[projection]）走 [MediaProjectionSource]，帧也经**同一个** analyzer
     * 入表（`MediaProjectionSource` 的 ingest）；analyzer 缺位时它如实
     * `ERR_NOT_IMPLEMENTED`（绝不本地发号 —— 两个帧源各从 1 发号会互相错放帧）。
     * 同意征询（[AndroidScreenConsentBroker]）只在投屏接线时传入：未接线时 handler
     * 走兼容路径，不会去碰同意口。
     */
    private fun screenHandler(
        analyzer: ImageAnalyzer?,
        projection: MediaProjectionSessions?,
        filesDir: Path?,
        consent: ScreenConsentBroker? = null,
    ): NamespaceHandler =
        CapabilityNamespaces.screen(
            ScreenshotSource(AndroidFrameProducer(), analyzer = analyzer),
            projection = projection?.let { MediaProjectionSource(it, analyzer) },
            consent = projection?.let { consent ?: AndroidScreenConsentBroker() },
            // §9.2 录屏腿：与取帧腿**同一个设备对象**（两条腿共用一条 MediaProjection
            // 会话账 —— 一台设备同时只有一条）。`projection` 为 null（单测/JVM）时不接线，
            // `startRecording` 如实 ERR_NOT_IMPLEMENTED。
            recorder = (projection as? ScreenRecordingSessions)?.let { sessions ->
                filesDir?.let { MediaProjectionRecorder(sessions, it) }
            },
        )

    /**
     * §8.5 意图日志存储的**生产打开**：SQLite（`SqliteIntentStore`，住 `:platform:system`），
     * 并在此之前做一次性迁移（老设备的 `intent-log.jsonl` → SQLite，带原 runId、
     * 幂等可重入 —— 理由见 [IntentStoreWiring] 的 KDoc）。
     *
     * **打不开就是失败，不回落**（2026-10-08 裁定）：异常原样抛给调用方
     * （`AppShellApplication.installWithFiles` 的失败分支 —— 记日志、壳保持未就绪、
     * 闹钟走漏投记账）。原先那条「打不开就回落 jsonl」已删：回落目标的 runId 分配
     * （`max+1`）与幂等锚点（锁内先查后写）都靠单写者假设撑着，那不是降级，
     * 是把「调度坏了」伪装成「调度还能用」。
     *
     * 为什么这个函数住本类而不是 `AppShellKit`：它碰 Android（`Context`），而
     * `AppShellKit` 的纪律是**纯 JVM 可测**（只收 :domain 缝类型，不 import `android.`）。
     * 本类已经是「唯一碰 Android 的那一步」（`of(context)` 同址）。
     */
    fun intentStore(context: Context, autojsDir: Path): IntentStore =
        IntentStoreWiring.open(context.applicationContext, autojsDir)

    /**
     * 生产入口：`Context` → [SystemSpis.of] 十件 + DialogHost 构造（本类是唯一同时
     * 碰得到两个平台模块与 overlay 实况的装配点）→ [inject]。
     * `overlayAvailable` 缺省 `{ false }`：悬浮窗/对话框先走 `TYPE_APPLICATION_OVERLAY`；
     * a11y 服务在跑时由调用方改传 `{ true }`（语义见 [SystemSpis.of]）——
     * 同一个探针喂给悬浮窗与对话框两条路，不各读各的。
     *
     * `imageAnalyzer` 缺省 `NativeImageAnalyzer.of(JniOps.loadOrNull())`：so 缺位 →
     * null → 图像面不注入（见 [Injection.imagesHandler]）。**默认值在装配期求值**，
     * 单测可传 null/替身绕过 native —— 同一函数真假可注入，不绑死构造。
     */
    /**
     * 语法高亮会话工厂（编辑器前端调用，树形解析器住 `:platform:editor`）。
     * 根包 `AppShellApplication` 经此拿工厂 —— 它不 import 任何 `com.autoscript.platform..`
     * （ArchitectureTest 看住）。JS 扩展 → tree-sitter；非 JS → NONE；so 缺位 → NONE。
     */
    fun syntaxHighlighter(relPath: String): com.autoscript.domain.editor.SyntaxHighlighter =
        EditorHighlighters.create(relPath)

    /**
     * §8.7 唤醒锁账本的生产构造缝（账本类住 `:platform:system`）。
     * 根包 `AppShellApplication` 经此拿账本 —— 它不 import 任何
     * `com.autoscript.platform..`（ArchitectureTest「平台实现只许装配包碰」看住）。
     */
    fun wakeLockLedger(context: Context): WakeLockLedger =
        WakeLockLedger(AndroidWakeLockOps(context))

    /**
     * §8.7 脚本电源 handler 构造缝（`AppShell.assemble` 的 `powerManagerHandler` 独立缝）：
     * 账本取 keeper 持有的**同一本账**（脚本锁与框架锁引用计数共存），keepalive 喂同一
     * 实现 `KeepAliveRenew` 的实例（`:domain` 窄缝，见 `ForegroundKeeper`）。返回类型是
     * `NamespaceHandler`（`:domain`）—— 根包调用处连平台类型名都不必提。
     */
    fun powerManagerHandler(keeper: ForegroundKeeper): NamespaceHandler =
        PowerManagerNamespaceHandler(keeper.wakeLocks(), keeper)

    /**
     * 控制台 shell 面的执行体：ROOT/DEFAULT 转给 [AndroidShellExecutor]，
     * **ADB 换成 Shizuku**（身份由 Shizuku 服务进程决定，不承诺具体 uid）。
     *
     * 为什么不是改 `AndroidShellExecutor` 的 ADB 档：那个类的 ADB 档有它自己的语义
     * （「设备侧已在 adb shell 内」，即应用 uid），改它会动到 a11y 的输入注入那条路
     * （`ShellInputProvider.adb` 传的是 Shizuku 缝，不走 `ShellMode.ADB`）。两条面各要
     * 各的语义，故**在装配层分流**，两边都不动。
     */
    private class ConsoleShellExecutor(private val platform: ShellExecutor) : ShellExecutor {
        override suspend fun exec(command: String, mode: ShellMode, timeoutMillis: Long): ShellResult =
            when (mode) {
                ShellMode.ROOT, ShellMode.DEFAULT -> platform.exec(command, mode, timeoutMillis)
                ShellMode.ADB -> {
                    // **必须切线程**：`ShizukuInput.exec` 阻塞在反射调用的
                    // `IRemoteProcess.waitForTimeout` 上，`ConsoleShellRunner` 的
                    // `withTimeoutOrNull` 只能取消协程、**打断不了这个阻塞调用**。
                    // 控制台这条链一路跑在 `Dispatchers.Main` 上（MainActivity 的
                    // `rememberCoroutineScope()`），不切就是拿 UI 线程陪跑到超时
                    // —— 整个窗口在这段时间里画不出帧、也处理不了输入。
                    //
                    // 这**不是**「首次 `input tap` 挂 30s」那个 bug 的成因（那条实测
                    // 只有冷置后的第 1 条挂，而本缺陷会让每一条都钉住 UI 线程），
                    // 两者各自独立。2026-10-10 外审第 1 条。
                    val r = withContext(Dispatchers.IO) { ShizukuInput.exec(command, timeoutMillis) }
                    // 逐字段转接（`:platform:capabilities` 看不到 `:platform:system` 的
                    // `ShellResult`，两边各有一个同形 DTO）。**`truncated` 必须一起搬**：
                    // 漏掉它，adb 档的输出被截到上限时控制台不会打那句「已截断」——
                    // 那是「悄悄丢字节」，正是 §9.6 截断口径要防的事（2026-10-09 补）。
                    ShellResult(
                        code = r.code,
                        stdout = r.stdout,
                        stderr = r.stderr,
                        truncated = r.truncated,
                    )
                }
            }
    }

    /**
     * `filesDir` 缺省 = App 私有文件目录（§9.2 录屏产物落点根，实际落点是
     * `ScriptPaths.recordingsDir(filesDir, projectId)`）；传 null = 不接录屏腿
     * （那种装配下 `screen.startRecording` 如实 `ERR_NOT_IMPLEMENTED`）。
     */
    fun of(
        context: Context,
        overlayAvailable: () -> Boolean = { false },
        imageAnalyzer: ImageAnalyzer? = NativeImageAnalyzer.of(JniOps.loadOrNull()),
        projection: MediaProjectionSessions? = AndroidMediaProjectionSessions(
            context.applicationContext,
            AndroidProjectionForeground.forApplication(context.applicationContext),
        ),
        /** 录屏产物落点根（§9.2 录屏腿）= App 私有文件目录。 */
        filesDir: Path? = context.applicationContext.filesDir.toPath(),
    ): Injection {
        val app = context.applicationContext
        return inject(
            SystemSpis.of(context, overlayAvailable),
            dialogs = AndroidDialogHost(SystemDialogOps(app, overlayAvailable), overlayAvailable),
            // §9.2 图像面真实现（native 管线到位后接上）：so 缺位 → 构造回 null →
            // 不喂分析器，桥对 images.* 如实 ERR_NOT_IMPLEMENTED（凑数防线）。
            images = imageAnalyzer,
            // §9.2 投屏会话面：真设备实现（独立 mediaProjection FGS + VirtualDisplay
            // + ImageReader）。单测传 null 走兼容路径 —— 同一函数真假可注入。
            projection = projection,
            filesDir = filesDir,
        )
    }
}

/**
 * `ShellExecutor`（`:platform:system` 的 SPI）→ `ShellOpExecutor`（`:domain` 的缝）。
 *
 * 为什么需要这一层转接：`ShellConsoleResult`（`:domain`）与 `ShellResult`
 * （`:platform:system`）是同形但**两个类型** —— 后者带 `truncated` 的内部口径，
 * 而 `:app-service:npm` 看不到 `:platform:*`。转接点只能在本包（唯一同时看得见
 * 两个平台模块的地方，包级例外二）。
 */
fun ShellExecutor.asShellOpExecutor(): ShellOpExecutor = ShellOpExecutor { command, mode, timeoutMillis ->
    // `:domain` 的模式 → `:platform:system` 的模式（一一对应，两个枚举刻意同名不同型）。
    // 转接表就写在这里而不是另起一个 `consoleShellMode`：它只服务这一处，且**必须**与
    // 上面的 `when` 分支同进同出 —— 拆开会让「枚举加一个成员」只改一边就编译过。
    val platformMode = when (mode) {
        ShellConsoleMode.DEFAULT -> ShellMode.DEFAULT
        ShellConsoleMode.ROOT -> ShellMode.ROOT
        ShellConsoleMode.ADB -> ShellMode.ADB
    }
    val r = exec(command, platformMode, timeoutMillis)
    ShellConsoleResult(code = r.code, stdout = r.stdout, stderr = r.stderr, truncated = r.truncated)
}
