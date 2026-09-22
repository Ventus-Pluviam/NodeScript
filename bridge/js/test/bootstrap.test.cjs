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
const { execFile } = require('node:child_process')

/** 三新案跑子进程隔离（runtimeBridge.install 单例每进程一次）：子脚本直连 chaos 宿主。 */
const CHAOS_SCRIPT = `
const { SocketBootstrap } = require(%s)
const { runtimeBridge } = require(%s)
const assert = require('node:assert/strict')
const [sock, scenario] = [process.argv[1], process.argv[2]]
const b = new SocketBootstrap({ socketPath: sock, maxFrameBytes: 64 * 1024 })
let sockErr = null
b.onSocketError = (e) => { sockErr = e }
b.connect().then(async () => {
  b.install()
  if (scenario === 'reorder') {
    const pSlow = runtimeBridge.invoke('probe', 'slow', null, { ttl: 2_000 })
    const rFast = await runtimeBridge.invoke('probe', 'fast', null, { ttl: 2_000 })
    assert.deepEqual(rFast, { fast: true })
    assert.deepEqual(await pSlow, { slow: true })
  } else if (scenario === 'ttl') {
    await assert.rejects(runtimeBridge.invoke('probe', 'slow', null, { ttl: 100 }), (e) => e.code === 'ERR_TIMEOUT')
    await assert.rejects(runtimeBridge.invoke('probe', 'late', null, { ttl: 100 }), (e) => e.code === 'ERR_TIMEOUT')
    await new Promise((r) => setTimeout(r, 400))
    assert.deepEqual(await runtimeBridge.invoke('probe', 'oob', null, { ttl: 2_000 }), { oob: 'done' })
  } else if (scenario === 'bigframe') {
    await assert.rejects(runtimeBridge.invoke('probe', 'big', null, { ttl: 5_000 }))
    assert.ok(sockErr !== null, '应触发 socket 熔断回调')
    assert.match(sockErr.message, /超过上限/)
  }
  console.log('CHILD_DONE ' + scenario)
  b.close()
  process.exit(0)
}).catch((e) => { console.error('CHILD_FAIL', e); process.exit(1) })
`
function childFor(sockPath, scenario) {
  const boot = JSON.stringify(path.resolve(__dirname, '..', 'dist', 'bootstrap.js'))
  const rt = JSON.stringify(path.resolve(__dirname, '..', 'dist', 'runtime.js'))
  return new Promise((resolve, reject) => {
    const child = execFile(process.execPath, ['-e', CHAOS_SCRIPT.replace('%s', boot).replace('%s', rt), sockPath, scenario], (err, stdout, stderr) => {
      if (err) reject(new Error(`子进程失败: ${stdout} ${stderr} ${err.message}`))
      else resolve(stdout)
    })
  })
}

/** 慢宿主：slow 法 300ms 回，fast 即时回（乱序）；late 250ms 迟到；oob 先带外未知 id 再回正常；big 回 200KB。 */
async function startChaosHost(socketPath) {
  const server = net.createServer((sock) => {
    let buf = Buffer.alloc(0)
    const send = (o) => sock.write(`${JSON.stringify(o)}\n`)
    sock.on('data', (chunk) => {
      buf = buf.length === 0 ? chunk : Buffer.concat([buf, chunk])
      let nl
      while ((nl = buf.indexOf(0x0a)) !== -1) {
        const line = buf.subarray(0, nl).toString('utf8')
        buf = buf.subarray(nl + 1)
        if (line.length === 0) continue
        const req = JSON.parse(line)
        if (req.m === 'slow') {
          setTimeout(() => send({ t: 'ok', id: req.id, payload: JSON.stringify({ slow: true }) }), 300)
        } else if (req.m === 'fast') {
          send({ t: 'ok', id: req.id, payload: JSON.stringify({ fast: true }) })
        } else if (req.m === 'late') {
          setTimeout(() => send({ t: 'ok', id: req.id, payload: JSON.stringify({ late: true }) }), 250)
        } else if (req.m === 'oob') {
          send({ t: 'ok', id: 999999, payload: JSON.stringify({ ghost: 1 }) })
          send({ t: 'ok', id: req.id, payload: JSON.stringify({ oob: 'done' }) })
        } else if (req.m === 'big') {
          send({ t: 'ok', id: req.id, payload: JSON.stringify({ blob: 'x'.repeat(200_000) }) })
        }
      }
    })
  })
  return new Promise((resolve) => server.listen(socketPath, () => resolve(server)))
}

test('SocketBootstrap：乱序回包按 id 归位（子进程隔离单例）', async () => {
  const sockPath = path.join(os.tmpdir(), `autoscript-chaos-${process.pid}-${Date.now()}.sock`)
  const server = await startChaosHost(sockPath)
  try {
    assert.match(await childFor(sockPath, 'reorder'), /CHILD_DONE reorder/)
  } finally {
    server.close()
    fs.rmSync(sockPath, { force: true })
  }
})

test('SocketBootstrap：TTL 超时 + 迟到/带外丢弃（子进程隔离单例）', async () => {
  const sockPath = path.join(os.tmpdir(), `autoscript-chaos-${process.pid}-${Date.now()}-2.sock`)
  const server = await startChaosHost(sockPath)
  try {
    assert.match(await childFor(sockPath, 'ttl'), /CHILD_DONE ttl/)
  } finally {
    server.close()
    fs.rmSync(sockPath, { force: true })
  }
})

test('SocketBootstrap：超限巨帧熔断（子进程隔离单例）', async () => {
  const sockPath = path.join(os.tmpdir(), `autoscript-chaos-${process.pid}-${Date.now()}-3.sock`)
  const server = await startChaosHost(sockPath)
  try {
    assert.match(await childFor(sockPath, 'bigframe'), /CHILD_DONE bigframe/)
  } finally {
    server.close()
    fs.rmSync(sockPath, { force: true })
  }
})
