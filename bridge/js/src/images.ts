/**
 * 截图与图像命名空间（docs/framework-design.md §9.2 / §8.8 / §12.2）：
 * screen.capture() → FrameSource 句柄（分类错误而非黑图：锁屏/FLAG_SECURE/
 * 无窗口/节流一律抛 ERR_*，见 Kotlin ScreenPolicy）；
 * images.decode/matchTemplate/findImage/findColor/release 走 native 分析面（§12.2 第七条独立缝，
 * Kotlin 对偶 `ImagesNamespaceHandler` + `:domain` `ImageAnalyzer`）。
 *
 * **两张桥面别混**：`screen.*` 是截图帧源（句柄由 `ScreenshotSource` 发号，`recycle`
 * 归它自己）；`images.*` 是图像分析面（句柄由 `decode` 从**文件**发号，`release` 归它）。
 * 两者句柄互不通用 —— 拿 `screen.capture()` 的帧去 `images.findImage()` 只会得到
 * `ERR_STALE_HANDLE`（handler 的说法：这张帧不在我的在场面表里）。
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

/**
 * 找色命中（§9.2 native 面第一个 P1 算子）：`x`/`y` 是**全帧坐标**（`region` 只是
 * 搜索范围不是坐标系）；`r`/`g`/`b`/`a` 是命中点的**实际像素分量** —— 不一定是调用方
 * 传进去的目标色逐字值（容差带内哪一个被扫到就回哪一个，拿它做二次判断看的是真值）。
 */
export interface ColorHitResult {
  x: number
  y: number
  r: number
  g: number
  b: number
  a: number
}

/** 找色可选搜索区域 `[x,y,w,h]`（缺省全帧；给了就必须整体落在帧内）。 */
export type Region4 = readonly [number, number, number, number]

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

/** 图像帧代理（`images.decode` 的回包）：同 [FrameSource] 形状，但 `recycle` 打 `images/release`。 */
function wrapImageFrame(raw: { ref: { refId: number; generation: number }; width: number; height: number }): FrameSource {
  const ref = raw.ref
  return {
    ref,
    width: raw.width,
    height: raw.height,
    recycle: async (opts = {}) => {
      await runtimeBridge.invoke('images', 'release', { ref }, { ttl: opts.timeout ?? 5_000 })
    },
  }
}

export const images = {
  /**
   * 从文件读一帧（`decode`，§9.2）：回 `{ref,width,height}` 帧句柄 —— 宽高是**文件真值**
   * （脚本要拿它做坐标换算）。路径不得空白；文件缺失/不是合法图片由宿主原码透传
   * （`ERR_FILE_NOT_FOUND`/`ERR_IO`，不折叠成参数错）。
   *
   * **路径写绝对路径**（§18 第 9 项 2026-09-25 已拍板：只收绝对路径）：**四层里没有一层解析路径** —— 计算核直接 `fopen`/`imread`，
   * 于是相对路径按**宿主进程 CWD** 解析，而 so 载在 `:main` 里、那个进程的 CWD 是 `/`。
   * `decode('part.png')` 会去根目录找一个并不存在的文件，**回 `ERR_FILE_NOT_FOUND`
   * 且报的路径是对的** —— 看起来像"文件真的不在"，不像"口径没定"。别写相对路径。
   *
   * 帧是**文件侧**的句柄：`recycle()` 打 `images/release`（不是 `screen/recycle`）。
   */
  async decode(path: string, opts: { timeout?: number } = {}): Promise<FrameSource> {
    const raw = (await runtimeBridge.invoke('images', 'decode', { path }, {
      ttl: opts.timeout ?? 10_000,
    })) as { ref: { refId: number; generation: number }; width: number; height: number }
    return wrapImageFrame(raw)
  },

  /**
   * 从文件读图（`decode` 的 v9 名：Pro 侧叫 `fromFile`）。保留此别名是为了脚本可读性，
   * wire 上仍是 `decode`（两侧同名，不搞两套方法名）。
   *
   * 别名只此一个：`load`/`open`/`read`/`bitmap` 一律不提供 —— 宿主侧同样只认 `decode`。
   *
   * v9 的 `fromFile('part.png')` 这种相对写法**在 AutoScript 眼下不成立**（同上：
   * 无路径解析，按 `:main` 的 CWD 走）。别名保留的是名字，不是相对路径语义。
   */
  async fromFile(path: string, opts: { timeout?: number } = {}): Promise<FrameSource> {
    return images.decode(path, opts)
  },

  /**
   * 模板匹配（`matchTemplate`）：在 `haystack` 帧里找 `needle` 帧，置信度 ≥ `threshold` 即命中。
   * **两帧都必须是 `images.decode` 出来的句柄**（`screen.capture()` 的帧不通用 → STALE）。
   *
   * 未匹配**不是异常**：回 `null`（图里没有达到阈值的位置）。帧已释放 →
   * `ERR_STALE_HANDLE`；阈值缺省 `0.9`（v9 同名默认值；域 `[0,1]` 之外 →
   * `ERR_INVALID_PARAM` 且一次匹配都不发）。
   */
  async matchTemplate(
    haystack: FrameSource,
    needle: FrameSource,
    opts: { threshold?: number; timeout?: number } = {},
  ): Promise<MatchResult | null> {
    const payload = await runtimeBridge.invoke('images', 'matchTemplate', {
      haystack: haystack.ref,
      needle: needle.ref,
      threshold: opts.threshold ?? 0.9,
    }, { ttl: opts.timeout ?? 10_000 })
    return payload as MatchResult | null
  },

  /**
   * 找图（`findImage`，threshold 语义/缺省同 [matchTemplate]）。
   * v9 的两个名字是同一个 opencv 概念：wire 形状逐字段相同，宿主侧同一套校验。
   */
  async findImage(
    haystack: FrameSource,
    needle: FrameSource,
    opts: { threshold?: number; timeout?: number } = {},
  ): Promise<MatchResult | null> {
    const payload = await runtimeBridge.invoke('images', 'findImage', {
      haystack: haystack.ref,
      needle: needle.ref,
      threshold: opts.threshold ?? 0.9,
    }, { ttl: opts.timeout ?? 10_000 })
    return payload as MatchResult | null
  },

  /**
   * 找色（`findColor`，§9.2 native 面第一个 P1 算子；§7.7 承诺 `findColor` 1080p < 10ms）：
   * 在 `haystack` 帧（或其 `region` 子矩形）里找**第一个**与 `color` 的**每个分量**
   * 差都不超过 `tolerance` 的像素，回它的全帧坐标与实际像素分量。
   *
   * 与 `matchTemplate` 的分界：那是"整块图案在哪"，这是"这个色在哪"—— 找色不问
   * 图案、形状、连通性，只看分量是否落在容差带内（native `inRange` 的逐分量包含语义）。
   *
   * 命中多个时回的是一个**稳定可复现**的坐标，但不承诺"离左上角最近"——要挑
   * 最近/最大连通域的脚本拿 x/y 自己再筛。
   *
   * **未命中是答案不是异常**：回 `null`（扫过了、没有），不编 `ERR_NOT_FOUND`。
   * 但**"扫过 0 像素"**（空区域/region 越界）是 `ERR_INVALID_PARAM` —— 那不是"没有"，
   * 是"根本没找"，混成 `null` 会让脚本把空区域当成搜过一遍。
   *
   * 参数域（越界一律 `ERR_INVALID_PARAM` 且一次 native 调用都不发）：
   * `color` 恒四分量 `[r,g,b,a]`（**R,G,B,A 序**，与 Android `0xAARRGGBB` 同序），
   * 各 `[0,255]`；`tolerance` `[0,255]`（逐分量，非欧氏距离）；`region` 给了必须四元组
   * 且整体落在帧内（不静默裁剪 —— 半截区域在帧外时"帧外的像素"没有答案）。
   */
  async findColor(
    haystack: FrameSource,
    color: readonly number[],
    tolerance: number,
    opts: { region?: Region4; timeout?: number } = {},
  ): Promise<ColorHitResult | null> {
    const payload = await runtimeBridge.invoke('images', 'findColor', {
      haystack: haystack.ref,
      color,
      tolerance,
      region: opts.region,
    }, { ttl: opts.timeout ?? 10_000 })
    return payload as ColorHitResult | null
  },

  /**
   * 释放 `decode` 出来的帧。首次释放回 `true`；**再放同一帧 → `ERR_STALE_HANDLE`**
   * （不是静默成功也不是内部错 —— 未知/跨代同码，"已释放"与"从未存在"由这句 detail 可辨）。
   * 所以脚本 `finally` 里的补刀要自己兜这个码（或只放一次）。帧对象自带的
   * `recycle()` 就是转发到这里。
   */
  async release(frame: FrameSource, opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke('images', 'release', { ref: frame.ref }, { ttl: opts.timeout ?? 5_000 })
  },
}
