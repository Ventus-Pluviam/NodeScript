'use strict'
/**
 * zip 双侧契约测试（§9.6 + Kotlin ZipNamespaceHandlerTest）：
 * mock 宿主逐字复刻 Kotlin handler 的回包，钉 wire 形状与错误透传。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default

let installed = false
function installMockZip() {
  if (installed) return
  installed = true
  const seen = []
  installMockZip.seen = seen
  let failWith = null
  installMockZip.failWith = (e) => { failWith = e }
  auto.install((ns, method, payloadJson, reqId) => {
    if (ns !== 'zip') return undefined
    const p = payloadJson ? JSON.parse(payloadJson) : null
    seen.push({ ns, method, p })
    const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
    const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
    if (failWith) { const f = failWith; failWith = null; return err(f.code, f.detail) }
    for (const key of method === 'compress' ? ['source', 'archive'] : ['archive', 'targetDir']) {
      if (typeof p?.[key] !== 'string' || p[key].trim() === '') return err('ERR_INVALID_PARAM', `${key} 不得为空白`)
    }
    switch (method) {
      case 'compress':
      case 'extract':
        return ok('true')
      default:
        return err('ERR_NOT_IMPLEMENTED', `未知 zip 方法: ${method}`)
    }
  })
}

function lastCall() {
  const seen = installMockZip.seen
  return seen[seen.length - 1]
}

test('compress：两路径按 {source,archive} 上线，成功即 resolve', async () => {
  installMockZip()
  await auto.zip.compress('/data/proj', '/data/out.zip')
  assert.equal(lastCall().method, 'compress')
  assert.deepEqual(lastCall().p, { source: '/data/proj', archive: '/data/out.zip' })
})

test('extract：两路径按 {archive,targetDir} 上线', async () => {
  installMockZip()
  await auto.zip.extract('/data/out.zip', '/data/x')
  assert.equal(lastCall().method, 'extract')
  assert.deepEqual(lastCall().p, { archive: '/data/out.zip', targetDir: '/data/x' })
})

test('宿主错误原样抛出——ERR_FILE_NOT_FOUND 不折叠', async () => {
  installMockZip()
  installMockZip.failWith({ code: 'ERR_FILE_NOT_FOUND', detail: '压缩源不存在: /nope' })
  await assert.rejects(
    () => auto.zip.compress('/nope', '/data/out.zip'),
    (e) => e.code === 'ERR_FILE_NOT_FOUND' && e.message.includes('压缩源不存在'),
  )
})

test('没有 unzip 等未约定别名——facade 面上就不存在', async () => {
  installMockZip()
  assert.equal(typeof auto.zip.unzip, 'undefined')
  assert.equal(typeof auto.zip.zip, 'undefined')
  assert.equal(typeof auto.zip.list, 'undefined')
})
