'use strict'

/**
 * RuntimeBridge 的 AbortSignal 账（§12.1 超时/取消）。
 *
 * 两条都是「监听/记账跟调用同寿」的判据：
 *
 * 1. **结算即摘除 abort 监听**：旧实现只在信号真触发时靠 `once:true` 自动摘，
 *    正常结算的路径没人摘 —— 脚本复用同一个 AbortController（脚本级取消源）时
 *    每次调用留一个监听，第 11 次起撞 EventTarget 的 MaxListenersExceeded 告警线，
 *    且这些闭包要活到信号触发/GC 才释放。
 * 2. **调用前信号已中止**：对已 aborted 的信号 addEventListener 永远不再触发 ——
 *    旧实现照投照记账，挂着等 TTL 走完。现与到点取消同口径（ERR_TIMEOUT 收尾），
 *    不投递也不记账。
 *
 * 用 `RuntimeBridgeImpl`（dist 直取）而不是全局单例：每个用例要自己的 install。
 */

const path = require('node:path')
const { test } = require('node:test')
const assert = require('node:assert/strict')
const { getEventListeners } = require('node:events')

const { RuntimeBridgeImpl } = require(path.resolve(__dirname, '..', 'dist', 'runtime.js'))

/** 内联答案路径（handler 同步返回非 undefined）：立即结算。 */
test('invoke 结算后从 AbortSignal 摘除监听（同一信号复用不累积）', async () => {
  const bridge = new RuntimeBridgeImpl()
  bridge.install(() => 'inline')
  const ac = new AbortController()
  // 12 次 > EventTarget 缺省 10 监听告警线：每次留一个的话这里必告警；
  // 摘干净了监听数恒为 0。
  for (let i = 0; i < 12; i += 1) {
    await bridge.invoke('ns', 'm', null, { signal: ac.signal })
  }
  assert.deepEqual(getEventListeners(ac.signal, 'abort'), [])
})

/** 异步路径（handler 回 undefined → 等 handleResponse 按 id 结算）。 */
test('挂起结算（handleResponse）后同样摘除监听', async () => {
  const bridge = new RuntimeBridgeImpl()
  let id = -1
  bridge.install((ns, m, payload, reqId) => {
    id = reqId
    return undefined
  })
  const ac = new AbortController()
  const p = bridge.invoke('ns', 'm', null, { signal: ac.signal, ttl: 5_000 })
  assert.ok(id > 0, 'handler 该拿到宿主分配的 reqId')
  bridge.handleResponse({ t: 'ok', id, payload: '"hi"' })
  assert.equal(await p, 'hi')
  assert.deepEqual(getEventListeners(ac.signal, 'abort'), [])
})

test('挂起中 abort：按取消口径 ERR_TIMEOUT 收尾且监听被摘除', async () => {
  const bridge = new RuntimeBridgeImpl()
  bridge.install(() => undefined)
  const ac = new AbortController()
  const p = bridge.invoke('ns', 'm', null, { signal: ac.signal, ttl: 60_000 })
  ac.abort()
  await assert.rejects(p, (e) => e && e.code === 'ERR_TIMEOUT')
  assert.deepEqual(getEventListeners(ac.signal, 'abort'), [])
})

test('调用前信号已中止：不投递、不记账，按取消口径 ERR_TIMEOUT 收尾', async () => {
  const bridge = new RuntimeBridgeImpl()
  let dispatched = 0
  bridge.install(() => {
    dispatched += 1
    return undefined
  })
  const ac = new AbortController()
  ac.abort()
  await assert.rejects(
    bridge.invoke('ns', 'm', null, { signal: ac.signal }),
    (e) => e && e.code === 'ERR_TIMEOUT',
  )
  assert.equal(dispatched, 0, '已中止的信号连一帧都不该投')
})
