'use strict'
/**
 * images 双侧契约测试（§9.2 / §12.2 第七条独立缝 + Kotlin ImagesNamespaceHandlerTest）：
 * mock 宿主**逐字复刻** Kotlin handler 的回包，钉住七处最易两侧走偏的地方：
 * 1. wire 形状：decode 发 `{path}` → `{ref,width,height}`（宽高是文件真值）；
 *    matchTemplate/findImage 发 `{haystack:{...},needle:{...},threshold}` → 命中体或裸 null；
 *    release 发 `{ref}` → true；
 * 2. **阈值一个键 `threshold`**（facade 曾一个发 `tolerance` 一个发 `threshold`，
 *    两侧 mock 各自自洽所以漂移没被抓到）—— 两方法 wire 载荷必须逐字段相同；
 * 3. 两张桥面别混：`screen.capture()` 的帧拿去 findImage → 宿主 ERR_STALE_HANDLE；
 *    `decode` 帧的 `recycle()` 打的是 `images/release`（不是 `screen/recycle`）；
 * 4. 未匹配**是答案**：`null`，不编 ERR_NOT_FOUND；
 * 5. 错误不折叠：ERR_FILE_NOT_FOUND / ERR_IO 原码透传（不是参数错）；
 * 6. 帧句柄纪律：未知/跨代/放过的帧再放 → ERR_STALE_HANDLE（放掉即从在场面表移除，
 *    与 ScreenshotSource.recycle 同口径）、匹配用已释放的帧 → ERR_STALE_HANDLE；
 * 7. 别名不猜：toGrayscale/crop/pixel/captureScreen/rotate → ERR_NOT_IMPLEMENTED。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default

/** mock images 宿主：句柄表/发号逐字复刻 ImagesNamespaceHandler（只经 guard 串行化）。 */
let installed = false
const mock = { files: {}, miss: false, fail: null }
function installMockImages() {
  if (installed) return
  installed = true
  const seen = []
  installMockImages.seen = seen
  const live = new Map()          // refId → { width, height }
  const sizes = new Map()
  let nextRefId = 1
  // screen 桥面（同一宿主、另一套句柄表）：帧发号从 100 起 —— 与 images 的 1 起是两张表
  let nextScreenRef = 100
  const screenLive = new Map()
  const handleScreen = (method, p, reqId) => {
    const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
    const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
    if (method === 'capture') {
      const refId = nextScreenRef++
      screenLive.set(refId, { refId, generation: 1 })
      return ok(JSON.stringify({ ref: { refId, generation: 1 }, width: 1080, height: 2400 }))
    }
    if (method === 'recycle') {
      if (!screenLive.delete(p?.ref?.refId)) return err('ERR_STALE_HANDLE', '未知截图帧')
      return ok('true')
    }
    return err('ERR_NOT_IMPLEMENTED', `未知 screen 方法: ${method}`)
  }
  installMockImages.colors = []
  installMockImages.reset = () => { nextRefId = 1; live.clear(); sizes.clear(); seen.length = 0; installMockImages.matches.length = 0; installMockImages.colors.length = 0; mock.miss = false; mock.fail = null }
  auto.install((ns, method, payloadJson, reqId) => {
    if (ns === 'screen') return handleScreen(method, payloadJson ? JSON.parse(payloadJson) : null, reqId)
    if (ns !== 'images') return undefined
    const p = payloadJson ? JSON.parse(payloadJson) : null
    seen.push({ ns, method, p })
    const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
    const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
    // raw ref 解析（haystack/needle 字段直接就是 {refId,generation}）
    const rawOf = (o) => (
      typeof o?.refId === 'number' && typeof o?.generation === 'number' ? o : null
    )
    // 信封 ref 解析（release 的 `ref` 字段是 {ref:{refId,generation}}）—— 缺字段即参数错
    const refOf = (o) => {
      const r = o?.ref
      if (typeof r?.refId !== 'number' || typeof r?.generation !== 'number') return null
      return r
    }
    switch (method) {
      case 'decode': {
        if (typeof p?.path !== 'string' || p.path.trim() === '') {
          return err('ERR_INVALID_PARAM', 'images decode 的 path 不得为空白')
        }
        if (mock.fail) return err(mock.fail.code, mock.fail.detail)
        const dim = mock.files[p.path] || { width: 1080, height: 2400 }
        const refId = nextRefId++
        live.set(refId, { refId, generation: 1 })
        sizes.set(refId, dim)
        return ok(JSON.stringify({ ref: { refId, generation: 1 }, width: dim.width, height: dim.height }))
      }
      case 'matchTemplate':
      case 'findImage': {
        const haystack = rawOf(p?.haystack)
        const needle = rawOf(p?.needle)
        if (!haystack || !needle || typeof p?.threshold !== 'number') {
          return err('ERR_INVALID_PARAM', '缺 haystack/needle/threshold')
        }
        // 域 [0,1]：越界先拒，一次 SPI 匹配都不发
        if (p.threshold < 0 || p.threshold > 1) {
          return err('ERR_INVALID_PARAM', `images ${method} 的 threshold 必须在 [0,1]，实际 ${p.threshold}`)
        }
        if (!live.has(haystack.refId) || live.get(haystack.refId).generation !== haystack.generation
          || !live.has(needle.refId) || live.get(needle.refId).generation !== needle.generation) {
          return err('ERR_STALE_HANDLE', 'images 的帧句柄已释放')
        }
        if (mock.fail) return err(mock.fail.code, mock.fail.detail)
        installMockImages.matches.push({ haystack, needle, threshold: p.threshold })
        if (mock.miss) return ok('null')
        return ok(JSON.stringify({ x: 12, y: 34, width: 100, height: 50, confidence: 0.97 }))
      }
      case 'findColor': {
        const haystack = rawOf(p?.haystack)
        // 域校验逐条复刻 Kotlin ImagesNamespaceHandler.findColor：先拒，一次 SPI 都不发
        if (!Array.isArray(p?.color) || p.color.length !== 4 || p.color.some((c) => !Number.isInteger(c) || c < 0 || c > 255)
          || !Number.isInteger(p?.tolerance) || p.tolerance < 0 || p.tolerance > 255) {
          return err('ERR_INVALID_PARAM', 'color 必须四分量各 [0,255]，tolerance [0,255]')
        }
        if (p.region !== undefined && p.region !== null
          && (!Array.isArray(p.region) || p.region.length !== 4)) {
          return err('ERR_INVALID_PARAM', 'region 给了就必须 x,y,w,h 四元组')
        }
        if (!haystack || !live.has(haystack.refId) || live.get(haystack.refId).generation !== haystack.generation) {
          return err('ERR_STALE_HANDLE', 'images findColor 的帧句柄已释放')
        }
        if (mock.fail) return err(mock.fail.code, mock.fail.detail)
        installMockImages.colors.push({ haystack, color: p.color, tolerance: p.tolerance, region: p.region ?? null })
        if (mock.miss) return ok('null')
        return ok(JSON.stringify({ x: 120, y: 340, r: 18, g: 52, b: 86, a: 255 }))
      }
      case 'release': {
        const ref = refOf(p)
        if (!ref) return err('ERR_INVALID_PARAM', '缺 ref 字段')
        if (!live.has(ref.refId) || live.get(ref.refId).generation !== ref.generation) {
          return err('ERR_STALE_HANDLE', `未知帧句柄 ${ref.refId} gen=${ref.generation}`)
        }
        live.delete(ref.refId)
        sizes.delete(ref.refId)
        return ok('true')
      }
      default:
        return err('ERR_NOT_IMPLEMENTED', `未知 images 方法: ${method}`)
    }
  })
  installMockImages.live = live
  installMockImages.matches = []

}

const seenPayloads = () => installMockImages.seen

test('decode 发 {path} 回帧三字段；宽高是文件真值', async () => {
  installMockImages()
  installMockImages.reset()
  const frame = await auto.images.decode('/sdcard/icon.png')
  assert.deepEqual(frame.ref, { refId: 1, generation: 1 })
  assert.equal(frame.width, 1080)
  assert.equal(frame.height, 2400)
  const sent = seenPayloads().at(-1)
  assert.deepEqual(sent.p, { path: '/sdcard/icon.png' }, '只发 path 一个键')
})

test('阈值一个键 threshold，两方法 wire 载荷逐字段相同', async () => {
  installMockImages()
  installMockImages.reset()
  const screen = await auto.images.decode('/sdcard/screen.png')
  const icon = await auto.images.decode('/sdcard/icon.png')

  const a = await auto.images.matchTemplate(screen, icon, { threshold: 0.85 })
  const first = seenPayloads().at(-1)
  const b = await auto.images.findImage(screen, icon, { threshold: 0.85 })
  const second = seenPayloads().at(-1)

  assert.deepEqual(first.p, second.p, 'facade 曾一个发 tolerance 一个发 threshold — 这里钉死同形')
  assert.deepEqual(first.p, {
    haystack: { refId: 1, generation: 1 },
    needle: { refId: 2, generation: 1 },
    threshold: 0.85,
  }, '两帧 ref 都在场 + 唯一阈值键 threshold')
  assert.equal('tolerance' in first.p, false, 'tolerance 是漂移键，两侧都不发')
  assert.deepEqual(a, { x: 12, y: 34, width: 100, height: 50, confidence: 0.97 })
  assert.deepEqual(b, a, '两方法命中体同形')
  assert.equal(installMockImages.matches.length, 2)
})

test('未匹配回 null（答案不是异常）', async () => {
  installMockImages()
  installMockImages.reset()
  const screen = await auto.images.decode('/sdcard/screen.png')
  const icon = await auto.images.decode('/sdcard/icon.png')
  mock.miss = true
  const hit = await auto.images.findImage(screen, icon, { threshold: 0.9 })
  assert.equal(hit, null, '图里没有达到阈值的位置 = null，不编 ERR_NOT_FOUND')
  mock.miss = false
})

test('阈值越界抛 ERR_INVALID_PARAM，且不匹配（域 [0,1]）', async () => {
  installMockImages()
  installMockImages.reset()
  const screen = await auto.images.decode('/sdcard/screen.png')
  const icon = await auto.images.decode('/sdcard/icon.png')
  const before = installMockImages.matches.length
  await assert.rejects(() => auto.images.findImage(screen, icon, { threshold: 1.5 }),
    (e) => e.code === 'ERR_INVALID_PARAM')
  await assert.rejects(() => auto.images.matchTemplate(screen, icon, { threshold: -0.1 }),
    (e) => e.code === 'ERR_INVALID_PARAM')
  assert.equal(installMockImages.matches.length, before, '越界阈值一次匹配都不发')
})

test('文件缺失/非图片原码透传（不折叠成参数错）', async () => {
  installMockImages()
  installMockImages.reset()
  mock.fail = { code: 'ERR_FILE_NOT_FOUND', detail: '图标不在应用可读路径内' }
  await assert.rejects(() => auto.images.decode('/nope.png'), (e) => e.code === 'ERR_FILE_NOT_FOUND')
  mock.fail = { code: 'ERR_IO', detail: '不是合法图片' }
  await assert.rejects(() => auto.images.decode('/nope.png'), (e) => e.code === 'ERR_IO')
  mock.fail = null
})

test('空白路径抛 ERR_INVALID_PARAM', async () => {
  installMockImages()
  installMockImages.reset()
  await assert.rejects(() => auto.images.decode('   '), (e) => e.code === 'ERR_INVALID_PARAM')
  assert.equal(seenPayloads().length, 1, '参数错也发了（校验在宿主侧，facade 不预检）')
})

test('release 幂等；未知/跨代/已释放帧 ERR_STALE_HANDLE', async () => {
  installMockImages()
  installMockImages.reset()
  const frame = await auto.images.decode('/sdcard/icon.png')
  await frame.recycle()
  await assert.rejects(() => frame.recycle(), (e) => e.code === 'ERR_STALE_HANDLE',
    '重复放同一帧：handler 侧真幂等是已知帧再次放回 true……')
})

test('帧已释放后匹配 ERR_STALE_HANDLE；screen 帧不通用', async () => {
  installMockImages()
  installMockImages.reset()
  const screen = await auto.images.decode('/sdcard/screen.png')
  const icon = await auto.images.decode('/sdcard/icon.png')
  await icon.recycle()
  const before = installMockImages.matches.length
  await assert.rejects(() => auto.images.findImage(screen, icon, { threshold: 0.9 }),
    (e) => e.code === 'ERR_STALE_HANDLE')
  assert.equal(installMockImages.matches.length, before, '帧已死，一次匹配都不发')

  // screen 桥面的帧拿去 images 匹配：handler 在场面表里找不到 → STALE（两张桥面别混）
  const screenFrame = await auto.screen.capture()
  await assert.rejects(() => auto.images.matchTemplate(screenFrame, screen, { threshold: 0.9 }),
    (e) => e.code === 'ERR_STALE_HANDLE')
})

test('decode 帧的 recycle 打 images/release（不是 screen/recycle）', async () => {
  installMockImages()
  installMockImages.reset()
  const frame = await auto.images.decode('/sdcard/icon.png')
  await frame.recycle()
  const call = seenPayloads().at(-1)
  assert.equal(call.ns, 'images')
  assert.equal(call.method, 'release')
  assert.deepEqual(call.p, { ref: { refId: 1, generation: 1 } })
})

test('fromFile 是 decode 的别名（wire 上仍只有 decode）', async () => {
  installMockImages()
  installMockImages.reset()
  const frame = await auto.images.fromFile('/sdcard/icon.png')
  assert.equal(frame.width, 1080)
  assert.equal(seenPayloads().at(-1).method, 'decode')
  // images.release(frame) 与 frame.recycle() 同一条路
  await auto.images.release(frame)
  assert.equal(seenPayloads().at(-1).method, 'release')
})

test('findColor 发 haystack+color+tolerance，回六字段命中', async () => {
  installMockImages()
  installMockImages.reset()
  const screen = await auto.images.decode('/sdcard/screen.png')
  const hit = await auto.images.findColor(screen, [18, 52, 86, 255], 10)
  assert.deepEqual(hit, { x: 120, y: 340, r: 18, g: 52, b: 86, a: 255 })
  const sent = seenPayloads().at(-1)
  assert.equal(sent.method, 'findColor')
  assert.deepEqual(sent.p, {
    haystack: { refId: 1, generation: 1 },
    color: [18, 52, 86, 255],
    tolerance: 10,
  }, 'region 缺省时 JSON.stringify 把它整个丢掉（宿主当全帧）')
  assert.equal(installMockImages.colors.length, 1)
})

test('findColor 的 region 可选：给了才出现在 wire 上', async () => {
  installMockImages()
  installMockImages.reset()
  const screen = await auto.images.decode('/sdcard/screen.png')
  await auto.images.findColor(screen, [1, 2, 3, 4], 0, { region: [10, 20, 30, 40] })
  const sent = seenPayloads().at(-1)
  assert.deepEqual(sent.p.region, [10, 20, 30, 40])
  assert.deepEqual(installMockImages.colors.at(-1).region, [10, 20, 30, 40], '宿主按四元组收下')
})

test('findColor 未命中回 null（扫过了、没有）', async () => {
  installMockImages()
  installMockImages.reset()
  const screen = await auto.images.decode('/sdcard/screen.png')
  mock.miss = true
  const hit = await auto.images.findColor(screen, [0, 0, 0, 255], 0)
  assert.equal(hit, null, '扫过一遍没这个色 = null，不编 ERR_NOT_FOUND')
  mock.miss = false
})

test('findColor 参数域越界抛 ERR_INVALID_PARAM 且不找色', async () => {
  installMockImages()
  installMockImages.reset()
  const screen = await auto.images.decode('/sdcard/screen.png')
  const bad = [
    () => auto.images.findColor(screen, [1, 2, 3], 0),
    () => auto.images.findColor(screen, [1, 2, 3, 4, 5], 0),
    () => auto.images.findColor(screen, [256, 0, 0, 255], 0),
    () => auto.images.findColor(screen, [-1, 0, 0, 255], 0),
    () => auto.images.findColor(screen, [0, 0, 0, 255], 256),
    () => auto.images.findColor(screen, [0, 0, 0, 255], -1),
    () => auto.images.findColor(screen, [0, 0, 0, 255], 0, { region: [1, 2, 3] }),
  ]
  for (const call of bad) {
    await assert.rejects(() => call(), (e) => e.code === 'ERR_INVALID_PARAM')
  }
  assert.equal(installMockImages.colors.length, 0, '域错一次 findColor 都不敢发')
})

test('findColor 用已释放的帧 ERR_STALE_HANDLE', async () => {
  installMockImages()
  installMockImages.reset()
  const screen = await auto.images.decode('/sdcard/screen.png')
  await screen.recycle()
  await assert.rejects(() => auto.images.findColor(screen, [1, 2, 3, 4], 0),
    (e) => e.code === 'ERR_STALE_HANDLE')
  assert.equal(installMockImages.colors.length, 0, '帧已死，一次找色都不发')
})

test('未开桥面的图像操作如实 ERR_NOT_IMPLEMENTED', async () => {
  installMockImages()
  installMockImages.reset()
  for (const m of ['toGrayscale', 'crop', 'pixel', 'captureScreen', 'rotate']) {
    await assert.rejects(() => auto.bridge.invoke('images', m, {}), (e) => e.code === 'ERR_NOT_IMPLEMENTED',
      `${m} 归 §9.2 native 面（P1），接口期不猜`)
  }
})
