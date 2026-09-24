'use strict'
/**
 * sensors 双侧契约测试（§12.2 + Kotlin SensorsNamespaceHandlerTest）：
 * mock 宿主**逐字复刻** Kotlin handler 的回包，钉住六处最易两侧走偏的地方：
 * 1. register 的 wire 形状 {name,delay?} —— delay 缺省即键不在场（宿主侧 NORMAL）；
 * 2. drain 的 wire 形状 {ref,sinceSeq?,max?} → {first,last,events}，空增量
 *    first == last == sinceSeq + 空表；
 * 3. 三码不折叠：ERR_NOT_SUPPORTED / ERR_SERVICE_DISABLED / ERR_STALE_HANDLE 原码透传；
 * 4. ignoresUnsupported=true 只折叠 NOT_SUPPORTED（拒收/句柄错照常抛）；
 * 5. 未知方法如实 ERR_NOT_IMPLEMENTED（`on`/`subscribe`/`watch` 两侧都不提供）；
 * 6. on('change') 是节流轮询：空增量不回调、有增量回调一次并前移游标、
 *    ERR_STALE_HANDLE 自动停（别人 unregisterAll 掐了订阅）。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default

let installed = false
function installMockSensors() {
  if (installed) return
  installed = true
  const seen = []
  installMockSensors.seen = seen
  const KNOWN = new Set([
    'accelerometer', 'magnetic_field', 'orientation', 'gyroscope', 'light', 'pressure',
    'proximity', 'gravity', 'linear_acceleration', 'rotation_vector',
    'ambient_temperature', 'relative_humidity',
  ])
  let nextRefId = 1
  const live = new Map()          // refId → { events, nextSeq }
  installMockSensors.reset = () => { nextRefId = 1; live.clear(); seen.length = 0 }
  installMockSensors.push = (refId, values, accuracy = 3, timestamp = 1000) => {
    const sub = live.get(refId)
    if (!sub) return null
    const seq = sub.nextSeq++
    sub.events.push({ seq, values, accuracy, timestamp })
    while (sub.events.length > 512) sub.events.shift()
    return seq
  }
  installMockSensors.kill = (refId) => live.delete(refId)
  auto.install((ns, method, payloadJson, reqId) => {
    if (ns !== 'sensors') return undefined
    const p = payloadJson ? JSON.parse(payloadJson) : null
    seen.push({ ns, method, p })
    const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
    const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
    const refOf = () => {
      const r = p?.ref
      if (typeof r?.refId !== 'number' || typeof r?.generation !== 'number') {
        return { bad: err('ERR_INVALID_PARAM', '缺 ref 字段') }
      }
      return { ref: r }
    }
    switch (method) {
      case 'isSupported': {
        if (typeof p?.name !== 'string' || p.name.trim() === '') {
          return err('ERR_INVALID_PARAM', 'sensors name 不得为空白')
        }
        return ok(KNOWN.has(p.name.trim().toLowerCase()) ? 'true' : 'false')
      }
      case 'register': {
        if (typeof p?.name !== 'string' || p.name.trim() === '') {
          return err('ERR_INVALID_PARAM', 'sensors name 不得为空白')
        }
        if (p.delay !== undefined && !['FASTEST', 'GAME', 'UI', 'NORMAL'].includes(p.delay)) {
          return err('ERR_INVALID_PARAM', `未知 SensorDelay: ${p.delay}`)
        }
        if (!KNOWN.has(p.name.trim().toLowerCase())) {
          return err('ERR_NOT_SUPPORTED', `设备不支持传感器: ${p.name}`)
        }
        const refId = nextRefId++
        live.set(refId, { events: [], nextSeq: 1 })
        return ok(JSON.stringify({ refId, generation: 1 }))
      }
      case 'unregister': {
        const { ref, bad } = refOf()
        if (bad) return bad
        if (ref.generation !== 1 || !live.has(ref.refId)) {
          return err('ERR_STALE_HANDLE', `未知传感器订阅 refId=${ref.refId}`)
        }
        live.delete(ref.refId)
        return ok('true')
      }
      case 'unregisterAll':
        live.clear()
        return ok('true')
      case 'drain': {
        const { ref, bad } = refOf()
        if (bad) return bad
        if (ref.generation !== 1 || !live.has(ref.refId)) {
          return err('ERR_STALE_HANDLE', `未知传感器订阅 refId=${ref.refId}`)
        }
        const sinceSeq = typeof p?.sinceSeq === 'number' ? p.sinceSeq : 0
        const max = typeof p?.max === 'number' ? p.max : 128
        if (!(max > 0)) return err('ERR_INVALID_PARAM', `drain 的 max 必须 > 0，实际 ${max}`)
        const sub = live.get(ref.refId)
        const picked = sub.events.filter((e) => e.seq > sinceSeq).slice(0, max)
        if (picked.length === 0) return ok(JSON.stringify({ first: sinceSeq, last: sinceSeq, events: [] }))
        return ok(JSON.stringify({ first: picked[0].seq, last: picked[picked.length - 1].seq, events: picked }))
      }
      default:
        return err('ERR_NOT_IMPLEMENTED', `未知 sensors 方法: ${method}`)
    }
  })
}

function lastCall() {
  const seen = installMockSensors.seen
  return seen[seen.length - 1]
}

test('register 的 wire 形状是 {name,delay?}——缺省 delay 即键不在场', async () => {
  installMockSensors()
  installMockSensors.reset()
  const sub = await auto.sensors.register('accelerometer', { delay: 'GAME' })
  assert.deepEqual(lastCall().p, { name: 'accelerometer', delay: 'GAME' })
  assert.equal(sub.cursor, 0)
  await sub.unsubscribe()

  const sub2 = await auto.sensors.register('accelerometer')
  assert.deepEqual(lastCall().p, { name: 'accelerometer' }, '缺省 delay → 键不在场，宿主侧 NORMAL')
  await sub2.unsubscribe()
})

test('drain 游标：增量前移游标，空增量游标不动', async () => {
  installMockSensors()
  installMockSensors.reset()
  const sub = await auto.sensors.register('accelerometer')
  // mock 侧 reset 后首订阅 refId 恒 1。
  const firstRef = 1
  installMockSensors.push(firstRef, [0.1, 9.8, 0.2])
  installMockSensors.push(firstRef, [0.2, 9.7, 0.3])

  const got = await sub.drain()
  assert.equal(got.length, 2)
  assert.equal(got[0].seq, 1)
  assert.equal(got[1].seq, 2)
  assert.equal(sub.cursor, 2, '有增量 → 游标前移到 last')

  const empty = await sub.drain()
  assert.deepEqual(empty, [])
  assert.equal(sub.cursor, 2, '空增量 → 游标不动')
  await sub.unsubscribe()
})

test('三码不折叠 + ignoresUnsupported 只折叠 NOT_SUPPORTED', async () => {
  installMockSensors()
  installMockSensors.reset()
  // 未知名 → NOT_SUPPORTED
  await assert.rejects(
    () => auto.sensors.register('heart_rate'),
    (e) => e.code === 'ERR_NOT_SUPPORTED',
  )
  // 折叠：ignoresUnsupported=true 时回 null
  const folded = await auto.sensors.register('heart_rate', { ignoresUnsupported: true })
  assert.equal(folded, null)

  // 参数错不折叠：空白名 → INVALID_PARAM（且 ignoresUnsupported 也救不了）
  await assert.rejects(
    () => auto.sensors.register('  ', { ignoresUnsupported: true }),
    (e) => e.code === 'ERR_INVALID_PARAM',
  )
  // 拼错 delay → INVALID_PARAM
  await assert.rejects(
    // @ts-expect-error 故意传非法值验宿主口径
    () => auto.sensors.register('accelerometer', { delay: 'TURBO' }),
    (e) => e.code === 'ERR_INVALID_PARAM',
  )

  // 句柄已死 → STALE（unsubscribe 后再 drain）
  const sub = await auto.sensors.register('accelerometer')
  await sub.unsubscribe()
  await assert.rejects(() => sub.drain(), (e) => e.code === 'ERR_STALE_HANDLE')
})

test('isSupported 与 unregisterAll 直通', async () => {
  installMockSensors()
  installMockSensors.reset()
  assert.strictEqual(await auto.sensors.isSupported('accelerometer'), true)
  assert.strictEqual(await auto.sensors.isSupported('heart_rate'), false)
  await assert.rejects(() => auto.sensors.isSupported(''), (e) => e.code === 'ERR_INVALID_PARAM')
  await auto.sensors.unregisterAll()
  assert.equal(lastCall().method, 'unregisterAll')
})

test('on(change) 是节流轮询：空增量不回调，有增量回调并前移游标', async () => {
  installMockSensors()
  installMockSensors.reset()
  const sub = await auto.sensors.register('light')
  const firstRef = 1
  const received = []
  const stop = sub.on('change', (events) => received.push(events), { intervalMs: 16 })
  // 空环两拍 → 不回调
  await new Promise((r) => setTimeout(r, 50))
  assert.equal(received.length, 0, '空增量不回调')
  // 推两条 → 至少一次回调，且游标前移
  installMockSensors.push(firstRef, [120])
  installMockSensors.push(firstRef, [130])
  await new Promise((r) => setTimeout(r, 60))
  assert.ok(received.length >= 1, '有增量回调')
  assert.equal(sub.cursor, 2)
  stop()
  await sub.unsubscribe()
})

test('on(change) 订阅被掐后自动停并交 onError', async () => {
  installMockSensors()
  installMockSensors.reset()
  const sub = await auto.sensors.register('light')
  const firstRef = 1
  const errors = []
  const stop = sub.on('change', () => {}, { intervalMs: 16, onError: (e) => errors.push(e) })
  installMockSensors.kill(firstRef)   // 模拟别人 unregisterAll
  await new Promise((r) => setTimeout(r, 60))
  assert.ok(errors.length >= 1 && errors[0].code === 'ERR_STALE_HANDLE', '最后一次错误交 onError')
  stop()
})

test('诚实缺位：没有 on/subscribe/watch/once 等未约定别名', async () => {
  installMockSensors()
  assert.equal(typeof auto.sensors.on, 'undefined')
  assert.equal(typeof auto.sensors.subscribe, 'undefined')
  assert.equal(typeof auto.sensors.watch, 'undefined')
  assert.equal(typeof auto.sensors.once, 'undefined')
})
