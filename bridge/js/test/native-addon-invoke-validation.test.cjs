'use strict'
/**
 * addon `invoke` 的**入参类型门**（`:bridge:native` `bridge_addon.cc`）——
 * 钉的是 2026-11-08 在 host 上实测到的一类静默：N-API 的类型不符**不报错、也不写缓冲**。
 *
 * 四条实测症状（修前）：
 *   1. `ns` 不是字符串（如数字/对象）→ `napi_get_value_string_utf8` 返回
 *      `napi_string_expected` 且**不动** `char ns[64]`，于是这一帧带着**上一次调用**
 *      留下的 ns 发出去（实测：先 `invoke('A'*60, …)`、再 `invoke(1234, …)`，
 *      第二帧线上是 `"ns":"AAAA…"`）—— 一条调用静默变成另一条；
 *   2. `ns`/`method` 超过 63 字节 → 静默截断成**另一个名字**发出去；
 *   3. `reqId` 不是数字（BigInt/字符串）→ `napi_get_value_int64` 不动出参，
 *      id 恒 0 成帧；JS 侧 `inflight` 里没有 0（reqId 从 1 起、心跳走负数命名空间），
 *      回包查不到即丢 → 调用悬挂到 TTL 超时，而宿主其实已经执行了它；
 *   4. `payload` 不是字符串（对象/数字）→ 静默当 null 发，方法按"无参"执行。
 *
 * 修法：四条全部 `ERR_INVALID_PARAM` 响亮拒绝。本文件用**真 addon + 真 unix socket**
 * 断言「抛错」与「线上没有多出帧」两件事 —— 只断言抛错不够：抛错之后若仍写出去一帧，
 * 那条请求还是被宿主执行了。
 *
 * 与 `native-addon-roundtrip.test.cjs` 分文件：`node --test` 每文件一进程，而 addon
 * 的 `setup` 是**进程级一次性**（第二次抛），两个用例挤一个进程会互相踩。
 * 独立文件也让 runtimeBridge 的单例安装互不相干。
 *
 * 门禁：`AUTOSCRIPT_TEST_ADDON` = 已编译 addon 路径；未设时本文件尝试**自己编一份**
 * （g++ + Node 自带头文件，官方 tarball 里 `include/node/` 是现成的，本机与
 * ubuntu-latest 都一样）。编不出来就 skip —— 与本仓其它 env 门禁同一条诚实口径。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const net = require('node:net')
const os = require('node:os')
const path = require('node:path')
const fs = require('node:fs')
const { execFileSync } = require('node:child_process')

const ROOT = (() => {
  let p = path.resolve(__dirname, '..', '..')
  for (let i = 0; i < 8; i += 1) {
    if (fs.existsSync(path.join(p, 'settings.gradle.kts'))) return p
    p = path.dirname(p)
  }
  throw new Error('找不到仓库根')
})()

const ADDON_SRC = path.join(ROOT, 'bridge/native/src/main/cpp/bridge_addon.cc')

/** Node 自带头文件目录（`include/node`）；装的是官方发行包时它在 prefix 下。 */
function nodeIncludeDir() {
  const candidates = [
    path.join(path.dirname(path.dirname(process.execPath)), 'include', 'node'),
    '/usr/include/node',
  ]
  return candidates.find((d) => fs.existsSync(path.join(d, 'node_api.h'))) || null
}

/**
 * 拿一份可用的 addon：env 指定优先，否则现编。
 * 返回 null = 本机缺 g++/头文件（调用方 skip，不假装通过）。
 */
function resolveAddon() {
  if (process.env.AUTOSCRIPT_TEST_ADDON) {
    const p = process.env.AUTOSCRIPT_TEST_ADDON
    return fs.existsSync(p) ? p : null
  }
  const inc = nodeIncludeDir()
  if (!inc) return null
  const out = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'as-addon-')), 'bridge_native.node')
  try {
    execFileSync('g++', [
      '-std=c++17', '-O1', '-shared', '-fPIC', '-DNAPI_VERSION=10',
      `-I${inc}`, ADDON_SRC, '-o', out, '-lpthread',
    ], { stdio: 'pipe' })
    return out
  } catch {
    return null
  }
}

const REAL_ADDON = resolveAddon()
const SKIP = REAL_ADDON
  ? false
  : '设 AUTOSCRIPT_TEST_ADDON=已编译 addon 路径，或安装 g++ 与 Node 头文件（include/node）以启用'

/**
 * 起一个最小宿主（§7.5 握手 + 记录每帧），完成 hello/ACK 后把已连 fd 交出去。
 * 返回 { addon, frames, close }；frames 是宿主**真正收到**的请求帧。
 */
async function openHost() {
  const sockPath = path.join(os.tmpdir(), `autoscript-invoke-${process.pid}-${Date.now()}.sock`)
  const frames = []
  const server = net.createServer((sock) => {
    sock.on('error', () => {})
    let buf = ''
    sock.on('data', (chunk) => {
      buf += chunk.toString('utf8')
      let nl
      while ((nl = buf.indexOf('\n')) !== -1) {
        const line = buf.slice(0, nl)
        buf = buf.slice(nl + 1)
        if (!line) continue
        const req = JSON.parse(line)
        if (req.t === 'hello') { sock.write('{"t":"helloAck","v":1}\n'); continue }
        frames.push(req)
        sock.write(JSON.stringify({ t: 'ok', id: req.id, payload: null }) + '\n')
      }
    })
  })
  await new Promise((resolve) => server.listen(sockPath, resolve))

  const client = net.connect(sockPath)
  await new Promise((resolve, reject) => {
    client.once('connect', resolve)
    client.once('error', reject)
  })
  await new Promise((resolve, reject) => {
    let ack = ''
    const onData = (chunk) => {
      ack += chunk.toString('utf8')
      if (!ack.endsWith('\n')) return
      client.pause()
      client.removeListener('data', onData)
      try { assert.deepEqual(JSON.parse(ack), { t: 'helloAck', v: 1 }); resolve() }
      catch (e) { reject(e) }
    }
    client.on('data', onData)
    client.write(JSON.stringify({ t: 'hello', v: 1, token: 'a'.repeat(64) }) + '\n')
  })

  // eslint-disable-next-line @typescript-eslint/no-require-imports -- 动态路径 require N-API 模块
  const addon = require(REAL_ADDON)
  addon.setSocketFd(client._handle.fd)
  addon.setup(() => {})
  return {
    addon,
    frames,
    close: async () => {
      try { client.destroy() } catch { /* 幂等 */ }
      await new Promise((resolve) => server.close(resolve))
      fs.rmSync(sockPath, { force: true })
    },
  }
}

/** 期望 ERR_INVALID_PARAM；返回错误对象供断言话术。 */
function expectInvalidParam(fn, what) {
  try {
    fn()
  } catch (e) {
    assert.equal(e.code, 'ERR_INVALID_PARAM', `${what} 的错误码（实际 ${e.code}: ${e.message}）`)
    return e
  }
  assert.fail(`${what} 没有抛错 —— 静默成帧正是这条门的回归形态`)
}

test(
  'invoke 入参类型不符 → ERR_INVALID_PARAM，且线上不多出一帧（N-API 类型不符不写缓冲）',
  { skip: SKIP },
  async () => {
    const host = await openHost()
    const { addon, frames } = host
    try {
      // ── ① 先打一发正常帧，把 ns/method 的栈槽位填满（症状 1 的前提）──
      addon.invoke('A'.repeat(60), 'matcher', null, 1, 2_000)
      {
        const deadline = Date.now() + 2_000
        while (frames.length < 1 && Date.now() < deadline) {
          await new Promise((r) => setTimeout(r, 25))
        }
      }
      assert.equal(frames.length, 1, '第一发正常帧应到宿主')

      // ── ② ns 不是字符串：修前这一帧会带着上一发的 'A'*60 发出去 ──
      expectInvalidParam(() => addon.invoke(1234, 'matcher', null, 2, 2_000), 'ns=Number')
      expectInvalidParam(() => addon.invoke(Buffer.from('bbbb'), 'm', null, 3, 2_000), 'ns=Buffer')
      // method 同一条（同一个 N-API 行为）
      expectInvalidParam(() => addon.invoke('images', 1234, null, 4, 2_000), 'method=Number')

      // ── ③ ns/method 超长：修前静默截断成另一个名字 ──
      expectInvalidParam(() => addon.invoke('z'.repeat(200), 'm', null, 5, 2_000), 'ns=200 字节')
      expectInvalidParam(() => addon.invoke('a11y', 'm'.repeat(200), null, 6, 2_000), 'method=200 字节')
      // 63 字节是上限内（+NUL = 64），必须照常放行
      addon.invoke('n'.repeat(63), 'm', null, 7, 2_000)

      // ── ④ reqId/ttl 不是数字：修前 id 恒 0 成帧、回包没人认领（悬挂到 TTL）──
      expectInvalidParam(() => addon.invoke('a11y', 'm', null, 10n, 2_000), 'reqId=BigInt')
      expectInvalidParam(() => addon.invoke('a11y', 'm', null, '12', 2_000), 'reqId=string')
      expectInvalidParam(() => addon.invoke('a11y', 'm', null, 13, '2000'), 'ttl=string')

      // ── ⑤ payload 不是字符串：修前静默按 null 发（方法按"无参"执行）──
      expectInvalidParam(() => addon.invoke('a11y', 'm', { x: 1 }, 14, 2_000), 'payload=object')
      expectInvalidParam(() => addon.invoke('a11y', 'm', 42, 15, 2_000), 'payload=number')

      // ── ⑥ 线上帧账：只有 ① 与"63 字节"那一发；所有被拒的调用一帧都没出去 ──
      const deadline = Date.now() + 2_000
      while (frames.length < 2 && Date.now() < deadline) {
        await new Promise((r) => setTimeout(r, 25))
      }
      assert.deepEqual(
        frames.map((f) => f.id),
        [1, 7],
        `宿主收到的帧必须只有放行的两条；被拒的调用不得静默成帧（实际 ${JSON.stringify(frames.map((f) => [f.id, f.ns, f.m]))}）`,
      )
      assert.equal(frames[1].ns, 'n'.repeat(63), '63 字节的 ns 原样成帧（不截断）')
      assert.equal(frames[0].ns, 'A'.repeat(60), '第一发正常帧原样成帧')
    } finally {
      await host.close()
    }
  },
)