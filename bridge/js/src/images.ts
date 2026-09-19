/**
 * 截图与图像命名空间（docs/framework-design.md §9.2 / §8.8 / §12.3）：
 * screen.capture() → FrameSource 句柄（分类错误而非黑图：锁屏/FLAG_SECURE/
 * 无窗口/节流一律抛 ERR_*，见 Kotlin ScreenPolicy）；images.findImage /
 * matchTemplate 走 native（P1）。
 */

import { runtimeBridge } from './runtime'

/**
 * 帧句柄（§7.4）：JS 侧持 HandleRef 代理；recycle 释放（比 GC 优先，§7.4 引用计数对象）。
 * width/height 是对齐 :domain:automation.ImageFrame 的数据字段（capture/fromFile 回包直接携带），
 * 非方法（facade 回包是普通对象，不做 getter 桥调用）。
 */
export interface FrameSource {
  readonly ref: { refId: number; generation: number }
  readonly width: number
  readonly height: number
  /** 显式释放：比 GC 优先（对齐 Image.recycle 语义）。 */
  recycle(opts?: { timeout?: number }): Promise<void>
}

/** 图像模板匹配结果（对齐 opencv 坐标/置信度语义）。 */
export interface MatchResult {
  x: number
  y: number
  width: number
  height: number
  confidence: number
}

export const screen = {
  /**
   * 截图（§9.2）：a11y takeScreenshot（333ms 节流）/ MediaProjection 会话。
   * 分类错误直接抛（§8.8）：锁屏 → ERR_SCREEN_LOCKED，FLAG_SECURE → ERR_BLACK_FRAME，
   * 无窗口/空帧 → ERR_SERVICE_DISABLED，节流命中 → ERR_INVALID_PARAM（退避重试）。
   * 成功回帧句柄 `{ref:{refId,generation},width,height}` + `recycle()` 显式释放。
   */
  async capture(opts: { timeout?: number; signal?: AbortSignal } = {}): Promise<FrameSource> {
    const raw = (await runtimeBridge.invoke('screen', 'capture', null, {
      ttl: opts.timeout ?? 10_000,
      signal: opts.signal,
    })) as { ref: { refId: number; generation: number }; width: number; height: number }
    return wrapFrame(raw)
  },

  /**
   * 会话式截屏（ScreenCapturer，§9.2 MediaProjection）：
   * open 时即做策略判定（锁屏等直接 Err，不发空会话）；`nextFrame()` 取帧，
   * `close()` 关闭（会话是连接态，二次关如实报 ERR_NOT_FOUND）。
   */
  async startCapturer(opts: { width?: number; height?: number; timeout?: number } = {}): Promise<ScreenCapturer> {
    const raw = (await runtimeBridge.invoke('screen', 'startCapturer', {
      width: opts.width,
      height: opts.height,
    }, { ttl: opts.timeout ?? 10_000 })) as { session: { refId: number; generation: number } }
    return wrapCapturer(raw.session)
  },
}

/** 会话式截图器（§9.2；open/close 生命周期，帧经 nextFrame 拉取）。 */
export interface ScreenCapturer {
  readonly session: { refId: number; generation: number }
  nextFrame(opts?: { timeout?: number }): Promise<FrameSource>
  close(opts?: { timeout?: number }): Promise<void>
}

function wrapCapturer(session: { refId: number; generation: number }): ScreenCapturer {
  return {
    session,
    nextFrame: async (opts = {}) => {
      const raw = (await runtimeBridge.invoke('screen', 'nextFrame', { session }, {
        ttl: opts.timeout ?? 10_000,
      })) as { ref: { refId: number; generation: number }; width: number; height: number }
      return wrapFrame(raw)
    },
    close: async (opts = {}) => {
      await runtimeBridge.invoke('screen', 'closeSession', { session }, {
        ttl: opts.timeout ?? 10_000,
      })
    },
  }
}

/** 帧句柄代理：capture/nextFrame 回包是纯数据，recycle 经 invoke 回桥（幂等，fire-and-forget 不适用——要确认释放）。 */
function wrapFrame(raw: { ref: { refId: number; generation: number }; width: number; height: number }): FrameSource {
  const ref = raw.ref
  return {
    ref,
    width: raw.width,
    height: raw.height,
    recycle: async (opts = {}) => {
      await runtimeBridge.invoke('screen', 'recycle', { ref }, { ttl: opts.timeout ?? 5_000 })
    },
  }
}

export const images = {
  /** 从文件读图（native 0 拷贝）。 */
  async fromFile(path: string, opts: { timeout?: number } = {}): Promise<FrameSource> {
    return (await runtimeBridge.invoke('images', 'fromFile', { path }, { ttl: opts.timeout ?? 10_000 })) as FrameSource
  },

  /** 模板匹配（P1 native opencv；P0 抛 ERR_NOT_IMPLEMENTED）。 */
  async matchTemplate(
    _haystack: FrameSource,
    _needle: FrameSource,
    opts: { tolerance?: number; timeout?: number } = {},
  ): Promise<MatchResult | null> {
    // P0：native 图像管线在 :bridge:image P1；如实上报 ERR_NOT_IMPLEMENTED（§1 诚实质疑）。
    return runtimeBridge.invoke('images', 'matchTemplate', {
      tolerance: opts.tolerance,
    }, { ttl: opts.timeout ?? 10_000 }) as Promise<MatchResult | null>
  },

  /** 找图（native；threshold 对齐 Pro v9）。 */
  async findImage(
    _haystack: FrameSource,
    _needle: FrameSource,
    opts: { threshold?: number; timeout?: number } = {},
  ): Promise<MatchResult | null> {
    return runtimeBridge.invoke('images', 'findImage', {
      threshold: opts.threshold,
    }, { ttl: opts.timeout ?? 10_000 }) as Promise<MatchResult | null>
  },
}