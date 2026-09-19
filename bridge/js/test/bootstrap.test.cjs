'use strict'
/**
 * SocketBootstrap 闭环测试（docs/framework-design.md §7.5 Transport）：
 * - unix socket + newline frame：request 出 / response 入（ok + err）→ 全异步 requestId 结算；
 * - 未连接投递 → 快速 ERR_ENGINE_STOPPED（不悬挂）。
 * 独立进程跑（node --test 每文件一进程），与 facade.test 的安装互不干扰。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const net = require('node:net')
const os = require('node:os')
const path = require('node:path')
const fs = require('node:fs')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default
const { AutojsError } = autoModule
const { SocketBootstrap } = require(path.resolve(__dirname, '..', 'dist', 'bootstrap.js'))

/** 最小 mock 宿主：读 newline frame，按 method 回 ok/err。 */
async function startMockHost(socketPath) {
  const server = net.createServer((sock) => {
    let buf = Buffer.alloc(0)
    sock.on('data', (chunk) => {
      buf = buf.length === 0 ? chunk : Buffer.concat([buf, chunk])
      let nl
      while ((nl = buf.indexOf(0x0a)) !== -1) {
        const line = buf.subarray(0, nl).toString('utf8')
        buf = buf.subarray(nl + 1)
        if (line.length === 0) continue
        const req = JSON.parse(line)
        const out = req.m === 'fail'
          ? { t: 'err', id: req.id, code: 'ERR_NOT_IMPLEMENTED', detail: 'mock 拒绝' }
          : { t: 'ok', id: req.id, payload: JSON.stringify({ echoed: req.m, arg: req.payload ? JSON.parse(req.payload) : null }) }
        sock.write(`${JSON.stringify(out)}\n`)
      }
    })
  })
  return new Promise((resolve) => server.listen(socketPath, () => resolve(server)))
}

test('SocketBootstrap：unix socket 请求/响应闭环（ok/err/空参）', async () => {
  const sockPath = path.join(os.tmpdir(), `autoscript-bridge-${process.pid}-${Date.now()}.sock`)
  const server = await startMockHost(sockPath)
  let b = null
  try {
    b = new SocketBootstrap({ socketPath: sockPath })
    assert.strictEqual(b.connected, false)
    await b.connect()
    b.install()
    assert.strictEqual(auto.installed, true)

    const ok = await auto.bridge.invoke('mock', 'echo', { x: 1 })
    assert.deepEqual(ok, { echoed: 'echo', arg: { x: 1 } })

    const okNull = await auto.bridge.invoke('mock', 'echo', null)
    assert.deepEqual(okNull, { echoed: 'echo', arg: null })

    await assert.rejects(
      () => auto.bridge.invoke('mock', 'fail'),
      (e) => e instanceof AutojsError && e.code === 'ERR_NOT_IMPLEMENTED' && e.detail === 'mock 拒绝',
    )
  } finally {
    if (b) b.close()
    server.close()
    fs.rmSync(sockPath, { force: true })
  }
})

test('SocketBootstrap：未连接投递 → 快速 ERR_ENGINE_STOPPED（不悬挂）', () => {
  const b = new SocketBootstrap({ socketPath: '/nonexistent-bridge.sock' })
  assert.throws(
    () => b.handler('mock', 'echo', null, 1, 5_000),
    (e) => e.code === 'ERR_ENGINE_STOPPED',
  )
})