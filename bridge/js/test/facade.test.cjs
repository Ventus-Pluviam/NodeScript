'use strict'
/**
 * facade 冒烟测试（docs/framework-design.md §12.4 契约面）：
 * - 信封/单例安装/选择器条件聚合
 * - 错误目录（ERR_* 与 :domain:core.ErrorCode 逐字对齐）
 * - workManager 排期（daily/once 的 nextFireAfter 唯一时序来源）
 * - 未装宿主 → ERR_ENGINE_STOPPED；慢 handler + TTL → ERR_TIMEOUT
 * - InvokeHandler 异步路径：内联答案 / handleResponse 按 id 结算 / 响应去重 / 错误折叠
 *
 * 直接对 dist/ 构建产物断言（先 tsc 再 npm run build）。
 * 安装是单例：全部跨进程路径断言集中在一个 test 内完成（node:test 同文件顺序执行）。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default
const { NotFoundError, AutojsError, ERROR_CODES } = autoModule

test('未装宿主：invoke 以 ERR_ENGINE_STOPPED 快速拒绝（不静默）', async () => {
  assert.strictEqual(auto.installed, false)
  await assert.rejects(() => auto.engines.poolStats(), (e) => e.code === 'ERR_ENGINE_STOPPED')
})

test('桥异步路径：内联答案 / handleResponse / 响应去重 / 错误折叠 / TTL', async () => {
  // 宿主：按 method 分发；deferred 返回 undefined 表示「已投递，等响应」
  const captured = []
  auto.install((ns, method, payloadJson, reqId) => {
    assert.strictEqual(ns, 'probe')
    captured.push(reqId)
    if (method === 'inline') return { echo: JSON.parse(payloadJson) } // 同步返回值 → 内联答案
    if (method === 'hang') return undefined // 永不回应 → 走 TTL
    return undefined // deferred：由 handleResponse 结算
  })
  assert.strictEqual(auto.installed, true)

  // 内联答案
  const inl = await auto.bridge.invoke('probe', 'inline', { a: 1 })
  assert.deepEqual(inl, { echo: { a: 1 } })

  // deferred + ok
  const p1 = auto.bridge.invoke('probe', 'deferred', null)
  const id1 = captured[captured.length - 1]
  auto.handleResponse({ t: 'ok', id: id1, payload: JSON.stringify('hi') })
  assert.strictEqual(await p1, 'hi')

  // deferred + err（错误折叠为 AutojsError，code 保留）
  const p2 = auto.bridge.invoke('probe', 'deferred', null)
  const id2 = captured[captured.length - 1]
  auto.handleResponse({ t: 'err', id: id2, code: 'ERR_NOT_IMPLEMENTED', detail: 'native 缺失' })
  await assert.rejects(p2, (e) => {
    return e instanceof AutojsError && e.code === 'ERR_NOT_IMPLEMENTED' && e.detail === 'native 缺失'
  })

  // 重复/未知 id 响应 → 丢弃（不结算未挂起调用、不抛错）
  auto.handleResponse({ t: 'ok', id: 999_999, payload: 'null' })
  assert.strictEqual(captured.filter((i) => i === 999_999).length, 0)

  // TTL：hang 永不回应 → 80ms 后 ERR_TIMEOUT
  const p3 = auto.bridge.invoke('probe', 'hang', null, { ttl: 80 })
  await assert.rejects(p3, (e) => e.code === 'ERR_TIMEOUT')
})

test('单例安装：重复 install 抛错', () => {
  assert.throws(() => auto.install(() => {}), /不允许重复 install/)
})

test('信封编解码（§7.1 JsonTransport 字段对齐）', () => {
  const req = auto.envelope.encodeRequest({ id: 1, ns: 'a11y', m: 'findOne', ttl: 5_000, payload: null })
  assert.deepEqual(req, { t: 'req', id: 1, ns: 'a11y', m: 'findOne', ttl: 5_000, payload: null })
  assert.deepEqual(auto.envelope.ok(1, '{}'), { t: 'ok', id: 1, payload: '{}' })
  assert.deepEqual(auto.envelope.err(1, 'ERR_X', 'd'), { t: 'err', id: 1, code: 'ERR_X', detail: 'd' })
})

test('选择器条件聚合 + 计时', () => {
  const sel = auto.a11y.selector().text('x').desc('y').clickable(true).time(3_000)
  assert.deepEqual(sel.__conditions__, { text: 'x', desc: 'y', clickable: true })
})

test('错误目录与 NotFoundError（§7.6 对齐）', () => {
  const nb = new NotFoundError('hi')
  assert.ok(nb instanceof NotFoundError)
  assert.strictEqual(nb.code, 'ERR_NOT_FOUND')
  assert.ok(nb.is('ERR_NOT_FOUND'))
  for (const c of ['ERR_TIMEOUT', 'ERR_STALE_HANDLE', 'ERR_PERMISSION_DENIED', 'ERR_NOT_FOUND']) {
    assert.ok(ERROR_CODES.includes(c), `目录缺失 ${c}`)
  }
})

test('workManager 排期：daily 钟点 + once 相对延迟', () => {
  const now = Date.now()
  const dd = new Date(auto.workManager.nextFireAfter(auto.workManager.daily(8, 30), now))
  assert.strictEqual(dd.getHours(), 8)
  assert.strictEqual(dd.getMinutes(), 30)
  assert.ok(auto.workManager.nextFireAfter(auto.workManager.daily(8, 30), now) > now)
  assert.strictEqual(auto.workManager.nextFireAfter(auto.workManager.once(60), 1_000), 61_000)
  assert.throws(() => auto.workManager.daily(24, 0), RangeError)
  assert.throws(() => auto.workManager.once(-1), RangeError)
})

test('workManager 排期：cron 本地预览与宿主同值（每日九点/每周一/不可能日期 null）', () => {
  // 锚点：D0 = 1970-01-11（周日）。断言一律用本地墙钟构造期望 —— nextFireAfter
  //（daily 与 cron 镜像都是）按本地 Date 算，写死 UTC epoch 会在非 UTC 时区红。
  const d0 = Date.UTC(1970, 0, 11, 0, 0, 0, 0)
  const mon9Local = new Date(1970, 0, 12, 9, 0, 0, 0).getTime() // 周一 09:00（本地）
  assert.strictEqual(
    auto.workManager.nextFireAfter(auto.workManager.cron('0 9 * * *'), d0),
    auto.workManager.nextFireAfter(auto.workManager.daily(9, 0), d0),
    'cron 每日九点与 daily 同值',
  )
  assert.strictEqual(auto.workManager.nextFireAfter(auto.workManager.cron('0 9 * * 1'), d0), mon9Local)
  assert.strictEqual(auto.workManager.nextFireAfter(auto.workManager.cron('0 9 * * mon'), d0), mon9Local)
  assert.strictEqual(auto.workManager.nextFireAfter(auto.workManager.cron('0 0 30 2 *'), d0), null)
  assert.strictEqual(
    auto.workManager.nextFireAfter(auto.workManager.fromInput({ on: 'cron', expr: '0 9 * * 1' }), d0),
    mon9Local,
    'fromInput cron 分支',
  )
  assert.throws(() => auto.workManager.cron('0 9 * *'), RangeError)
  assert.throws(() => auto.workManager.cron(42), RangeError)
})

test('新增 §10.8 错误码同步（:domain 已加目录）', () => {
  for (const c of [
    'ERR_NPM_SPAWN_BLOCKED',
    'ERR_NOT_SUPPORTED',
    'ERR_REGISTRY_UNAVAILABLE',
    'ERR_NPM_LOWMEM',
  ]) {
    assert.ok(ERROR_CODES.includes(c), `目录缺失 ${c}`)
  }
})

test('console 数据面：fire-and-forget 永不抛 + 序列化降级 + queueError', async () => {
  // 本文件早先已 install probe handler（只认 ns==='probe'，其余抛断言错）。
  // console.* 走 ns='console' → 宿主抛错 → send() 吞掉并经 queueError 通知，脚本侧 promise 必须 resolve。
  const queueErrors = []
  const off = auto.console.onQueueError((e) => queueErrors.push(e))
  try {
    const cyclic = {}
    cyclic.self = cyclic
    await auto.console.log('hello', { a: 1 }, 42) // resolve，不抛
    await auto.console.warn('循环', cyclic) // JSON.stringify 抛 → String() 降级，仍 resolve
    await auto.console.error('函数', () => {}, 10n) // 函数/BigInt 降级，仍 resolve
    await auto.console.info('i')
    await auto.console.debug('d')
    assert.strictEqual(queueErrors.length, 5)
    assert.ok(queueErrors.every((e) => e.level && typeof e.reason === 'string'))
  } finally {
    off()
  }
  // 退订后不再通知（但调用仍 resolve）
  await auto.console.log('after-off')
})