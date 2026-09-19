'use strict'
/**
 * a11y namespace 双侧契约测试（§9.1 / §12.3 + Kotlin A11yNamespaceHandler）：
 * JS facade 的 wire 形状（conditions 对象 / ref:{refId,generation} / findOne 命中体）
 * 与 Kotlin 侧解析器逐字段对齐。Kotlin 真机语义由 A11yNamespaceHandlerTest 覆盖；
 * 这里用 mock 宿主验证 JS 侧"发的出去、回的来能解析"。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default
const { NotFoundError } = autoModule

/** mock a11y 宿主：内存两节点，按 Kotlin A11yNamespaceHandler 响应形状回包。 */
let installed = false
function installMockA11y() {
  if (installed) return
  installed = true
  const nodes = new Map([
    [1, { text: '启动', desc: '启动按钮', className: 'Button', clickable: true }],
    [2, { text: '取消', className: 'Button', clickable: true }],
  ])
  const matches = (cond) => {
    const out = []
    for (const [refId, a] of nodes) {
      let ok = true
      for (const k of Object.keys(cond || {})) {
        if (!(k in a) && cond[k] !== undefined) { ok = false; break }
        if (a[k] !== cond[k]) { ok = false; break }
      }
      if (ok) out.push({ ref: { refId, generation: 1 } })
    }
    return out
  }
  auto.install((ns, method, payloadJson, reqId) => {
    if (ns !== 'a11y') return undefined
    const p = payloadJson ? JSON.parse(payloadJson) : null
    const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
    const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
    switch (method) {
      case 'findOne': {
        const m = matches(p.conditions)
        if (m.length === 0) err('ERR_NOT_FOUND', '选择器无匹配')
        else ok(JSON.stringify(m[0]))
        return undefined
      }
      case 'findAll': {
        const m = matches(p.conditions)
        ok(JSON.stringify(p.max != null ? m.slice(0, p.max) : m))
        return undefined
      }
      case 'click': {
        if (!nodes.has(p.ref.refId)) err('ERR_STALE_HANDLE', '节点已释放')
        else ok('true')
        return undefined
      }
      case 'scroll': {
        if (!nodes.has(p.ref.refId)) err('ERR_STALE_HANDLE', '节点已释放')
        else if (p.direction != null && !['forward', 'backward', 'up', 'down', 'left', 'right'].includes(String(p.direction).toLowerCase())) {
          err('ERR_INVALID_PARAM', `未知滚动方向 ${p.direction}`)
        } else ok('true')
        return undefined
      }
      case 'waitFor': {
        // Kotlin 侧 waitFor 复用 findOne 的选择器解析路径：载荷键是 conditions，
        // 与 findOne 同构（JS facade 曾发 selector，会因白名单外字段被拒）。
        const m = matches(p.conditions)
        if (Object.prototype.hasOwnProperty.call(p, 'selector')) {
          err('ERR_INVALID_PARAM', '未知选择器条件 selector')
        } else if (m.length === 0) err('ERR_NOT_FOUND', '选择器无匹配')
        else ok('true')
        return undefined
      }
      case 'copy': {
        if (!nodes.has(p.ref.refId)) err('ERR_STALE_HANDLE', '节点已释放')
        else ok('true')
        return undefined
      }
      case 'paste': {
        if (!nodes.has(p.ref.refId)) err('ERR_STALE_HANDLE', '节点已释放')
        else ok('true')
        return undefined
      }
      case 'events': {
        if (p.batch != null && p.batch <= 0) err('ERR_INVALID_PARAM', 'batch 必须 > 0')
        else ok(JSON.stringify({
          first: p.sinceSeq ?? 0,
          last: (p.sinceSeq ?? 0) + 1,
          events: [{ seq: (p.sinceSeq ?? 0) + 1, type: 'nodeAdded', node: { refId: 1, generation: 1 }, payload: null }],
        }))
        return undefined
      }
      case 'canPerformGestures': {
        ok('true')
        return undefined
      }
      case 'gesture': {
        const s = p.strokes
        if (!Array.isArray(s) || s.length === 0) err('ERR_INVALID_PARAM', '手势至少包含一个笔画')
        else ok('true')
        return undefined
      }
      default:
        err('ERR_NOT_IMPLEMENTED', `未知 a11y 方法: ${method}`)
        return undefined
    }
  })
}

test('a11y.findOne 命中回 ref 句柄；conditions 逐字段透传', async () => {
  installMockA11y()
  const btn = await auto.a11y.selector().text('启动').clickable(true).findOne()
  assert.deepEqual(btn.ref, { refId: 1, generation: 1 })
})

test('a11y.findOne 无匹配 → NotFoundError（对偶 Kotlin ERR_NOT_FOUND）', async () => {
  await assert.rejects(
    () => auto.a11y.selector().text('不存在').findOne(),
    (e) => e instanceof NotFoundError && e.code === 'ERR_NOT_FOUND',
  )
  const nullHit = await auto.a11y.selector().text('不存在').findOneOrNull()
  assert.strictEqual(nullHit, null)
})

test('a11y.findAll 回数组并按 max 截断', async () => {
  const all = await auto.a11y.selector().className('Button').findAll()
  assert.strictEqual(all.length, 2)
  const capped = await auto.a11y.selector().className('Button').findAll({ max: 1 })
  assert.strictEqual(capped.length, 1)
})

test('a11y.click 经 ref 句柄；跨代 → ERR_STALE_HANDLE', async () => {
  const btn = await auto.a11y.selector().text('启动').findOne()
  assert.strictEqual(await btn.click(), true)
  await assert.rejects(() => auto.bridge.invoke('a11y', 'click', { ref: { refId: 999, generation: 1 } }), (e) => e.code === 'ERR_STALE_HANDLE')
})

test('a11y.scroll 缺省向前；非法方向 → ERR_INVALID_PARAM', async () => {
  const btn = await auto.a11y.selector().text('启动').findOne()
  assert.strictEqual(await btn.scroll(), true)
  assert.strictEqual(await btn.scroll('down'), true)
  await assert.rejects(() => btn.scroll('diagonal'), (e) => e.code === 'ERR_INVALID_PARAM')
})

test('a11y.events 游标拉取回 first/last/events', async () => {
  const batch = await auto.a11y.events({ sinceSeq: 0 })
  assert.strictEqual(batch.first, 0)
  assert.strictEqual(batch.last, 1)
  assert.strictEqual(batch.events.length, 1)
  assert.strictEqual(batch.events[0].type, 'nodeAdded')
  assert.deepEqual(batch.events[0].node, { refId: 1, generation: 1 })
  await assert.rejects(() => auto.a11y.events({ batch: 0 }), (e) => e.code === 'ERR_INVALID_PARAM')
})

test('a11y.copy/paste 经 ref 句柄；跨代 → ERR_STALE_HANDLE', async () => {
  const btn = await auto.a11y.selector().text('启动').findOne()
  assert.strictEqual(await btn.copy(), true)
  assert.strictEqual(await btn.paste(), true)
  await assert.rejects(() => auto.bridge.invoke('a11y', 'copy', { ref: { refId: 999, generation: 1 } }), (e) => e.code === 'ERR_STALE_HANDLE')
})

test('a11y.gesture 上送 strokes；canPerformGestures 回门状态', async () => {
  assert.strictEqual(await auto.a11y.canPerformGestures(), true)
  const ok = await auto.a11y.gesture({
    strokes: [{ points: [{ x: 100, y: 800 }, { x: 100, y: 200 }], durationMillis: 300 }],
  })
  assert.strictEqual(ok, true)
  await assert.rejects(() => auto.a11y.gesture({ strokes: [] }), (e) => e.code === 'ERR_INVALID_PARAM')
})

test('a11y.waitFor 发 conditions 键（对偶 Kotlin waitFor 解析路径）', async () => {
  installMockA11y()
  assert.strictEqual(await auto.a11y.waitFor(auto.a11y.selector().text('启动')), true)
  // 发错键（selector）被如实拒绝，不静默变全量匹配
  await assert.rejects(
    () => auto.bridge.invoke('a11y', 'waitFor', { selector: { text: '启动' } }),
    (e) => e.code === 'ERR_INVALID_PARAM',
  )
})
