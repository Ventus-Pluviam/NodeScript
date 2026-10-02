package com.autoscript.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.autoscript.ui.state.Particle
import com.autoscript.ui.state.Particles

/** 一簇正在播的粒子。 */
private class Burst(
    val rect: Rect,
    val particles: List<Particle>,
    val color: Color,
) {
    /** 0 → 1 的播放进度；由 pager/列表之外的这一层自己推，不依赖任何业务状态。 */
    val progress = Animatable(0f)
}

/**
 * 行"消失"（取消任务、停止在途执行）时在原地炸一小簇粒子。
 *
 * **参考 Telegram 的 `ThanosEffect`**（`Components/ThanosEffect.java`）：删东西时那一行不是
 * 直接不见，而是散成粒子往上飘。**照搬不了的部分**：它把行位图切网格、走 OpenGL ES 3.1
 * 着色器，粒子是**行自己的像素**；本仓不引 GL 层，粒子是主题色的圆点 —— 形状对，
 * "散的是这行的像素"这一点不对。KDoc 里写清楚，免得后来的人以为这里是同一种实现。
 *
 * 用法（三步，缺一不可）：
 * 1. 列表容器用 `Box` 包住，[Overlay] 与之**同层**（同层才有同一套坐标）；
 * 2. 每行布局时 `Modifier.onGloballyPositioned { particles.place(id, it.boundsInParent()) }`；
 * 3. 列表数据变化时 `LaunchedEffect(ids) { particles.sync(ids, color) }`。
 *
 * [place] 用的是 `boundsInParent()`：LazyColumn 的项其父节点就是 LazyLayout，坐标原点
 * 与包裹它的那个 `Box` 左上角重合，故 [Overlay] 里直接按这份坐标画就对得上。
 */
class DeletionParticles internal constructor() {

    private val bounds = mutableStateMapOf<String, Rect>()
    private val live = mutableStateListOf<Burst>()
    private var known: List<String> = emptyList()

    /** 行每次布局都报告自己的位置。 */
    fun place(id: String, rect: Rect) {
        bounds[id] = rect
    }

    /**
     * 与上一次的 id 快照对比：**消失的那些**在原地炸一簇。
     *
     * 首帧不炸（没有"上一次"可比）；没有记录过位置的行也不炸
     * （它从没被画出过，没有"原地"可言）。
     */
    fun sync(ids: List<String>, color: Color) {
        val prev = known
        known = ids
        // 判据在 `state/Particles.vanished`（首帧不炸、重复只炸一次），这里只负责炸。
        for (gone in Particles.vanished(prev, ids)) {
            val rect = bounds.remove(gone) ?: continue
            val n = Particles.countFor(rect)
            if (n <= 0) continue
            live.add(Burst(rect, Particles.burst(rect, n, seed = gone.hashCode().toLong()), color))
            // 同时最多留几簇：连删十几个时不能把整屏铺满粒子（也就没人看得清列表了）。
            while (live.size > MAX_CONCURRENT) live.removeAt(0)
        }
    }

    /** 粒子层。**不吞触摸**（Box 上没有任何手势修饰符），列表照常能滑。 */
    @Composable
    fun Overlay(modifier: Modifier = Modifier) {
        Box(modifier) {
            for (burst in live) {
                key(burst) {
                    BurstLayer(burst) { live.remove(burst) }
                }
            }
        }
    }

    private companion object {
        const val MAX_CONCURRENT = 4
    }
}

@Composable
private fun BurstLayer(burst: Burst, onDone: () -> Unit) {
    LaunchedEffect(burst) {
        burst.progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(Particles.DURATION_MILLIS, easing = LinearEasing),
        )
        onDone()
    }
    Canvas(Modifier.fillMaxSize()) {
        val k = burst.progress.value
        for (p in burst.particles) {
            val s = Particles.at(p, burst.rect, k)
            if (s.alpha <= 0.01f) continue
            drawCircle(
                color = burst.color.copy(alpha = burst.color.alpha * s.alpha),
                radius = s.size,
                center = s.center,
            )
        }
    }
}

/** 一次页面（一个屏）一份粒子账，跟着该屏的列表走。 */
@Composable
fun rememberDeletionParticles(): DeletionParticles = remember { DeletionParticles() }
