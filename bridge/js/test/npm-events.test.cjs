'use strict'
/**
 * npm 事件**拉取面**单测（§10.7 drainEvents/drainApprovals + §12.3 onProgress 等）。
 *
 * 背景：桥没有宿主→脚本的推送通道（§7.5 入站面只有按 requestId 结算的 ok/err），
 * 三条订阅曾是「订阅了但永远不响」——本文件钉住拉取轮询把它们真正接上：
 * - mock 宿主**逐字复刻** Kotlin `NpmBridgeHandler.events/approvals` 的回包
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
  pumpApprovals,
  installEventPollPeriod,
  installApprovalPollPeriod,
} = require(path.resolve(__dirname, '..', 'dist', 'npm.js'))

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ── mock 宿主：环 + 拉取语义（与 InstallCoordinator.SeqRing/NpmBridgeHandler 同形） ──
const rings = { events: [], approvals: [] } // 与 Kotlin installEventRing / approvalRing 对偶：两条流不共环
let eventSeq = 0
let approvalSeq = 0
let eventCalls = 0
let approvalCalls = 0
let lastEventSince = null
let hostMode = 'ok' // ok | broken | flaky

function hostPush(e, ring = 'events') {
  if (ring === 'approvals') rings.approvals.push({ seq: ++approvalSeq, ...e })
  else rings.events.push({ seq: ++eventSeq, ...e })
}
function drain(sinceSeq, batch, key) {
  const picked = rings[key === 'requests' ? 'approvals' : 'events']
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
    case 'approvals': {
      approvalCalls += 1
      if (hostMode === 'broken') {
        err('ERR_NOT_IMPLEMENTED', `未知 npm 方法: approvals`)
        return undefined
      }
      ok(JSON.stringify(drain(p.sinceSeq, p.batch ?? 32, 'requests')))
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

test('approvals 拉取：审批请求带游标到手，取完不重复', async () => {
  hostPush(
    {
      type: 'approval',
      id: 'apr-1',
      projectId: 'main',
      pkg: 'esbuild',
      versionHash: 'hash-1',
      action: 'install_script',
      requestedAtMillis: 7,
    },
    'approvals', // 两条流各一个环（对偶 Kotlin 两个 SeqRing）
  )
  const got = []
  const off = npm.onApproval((e) => got.push(e))
  try {
    await sleep(10)
    assert.strictEqual(got.length, 1, `首订立拉审批：${JSON.stringify(got)}`)
    assert.strictEqual(got[0].action, 'install_script')
    assert.strictEqual(got[0].pkg, 'esbuild')
    assert.strictEqual(got[0].requestedAtMillis, 7)
    await pumpApprovals()
    assert.strictEqual(got.length, 1, '审批游标独立前进，不重复投递')
  } finally {
    off()
  }
})

test('宿主没实现 events → ERR_NOT_IMPLEMENTED 响亮上抛（订阅了却收不到 = 最恶失败面）', async () => {
  hostMode = 'broken'
  try {
    await assert.rejects(
      () => pumpInstallEvents(),
      (e) => e.code === 'ERR_NOT_IMPLEMENTED',
      '不许吞：吞掉就是静默的「永远收不到」',
    )
    await assert.rejects(() => pumpApprovals(), (e) => e.code === 'ERR_NOT_IMPLEMENTED')
  } finally {
    hostMode = 'ok'
  }
})

test('瞬时错误吞掉走下一拍，游标不动（不丢事件、不炸脚本）', async () => {
  await pumpInstallEvents() // 先拉一拍取基线游标（环里此刻有前用例余量已被取完）
  const cursorBefore = lastEventSince
  hostPush(progressWire({ phase: 'reify', percent: 80 }))

  hostMode = 'flaky'
  await pumpInstallEvents() // 超时：吞掉（脚本不炸）
  assert.strictEqual(lastEventSince, cursorBefore, '瞬时错那一拍不该改游标')

  hostMode = 'ok'
  const got = []
  const off = npm.onProgress((e) => got.push(e)) // 首订立拉 = 恢复后的第一拍
  try {
    await sleep(10)
    assert.strictEqual(got.length, 1, '恢复后从原游标续拉，事件一个不丢')
    assert.strictEqual(got[0].percent, 80)
    await pumpInstallEvents() // 再拉一拍：这一拍用的 sinceSeq 就是推进后的游标
    assert.ok(lastEventSince > cursorBefore, `成功的一拍才推进游标（${cursorBefore} → ${lastEventSince}）`)
  } finally {
    hostMode = 'ok'
    off()
  }
})

test('退订干净后定时器自停（unref 不保活事件循环，同 startHeartbeat）', async () => {
  installEventPollPeriod(10)
  installApprovalPollPeriod(10)
  const off = npm.onProgress(() => {})
  await sleep(30)
  off()
  await sleep(30) // 下一拍发现没人听 → 自停
  const settled = eventCalls
  await sleep(50) // 若没自停，这几拍会继续涨
  assert.strictEqual(eventCalls, settled, `事件轮询须自停（停在 ${settled}，后又 ${eventCalls - settled} 拍）`)
})

test('轮询周期注入缝拒绝非正值（与 installHeartbeatPeriod 同纪律）', () => {
  assert.throws(() => installEventPollPeriod(0), /必须 > 0/)
  assert.throws(() => installEventPollPeriod(-5), /必须 > 0/)
  assert.throws(() => installApprovalPollPeriod(NaN), /必须 > 0/)
  installEventPollPeriod(250)
  installApprovalPollPeriod(250)
})

// ── 毒事件用例放最后：抛在游标前进之前，先跑会把游标卡死污染上面用例 ──

test('未知 phase 响亮（Kotlin 新增枚举而 JS 没同步 = 脚本 switch 整段落 default）', async () => {
  hostPush(progressWire({ phase: 'post_check' })) // lowercase 事故的形状：真名是 post-check
  try {
    await assert.rejects(() => pumpInstallEvents(), /未知安装阶段: post_check/)
  } finally {
    rings.events.length = 0 // 毒事件抛在游标前进之前（不许跳过装作没事），跑完清环免得污染后用例
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

test('未知审批动作响亮（action wire 名不是 JS 联合成员）', async () => {
  rings.approvals.push({
    seq: ++approvalSeq,
    type: 'approval',
    id: 'apr-9',
    projectId: 'main',
    pkg: 'x',
    versionHash: 'h',
    action: 'RUN_SCRIPT', // 真实 wire 是 run_script；大写 = 漂移
    requestedAtMillis: 1,
  })
  try {
    await assert.rejects(() => pumpApprovals(), /未知审批动作: RUN_SCRIPT/)
  } finally {
    rings.approvals.length = 0
  }
})
