package com.autoscript.platform.system

import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.ImageMatch
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `images` 的宿主侧真实现（docs §9.2；SPI 见 `:domain` 的 [ImageAnalyzer]，
 * 语义层 handler 在 `:platform:capabilities` 的 `ImagesNamespaceHandler`）。
 * 像素计算全在 native（`libopencv.so`，OpenCV 4.14 静态链接，
 * `:bridge:image` 的 `imgnative.cpp`）；本类只做三件**本机 JVM 可测**的事：
 *
 * 1. **句柄发号**：[HandleRef.refId] 单调递增、generation 恒 1（一个文件一个帧，
 *    与 `ScreenshotSource`/`AndroidSensorSource` 同形）；native 帧号 →
 *    refId 的对照表住 [frames]（release 先删表再放 native：脚本侧说放了的帧，
 *    下一次匹配一定 STALE，不会出现"表里在场、native 已不在场"的窗口）。
 * 2. **错误码对表**：native 状态码（`imgnative.cpp` 文件头：0 OK / 1 STALE /
 *    2 FILE_NOT_FOUND / 3 IO）→ [ErrorCode]，**原码透传不折叠**（handler 要求
 *    调用方能分辨"路径错了"与"解码失败"，桥面 KDoc 判据 4）。
 * 3. **native 缺席时的诚实缺位**：so 不在 → [Unavailable]（装配层不喂分析器，
 *    桥对 `images.*` 如实 `ERR_NOT_IMPLEMENTED`），**绝不塞内存替身** ——
 *    看不见像素的"内存分析器"只能靠自报坐标假装命中（§9.2 / :domain KDoc）。
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

    /** refId → native 帧号（唯一持有帧所有权的地方；只在 [guard] 内读写）。 */
    private val guard = Any()
    private val frames = HashMap<Long, Long>()
    private var nextRefId = 1L

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
            val (nativeRef, width, height) = decoded
            val refId = synchronized(guard) {
                val id = nextRefId++
                frames[id] = nativeRef
                id
            }
            ImageFrame(HandleRef(refId, GENERATION), width, height)
        }
    }

    override suspend fun release(handle: HandleRef) = withContext(Dispatchers.IO) {
        if (handle.generation != GENERATION) {
            throw AutojsException(
                ErrorCode.ERR_STALE_HANDLE,
                "images 帧句柄代次不匹配：期望 $GENERATION，实际 ${handle.generation}",
            )
        }
        val nativeRef = synchronized(guard) { frames.remove(handle.refId) }
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
    ): ImageMatch? = match("matchTemplate", haystack, needle, threshold)

    override suspend fun findImage(
        haystack: HandleRef,
        needle: HandleRef,
        threshold: Double,
    ): ImageMatch? = match("findImage", haystack, needle, threshold)

    /**
     * 两方法同一个实现（契约：`findImage` 是 `matchTemplate` 的调用侧别名，
     * 阈值语义同）：把两个 refId 换成本机帧号，一次 native 匹配。
     * 域校验归桥面 handler（此处按契约假定已在 [0,1]）。
     */
    private suspend fun match(
        methodName: String,
        haystack: HandleRef,
        needle: HandleRef,
        threshold: Double,
    ): ImageMatch? = withContext(Dispatchers.IO) {
        val native = synchronized(guard) {
            val h = frames[haystack.refId]
            val n = frames[needle.refId]
            if (h == null || n == null) {
                throw AutojsException(
                    ErrorCode.ERR_STALE_HANDLE,
                    "images $methodName 的帧句柄已释放（haystack=${haystack.refId}, needle=${needle.refId}）",
                )
            }
            h to n
        }
        val status = IntArray(1)
        val hit = ops.match(native.first, native.second, threshold, status)
        val rc = status[0]
        when {
            hit != null -> hit
            rc != 0 -> throw statusToException(rc, "$methodName")
            // null + 状态码 0 = 未达阈值（答案，不是异常）；JNI 自身失败也在
            // 这一支，但那与 native 语义不可分辨 —— 不编造一个命中糊过去。
            else -> null
        }
    }

    /** native 状态码 → 分类错误（`imgnative.cpp` 文件头的对表，原码透传）。 */
    private fun statusToException(status: Int, what: String): AutojsException {
        val code = when (status) {
            STATUS_STALE -> ErrorCode.ERR_STALE_HANDLE
            STATUS_FILE_NOT_FOUND -> ErrorCode.ERR_FILE_NOT_FOUND
            else -> ErrorCode.ERR_IO
        }
        return AutojsException(code, "images native 拒绝 $what（status=$status）")
    }

    /**
     * native 接触面（README ops 表的本行）：真机 [JniOps]（`System.loadLibrary`
     * + 三个 external 方法）；单测注入的内存替身**可复现全部语义** ——
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
         * @return 命中五元组；**未命中** null + `status[0] == 0`（答案）；
         * 分类失败 null + status 非 0。
         */
        fun match(
            haystack: Long,
            needle: Long,
            threshold: Double,
            status: IntArray,
        ): ImageMatch?

        /** @return 0 = OK；1 = 未知/已释放的 native 帧号。 */
        fun release(nativeRef: Long): Int
    }

    companion object {
        /** 句柄代次：帧不复用，故恒 1（§7.4 generation 语义）。 */
        const val GENERATION: Long = 1

        // native 状态码（与 imgnative.cpp 文件头逐条对表；改必须同批）：0 = OK
        // 不进 [statusToException]；1/2 点名命名，其余非零码一律归 IO（上游加码
        // 时不至于被误读成"只此两种非零"）。
        const val STATUS_STALE = 1
        const val STATUS_FILE_NOT_FOUND = 2

        /**
         * 真机构造：so 缺位 → null（装配层 [SystemSpis] 不喂分析器，桥回
         * `ERR_NOT_IMPLEMENTED`）。**故意不为省一次判空去 try/catch loadLibrary**——
         * 装载失败就是这条缝不可用，装配层据此放弃注入比让脚本看到崩溃栈好。
         */
        fun of(ops: Ops?): ImageAnalyzer? = ops?.let { NativeImageAnalyzer(it) }
    }
}

/**
 * so 装载面（[NativeImageAnalyzer.Ops] 的真机实现）：`System.loadLibrary("opencv")`
 * 装载 `libopencv.so`（`:bridge:image` 产物）+ 三个 `external` native 方法。
 * 方法名与 `:bridge:image` 的 `images_jni.cc`
 * 的 `Java_com_autoscript_platform_system_NativeImageAnalyzer_*` 对表 ——
 * **换包名/换类名必须同批改那边**（JNI 符号名是字符串约定，编译器不看护）。
 *
 * loadLibrary 在**类初始化**时做（companion 之外的实例化都跑得到）：so 缺位
 * 抛 `UnsatisfiedLinkError`，由 [NativeImageAnalyzer.of] 的捕获转成"不注入"。
 * 装载只做一次（对象只在装配期建一次，`frames` 表随进程存活）。
 */
class JniOps : NativeImageAnalyzer.Ops {

    private external fun decodeNative(path: String, status: IntArray): LongArray?

    private external fun matchNative(
        haystack: Long,
        needle: Long,
        threshold: Double,
        status: IntArray,
    ): DoubleArray?

    private external fun releaseNative(nativeRef: Long): Int

    override fun decode(path: String, status: IntArray): Triple<Long, Int, Int>? {
        val r = decodeNative(path, status)
        return if (r == null || r.size < 3) null else Triple(r[0], r[1].toInt(), r[2].toInt())
    }

    override fun match(
        haystack: Long,
        needle: Long,
        threshold: Double,
        status: IntArray,
    ): ImageMatch? {
        val r = matchNative(haystack, needle, threshold, status)
        // 长度 5 = 命中；长度 0 = 未命中（答案）；null = native 自身失败（status 非 0）
        return when {
            r == null -> null
            r.isEmpty() -> null
            r.size >= 5 -> ImageMatch(
                x = r[0].toInt(),
                y = r[1].toInt(),
                width = r[2].toInt(),
                height = r[3].toInt(),
                confidence = r[4],
            )
            else -> null
        }
    }

    override fun release(nativeRef: Long): Int = releaseNative(nativeRef)

    companion object {
        /**
         * 装载 so。缺位/体系结构不符返回 null（装配层据此放弃注入）——
         * `UnsatisfiedLinkError` 是 Error 不是 Exception，普通 try/catch
         * 抓不到它，这里显式按 Throwable 收。
         */
        fun loadOrNull(): NativeImageAnalyzer.Ops? = try {
            System.loadLibrary("opencv")
            JniOps()
        } catch (_: Throwable) {
            null
        }
    }
}
