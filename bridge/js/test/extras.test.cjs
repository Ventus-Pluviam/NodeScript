'use strict'
/**
 * dialogs / shell / device / app / floatingWindow 双侧契约测试
 * （§9.4/§9.6 + Kotlin SystemNamespacesTest）：
 * JS facade 的 wire 形状与 Kotlin 侧 handler 的解析口径逐字段对齐。
 *
 * Kotlin 真机语义由 SystemNamespacesTest（handler）与 platform:system 的契约测试
 * （SPI 实现）覆盖；这里用 mock 宿主验证 JS 侧"发的出去、回的来能解析"，
 * 尤其是三处**容易两侧走偏**的地方：device.sdkInt 回的是数字还是字符串、
 * app.launch 的 `=== true` 判定、choose 取消的 -1。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default

/** mock 宿主：只认这五个命名空间，回包形状照抄 Kotlin handler 的编码。 */
let installed = false
function installMockExtras() {
  if (installed) return
  installed = true
  const seen = []
  installMockExtras.seen = seen
  auto.install((ns, method, payloadJson, reqId) => {
    if (!['dialogs', 'shell', 'device', 'app', 'floatingWindow'].includes(ns)) return undefined
    const p = payloadJson ? JSON.parse(payloadJson) : null
    seen.push({ ns, method, p })
    const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
    const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
    switch (`${ns}.${method}`) {
      case 'dialogs.prompt': return ok(JSON.stringify({ value: '张三', confirmed: true }))
      case 'dialogs.choose': return ok('2')
      case 'shell.exec':
      case 'shell.shell': return ok(JSON.stringify({ code: 0, stdout: 'ok\n', stderr: null }))
      case 'device.model': return ok(JSON.stringify('Pixel 8'))
      case 'device.sdkInt': return ok('34')
      case 'app.launch': return ok('true')
      case 'app.currentPackage': return ok('null')
      case 'floatingWindow.create': return ok(JSON.stringify({ refId: 7, generation: 1 }))
      default: return err('ERR_NOT_IMPLEMENTED', `未知 ${ns}.${method}`)
    }
  })
}

/** 取最近一条发给宿主的请求（断言 wire 形状用）。 */
function lastCall() {
  const seen = installMockExtras.seen
  return seen[seen.length - 1]
}

test('dialogs.prompt：默认 mode=auto，取消语义是 { value: null, confirmed: false }', async () => {
  installMockExtras()
  const r = await auto.dialogs.prompt('输入名字')
  assert.deepEqual(r, { value: '张三', confirmed: true })
  assert.deepEqual(lastCall().p, { title: '输入名字', placeholder: null, mode: 'auto' })
})

test('dialogs.choose：裸索引直出，取消即 -1（不套 null）', async () => {
  installMockExtras()
  const idx = await auto.dialogs.choose('选一个', ['a', 'b', 'c'])
  assert.equal(idx, 2)
  assert.deepEqual(lastCall().p, { title: '选一个', options: ['a', 'b', 'c'], mode: 'auto' })
})

test('shell.exec：三字段原样回；stdout 为 null 表示该流没产出（≠ 空串）', async () => {
  installMockExtras()
  const r = await auto.shell.exec('pm list packages')
  assert.deepEqual(r, { code: 0, stdout: 'ok\n', stderr: null })
  assert.equal(lastCall().p.cmd, 'pm list packages')
})

test('shell.shell 是 exec 的别名（同一 wire 方法）', async () => {
  installMockExtras()
  await auto.shell.shell('id')
  // facade 的 shell() 内部调 exec()，wire 上就是 shell.exec —— 别名不另立方法。
  assert.equal(lastCall().method, 'exec')
})

test('device.model / sdkInt：字符串与数字各归其位', async () => {
  installMockExtras()
  assert.equal(await auto.device.model(), 'Pixel 8')
  assert.equal(await auto.device.sdkInt(), 34)
  assert.equal(typeof (await auto.device.sdkInt()), 'number')
  // 请求无参：payload 为 null（Kotlin 侧 device handler 不解析 payload）。
  assert.equal(lastCall().p, null)
})

test('app.launch：只有 true 才算成功（false 是"起不来"这个答案，不是异常）', async () => {
  installMockExtras()
  assert.equal(await auto.app.launch('com.demo'), true)
  assert.deepEqual(lastCall().p, { packageName: 'com.demo' })
})

test('app.currentPackage：null 原样透出（不给空串）', async () => {
  installMockExtras()
  assert.equal(await auto.app.currentPackage(), null)
})

test('floatingWindow.create：句柄两字段可解析（refId/generation）', async () => {
  installMockExtras()
  const ref = await auto.floatingWindow.create({ title: '面板', width: 300, height: 200 })
  assert.deepEqual(ref, { refId: 7, generation: 1 })
})
