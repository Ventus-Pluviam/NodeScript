'use strict'
/**
 * screen namespace 双侧契约测试（§9.2 / §8.8 + Kotlin ScreenNamespaceHandler）：
 * JS facade 的 wire 形状（帧句柄 {ref,width,height} / 会话 {session} / recycle/nextFrame
 * 载荷）与 Kotlin 侧解析器逐字段对齐。分类错误（锁屏/FLAG_SECURE/节流）如实抛 ERR_*。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default

/** mock screen 宿主：按 Kotlin ScreenNamespaceHandler 响应形状回包。 */
let installed = false
const mockState = { locked: false, throttle: false }
/** 每条到宿主的请求（断言 wire 形状用：尺寸提示这类"发了什么"只有这里看得见）。 */
const seen = []
function installMockScreen() {
  if (installed) return
  installed = true
  let nextRef = 1
  let nextSession = 1
  const sessions = new Map()
  auto.install((ns, method, payloadJson, reqId) => {
    if (ns !== 'screen') return undefined
    const p = payloadJson ? JSON.parse(payloadJson) : null
    seen.push({ method, p })
    const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
    const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
    switch (method) {
      case 'capture': {
        if (mockState.locked) err('ERR_SCREEN_LOCKED', '屏幕锁定，无法截取')
        else if (mockState.throttle) err('ERR_INVALID_PARAM', '截图节流中（333ms）')
        else ok(JSON.stringify({ ref: { refId: nextRef++, generation: 1 }, width: 1080, height: 2400 }))
        return undefined
      }
      case 'recycle': {
        ok('true')
        return undefined
      }
      case 'startCapturer': {
        if (mockState.locked) err('ERR_SCREEN_LOCKED', '屏幕锁定，无法截取')
        else {
          const id = nextSession++
          sessions.set(id, true)
          ok(JSON.stringify({ session: { refId: id, generation: 1 } }))
        }
        return undefined
      }
      case 'nextFrame': {
        if (!sessions.has(p.session.refId)) err('ERR_NOT_FOUND', `未知截图会话 ${p.session.refId}`)
        else ok(JSON.stringify({ ref: { refId: nextRef++, generation: 1 }, width: 1080, height: 2400 }))
        return undefined
      }
      case 'closeSession': {
        if (!sessions.delete(p.session.refId)) err('ERR_NOT_FOUND', `未知截图会话 ${p.session.refId}`)
        else ok('true')
        return undefined
      }
      default:
        err('ERR_NOT_IMPLEMENTED', `未知 screen 方法: ${method}`)
        return undefined
    }
  })
}

test('screen.capture 回帧句柄三字段；recycle 显式释放', async () => {
  installMockScreen()
  const frame = await auto.screen.capture()
  assert.deepEqual(frame.ref, { refId: 1, generation: 1 })
  assert.strictEqual(frame.width, 1080)
  assert.strictEqual(frame.height, 2400)
  await frame.recycle()
})

test('screen.capture 锁屏抛 ERR_SCREEN_LOCKED（分类错误而非黑图）', async () => {
  mockState.locked = true
  try {
    await assert.rejects(() => auto.screen.capture(), (e) => e.code === 'ERR_SCREEN_LOCKED')
  } finally {
    mockState.locked = false
  }
})

test('screen.capture 节流抛 ERR_INVALID_PARAM', async () => {
  mockState.throttle = true
  try {
    await assert.rejects(() => auto.screen.capture(), (e) => e.code === 'ERR_INVALID_PARAM')
  } finally {
    mockState.throttle = false
  }
})

test('screen.startCapturer 会话全链路：nextFrame 取帧 + close', async () => {
  const cap = await auto.screen.startCapturer()
  assert.deepEqual(cap.session, { refId: 1, generation: 1 })
  const frame = await cap.nextFrame()
  assert.strictEqual(frame.width, 1080)
  await cap.close()
  await assert.rejects(() => cap.nextFrame(), (e) => e.code === 'ERR_NOT_FOUND')
})

test('screen.startCapturer 的尺寸提示原样过桥（回包不带尺寸——尺寸是提示不是事实）', async () => {
  const cap = await auto.screen.startCapturer({ width: 720, height: 1280 })
  const last = seen[seen.length - 1]
  assert.equal(last.method, 'startCapturer')
  assert.deepEqual(last.p, { width: 720, height: 1280 })
  assert.ok(cap.session, '回包只有会话句柄，没有宽高')
  await cap.close()
})
