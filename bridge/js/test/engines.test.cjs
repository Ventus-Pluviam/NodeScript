'use strict'
/**
 * engines namespace 双侧契约测试（§8 / §12.3 + Kotlin EnginesNamespaceHandler）：
 * JS facade 的 wire 形状（exec payload 字段 / handle:{refId,generation} / poolStats 快照）
 * 与 Kotlin 侧解析器逐字段对齐。Kotlin 真机语义由 EnginesNamespaceHandlerTest 覆盖；
 * 这里用 mock 宿主验证 JS 侧"发的出去、回的来能解析"。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default

/** mock engines 宿主：按 Kotlin EnginesNamespaceHandler 的响应形状回包。 */
let sharedRuns = null
let sharedSeq = null
/** mock 通道缓冲：emit 写入、drain 按 sinceSeq 游标拉取（复刻 Kotlin ChannelState 语义）。 */
let emitted = null
let closedChannels = null
function installMockEngines() {
  if (sharedRuns) return sharedRuns
  const runs = new Map()
  sharedRuns = runs
  sharedSeq = new Map()
  emitted = []
  closedChannels = new Set()
  let nextRun = 100
  auto.install((ns, method, payloadJson, reqId) => {
    if (ns !== 'engines') return undefined
    const p = payloadJson ? JSON.parse(payloadJson) : null
    switch (method) {
      case 'exec': {
        if (!p || !p.projectId || !p.scriptPath) {
          auto.handleResponse({ t: 'err', id: reqId, code: 'ERR_INVALID_PARAM', detail: '缺字段' })
          return undefined
        }
        const runId = nextRun++
        runs.set(runId, { payload: p, status: 'RUNNING' })
        auto.handleResponse({
          t: 'ok', id: reqId,
          payload: JSON.stringify({ runId, handle: { refId: runId, generation: 1 } }),
        })
        return undefined
      }
      case 'stop': {
        if (!runs.has(p.runId)) {
          auto.handleResponse({ t: 'err', id: reqId, code: 'ERR_NOT_FOUND', detail: `未知 runId: ${p.runId}` })
        } else {
          runs.delete(p.runId)
          auto.handleResponse({ t: 'ok', id: reqId, payload: 'true' })
        }
        return undefined
      }
      case 'poolStats': {
        auto.handleResponse({
          t: 'ok', id: reqId,
          payload: JSON.stringify({ capacity: 1, free: runs.size === 0 ? 1 : 0, busy: runs.size }),
        })
        return undefined
      }
      case 'channel': {
        if (!p || !p.name) {
          auto.handleResponse({ t: 'err', id: reqId, code: 'ERR_INVALID_PARAM', detail: '缺 name' })
        } else {
          auto.handleResponse({ t: 'ok', id: reqId, payload: JSON.stringify({ name: p.name, channelId: 7 }) })
        }
        return undefined
      }
      case 'channelEmit': {
        // Kotlin channelEmit 语义：未知 channelId → ERR_NOT_FOUND；空事件名 → ERR_INVALID_PARAM
        if (!p || typeof p.channelId !== 'number' || typeof p.event !== 'string') {
          auto.handleResponse({ t: 'err', id: reqId, code: 'ERR_INVALID_PARAM', detail: '缺 channelId/event' })
        } else if (p.channelId !== 7) {
          auto.handleResponse({ t: 'err', id: reqId, code: 'ERR_NOT_FOUND', detail: `未知 channelId: ${p.channelId}` })
        } else if (p.event.length === 0) {
          auto.handleResponse({ t: 'err', id: reqId, code: 'ERR_INVALID_PARAM', detail: '事件名不得为空' })
        } else {
          emitted.push({ event: p.event, payload: p.payload ?? null })
          auto.handleResponse({ t: 'ok', id: reqId, payload: null })
        }
        return undefined
      }
      case 'channelDrain': {
        if (!p || typeof p.channelId !== 'number') {
          auto.handleResponse({ t: 'err', id: reqId, code: 'ERR_INVALID_PARAM', detail: '缺 channelId' })
        } else if (p.channelId !== 7) {
          auto.handleResponse({ t: 'err', id: reqId, code: 'ERR_NOT_FOUND', detail: `未知 channelId: ${p.channelId}` })
        } else {
          const since = typeof p.sinceSeq === 'number' ? p.sinceSeq : 0
          const max = typeof p.max === 'number' ? p.max : 128
          const events = emitted
            .map((e, i) => ({ seq: i + 1, event: e.event, payload: e.payload }))
            .filter((e) => e.seq > since)
            .slice(0, max)
          auto.handleResponse({
            t: 'ok', id: reqId,
            payload: JSON.stringify({ last: events.length > 0 ? events[events.length - 1].seq : since, events }),
          })
        }
        return undefined
      }
      case 'channelClose': {
        if (!p || typeof p.channelId !== 'number') {
          auto.handleResponse({ t: 'err', id: reqId, code: 'ERR_INVALID_PARAM', detail: '缺 channelId' })
        } else if (p.channelId !== 7) {
          auto.handleResponse({ t: 'err', id: reqId, code: 'ERR_NOT_FOUND', detail: `未知 channelId: ${p.channelId}` })
        } else {
          closedChannels.add(p.channelId)
          auto.handleResponse({ t: 'ok', id: reqId, payload: 'true' })
        }
        return undefined
      }
      case 'status': {
        // Kotlin probeStatus 语义：在途 → 状态名；结算/从未存在 → ERR_NOT_FOUND（不伪造 STOPPED）
        if (!p || typeof p.runId !== 'number') {
          auto.handleResponse({ t: 'err', id: reqId, code: 'ERR_INVALID_PARAM', detail: '缺 runId' })
        } else if (!runs.has(p.runId)) {
          auto.handleResponse({ t: 'err', id: reqId, code: 'ERR_NOT_FOUND', detail: `未知 runId: ${p.runId}` })
        } else {
          auto.handleResponse({ t: 'ok', id: reqId, payload: JSON.stringify(runs.get(p.runId).status) })
        }
        return undefined
      }
      case 'heartbeat': {
        // Kotlin HeartbeatLedger 语义：缺字段 → ERR_INVALID_PARAM；
        // 采纳与否由 **序号** 判（同/旧 seq 不刷时间戳 → Ok false，不是错误）。
        if (!p || typeof p.runId !== 'number' || typeof p.seq !== 'number') {
          auto.handleResponse({ t: 'err', id: reqId, code: 'ERR_INVALID_PARAM', detail: '缺 runId/seq' })
          return undefined
        }
        const prev = sharedSeq.get(p.runId) ?? 0
        const accepted = p.seq > prev
        if (accepted) sharedSeq.set(p.runId, p.seq)
        auto.handleResponse({ t: 'ok', id: reqId, payload: accepted ? 'true' : 'false' })
        return undefined
      }
      default:
        auto.handleResponse({ t: 'err', id: reqId, code: 'ERR_NOT_IMPLEMENTED', detail: `未知 engines 方法: ${method}` })
        return undefined
    }
  })
  return runs
}

test('engines.exec → runId + handle:{refId,generation}（Kotlin 形状）', async () => {
  const runs = installMockEngines()
  const s = await auto.engines.exec({ projectId: 'p1', scriptPath: 'a.js', args: ['x'], runNonce: 'n1' })
  assert.strictEqual(typeof s.runId, 'number')
  assert.strictEqual(s.handle.refId, s.runId, 'handle.refId 与 runId 同源（Kotlin 语义）')
  assert.strictEqual(s.handle.generation, 1)
  assert.strictEqual(runs.get(s.runId).payload.runNonce, 'n1', 'runNonce 透传（§8.5）')
  assert.deepEqual(runs.get(s.runId).payload.args, ['x'])
})
test('engines.exec waitTimeoutMillis 透传 payload（Kotlin 优先口径）', async () => {
  const runs = installMockEngines()
  for (const [runId] of runsSnapshot()) await auto.engines.stop(runId).catch(() => {})
  const s = await auto.engines.exec({ projectId: 'p1', scriptPath: 'a.js', waitTimeoutMillis: 200 })
  assert.strictEqual(runs.get(s.runId).payload.waitTimeoutMillis, 200, '显式排队上限进 payload（Kotlin 侧优先于桥 TTL）')
  await auto.engines.stop(s.runId)
  const s2 = await auto.engines.exec({ projectId: 'p1', scriptPath: 'a.js' })
  assert.strictEqual('waitTimeoutMillis' in runs.get(s2.runId).payload, false, '缺省不发该键（JSON.stringify 丢弃 undefined）→ 宿主按桥 TTL 推导')
  await auto.engines.stop(s2.runId)
})

/** 跨 test 共享的 mock runs 快照（单例桥：前序 test 的遗留 run 需显式清理）。 */
function runsSnapshot() {
  return sharedRuns ? [...sharedRuns.keys()].map((runId) => [runId]) : []
}

test('engines.stop：干净 true；二次 stop → ERR_NOT_FOUND（不静默）', async () => {
  // 先清掉 test1 遗留的 run（单例桥 mock runs 跨 test 共享）
  for (const [runId] of runsSnapshot()) await auto.engines.stop(runId).catch(() => {})
  const s = await auto.engines.exec({ projectId: 'p1', scriptPath: 'a.js' })
  assert.strictEqual(await auto.engines.stop(s.runId), true)
  await assert.rejects(() => auto.engines.stop(s.runId), (e) => e.code === 'ERR_NOT_FOUND')
})

test('engines.poolStats：capacity/free/busy 快照', async () => {
  // 先清遗留 run，再断言空闲快照形状（mock 容量 1）
  for (const [runId] of runsSnapshot()) await auto.engines.stop(runId).catch(() => {})
  const before = await auto.engines.poolStats()
  assert.deepEqual(before, { capacity: 1, free: 1, busy: 0 })
  const s = await auto.engines.exec({ projectId: 'p1', scriptPath: 'a.js' })
  const during = await auto.engines.poolStats()
  assert.deepEqual(during, { capacity: 1, free: 0, busy: 1 })
  await auto.engines.stop(s.runId)
})

test('engines.channel：命名通道回 {name,channelId} 且包成可用通道', async () => {
  const ch = await auto.engines.channel('progress')
  assert.strictEqual(ch.name, 'progress')
  assert.strictEqual(ch.channelId, 7)
  assert.strictEqual(typeof ch.emit, 'function', 'channel() 不再是 wire 原样：.emit 可用')
  assert.strictEqual(typeof ch.on, 'function')
  assert.strictEqual(typeof ch.close, 'function')
})

test('通道 emit→on：发出去的事件经 drain 游标送到订阅者', async () => {
  installMockEngines()
  emitted.length = 0
  const ch = await auto.engines.channel('progress')
  await ch.emit('done', '3')
  await ch.emit('other', null)
  const got = []
  const sub = ch.on('done', (payload) => got.push(payload), { pollMillis: 20 })
  await new Promise((r) => setTimeout(r, 150))
  sub.cancel()
  assert.deepEqual(got, ['3'], '只收到订阅的事件名，游标只进不退不重放')
})

test('通道 on 取消后不再回调', async () => {
  installMockEngines()
  emitted.length = 0
  const ch = await auto.engines.channel('progress')
  const got = []
  const sub = ch.on('done', (payload) => got.push(payload), { pollMillis: 20 })
  sub.cancel()
  await ch.emit('done', 'late')
  await new Promise((r) => setTimeout(r, 100))
  assert.deepEqual(got, [], 'cancel 后轮询即停')
})

test('通道 emit 空事件名回 ERR_INVALID_PARAM（宿主判，不预检）', async () => {
  installMockEngines()
  const ch = await auto.engines.channel('progress')
  await assert.rejects(() => ch.emit(''), (e) => e.code === 'ERR_INVALID_PARAM')
})

test('通道 close 幂等：重复 close 不再发桥调用', async () => {
  installMockEngines()
  emitted.length = 0
  const ch = await auto.engines.channel('progress')
  await ch.close()
  assert.ok(closedChannels.has(7), '第一次 close 发 channelClose')
  closedChannels.delete(7)
  await ch.close()
  assert.ok(!closedChannels.has(7), '第二次 close 本地 no-op，不再发桥调用')
})

/**
 * engines.heartbeat（§8.4 缺口②）：wire 形状与 Kotlin HeartbeatLedger 对齐 ——
 * `{runId,seq}` 为上、采纳与否由 **序号** 判（不是调用方自称）。这里用 mock 宿主验证
 * JS 侧"发的出去、回的来能解析"；账本真机语义由 HeartbeatLedgerTest 覆盖。
 */
test('engines.heartbeat：递增 seq 被采纳，重复 seq 回 false', async () => {
  const runs = installMockEngines()
  const s = await auto.engines.exec({ projectId: 'p1', scriptPath: 'a.js' })
  assert.strictEqual(await auto.engines.heartbeat(s.runId, 1), true)
  assert.strictEqual(await auto.engines.heartbeat(s.runId, 2), true)
  assert.strictEqual(await auto.engines.heartbeat(s.runId, 2), false, '同 seq = 积压帧，不刷时间戳')
  assert.strictEqual(await auto.engines.heartbeat(s.runId, 1), false, '旧 seq 同理')
  assert.ok(runs.has(s.runId))
  await auto.engines.stop(s.runId)
})

test('exec 回会话句柄：cancel 可用，channel 恒 null（显式开通道）', async () => {
  installMockEngines()
  const s = await auto.engines.exec({ projectId: 'p1', scriptPath: 'a.js' })
  assert.strictEqual(typeof s.cancel, 'function', 'exec 不再是 wire 原样：.cancel 可用')
  assert.strictEqual(typeof s.onExit, 'function')
  assert.strictEqual(s.channel, null, '会话不隐式持通道：走 engines.channel(name) 显式开')
  assert.strictEqual(await s.cancel(), true, 'cancel → engines.stop 归口')
  await assert.rejects(() => auto.engines.stop(s.runId), (e) => e.code === 'ERR_NOT_FOUND')
})

test('engines.status：在途回状态名，结算后 NOT_FOUND（不伪造 STOPPED）', async () => {
  installMockEngines()
  const s = await auto.engines.exec({ projectId: 'p1', scriptPath: 'a.js' })
  assert.strictEqual(await auto.engines.status(s.runId), 'RUNNING')
  await auto.engines.stop(s.runId)
  await assert.rejects(() => auto.engines.status(s.runId), (e) => e.code === 'ERR_NOT_FOUND')
})

/** 回调 promise 配 ref 超时 race：onExit/channel.on 的回调只靠轮询 resolve，
 * loop 里若只剩 unref 轮询定时器会被提前收割（cancelledByParent）—— race 里的
 * sleep 是 ref，保活 loop；超时未回调则响亮失败而非挂起。 */
function withTimeout(promise, ms, what) {
  let timer = null
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`超时 ${ms}ms 未回调: ${what}`)), ms)
  })
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
}

test('会话 onExit：STOPPED 报 null（干净结束）', async () => {
  installMockEngines()
  const s = await auto.engines.exec({ projectId: 'p1', scriptPath: 'a.js' })
  await withTimeout(new Promise((resolve) => {
    const sub = s.onExit((info) => {
      assert.strictEqual(info, null, 'STOPPED = 干净结束，无 CrashInfo')
      resolve(undefined)
    }, { pollMillis: 20 })
    void sub
    sharedRuns.get(s.runId).status = 'STOPPED'
  }), 1000, 'STOPPED onExit')
  await auto.engines.stop(s.runId).catch(() => {})
})

test('会话 onExit：CRASHED 报 cause（不吞终态）', async () => {
  installMockEngines()
  const s = await auto.engines.exec({ projectId: 'p1', scriptPath: 'a.js' })
  await withTimeout(new Promise((resolve) => {
    s.onExit((info) => {
      assert.strictEqual(info && info.cause, 'CRASHED')
      resolve(undefined)
    }, { pollMillis: 20 })
    sharedRuns.get(s.runId).status = 'CRASHED'
  }), 1000, 'CRASHED onExit')
  await auto.engines.stop(s.runId).catch(() => {})
})

test('会话 onExit：本会话 cancel 后结算报 null，外部结算报 UNKNOWN（不伪装干净）', async () => {
  installMockEngines()
  // 本会话亲手停的：结算离表 = 我们停的 = 干净
  const s1 = await auto.engines.exec({ projectId: 'p1', scriptPath: 'a.js' })
  await withTimeout(new Promise((resolve) => {
    s1.onExit((info) => {
      assert.strictEqual(info, null, '经本会话 cancel 的结算 = 干净')
      resolve(undefined)
    }, { pollMillis: 20 })
    void s1.cancel().then(() => {})
  }), 1000, 'cancel 后 onExit')
  // 外部结算的（他人 stop/看门狗/跑完离表）：UNKNOWN，绝不报 null
  const s2 = await auto.engines.exec({ projectId: 'p1', scriptPath: 'a.js' })
  await withTimeout(new Promise((resolve) => {
    s2.onExit((info) => {
      assert.strictEqual(info && info.cause, 'UNKNOWN', '外部结算不得伪装成干净结束')
      resolve(undefined)
    }, { pollMillis: 20 })
    void auto.engines.stop(s2.runId).then(() => {})
  }), 1000, '外部结算 onExit')
})

test('会话 onExit：取消订阅后不再回调', async () => {
  installMockEngines()
  const s = await auto.engines.exec({ projectId: 'p1', scriptPath: 'a.js' })
  let called = false
  const sub = s.onExit(() => { called = true }, { pollMillis: 20 })
  sub.cancel()
  sharedRuns.get(s.runId).status = 'STOPPED'
  await new Promise((r) => setTimeout(r, 100))
  assert.strictEqual(called, false, 'cancel 后轮询即停')
  await auto.engines.stop(s.runId).catch(() => {})
})

test('engines.heartbeat：缺字段回 ERR_INVALID_PARAM（不伪造成功）', async () => {
  installMockEngines()
  await assert.rejects(
    () => auto.engines.heartbeat(1),
    (e) => e.code === 'ERR_INVALID_PARAM',
    '缺 seq 不得悄悄当作心跳',
  )
})
