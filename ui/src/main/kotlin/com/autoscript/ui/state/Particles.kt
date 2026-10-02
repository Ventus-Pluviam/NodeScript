package com.autoscript.ui.state

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 一颗粒子的**初值**：起始位置（相对所在行的左上角）、初速、大小、起始不透明度。
 *
 * 位置随时间的变化不写在这里 —— 那是 [Particles.at] 的事。分开的理由是绘制要按
 * 进度反复求值，而初值只算一次：一次爆开的几百颗粒子，每帧只做乘法与加法。
 */
data class Particle(
    val startX: Float,
    val startY: Float,
    val vx: Float,
    val vy: Float,
    val size: Float,
    val alpha: Float,
)

/** 某颗粒子在某个进度上的样子。 */
data class ParticleAt(val center: Offset, val size: Float, val alpha: Float)

/**
 * 行被删掉时那一下"散开"的粒子物理。
 *
 * **参考的是 Telegram 的 `ThanosEffect`**（`Components/ThanosEffect.java`）：它把这一行的
 * 位图切成网格（`calcParticlesGrid`），每格一颗粒子，着色器里给一个初速、往上飘、淡出，
 * `longevity = 1.5f`。**这里没有照搬的部分要说清楚**：它走 OpenGL ES 3.1 的纹理着色器，
 * 粒子是**行自己的像素**；本仓不引 GL 层，粒子是纯色小块 —— 形状（往上一簇、扩散、
 * 淡出）一样，"散开的是这行的像素"这一点不一样。
 *
 * 落地位置刻意选在 `state/`：这是**可以判定对错**的纯函数（起点在不在行内、有没有向上初速、
 * 结束时是不是都淡完了），故放这里吃 JVM 单测；`components/` 里的绘制层只负责把
 * [at] 的结果画出来，不含任何判断。
 */
object Particles {

    /** 一簇粒子的寿命（毫秒）。TG 那边是 1.5s 量级，这里压短：本仓的"行消失"是列表操作，不是删消息。 */
    const val DURATION_MILLIS = 720

    /** 重力（像素/秒²）。初速向上、重力向下 —— 于是先窜起、再减速，淡出发生在回落之前。 */
    const val GRAVITY = 1500f

    /** 单簇粒子上限：行越高颗粒越多，但不能让一行把整屏刷满。 */
    const val MAX_PARTICLES = 160

    /** 每 1000 平方像素铺几颗（0.02 ≈ 每行 60–120 颗，与 TG 的网格密度同一量级）。 */
    private const val DENSITY_PER_SQ_PX = 0.02f

    /**
     * 按行的尺寸铺一簇粒子。
     *
     * @param rect 行原来的位置（屏幕坐标）；粒子起点铺满这个矩形，故"散开的位置就是行原来的位置"。
     * @param count 期望颗粒数；0 或负数返回空（调用方据此跳过整条通路）。
     * @param seed 随机种子 —— **必须传**，同一 seed 必得同一簇（这样它能被单测钉住）。
     */
    fun burst(rect: Rect, count: Int, seed: Long): List<Particle> {
        if (count <= 0 || rect.width <= 0f || rect.height <= 0f) return emptyList()
        val n = minOf(count, MAX_PARTICLES)
        val rnd = Random(seed)
        return List(n) {
            Particle(
                // 起点在行内均匀铺开：网格 + 抖动（纯随机会有肉眼可见的结块与空隙）。
                startX = rect.width * ((it % 12) + 0.15f + rnd.nextFloat() * 0.7f) / 12f,
                startY = rect.height * ((it / 12) + 0.15f + rnd.nextFloat() * 0.7f) /
                    maxOf(1f, (n + 11) / 12f),
                // 初速：向上为主（-260..-620），横向小范围扩散（-150..150）。
                vx = (rnd.nextFloat() - 0.5f) * 300f,
                vy = -(260f + rnd.nextFloat() * 360f),
                size = 1.5f + rnd.nextFloat() * 2.5f,
                alpha = 0.55f + rnd.nextFloat() * 0.45f,
            )
        }
    }

    /** 按行尺寸推一个合理的颗粒数（绘制层用它算 [burst] 的 count）。 */
    fun countFor(rect: Rect): Int =
        (rect.width * rect.height * DENSITY_PER_SQ_PX).toInt().coerceIn(0, MAX_PARTICLES)

    /**
     * 求某颗粒子在 `progress`（0..1）时的样子。
     *
     * 位置 = 起点 + 初速·t + ½·重力·t²（水平无加速度）；不透明度线性淡出；
     * 尺寸略缩（0.85 倍），因为"散开"的东西同时变小才像碎屑而不是气球。
     */
    fun at(p: Particle, rect: Rect, progress: Float): ParticleAt {
        val k = progress.coerceIn(0f, 1f)
        val t = k * DURATION_MILLIS / 1000f
        return ParticleAt(
            center = Offset(
                x = rect.left + p.startX + p.vx * t,
                y = rect.top + p.startY + p.vy * t + 0.5f * GRAVITY * t * t,
            ),
            size = p.size * (1f - 0.15f * k),
            alpha = p.alpha * (1f - k),
        )
    }

    /** 一簇粒子在 `progress` 时还剩多少颗可见（供单测断言"末尾全灭"）。 */
    fun alive(p: Particle, rect: Rect, progress: Float): Boolean =
        at(p, rect, progress).alpha > 0.01f

    /**
     * 「这一次比上一次**少了哪些** id」—— 粒子该炸哪几行的判据。
     *
     * 收在这里而不是写在绘制层，是因为这条判定有几处容易写错、且**错法都不报错**：
     * - 首帧（`prev` 为空）必须返回空 —— 否则进页面就把整屏行当成"刚被删掉"，满屏粒子；
     * - 重复 id 只炸一次（`distinct`）—— 同一个 id 在一份列表里出现两遍时，否则炸两簇；
     * - 消失的 id 按 `prev` 的顺序返回 —— 连删多行时，先消失的先炸。
     *
     * @param prev 上一次的 id 快照；空表示"还没有上一次"。
     * @param now 这一次的 id 快照。
     */
    fun vanished(prev: List<String>, now: List<String>): List<String> {
        if (prev.isEmpty()) return emptyList()
        val live = now.toSet()
        return prev.filterNot { it in live }.distinct()
    }

    /** 位移速度（像素/秒）—— 只给单测读，绘制层不需要。 */
    internal fun speed(p: Particle): Float = sqrt(p.vx * p.vx + p.vy * p.vy)
}
