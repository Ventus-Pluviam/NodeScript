package com.autoscript.domain.automation

import com.autoscript.domain.bridge.HandleRef

/**
 * 图像分析契约（docs/framework-design.md §9.2；JS 对偶 `auto.images`，
 * 对标 AutoJsPro v9 `images.matchTemplate/findImage` + `@autojs/opencv`）。
 *
 * 为什么住 `:domain`：与 [FrameSource] / `com.autoscript.domain.system.SensorSource`
 * 同一套理由 —— 真实现要碰 native 管线（`libopencv.so`，OpenCV 4.x）与
 * `BitmapFactory`，§6 要求 `:platform:*` 只依赖 `:domain`；「拿什么帧、算什么」
 * 与「像素在哪、谁来遍历」切开，桥面 handler 才是纯 JVM 可测的。
 *
 * **P0 范围钉死在三个操作**（刻意不预支的面，逐条给理由）：
 * - **只有 `decode`/`matchTemplate`/`findImage`**（+ 对称的 [release]）：§9.2 管线图里的
 *   灰度/裁剪/缩放/旋转/找色/特征(ORB) 全在 `libopencv.so`（P1）—— 那些操作**没有**
 *   脚本消费方之前不开桥面。§12.3 文档示例里出现的 `captureScreen()`/`toGrayscale()`
 *   因此同步改写真形态（截图归 `auto.screen`，灰度归 P1 的 native 面）。
 * - **P1 第一个算子 = [findColor]**（2026-09-25 落地，§7.7 有量化承诺的那一条）：
 *   灰度/裁剪/缩放/旋转/特征静候消费方，唯独找色在 §15 性能表上挂着一行
 *   `找色 < 10ms` **却没有任何实现在背后**—— 预算表不是愿望清单，先还这笔账。
 *   落地范围刻意窄：单色 + 逐分量容差 + 可选区域 + 回第一个命中，见该方法 KDoc。
 * - **两个匹配方法同一个阈值键 `threshold`**：facade 曾一个发 `tolerance` 一个发
 *   `threshold` —— 同一个 opencv 概念（TM_CCORR_NORMED 得分 ≥ 阈值即命中）两个键名，
 *   两侧 mock 各自自洽所以漂移没被抓到。契约侧钉死一个名字，宿主不认的键不静默丢弃。
 * - **阈值域 [0,1]**（越界 → `ERR_INVALID_PARAM`，handler 校验；实现不再各自宽严不一）；
 *   置信度同域，与阈值可直接比较（不同实现的方法差异是实现细节，域是契约）。
 *
 * **帧的所有权归本 SPI**（与 `FrameSource.recycle` 的分界）：[decode] 发号
 * （[HandleRef.refId] 单调递增、[HandleRef.generation] 恒 1 —— 一个文件一个帧，
 * 不复用不缓存：缓存会让两个 refId 指向同一份像素，释放一个另一个即成野句柄），
 * 帧的 width/height **只在 decode 回包随帧给脚本**（脚本要拿真尺寸做坐标换算）。
 * 匹配 API 不再要求回传尺寸 —— 那是对实现报它自己已知的值，传了就是漂移面。
 *
 * **句柄纪律**（§7.4，与 `FloatingWindowHost`/`ScreenshotSource` 同形）：**未知/跨代**
 * 抛 `ERR_STALE_HANDLE`（"没见过的帧"）；**已释放的帧再放**同样 `ERR_STALE_HANDLE`
 * （与 `ScreenshotSource.recycle` 逐字同口径：放掉即从在场面表移除 —— 不提供
 * "静默成功"的第二次）。所以"重复放不炸"对脚本的含义是**同一个 finally 不会抛**：
 * 帧只要还握着（没放），怎么放都回 true；放过了就是不在场，如实 STALE。
 * 匹配时任一参数句柄已死 → 同码。
 *
 * **未匹配是答案不是异常**：`matchTemplate`/`findImage` 回 `null` = 屏上/图里没有
 * 达到阈值的位置 —— 调用方据此走自己的分支，不编 `ERR_NOT_FOUND`（那是 UiSelector 的语义）。
 *
 * **文件缺失是分类错误**：路径不存在 → `ERR_FILE_NOT_FOUND`（不是空帧、不是 null）——
 * 路径解析（相对项目根 or filesDir）由实现定，契约只要求"找到文件或如实说没有"。
 *
 * **真机实现缺席时由装配层不注入**（桥回 `ERR_NOT_IMPLEMENTED`），**绝不塞凑数实现**：
 * 一个看不见像素的"内存分析器"只能靠自报坐标假装匹配成功 —— 那比没有更坏
 * （脚本会照着假坐标点下去）。所以本 SPI 不提供内存替身，`InMemory*` 那套不适用。
 */
interface ImageAnalyzer {

    /**
     * 从文件解码一帧（native 0 拷贝；内容 opaque）。
     * @throws IllegalArgumentException 空白路径（handler 折 `ERR_INVALID_PARAM`）。
     * @throws com.autoscript.domain.core.AutojsException `ERR_FILE_NOT_FOUND` 路径
     * 不存在；`ERR_IO` 解码失败（不是合法图片）。
     */
    suspend fun decode(path: String): ImageFrame

    /**
     * 显式释放帧句柄（幂等；JS `FrameSource.recycle` 对偶）。
     * @throws com.autoscript.domain.core.AutojsException `ERR_STALE_HANDLE` 未知/跨代句柄。
     */
    suspend fun release(handle: HandleRef)

    /**
     * 模板匹配：在 [haystack] 里找 [needle]，得分 ≥ [threshold] 即命中。
     * @throws com.autoscript.domain.core.AutojsException `ERR_STALE_HANDLE` 任一帧已死。
     */
    suspend fun matchTemplate(haystack: HandleRef, needle: HandleRef, threshold: Double): ImageMatch?

    /**
     * 找图（[matchTemplate] 的调用侧别名，阈值语义同）：名字对齐 Pro v9 的 `findImage`。
     * @throws com.autoscript.domain.core.AutojsException `ERR_STALE_HANDLE` 任一帧已死。
     */
    suspend fun findImage(haystack: HandleRef, needle: HandleRef, threshold: Double): ImageMatch?

    /**
     * 找色（§9.2 native 面第一个 P1 算子；§7.7 承诺 `findColor` 1080p < 10ms）：
     * 在 [haystack]（或其 [region] 子矩形）里找**第一个**与 [color] 的分量差各不
     * 超过 [tolerance] 的像素，回它的全帧坐标与实际像素分量。
     *
     * 与 matchTemplate 的分界：那是"整块图案在哪"，这是"这个色在哪"—— 找色不问
     * 图案、形状、连不连通，只看分量是否落在容差带内（OpenCV `inRange` 的逐分量
     * 包含语义，见 `imgnative_color`）。
     *
     * **未命中是答案不是异常**：回 `null`（扫过了、没有），**不编** `ERR_NOT_FOUND`。
     * 与"区域扫过 0 像素"（空图/空区域 → `ERR_INVALID_PARAM`）不是一回事。
     *
     * @param color 目标色四分量 `[r,g,b,a]`，各 ∈ `[0,255]`（**序由字节序解释为
     *   R,G,B,A**，与 Android `Bitmap` 的 `0xAARRGGBB` 同序，不另立一套）；
     * @param tolerance 逐分量容差 ∈ `[0,255]`（非欧氏距离：每个分量各自带 ±tolerance
     *   的上下界，落在带内即命中）；
     * @param region 可选子矩形 `[x,y,w,h]`：给了就必须**整体**落在帧内（越界 →
     *   `ERR_INVALID_PARAM`，不静默裁剪成"只看得到的那半"——那会让脚本以为扫过全区域）；
     *   缺省 null = 全帧。命中坐标是**全帧坐标**（区域只是搜索范围，不是坐标系）。
     * @throws IllegalArgumentException 分量/容差越界或 region 形状不成立
     *   （handler 折 `ERR_INVALID_PARAM`）。
     * @throws com.autoscript.domain.core.AutojsException `ERR_STALE_HANDLE` 帧已死；
     *   `ERR_INVALID_PARAM` 区域越出帧界；`ERR_IO` native 拒收。
     */
    suspend fun findColor(
        haystack: HandleRef,
        color: List<Int>,
        tolerance: Int,
        region: List<Int>? = null,
    ): ColorHit?
}

/**
 * 一次匹配命中（左上角坐标 + 尺寸 + 置信度；对齐 opencv 语义）。
 * `width/height` 是**模板在画面里被匹配上的区域尺寸**（通常 = 模板尺寸），不是画面尺寸。
 */
data class ImageMatch(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val confidence: Double,
)

/**
 * 一次找色命中（§9.2 native 面 P1 第一个算子）。
 *
 * `x`/`y` 是**全帧坐标**（`region` 只是搜索范围，不是坐标系）；`r`/`g`/`b`/`a`
 * 是命中点的**实际像素分量**（不一定是目标色的逐字值 —— 容差带内的哪一个被扫到
 * 就回哪一个，脚本要拿它做二次判断时看的是真值，不是自己传进去的期望）。
 */
data class ColorHit(
    val x: Int,
    val y: Int,
    val r: Int,
    val g: Int,
    val b: Int,
    val a: Int,
)
