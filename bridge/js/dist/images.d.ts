/**
 * 截图与图像命名空间（docs/framework-design.md §9.2 / §8.8 / §12.2）：
 * screen.capture() → FrameSource 句柄（分类错误而非黑图：锁屏/FLAG_SECURE/
 * 无窗口/节流一律抛 ERR_*，见 Kotlin ScreenPolicy）；
 * images.decode/matchTemplate/findImage/release 走 native 分析面（§12.2 第七条独立缝，
 * Kotlin 对偶 `ImagesNamespaceHandler` + `:domain` `ImageAnalyzer`）。
 *
 * **两张桥面别混**：`screen.*` 是截图帧源（句柄由 `ScreenshotSource` 发号，`recycle`
 * 归它自己）；`images.*` 是图像分析面（句柄由 `decode` 从**文件**发号，`release` 归它）。
 * 两者句柄互不通用 —— 拿 `screen.capture()` 的帧去 `images.findImage()` 只会得到
 * `ERR_STALE_HANDLE`（handler 的说法：这张帧不在我的在场面表里）。
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
    /**
     * 从文件读一帧（`decode`，§9.2）：回 `{ref,width,height}` 帧句柄 —— 宽高是**文件真值**
     * （脚本要拿它做坐标换算）。路径不得空白；文件缺失/不是合法图片由宿主原码透传
     * （`ERR_FILE_NOT_FOUND`/`ERR_IO`，不折叠成参数错）。
     *
     * 帧是**文件侧**的句柄：`recycle()` 打 `images/release`（不是 `screen/recycle`）。
     */
    decode(path: string, opts?: {
        timeout?: number;
    }): Promise<FrameSource>;
    /**
     * 从文件读图（`decode` 的 v9 名：Pro 侧叫 `fromFile`）。保留此别名是为了脚本可读性，
     * wire 上仍是 `decode`（两侧同名，不搞两套方法名）。
     *
     * 别名只此一个：`load`/`open`/`read`/`bitmap` 一律不提供 —— 宿主侧同样只认 `decode`。
     */
    fromFile(path: string, opts?: {
        timeout?: number;
    }): Promise<FrameSource>;
    /**
     * 模板匹配（`matchTemplate`）：在 `haystack` 帧里找 `needle` 帧，置信度 ≥ `threshold` 即命中。
     * **两帧都必须是 `images.decode` 出来的句柄**（`screen.capture()` 的帧不通用 → STALE）。
     *
     * 未匹配**不是异常**：回 `null`（图里没有达到阈值的位置）。帧已释放 →
     * `ERR_STALE_HANDLE`；阈值缺省 `0.9`（v9 同名默认值；域 `[0,1]` 之外 →
     * `ERR_INVALID_PARAM` 且一次匹配都不发）。
     */
    matchTemplate(haystack: FrameSource, needle: FrameSource, opts?: {
        threshold?: number;
        timeout?: number;
    }): Promise<MatchResult | null>;
    /**
     * 找图（`findImage`，threshold 语义/缺省同 [matchTemplate]）。
     * v9 的两个名字是同一个 opencv 概念：wire 形状逐字段相同，宿主侧同一套校验。
     */
    findImage(haystack: FrameSource, needle: FrameSource, opts?: {
        threshold?: number;
        timeout?: number;
    }): Promise<MatchResult | null>;
    /**
     * 释放 `decode` 出来的帧。首次释放回 `true`；**再放同一帧 → `ERR_STALE_HANDLE`**
     * （不是静默成功也不是内部错 —— 未知/跨代同码，"已释放"与"从未存在"由这句 detail 可辨）。
     * 所以脚本 `finally` 里的补刀要自己兜这个码（或只放一次）。帧对象自带的
     * `recycle()` 就是转发到这里。
     */
    release(frame: FrameSource, opts?: {
        timeout?: number;
    }): Promise<void>;
};
