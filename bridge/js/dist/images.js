"use strict";
/**
 * 截图与图像命名空间（docs/framework-design.md §9.2 / §8.8 / §12.3）：
 * screen.capture() → FrameSource 句柄（分类错误而非黑图：锁屏/FLAG_SECURE/
 * 无窗口/节流一律抛 ERR_*，见 Kotlin ScreenPolicy）；images.findImage /
 * matchTemplate 走 native（P1）。
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.images = exports.screen = void 0;
const runtime_1 = require("./runtime");
exports.screen = {
    /**
     * 截图（§9.2）：a11y takeScreenshot（333ms 节流）/ MediaProjection 会话。
     * 分类错误直接抛（§8.8）：锁屏 → ERR_SCREEN_LOCKED，FLAG_SECURE → ERR_BLACK_FRAME，
     * 无窗口/空帧 → ERR_SERVICE_DISABLED，节流命中 → ERR_INVALID_PARAM（退避重试）。
     * 成功回帧句柄 `{ref:{refId,generation},width,height}` + `recycle()` 显式释放。
     */
    async capture(opts = {}) {
        const raw = (await runtime_1.runtimeBridge.invoke('screen', 'capture', null, {
            ttl: opts.timeout ?? 10_000,
            signal: opts.signal,
        }));
        return wrapFrame(raw);
    },
    /**
     * 会话式截屏（ScreenCapturer，§9.2 MediaProjection）：
     * open 时即做策略判定（锁屏等直接 Err，不发空会话）；`nextFrame()` 取帧，
     * `close()` 关闭（会话是连接态，二次关如实报 ERR_NOT_FOUND）。
     */
    async startCapturer(opts = {}) {
        const raw = (await runtime_1.runtimeBridge.invoke('screen', 'startCapturer', {
            width: opts.width,
            height: opts.height,
        }, { ttl: opts.timeout ?? 10_000 }));
        return wrapCapturer(raw.session);
    },
};
function wrapCapturer(session) {
    return {
        session,
        nextFrame: async (opts = {}) => {
            const raw = (await runtime_1.runtimeBridge.invoke('screen', 'nextFrame', { session }, {
                ttl: opts.timeout ?? 10_000,
            }));
            return wrapFrame(raw);
        },
        close: async (opts = {}) => {
            await runtime_1.runtimeBridge.invoke('screen', 'closeSession', { session }, {
                ttl: opts.timeout ?? 10_000,
            });
        },
    };
}
/** 帧句柄代理：capture/nextFrame 回包是纯数据，recycle 经 invoke 回桥（幂等，fire-and-forget 不适用——要确认释放）。 */
function wrapFrame(raw) {
    const ref = raw.ref;
    return {
        ref,
        width: raw.width,
        height: raw.height,
        recycle: async (opts = {}) => {
            await runtime_1.runtimeBridge.invoke('screen', 'recycle', { ref }, { ttl: opts.timeout ?? 5_000 });
        },
    };
}
exports.images = {
    /** 从文件读图（native 0 拷贝）。 */
    async fromFile(path, opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('images', 'fromFile', { path }, { ttl: opts.timeout ?? 10_000 }));
    },
    /** 模板匹配（P1 native opencv；P0 抛 ERR_NOT_IMPLEMENTED）。 */
    async matchTemplate(_haystack, _needle, opts = {}) {
        // P0：native 图像管线在 :bridge:image P1；如实上报 ERR_NOT_IMPLEMENTED（§1 诚实质疑）。
        return runtime_1.runtimeBridge.invoke('images', 'matchTemplate', {
            tolerance: opts.tolerance,
        }, { ttl: opts.timeout ?? 10_000 });
    },
    /** 找图（native；threshold 对齐 Pro v9）。 */
    async findImage(_haystack, _needle, opts = {}) {
        return runtime_1.runtimeBridge.invoke('images', 'findImage', {
            threshold: opts.threshold,
        }, { ttl: opts.timeout ?? 10_000 });
    },
};
