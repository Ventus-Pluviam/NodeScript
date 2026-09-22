'use strict'
/**
 * settings 双侧契约测试（§9.6 + Kotlin SettingsNamespaceHandlerTest）：
 * mock 宿主**逐字复刻** Kotlin handler 的回包，钉住四处最易两侧走偏的地方：
 * 1. 读缺失回裸 JSON null → JS `null`（不回 undefined，也不拿 0/空串冒充）；
 * 2. 两型分家：putString/putInt 的 wire 形状与值类型（不猜型的 get/put 不提供）；
 * 3. 授权错误原样抛（ERR_PERMISSION_DENIED / ERR_IO 不折叠）；
 * 4. 空串 value 是合法写入（只有 key 不得空白）。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default

let installed = false
function installMockSettings() {
  if (installed) return
  installed = true
  const seen = []
  installMockSettings.seen = seen
  const strs = new Map()
  const ints = new Map()
  let writable = true
  let systemRejects = false
  installMockSettings.setWritable = (v) => { writable = v }
  installMockSettings.setSystemRejects = (v) => { systemRejects = v }
  auto.install((ns, method, payloadJson, reqId) => {
    if (ns !== 'settings') return undefined
    const p = payloadJson ? JSON.parse(payloadJson) : null
    seen.push({ ns, method, p })
    const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
    const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
    const requireKey = () => {
      if (typeof p?.key !== 'string' || p.key.trim() === '') return err('ERR_INVALID_PARAM', 'settings key 不得为空白')
      return null
    }
    const writeGate = () => {
      if (!writable) return err('ERR_PERMISSION_DENIED', `系统设置未授权写入: ${p.key}`)
      if (systemRejects) return err('ERR_IO', `系统拒绝写入设置: ${p.key}`)
      return null
    }
    switch (method) {
      case 'canWrite':
        return ok(writable ? 'true' : 'false')
      case 'getString': {
        const bad = requireKey()
        if (bad) return bad
        // 逐字复刻 handler：缺失 → 裸 null，命中 → JSON 字符串
        return ok(strs.has(p.key) ? JSON.stringify(strs.get(p.key)) : 'null')
      }
      case 'getInt': {
        const bad = requireKey()
        if (bad) return bad
        return ok(ints.has(p.key) ? String(ints.get(p.key)) : 'null')
      }
      case 'putString': {
        const bad = requireKey()
        if (bad) return bad
        if (typeof p?.value !== 'string') return err('ERR_INVALID_PARAM', 'putString 缺 value 字段或 value 不是字符串')
        const gate = writeGate()
        if (gate) return gate
        strs.set(p.key, p.value)
        return ok('true')
      }
      case 'putInt': {
        const bad = requireKey()
        if (bad) return bad
        if (typeof p?.value !== 'number' || !Number.isInteger(p.value)) {
          return err('ERR_INVALID_PARAM', 'putInt 缺 value 字段或 value 不是数字')
        }
        if (p.value < -2147483648 || p.value > 2147483647) {
          return err('ERR_INVALID_PARAM', `putInt value 超出 Int 范围: ${p.value}`)
        }
        const gate = writeGate()
        if (gate) return gate
        ints.set(p.key, p.value)
        return ok('true')
      }
      default:
        return err('ERR_NOT_IMPLEMENTED', `未知 settings 方法: ${method}`)
    }
  })
}

function lastCall() {
  const seen = installMockSettings.seen
  return seen[seen.length - 1]
}

test('读缺失回 null（不回 undefined），空串与 0 是真值不是缺失', async () => {
  installMockSettings()
  assert.strictEqual(await auto.settings.getString('absent'), null, '缺键 = null')
  assert.strictEqual(await auto.settings.getInt('absent'), null, '缺键 = null（0 是合法亮度）')

  await auto.settings.putString('ring_volume', '')
  assert.strictEqual(await auto.settings.getString('ring_volume'), '', '空串是真值')
  await auto.settings.putInt('screen_brightness', 0)
  assert.strictEqual(await auto.settings.getInt('screen_brightness'), 0, '0 是真值')
})

test('两型 wire 形状分家——putString 发串、putInt 发数', async () => {
  installMockSettings()
  await auto.settings.putString('ring_volume', 'auto')
  assert.equal(lastCall().method, 'putString')
  assert.deepEqual(lastCall().p, { key: 'ring_volume', value: 'auto' })
  assert.strictEqual(await auto.settings.getString('ring_volume'), 'auto')

  await auto.settings.putInt('screen_off_timeout', 60_000)
  assert.equal(lastCall().method, 'putInt')
  assert.deepEqual(lastCall().p, { key: 'screen_off_timeout', value: 60_000 })
  assert.strictEqual(await auto.settings.getInt('screen_off_timeout'), 60_000)
})

test('canWrite 回裸 boolean', async () => {
  installMockSettings()
  installMockSettings.setWritable(true)
  assert.strictEqual(await auto.settings.canWrite(), true)
  installMockSettings.setWritable(false)
  assert.strictEqual(await auto.settings.canWrite(), false)
  installMockSettings.setWritable(true)
})

test('授权错误原样抛——ERR_PERMISSION_DENIED / ERR_IO 不折叠', async () => {
  installMockSettings()
  installMockSettings.setWritable(false)
  await assert.rejects(
    () => auto.settings.putString('ring_volume', 'auto'),
    (e) => e.code === 'ERR_PERMISSION_DENIED' && e.message.includes('未授权'),
  )
  installMockSettings.setWritable(true)
  installMockSettings.setSystemRejects(true)
  await assert.rejects(
    () => auto.settings.putInt('screen_off_timeout', 60_000),
    (e) => e.code === 'ERR_IO' && e.message.includes('系统拒绝写入'),
  )
  installMockSettings.setSystemRejects(false)
})

test('诚实缺位：settings 没有猜型的 get/put —— facade 面上就不存在', async () => {
  installMockSettings()
  assert.equal(typeof auto.settings.get, 'undefined', '两型该用哪个由调用方选，不猜')
  assert.equal(typeof auto.settings.put, 'undefined')
  assert.equal(typeof auto.settings.remove, 'undefined')
})
