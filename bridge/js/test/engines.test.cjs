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
function installMockEngines() {
  if (sharedRuns) return sharedRuns
  const runs = new Map()
  sharedRuns = runs
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
        runs.set(runId, p)
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
  assert.strictEqual(runs.get(s.runId).runNonce, 'n1', 'runNonce 透传（§8.5）')
  assert.deepEqual(runs.get(s.runId).args, ['x'])
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

test('engines.channel：命名通道回 {name,channelId}', async () => {
  const ch = await auto.engines.channel('progress')
  assert.strictEqual(ch.name, 'progress')
  assert.strictEqual(ch.channelId, 7)
})
