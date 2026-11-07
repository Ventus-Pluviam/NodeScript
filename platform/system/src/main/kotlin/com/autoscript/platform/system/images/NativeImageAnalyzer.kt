package com.autoscript.platform.system.images

import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.ColorHit
import com.autoscript.domain.automation.FeatureHit
import com.autoscript.domain.automation.ImageMatch
import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.bridge.ConnectionResourceRegistry
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * `images` 的宿主侧真实现（docs §9.2；SPI 见 `:domain` 的 [ImageAnalyzer]，
 * 语义层 handler 在 `:platform:capabilities` 的 `ImagesNamespaceHandler`）。
 * 像素计算全在 native（`libopencv.so`，OpenCV 4.14 静态链接，
 * `:bridge:image` 的 `imgnative.cpp`）；本类只做五件**本机 JVM 可测**的事：
 *
 * 1. **句柄发号**：[HandleRef.refId] 单调递增、generation 恒 1（一个文件一个帧，
 *    与 `ScreenshotSource`/`AndroidSensorSource` 同形）；native 帧号 →
 *    refId 的对照表住 [frames]（release 先删表再放 native：脚本侧说放了的帧，
 *    下一次匹配一定 STALE，不会出现"表里在场、native 已不在场"的窗口）。
 * 2. **错误码对表**：native 状态码（`imgnative.cpp` 文件头：0 OK / 1 STALE /
 *    2 FILE_NOT_FOUND / 3 IO / 4 INVALID_PARAM）→ [ErrorCode]，**原码透传不折叠**
 *    （handler 要求调用方能分辨"路径错了"与"参数关系不成立"/"解码失败"）。
 * 3. **findColor 的域与哨兵**：颜色四分量/容差/region 形状在这层 require
 *    （域错折 `ERR_INVALID_PARAM`，与 matchTemplate 的阈值域同一套纪律）；
 *    native 回 `x = -1` 视为**未命中（答案）**而不是某个坐标 —— (0,0) 是合法
 *    首像素，拿 0 当"没有"会把左上角的命中静悄悄吃掉。
 * 4. **native 缺席时的诚实缺位**：so 不在 → [Unavailable]（装配层不喂分析器，
 *    桥对 `images.*` 如实 `ERR_NOT_IMPLEMENTED`），**绝不塞内存替身** ——
 *    看不见像素的"内存分析器"只能靠自报坐标假装命中（§9.2 / :domain KDoc）。
 * 5. **连接级收口**：帧账带"发它的那条连接"，连接没了就把**它名下**的在场帧
 *    全部还给 native（[releaseConnection]）—— 脚本不调 `recycle()` 不再是
 *    "像素活到进程结束"（native 帧表没有批量释放口，§7.4 承诺的
 *    `FinalizationRegistry` 兜底在 JS 侧并不存在，见该方法 KDoc）。
 *
 * 分层照 README ops 表：Android 接触面只有 [Ops] 缝（真机 [JniOps] 做
 * `System.loadLibrary` + native 方法），本类零 android.* import —— 单测注入
 * 内存替身即可跑全部分支（含"so 缺席"那条，不必真机）。
 *
 * **IO 出界**：native 调用是阻塞的（文件读 + 匹配计算），全部包在
 * [Dispatchers.IO]；native 帧表自持互斥量，跨协程并发安全。
 */
class NativeImageAnalyzer(
    private val ops: Ops,
) : ImageAnalyzer {

    /** refId → 帧账（native 帧号 + 发它的那条连接；唯一持有帧所有权的地方，只在 [guard] 内读写）。 */
    private val guard = Any()
    private val frames = HashMap<Long, Frame>()
    private var nextRefId = 1L

    /**
     * 连接号 → 它名下**在场**的 refId（连接级收口的索引，见 [releaseConnection]）。
     *
     * 只含"有认证身份"的连接。两张表都在 [releaseConnection] 里**整条摘掉**，而连接结束时
     * 它必被调一次（`NewlineFrameServer` 的 abort 与 dispose 都 `revokeAll`，另有
     * `abortConnection(id)` / `revokeRunResources(runId)` 两条外部路）—— 所以条目的上界是
     * **活连接数**，不是"开过帧的连接数"。刻意不在"账空了"的时候提前摘：那会让
     * 「放掉最后一帧」与「同一时刻发一帧」两条线程交错出一个把新帧也收掉的窗口
     * （登记在，撤销时按账收，账空就是 no-op —— 少一个窗口比少一条空集值当）。
     */
    private val byConnection = HashMap<Long, MutableSet<Long>>()

    /** 连接号 → 它在 [ConnectionResourceRegistry] 上的收口句柄（[releaseConnection] 里摘）。 */
    private val reclaimHandles = HashMap<Long, ConnectionResourceRegistry.Handle>()

    /**
     * 一帧的账。[connectionId] = [NO_CONNECTION] 表示"没有认证身份"（宿主直调/JVM 测试）——
     * 那种帧**不进** [byConnection]：没有连接可撤销，登记到一个没人撤销的注册表等于假装
     * 有兜底，照旧只能由显式 [release] 收。
     */
    private class Frame(val nativeRef: Long, val connectionId: Long)

    override suspend fun decode(path: String): ImageFrame {
        require(path.isNotBlank()) { "images decode 的 path 不得为空白" }
        return withContext(Dispatchers.IO) {
            // status 出参：native 失败时不抛异常（不穿过 JNI），把码放进 int[1]。
            val status = IntArray(1)
            val decoded = ops.decode(path, status)
            val rc = status[0]
            if (decoded == null) {
                // null + 状态码 0 = JNI 自身失败（OOME/字符串编解码），不猜成
                // 某个 ERR_*：如实归类到 IO（脚本能看到的只有"这步没成"）。
                throw if (rc != 0) statusToException(rc, "decode $path") else {
                    AutojsException(ErrorCode.ERR_IO, "images decode 的 native 调用失败: $path")
                }
            }
            adoptFrame(decoded)
        }
    }

    override suspend fun ingest(width: Int, height: Int, rgba: ByteArray): ImageFrame {
        require(width > 0 && height > 0) { "ingest 的宽高必须为正，实际 ${width}x$height" }
        require(rgba.size == width * height * 4) {
            "ingest 的像素必须紧密打包 RGBA：期望 ${width * height * 4} 字节，实际 ${rgba.size}"
        }
        return withContext(Dispatchers.IO) {
            val status = IntArray(1)
            val ingested = ops.ingest(rgba, width, height, status)
            val rc = status[0]
            if (ingested == null) {
                throw if (rc != 0) statusToException(rc, "ingest ${width}x$height") else {
                    AutojsException(ErrorCode.ERR_IO, "images ingest 的 native 调用失败")
                }
            }
            adoptFrame(ingested)
        }
    }

    override suspend fun release(handle: HandleRef) = withContext(Dispatchers.IO) {
        if (handle.generation != GENERATION) {
            throw AutojsException(
                ErrorCode.ERR_STALE_HANDLE,
                "images 帧句柄代次不匹配：期望 $GENERATION，实际 ${handle.generation}",
            )
        }
        val nativeRef = synchronized(guard) { forgetLocked(handle.refId) }
            ?.nativeRef
            ?: throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "未知 images 帧 refId=${handle.refId}")
        val rc = ops.release(nativeRef)
        if (rc != 0) {
            throw statusToException(rc, "release refId=${handle.refId} native=$nativeRef")
        }
    }

    override suspend fun matchTemplate(
        haystack: HandleRef,
        needle: HandleRef,
        threshold: Double,
        region: List<Int>?,
    ): ImageMatch? = match("matchTemplate", haystack, needle, threshold, region)

    override suspend fun findImage(
        haystack: HandleRef,
        needle: HandleRef,
        threshold: Double,
        region: List<Int>?,
    ): ImageMatch? = match("findImage", haystack, needle, threshold, region)

    /**
     * 两方法同一个实现（契约：`findImage` 是 `matchTemplate` 的调用侧别名，
     * 阈值/region 语义同）：把两个 refId 换成本机帧号，一次 native 匹配。
     * 域校验归桥面 handler（此处按契约假定已在 [0,1]；region 形状与 findColor
     * 同款再兜一次 —— 两条判据若漂移，宁可这层炸也不让脏参数进 native）。
     */
    private suspend fun match(
        methodName: String,
        haystack: HandleRef,
        needle: HandleRef,
        threshold: Double,
        region: List<Int>?,
    ): ImageMatch? = withContext(Dispatchers.IO) {
        region?.let {
            require(it.size == 4) { "region 必须 x,y,w,h 四元组，实际 ${it.size}" }
        }
        val native = synchronized(guard) {
            val h = frames[haystack.refId]?.nativeRef
            val n = frames[needle.refId]?.nativeRef
            if (h == null || n == null) {
                throw AutojsException(
                    ErrorCode.ERR_STALE_HANDLE,
                    "images $methodName 的帧句柄已释放（haystack=${haystack.refId}, needle=${needle.refId}）",
                )
            }
            h to n
        }
        val status = IntArray(1)
        val hit = ops.match(native.first, native.second, threshold, region?.toIntArray(), status)
        val rc = status[0]
        when {
            hit != null -> hit
            rc != 0 -> throw statusToException(rc, "$methodName")
            // null + 状态码 0 = 未达阈值（答案，不是异常）；JNI 自身失败也在
            // 这一支，但那与 native 语义不可分辨 —— 不编造一个命中糊过去。
            else -> null
        }
    }

    override suspend fun findColor(
        haystack: HandleRef,
        color: List<Int>,
        tolerance: Int,
        region: List<Int>?,
    ): ColorHit? {
        // 域校验与 matchTemplate 同一条纪律：handler 已先拒，这里再兜一次 ——
        // 两条判据若漂移，宁可在这层炸 ERR_INVALID_PARAM，也不让非法色进 native。
        require(color.size == 4) { "images findColor 的 color 必须 r,g,b,a 四分量，实际 ${color.size}" }
        color.forEach { require(it in 0..255) { "颜色分量必须是 [0,255]，实际 $it" } }
        require(tolerance in 0..255) { "tolerance 必须是 [0,255]，实际 $tolerance" }
        region?.let {
            require(it.size == 4) { "region 必须 x,y,w,h 四元组，实际 ${it.size}" }
        }
        return withContext(Dispatchers.IO) {
            val nativeRef = synchronized(guard) {
                frames[haystack.refId]?.nativeRef
                    ?: throw AutojsException(
                        ErrorCode.ERR_STALE_HANDLE,
                        "images findColor 的帧句柄已释放（haystack=${haystack.refId}）",
                    )
            }
            val status = IntArray(1)
            val hit = ops.color(nativeRef, color.toIntArray(), tolerance, region?.toIntArray(), status)
            when {
                hit != null -> hit
                status[0] == STATUS_OK -> null   // 扫过了、没有（答案，不是异常）
                else -> throw statusToException(status[0], "findColor haystack=${haystack.refId}")
            }
        }
    }

    // ── P1 图像桥消费方五算子（:domain `ImageAnalyzer` 新方法）──
    // 形状统一：源帧 → 产出帧（[frameFromNative] 落表发号，与 decode/ingest 同一张
    // 表、同一个号段 —— release 对产出帧与源帧同口径）。
    // 域校验与 findColor 同一条纪律：handler 已先拒，这里再兜一次（两条判据若
    // 漂移，宁可在这层炸 ERR_INVALID_PARAM，也不让脏参数进 native）。

    override suspend fun toGrayscale(frame: HandleRef): ImageFrame =
        withContext(Dispatchers.IO) {
            val nativeRef = requireLive(frame, "toGrayscale")
            val status = IntArray(1)
            frameFromNative(ops.gray(nativeRef, status), status, "toGrayscale frame=${frame.refId}")
        }

    override suspend fun crop(frame: HandleRef, region: List<Int>): ImageFrame {
        require(region.size == 4) { "images crop 的 region 必须 x,y,w,h 四元组，实际 ${region.size}" }
        return withContext(Dispatchers.IO) {
            val nativeRef = requireLive(frame, "crop")
            val status = IntArray(1)
            frameFromNative(ops.crop(nativeRef, region.toIntArray(), status), status, "crop frame=${frame.refId}")
        }
    }

    override suspend fun resize(frame: HandleRef, width: Int, height: Int): ImageFrame {
        require(width > 0 && height > 0 && width <= 16384 && height <= 16384) {
            "images resize 的目标尺寸必须 (0,16384] 正整数，实际 ${width}x$height"
        }
        return withContext(Dispatchers.IO) {
            val nativeRef = requireLive(frame, "resize")
            val status = IntArray(1)
            frameFromNative(ops.resize(nativeRef, width, height, status), status, "resize frame=${frame.refId}")
        }
    }

    override suspend fun rotate(frame: HandleRef, degrees: Double): ImageFrame {
        require(degrees.isFinite()) { "images rotate 的 degrees 必须是有限数字，实际 $degrees" }
        return withContext(Dispatchers.IO) {
            val nativeRef = requireLive(frame, "rotate")
            val status = IntArray(1)
            frameFromNative(ops.rotate(nativeRef, degrees, status), status, "rotate frame=${frame.refId}")
        }
    }

    override suspend fun findFeature(scene: HandleRef, template: HandleRef): FeatureHit? =
        withContext(Dispatchers.IO) {
            val native = synchronized(guard) {
                val s = frames[scene.refId]?.nativeRef
                val t = frames[template.refId]?.nativeRef
                if (s == null || t == null) {
                    throw AutojsException(
                        ErrorCode.ERR_STALE_HANDLE,
                        "images findFeature 的帧句柄已释放（scene=${scene.refId}, template=${template.refId}）",
                    )
                }
                s to t
            }
            val status = IntArray(1)
            val hit = ops.feature(native.first, native.second, status)
            when {
                hit != null -> hit
                status[0] == STATUS_OK -> null   // 未匹配（答案，不是异常）
                else -> throw statusToException(status[0], "findFeature scene=${scene.refId}")
            }
        }

    /** 帧在场性兜底（与 match/findColor 同口径：帧表唯一事实源，判据在 [frames]）。 */
    private fun requireLive(frame: HandleRef, what: String): Long =
        synchronized(guard) {
            frames[frame.refId]?.nativeRef
                ?: throw AutojsException(
                    ErrorCode.ERR_STALE_HANDLE,
                    "images $what 的帧句柄已释放（frame=${frame.refId}）",
                )
        }

    /**
     * native 产出帧 → 本类 [frames] 表（发号与 [decode]/[ingest] 同一号段）+ 回包。
     * 失败 = null + status 非 0（同 decode 的判读：null + status 0 = JNI 自身失败，如实 IO）。
     */
    private suspend fun frameFromNative(
        decoded: Triple<Long, Int, Int>?,
        status: IntArray,
        what: String,
    ): ImageFrame {
        if (decoded == null) {
            throw if (status[0] != 0) statusToException(status[0], what) else {
                AutojsException(ErrorCode.ERR_IO, "images $what 的 native 调用失败")
            }
        }
        return adoptFrame(decoded)
    }

    // ── 帧账的三个动作：落表发号（[adoptFrame]）、摘表（[forgetLocked]）、
    // 按连接收口（[releaseConnection]）——在场性/发号/像素所有权的唯一事实源
    // 仍是这一张表（§7.4 发号侧归一），这里只是给每帧多记一个"谁发的"。──

    /**
     * native 产出帧 → 本类帧表：发号 + 归属记账 + 连接级收口登记。
     *
     * 三个发帧口（[decode]/[ingest]/[frameFromNative]）**共用这一条**，不留"哪条路
     * 忘了登记"的缝 —— 尤其是 `ingest`：`screen.capture()`/投屏 `nextFrame` 的帧
     * 都从这里进表（§18-8(b) 两缝一张表），而那正是单帧最大（1080×2400 RGBA
     * ≈ 10.4 MB）、最容易被循环刷出来的一条。
     */
    private suspend fun adoptFrame(decoded: Triple<Long, Int, Int>): ImageFrame {
        val (nativeRef, width, height) = decoded
        // 归属从**协程上下文**取（不是参数）：帧表跨连接共用，而"这帧是谁的"只有认证身份
        // 说得出 —— 与 `ScreenNamespaceHandler` 的会话归属同一条读法（不可自报）。
        val caller = coroutineContext[AuthenticatedRunContext]
        val connId = caller?.connectionId ?: NO_CONNECTION
        var needRegister = false
        val refId = synchronized(guard) {
            val id = nextRefId++
            frames[id] = Frame(nativeRef, connId)
            if (caller != null) {
                byConnection.getOrPut(connId) { LinkedHashSet() } += id
                // **一条连接一条登记**（不是"一帧一条"）：扫屏循环一帧一条会在**共享**的
                // 注册表里堆出上万条（那张表还住着投屏会话的收口）。判据是"这条连接有没有
                // 在册的句柄"，而不是"这帧是不是它的第一帧"—— 后者在「放掉最后一帧」与
                // 「同时发一帧」交错时会重复登记。
                needRegister = connId !in reclaimHandles
            }
            id
        }
        // 登记在锁外（注册表与 native 都不该在帧表锁里被调；见 [registerReclaim] 的竞态说明）。
        if (caller != null && needRegister) registerReclaim(connId, caller.resources)
        return ImageFrame(HandleRef(refId, GENERATION), width, height)
    }

    /**
     * 从帧表与连接归属账里摘掉一帧（调用方须持 [guard]；native 释放在锁外做）。
     *
     * **不动 [reclaimHandles]**：登记随连接的生命周期走，撤销时按账收（账空即 no-op）——
     * 提前摘会打开一个"放掉最后一帧与同时发一帧交错，新帧被当成孤儿收掉"的窗口。
     */
    private fun forgetLocked(refId: Long): Frame? {
        val frame = frames.remove(refId) ?: return null
        byConnection[frame.connectionId]?.remove(refId)
        return frame
    }

    /**
     * 把"这条连接没了要收什么"登记进它自己的资源收口口。
     *
     * **顺序与竞态**（照 `MediaProjectionSource` 那条已验证的路）：帧**先落表**再登记，
     * 于是"撤销在先、发帧在后"时 [ConnectionResourceRegistry.register] 会**立刻兑现**
     * 回调 —— 迟到的那一帧当场被收掉，不留孤儿。登记返回后按"账还在不在"分两种收尾，
     * 两条线程交错都收敛到同一结果：
     * - 账还在（没被撤销）→ 存下句柄，连接结束时兑现；
     * - 账已空（回调已 inline 跑完，或被撤销线程抢先）→ 句柄就地还掉，不往表里塞一条
     *   永远不会有人来兑现的尸。
     *
     * **并发发帧的重复登记就地还掉**（`putIfAbsent` 的输家）：两条线程同时给同一条连接
     * 发第一帧时，两边都可能看到"还没登记"。留着输家那条不会错（撤销时它只是 no-op），
     * 但会往**共享**的注册表里堆条目 —— 收口口是进程级资源，不该为同一件事挂两份凭据。
     */
    private fun registerReclaim(connId: Long, registry: ConnectionResourceRegistry) {
        val handle = registry.register { releaseConnection(connId) }
        synchronized(guard) {
            if (connId !in byConnection || reclaimHandles.putIfAbsent(connId, handle) != null) {
                handle.release()
            }
        }
    }

    /**
     * 连接级收口（§7.5 × §9.2）：把**这条连接**名下的在场帧全部还给 native。
     *
     * **为什么需要它**（三条事实叠起来就是漏）：
     * 1. native 帧表（`imgnative.cpp` 的 `g_frames`）**只有单帧 `imgnative_release`**，
     *    没有批量释放口 —— 宿主不逐个报 refId，native 无从知道该收哪些；
     * 2. §7.4 承诺的 GC 兜底（`FinalizationRegistry` → `release(id, gen)`）在
     *    `bridge/js` 里**并不存在**：`recycle()` 是脚本自愿调的，不调就没人调；
     * 3. 本类是 `PlatformWiring.of` 装配期建的**进程级单例**，帧表跨 run 复用 ——
     *    脚本退出、连接断开都不清它。
     *
     * 于是"一个不 recycle 的扫屏循环"（1080×2400 RGBA ≈ 10.4 MB/帧，截图节流 333 ms
     * → 约 31 MB/s）会把 native 堆一路吃穿，且**脚本结束后也不还**。收口点因此放在
     * 连接层：连接 abort / 排空结束 / `abortConnection(id)` / `revokeRunResources(runId)`
     * 四条路都经 [ConnectionResourceRegistry] 走到这里，与投屏会话同一条路。
     *
     * **只收自己的**（与 `MediaProjectionSource.releaseConnection` 同一条纪律）：别的
     * 连接的帧原样在场 —— 分析器是所有脚本共用的单例，按连接分区才不会"一个脚本退出
     * 带走别人的帧"。
     *
     * **非挂起**：撤销回调可能在任意线程被调，而 native 释放本来就是同步的。
     *
     * 一帧的 native 释放失败**不中断其余帧**（同 kill 路径"不中断全清"的口径）：全部
     * 尝试完之后把第一个失败如实抛出，由 [ConnectionResourceRegistry] 的 `onError`
     * 上报 —— 收口路径上的异常不许打断别的资源，但也不许静默。
     *
     * @return 实际还给 native 的帧数（诊断用）。
     */
    fun releaseConnection(connectionId: Long): Int {
        val doomed: List<Long>
        val stale: ConnectionResourceRegistry.Handle?
        synchronized(guard) {
            val ids = byConnection.remove(connectionId)?.toList() ?: emptyList()
            stale = reclaimHandles.remove(connectionId)
            doomed = ids.mapNotNull { refId -> frames.remove(refId)?.nativeRef }
        }
        // 还掉登记（锁外）：撤销那条路上它已被注册表的 `runEntry` 取走，这里是 no-op；
        // 宿主/看门狗直调那条路上它还在册 —— 不摘就是一条随连接数只增不减的尸。
        stale?.release()
        // native 释放在锁外（与 [release] 同一条纪律：临界区只做 map 读写）。
        var released = 0
        var firstFailure: AutojsException? = null
        for (nativeRef in doomed) {
            val rc = ops.release(nativeRef)
            if (rc == 0) {
                released++
            } else if (firstFailure == null) {
                firstFailure = statusToException(rc, "连接 $connectionId 收口 native=$nativeRef")
            }
        }
        firstFailure?.let { throw it }
        return released
    }

    /** 在册帧数（测试与诊断用；连接收口后该连接的帧应全部离场）。 */
    fun liveFrameCount(): Int = synchronized(guard) { frames.size }

    /** native 状态码 → 分类错误（`imgnative.cpp` 文件头的对表，原码透传）。 */
    private fun statusToException(status: Int, what: String): AutojsException {
        val code = when (status) {
            STATUS_STALE -> ErrorCode.ERR_STALE_HANDLE
            STATUS_FILE_NOT_FOUND -> ErrorCode.ERR_FILE_NOT_FOUND
            STATUS_INVALID_PARAM -> ErrorCode.ERR_INVALID_PARAM
            else -> ErrorCode.ERR_IO
        }
        return AutojsException(code, "images native 拒绝 $what（status=$status）")
    }

    /**
     * native 接触面（README ops 表的本行）：真机 [JniOps]（`System.loadLibrary`
     * + 四个 external 方法）；单测注入的内存替身**可复现全部语义** ——
     * 包括"未命中回 null""句柄已死""解码失败"，不需要真图也不需要 so。
     *
     * 方法不回异常（异常不穿 JNI 边界）：失败一律走 `status` 出参
     * （`IntArray(1)`，0 = OK）。`decode` 回 `null` + 非 0 status = 分类失败；
     * 回 `null` + status 0 = 未命中（`match`）或 native 调用自身失败（`decode`）。
     */
    interface Ops {
        /** @return `Triple(nativeRef, width, height)`；失败 null + `status[0]` 非 0。 */
        fun decode(path: String, status: IntArray): Triple<Long, Int, Int>?

        /**
         * 登记一屏**已在内存里的 RGBA 紧排像素**（§18 第 8 项 (b)：截屏帧进 images 帧表，
         * 发号侧归一）。与 [decode] 同一张 native 帧表、同一个号段。
         * @return `Triple(nativeRef, width, height)`；失败 null + `status[0]` 非 0。
         */
        fun ingest(rgba: ByteArray, width: Int, height: Int, status: IntArray): Triple<Long, Int, Int>?

        /**
         * @param region 可选 `[x,y,w,h]` 搜索范围（null = 全帧）；越界/比模板小
         *   由 native 判（INVALID_PARAM / IO），本层只透传。
         * @return 命中五元组；**未命中** null + `status[0] == 0`（答案）；
         * 分类失败 null + status 非 0。
         */
        fun match(
            haystack: Long,
            needle: Long,
            threshold: Double,
            region: IntArray?,
            status: IntArray,
        ): ImageMatch?

        /**
         * 找色：在 [nativeFrame]（或其 [region] = x,y,w,h）里找第一个与
         * [color] = r,g,b,a 分量差各不超过 [tolerance] 的像素。
         *
         * 失败一律 null + `status[0]` 非 0（同上两条）；**未命中是 null +
         * `status[0] == 0` 而 `x = -1`**（哨兵不能取 0 —— (0,0) 是合法首像素，
         * 拿它当"没有"会把左上角的命中静悄悄吃掉）。
         */
        fun color(
            nativeFrame: Long,
            color: IntArray,
            tolerance: Int,
            region: IntArray?,
            status: IntArray,
        ): ColorHit?

        /** @return 0 = OK；1 = 未知/已释放的 native 帧号。 */
        fun release(nativeRef: Long): Int

        /**
         * 灰度化：源帧 → 产出帧（回 `Triple(nativeRef, width, height)`，宽高与源帧
         * 同；新帧也落 native 帧表，由调用方 release）。失败 null + `status[0]` 非 0。
         */
        fun gray(nativeFrame: Long, status: IntArray): Triple<Long, Int, Int>?

        /**
         * 裁剪：源帧的 `region = [x,y,w,h]` 子矩形 → 产出帧（尺寸 = region 的 w/h）。
         * 失败 null + `status[0]` 非 0（region 越帧界 → 4）。
         */
        fun crop(nativeFrame: Long, region: IntArray, status: IntArray): Triple<Long, Int, Int>?

        /**
         * 缩放：源帧 → 目标尺寸 `width` × `height` 产出帧（插值 INTER_LINEAR 固定在
         * native）。失败 null + `status[0]` 非 0（尺寸非法/超配额 → 4）。
         */
        fun resize(nativeFrame: Long, width: Int, height: Int, status: IntArray): Triple<Long, Int, Int>?

        /**
         * 旋转：源帧绕帧中心逆时针转 [degrees] → 产出帧（expand 画布，宽高随角度
         * 由包络公式算出）。失败 null + `status[0]` 非 0（非有限角度/画布超配额 → 4）。
         */
        fun rotate(nativeFrame: Long, degrees: Double, status: IntArray): Triple<Long, Int, Int>?

        /**
         * 特征匹配：场景里找模板的**不同尺寸/轻微旋转变体** —— 回模板中心
         * `(x, y)` + 置信度；**未命中** null + `status[0] == 0`（答案）；失败 null +
         * status 非 0。不产出帧。
         */
        fun feature(scene: Long, template: Long, status: IntArray): FeatureHit?
    }

    companion object {
        /** 句柄代次：帧不复用，故恒 1（§7.4 generation 语义）。 */
        const val GENERATION: Long = 1

        /**
         * "没有认证身份"的连接号占位（宿主直调/JVM 测试）：与
         * `BridgeRouter` 的 `connectionId = caller?.connectionId ?: 0L` 同一个 0。
         * 这种帧不进 [byConnection]（见 [Frame]）。
         */
        const val NO_CONNECTION: Long = 0L

        // native 状态码（与 imgnative.cpp 文件头逐条对表；改必须同批）：0 = OK
        // 不进 [statusToException]；1/2 点名命名，其余非零码一律归 IO（上游加码
        // 时不至于被误读成"只此两种非零"）。
        const val STATUS_OK = 0
        const val STATUS_STALE = 1
        const val STATUS_FILE_NOT_FOUND = 2
        const val STATUS_INVALID_PARAM = 4

        /**
         * 真机构造：so 缺位 → null（装配层 [SystemSpis] 不喂分析器，桥回
         * `ERR_NOT_IMPLEMENTED`）。**故意不为省一次判空去 try/catch loadLibrary**——
         * 装载失败就是这条缝不可用，装配层据此放弃注入比让脚本看到崩溃栈好。
         */
        fun of(ops: Ops?): ImageAnalyzer? = ops?.let { NativeImageAnalyzer(it) }
    }
}
