'use strict'
/**
 * clipboard 双侧契约测试（§12.2 + Kotlin ClipboardNamespaceHandlerTest）：
 * mock 宿主**逐字复刻** Kotlin handler 的回包，钉住四处最易两侧走偏的地方：
 * 1. 读空回裸 JSON null → JS `null`（不回 undefined，也不拿空串冒充）；
 * 2. 两方法 wire 形状（getText 空参 / setText 发 `{text}`）；
 * 3. 写口径：空串合法原样存，缺参/非串 → ERR_INVALID_PARAM 且不写；
 * 4. 别名不猜：get/set/clear/hasText → ERR_NOT_IMPLEMENTED。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default

let installed = false
function installMockClipboard() {
  if (installed) return
  installed = true
  const seen = []
  installMockClipboard.seen = seen
  let text = null
  installMockClipboard.setText = (v) => { text = v }
  auto.install((ns, method, payloadJson, reqId) => {
    if (ns !== 'clipboard') return undefined
    const p = payloadJson ? JSON.parse(payloadJson) : null
    seen.push({ ns, method, p })
    const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
    const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
    switch (method) {
      case 'getText':
        // 逐字复刻 handler：A11yBridgeJson.encode(String?) —— 有值引号串、无值裸 null
        return ok(text === null ? 'null' : JSON.stringify(text))
      case 'setText': {
        if (typeof p?.text !== 'string') {
          return err('ERR_INVALID_PARAM', 'setText 缺 text 字段或 text 不是字符串')
        }
        text = p.text
        return ok('true')
      }
      default:
        return err('ERR_NOT_IMPLEMENTED', `未知 clipboard 方法: ${method}`)
    }
  })
}

test('读空回 null（不回 undefined），空串是真值不是缺失', async () => {
  installMockClipboard()
  installMockClipboard.setText(null)
  assert.strictEqual(await auto.clipboard.getText(), null, '空剪贴板 = null')

  await auto.clipboard.setText('')
  assert.strictEqual(await auto.clipboard.getText(), '', '空串是真值（a11y copy 空节点记空串）')

  await auto.clipboard.setText('hello')
  assert.strictEqual(await auto.clipboard.getText(), 'hello')
})

test('两方法 wire 形状——getText 空参、setText 发 {text}', async () => {
  installMockClipboard()
  const seen = installMockClipboard.seen
  seen.length = 0
  await auto.clipboard.setText('x')
  assert.deepEqual(seen[seen.length - 1], { ns: 'clipboard', method: 'setText', p: { text: 'x' } })
  seen.length = 0
  await auto.clipboard.getText()
  assert.deepEqual(seen[seen.length - 1], { ns: 'clipboard', method: 'getText', p: null })
})

test('写口径——缺参/非串 ERR_INVALID_PARAM 且不写', async () => {
  installMockClipboard()
  installMockClipboard.setText('keep')
  await assert.rejects(
    () => auto.clipboard.setText(undefined),
    (e) => e.code === 'ERR_INVALID_PARAM',
  )
  await assert.rejects(
    () => auto.clipboard.setText(128),
    (e) => e.code === 'ERR_INVALID_PARAM',
  )
  assert.strictEqual(await auto.clipboard.getText(), 'keep', '被拒的调用不写')
})

test('诚实缺位：别名两侧都不提供——get/set/clear/hasText', async () => {
  installMockClipboard()
  assert.equal(typeof auto.clipboard.get, 'undefined')
  assert.equal(typeof auto.clipboard.set, 'undefined')
  assert.equal(typeof auto.clipboard.clear, 'undefined')
  assert.equal(typeof auto.clipboard.hasText, 'undefined')
})
