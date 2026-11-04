package com.autoscript.platform.system.images

import com.autoscript.domain.automation.ColorHit
import com.autoscript.domain.automation.FeatureHit
import com.autoscript.domain.automation.ImageMatch

/**
 * so 装载面（[NativeImageAnalyzer.Ops] 的真机实现）：`System.loadLibrary("opencv")`
 * 装载 `libopencv.so`（`:bridge:image` 产物）+ 十个 `external` native 方法（P1 五算子 补齐）。
 * 方法名与 `:bridge:image` 的 `images_jni.cc`
 * 的 `Java_com_autoscript_platform_system_images_JniOps_*` 对表 ——
 * **换包名/换类名必须同批改那边**（JNI 符号名是字符串约定，编译器不看护；
 * `bridge/js/test/jni-names.test.cjs` 钉的就是这条，曾抓到
 * cc 用 `NativeImageAnalyzer_` 而声明类是 `JniOps` 的对不上）。
 *
 * loadLibrary 在**类初始化**时做（companion 之外的实例化都跑得到）：so 缺位
 * 抛 `UnsatisfiedLinkError`，由 [NativeImageAnalyzer.of] 的捕获转成"不注入"。
 * 装载只做一次（对象只在装配期建一次，`frames` 表随进程存活）。
 */
class JniOps : NativeImageAnalyzer.Ops {

    private external fun decodeNative(path: String, status: IntArray): LongArray?

    private external fun ingestNative(rgba: ByteArray, width: Int, height: Int, status: IntArray): LongArray?

    private external fun matchNative(
        haystack: Long,
        needle: Long,
        threshold: Double,
        region: IntArray?,
        status: IntArray,
    ): DoubleArray?

    private external fun releaseNative(nativeRef: Long): Int

    private external fun colorNative(
        frame: Long,
        color: IntArray,
        tolerance: Int,
        region: IntArray?,
        status: IntArray,
    ): LongArray?

    private external fun grayNative(frame: Long, status: IntArray): LongArray?

    private external fun cropNative(frame: Long, region: IntArray, status: IntArray): LongArray?

    private external fun resizeNative(frame: Long, width: Int, height: Int, status: IntArray): LongArray?

    private external fun rotateNative(frame: Long, degrees: Double, status: IntArray): LongArray?

    private external fun featureNative(scene: Long, template: Long, status: IntArray): DoubleArray?

    override fun decode(path: String, status: IntArray): Triple<Long, Int, Int>? {
        val r = decodeNative(path, status)
        return if (r == null || r.size < 3) null else Triple(r[0], r[1].toInt(), r[2].toInt())
    }

    override fun ingest(
        rgba: ByteArray,
        width: Int,
        height: Int,
        status: IntArray,
    ): Triple<Long, Int, Int>? {
        val r = ingestNative(rgba, width, height, status)
        return if (r == null || r.size < 3) null else Triple(r[0], r[1].toInt(), r[2].toInt())
    }

    override fun match(
        haystack: Long,
        needle: Long,
        threshold: Double,
        region: IntArray?,
        status: IntArray,
    ): ImageMatch? {
        val r = matchNative(haystack, needle, threshold, region, status)
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

    /**
     * color：native 回 jlong[6]{x,y,r,g,b,a}（x = -1 = 扫过未命中，答案）；
     * null = status 非 0 的分类失败。Kotlin 侧只做数组拆箱，语义不在这层解释。
     */
    override fun color(
        nativeFrame: Long,
        color: IntArray,
        tolerance: Int,
        region: IntArray?,
        status: IntArray,
    ): ColorHit? {
        val r = colorNative(nativeFrame, color, tolerance, region, status)
        if (r == null || r.size < 6) return null
        if (r[0] < 0) return null   // 未命中哨兵：扫过了、没有（不是假命中）
        return ColorHit(
            x = r[0].toInt(),
            y = r[1].toInt(),
            r = r[2].toInt(),
            g = r[3].toInt(),
            b = r[4].toInt(),
            a = r[5].toInt(),
        )
    }

    override fun gray(nativeFrame: Long, status: IntArray): Triple<Long, Int, Int>? {
        val r = grayNative(nativeFrame, status)
        return if (r == null || r.size < 3) null else Triple(r[0], r[1].toInt(), r[2].toInt())
    }

    override fun crop(nativeFrame: Long, region: IntArray, status: IntArray): Triple<Long, Int, Int>? {
        val r = cropNative(nativeFrame, region, status)
        return if (r == null || r.size < 3) null else Triple(r[0], r[1].toInt(), r[2].toInt())
    }

    override fun resize(nativeFrame: Long, width: Int, height: Int, status: IntArray): Triple<Long, Int, Int>? {
        val r = resizeNative(nativeFrame, width, height, status)
        return if (r == null || r.size < 3) null else Triple(r[0], r[1].toInt(), r[2].toInt())
    }

    override fun rotate(nativeFrame: Long, degrees: Double, status: IntArray): Triple<Long, Int, Int>? {
        val r = rotateNative(nativeFrame, degrees, status)
        return if (r == null || r.size < 3) null else Triple(r[0], r[1].toInt(), r[2].toInt())
    }

    /**
     * feature：native 回 jdouble[3]{x,y,confidence}（模板中心）；空数组/`x < 0`
     * = 未匹配（答案）；null = status 非 0 的分类失败。
     */
    override fun feature(scene: Long, template: Long, status: IntArray): FeatureHit? {
        val r = featureNative(scene, template, status)
        if (r == null || r.size < 3 || r[0] < 0) return null
        return FeatureHit(
            x = r[0].toInt(),
            y = r[1].toInt(),
            confidence = r[2],
        )
    }

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

