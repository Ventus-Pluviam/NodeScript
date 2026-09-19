/**
 * 截图与图像命名空间（docs/framework-design.md §9.2 / §8.8 / §12.3）：
 * screen.capture() → FrameSource 句柄（分类错误而非黑图：锁屏/FLAG_SECURE/
 * 无窗口/节流一律抛 ERR_*，见 Kotlin ScreenPolicy）；images.findImage /
 * matchTemplate 走 native（P1）。
 */
/**
 * 帧句柄（§7.4）：JS 侧持 HandleRef 代理；recycle 释放（比 GC 优先，§7.4 引用计数对象）。
 * width/height 是对齐 :domain:automation.ImageFrame 的数据字段（capture/fromFile 回包直接携带），
 * 非方法（facade 回包是普通对象，不做 getter 桥调用）。
 */
export interface FrameSource {
    readonly ref: {
        refId: number;
        generation: number;
    };
    readonly width: number;
    readonly height: number;
    /** 显式释放：比 GC 优先（对齐 Image.recycle 语义）。 */
    recycle(opts?: {
        timeout?: number;
    }): Promise<void>;
}
/** 图像模板匹配结果（对齐 opencv 坐标/置信度语义）。 */
export interface MatchResult {
    x: number;
    y: number;
    width: number;
    height: number;
    confidence: number;
}
export declare const screen: {
    /**
     * 截图（§9.2）：a11y takeScreenshot（333ms 节流）/ MediaProjection 会话。
     * 分类错误直接抛（§8.8）：锁屏 → ERR_SCREEN_LOCKED，FLAG_SECURE → ERR_BLACK_FRAME，
     * 无窗口/空帧 → ERR_SERVICE_DISABLED，节流命中 → ERR_INVALID_PARAM（退避重试）。
     * 成功回帧句柄 `{ref:{refId,generation},width,height}` + `recycle()` 显式释放。
     */
    capture(opts?: {
        timeout?: number;
        signal?: AbortSignal;
    }): Promise<FrameSource>;
    /**
     * 会话式截屏（ScreenCapturer，§9.2 MediaProjection）：
     * open 时即做策略判定（锁屏等直接 Err，不发空会话）；`nextFrame()` 取帧，
     * `close()` 关闭（会话是连接态，二次关如实报 ERR_NOT_FOUND）。
     */
    startCapturer(opts?: {
        width?: number;
        height?: number;
        timeout?: number;
    }): Promise<ScreenCapturer>;
};
/** 会话式截图器（§9.2；open/close 生命周期，帧经 nextFrame 拉取）。 */
export interface ScreenCapturer {
    readonly session: {
        refId: number;
        generation: number;
    };
    nextFrame(opts?: {
        timeout?: number;
    }): Promise<FrameSource>;
    close(opts?: {
        timeout?: number;
    }): Promise<void>;
}
export declare const images: {
    /** 从文件读图（native 0 拷贝）。 */
    fromFile(path: string, opts?: {
        timeout?: number;
    }): Promise<FrameSource>;
    /** 模板匹配（P1 native opencv；P0 抛 ERR_NOT_IMPLEMENTED）。 */
    matchTemplate(_haystack: FrameSource, _needle: FrameSource, opts?: {
        tolerance?: number;
        timeout?: number;
    }): Promise<MatchResult | null>;
    /** 找图（native；threshold 对齐 Pro v9）。 */
    findImage(_haystack: FrameSource, _needle: FrameSource, opts?: {
        threshold?: number;
        timeout?: number;
    }): Promise<MatchResult | null>;
};
