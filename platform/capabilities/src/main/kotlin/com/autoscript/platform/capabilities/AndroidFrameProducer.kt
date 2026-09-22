package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.ScreenSnapshot

/**
 * 截图帧源的 Android 生产者（docs §9.2 a11y 截图路径 / §8.8 分类错误）。
 *
 * 纯转接零逻辑：采集前快照与截帧全部经 [A11yBridge]（缺省 [SystemA11yBridge]）——
 * 服务未连即抛 ERR_SERVICE_DISABLED，锁屏/无窗口由 [ScreenshotSource] 的
 * [com.autoscript.domain.automation.ScreenPolicy] 预检分类，安全窗/限频/通道失效由
 * 设备层回调码分类（殊途同归都到 §8.8 的分类错误，绝不给黑图）。
 *
 * 装配：`ScreenshotSource(AndroidFrameProducer())` → `CapabilityNamespaces.screen`
 * → `PlatformWiring.screenHandler`。MediaProjection 升级 = 换本类，语义面不动。
 */
class AndroidFrameProducer(
    private val bridge: A11yBridge = SystemA11yBridge,
) : ScreenshotSource.FrameProducer {

    override suspend fun snapshot(): ScreenSnapshot = bridge.screenSnapshot()

    override suspend fun produce(width: Int, height: Int): ProducedFrame =
        bridge.takeScreenshot() // 尺寸以系统为准，入参只是请求提示（见 FrameProducer KDoc）
}
