'use strict'
/**
 * datastore 双侧契约测试（§9.6 + Kotlin DatastoreNamespaceHandlerTest）：
 * mock 宿主**逐字复刻** Kotlin handler 的回包形状，钉住三处最易两侧走偏的地方：
 * 1. found 信封拆信：缺失 → undefined、存的 JSON null → null（**不折叠**）；
 * 2. put 的 wire 形状 {key, value}（value 原样 JSON 值，宿主不解释）；
 * 3. remove/contains 回裸 boolean（`=== true` 判定，非 truthy 字符串）。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default

let installed = false
function installMockDatastore() {
  if (installed) return
  installed = true
  const seen = []
  installMockDatastore.seen = seen
  const mem = new Map() // key → 已解析 JSON 值（复刻 InMemoryDataStore 的 JSON 值面）
  auto.install((ns, method, payloadJson, reqId) => {
    if (ns !== 'datastore') return undefined
    const p = payloadJson ? JSON.parse(payloadJson) : null
    seen.push({ ns, method, p })
    const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
    const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
    switch (method) {
      case 'get': {
        if (typeof p?.key !== 'string' || p.key.trim() === '') return err('ERR_INVALID_PARAM', 'key 不得为空白')
        if (!mem.has(p.key)) return ok(JSON.stringify({ found: false }))
        // 逐字复刻 handler：{"found":true,"value":<text>}
        return ok(JSON.stringify({ found: true, value: mem.get(p.key) }))
      }
      case 'put': {
        if (typeof p?.key !== 'string' || p.key.trim() === '') return err('ERR_INVALID_PARAM', 'key 不得为空白')
        if (!('value' in p)) return err('ERR_INVALID_PARAM', 'put 缺 value 字段')
        mem.set(p.key, p.value)
        return ok('true')
      }
      case 'remove': {
        const had = mem.delete(p.key)
        return ok(had ? 'true' : 'false')
      }
      case 'contains':
        return ok(mem.has(p.key) ? 'true' : 'false')
      case 'keys':
        return ok(JSON.stringify([...mem.keys()]))
      case 'clear':
        mem.clear()
        return ok('true')
      default:
        return err('ERR_NOT_IMPLEMENTED', `未知 datastore 方法: ${method}`)
    }
  })
}

function lastCall() {
  const seen = installMockDatastore.seen
  return seen[seen.length - 1]
}

test('get：键缺失回 undefined，存的 JSON null 回 null —— 两者不折叠', async () => {
  installMockDatastore()
  assert.strictEqual(await auto.datastore.get('absent'), undefined, '缺失 = undefined')

  await auto.datastore.put('explicit', null)
  assert.strictEqual(await auto.datastore.get('explicit'), null, '存的 JSON null = null ≠ undefined')
})

test('put 的 wire 形状是 {key,value}，value 原样 JSON 值', async () => {
  installMockDatastore()
  await auto.datastore.put('cfg', { b: 2, a: [1, null] })
  assert.equal(lastCall().method, 'put')
  assert.deepEqual(lastCall().p, { key: 'cfg', value: { b: 2, a: [1, null] } })
  assert.deepEqual(await auto.datastore.get('cfg'), { b: 2, a: [1, null] })
})

test('remove/contains 回裸 boolean，remove 幂等', async () => {
  installMockDatastore()
  await auto.datastore.put('k', 1)
  assert.strictEqual(await auto.datastore.contains('k'), true)
  assert.strictEqual(await auto.datastore.remove('k'), true, '移除了真值 → true')
  assert.strictEqual(await auto.datastore.remove('k'), false, '再删 → false（不是 truthy 字符串）')
  assert.strictEqual(await auto.datastore.contains('k'), false)
})

test('keys 与 clear 全量语义', async () => {
  installMockDatastore()
  await auto.datastore.clear()   // mock 的 Map 跨用例共享：本用例先清场再布数据
  await auto.datastore.put('a', 1)
  await auto.datastore.put('b', 'x')
  assert.deepEqual(await auto.datastore.keys(), ['a', 'b'])
  await auto.datastore.clear()
  assert.deepEqual(await auto.datastore.keys(), [])
})

test('诚实缺位：datastore 没有 transaction 方法（不上桥 ≠ 抛 NOT_IMPLEMENTED）', async () => {
  installMockDatastore()
  assert.equal(typeof auto.datastore.transaction, 'undefined', 'P0 不提供：JS 侧直接无此函数')
})
