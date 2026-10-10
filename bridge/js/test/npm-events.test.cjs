'use strict'
/**
 * npm 事件**拉取面**单测（§10.7 drainEvents + §12.3 onProgress 等）。
 *
 * 背景：桥没有宿主→脚本的推送通道（§7.5 入站面只有按 requestId 结算的 ok/err），
 * 三条订阅曾是「订阅了但永远不响」——本文件钉住拉取轮询把它们真正接上：
 * - mock 宿主**逐字复刻** Kotlin `NpmBridgeHandler.events` 的回包
 *   （{first,last,items}，空增量 first=last=sinceSeq，环有界丢最旧）；
 * - 三路事件（progress/warning/finished）共用一个游标，取完不重复投递；
 * - 响亮分档：ERR_NOT_IMPLEMENTED / 未知 type / 未知 phase / 未知 kind 必须炸，
 *   瞬时错误（超时断链）吞掉走下一拍且游标不动；
 * - 定时器自停：没人听了就不占着轮询（unref 不保活事件循环，同 startHeartbeat）。
 *
 * 对 dist/ 产物断言（npm test 先 tsc）。毒事件类用例（未知 type/kind/action）放最后：
 * 它们刻意把游标卡在毒事件之前，先跑会污染后面用例的游标。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default
const {
  npm,
  pumpInstallEvents,
  installEventPollPeriod,
} = require(path.resolve(__dirname, '..', 'dist', 'npm.js'))

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ── mock 宿主：环 + 拉取语义（与 InstallCoordinator.SeqRing/NpmBridgeHandler 同形） ──
const rings = { events: [] } // 与 Kotlin installEventRing 对偶
let eventSeq = 0
let eventCalls = 0
let lastEventSince = null
let hostMode = 'ok' // ok | broken | flaky

function hostPush(e) {
  rings.events.push({ seq: ++eventSeq, ...e })
}
function drain(sinceSeq, batch, key) {
  const picked = rings.events
    .filter((e) => e.seq > sinceSeq)
    .slice(0, batch)
  if (picked.length === 0) return { first: sinceSeq, last: sinceSeq, [key]: [] }
  return { first: picked[0].seq, last: picked[picked.length - 1].seq, [key]: picked }
}

auto.install((ns, method, payloadJson, reqId) => {
  if (ns !== 'npm') return undefined
  const p = payloadJson ? JSON.parse(payloadJson) : null
  const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
  const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
  switch (method) {
    case 'events': {
      eventCalls += 1
      lastEventSince = p.sinceSeq // 记录这拍拉取用的游标（断言瞬时错不推进它）
      if (hostMode === 'broken') {
        // Kotlin NpmBridgeHandler 对未知方法的回包（mock 分支之外一律此形状）
        err('ERR_NOT_IMPLEMENTED', `未知 npm 方法: events`)
        return undefined
      }
      if (hostMode === 'flaky') {
        err('ERR_TIMEOUT', '宿主瞬时不可达')
        return undefined
      }
      ok(JSON.stringify(drain(p.sinceSeq, p.batch ?? 32, 'events')))
      return undefined
    }
    default:
      err('ERR_NOT_IMPLEMENTED', `未知 npm 方法: ${method}`)
      return undefined
  }
})

const progressWire = (extra = {}) => ({
  type: 'progress',
  projectId: 'main',
  handleId: 'h1',
  phase: 'download',
  name: 'lodash',
  percent: 42,
  ...extra,
})

test('首订即拉：progress 事件到手，字段逐字透传，空增量不重复投递', async () => {
  hostPush(progressWire())
  const got = []
  const off = npm.onProgress((e) => got.push(e))
  try {
    await sleep(10) // 首订立即拉一轮（不等一个周期）
    assert.strictEqual(got.length, 1, `首订须立拉：${JSON.stringify(got)}`)
    assert.strictEqual(got[0].phase, 'download')
    assert.strictEqual(got[0].name, 'lodash')
    assert.strictEqual(got[0].percent, 42)
    assert.strictEqual(got[0].projectId, 'main')
    assert.strictEqual(got[0].handleId, 'h1')
    assert.ok(!('seq' in got[0]), 'seq 是宿主游标，不在 facade 的 InstallEvent 形状里')

    await pumpInstallEvents() // 空增量（first=last=sinceSeq）
    assert.strictEqual(got.length, 1, '取过即空增量，不重复投递')
  } finally {
    off()
  }
})

test('可空字段缺省为 null（宿主没填的 name/percent 原样 null，不编默认值）', async () => {
  hostPush(progressWire({ phase: 'queued', name: null, percent: null }))
  const got = []
  const off = npm.onProgress((e) => got.push(e))
  try {
    await sleep(10)
    assert.strictEqual(got.length, 1)
    assert.strictEqual(got[0].name, null)
    assert.strictEqual(got[0].percent, null)
    assert.strictEqual(got[0].phase, 'queued')
  } finally {
    off()
  }
})

test('warning 走 feedWarning 通道（kind 逐字校验只写一处），finished 成功失败都到', async () => {
  hostPush({ type: 'warning', projectId: 'main', handleId: 'h1', kind: 'scripts-skipped', pkgs: ['esbuild'], message: '脚本没跑' })
  hostPush({ type: 'finished', projectId: 'main', handleId: 'h1', success: false, detail: 'boom' })
  const warns = []
  const done = []
  const offW = npm.onWarning((e) => warns.push(e))
  const offF = npm.onFinished((e) => done.push(e))
  try {
    await sleep(10)
    assert.strictEqual(warns.length, 1, 'poll 是 warning 的生产投递方（feedWarning 生产侧零调用者）')
    assert.strictEqual(warns[0].kind, 'scripts-skipped')
    assert.deepStrictEqual(warns[0].pkgs, ['esbuild'])
    assert.strictEqual(done.length, 1)
    assert.strictEqual(done[0].success, false, '失败也必须到（install 回包只是已入队）')
    assert.strictEqual(done[0].detail, 'boom')
  } finally {
    offW()
    offF()
  }
})

test('未知 type 响亮（宿主新增上桥分支而 JS 没同步）', async () => {
  rings.events.push({ seq: ++eventSeq, type: 'metric', projectId: 'main' })
  try {
    await assert.rejects(() => pumpInstallEvents(), /未知安装事件 type: metric/)
  } finally {
    rings.events.length = 0
  }
})


