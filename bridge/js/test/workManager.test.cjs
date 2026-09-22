'use strict'
/**
 * workManager 桥面测试（§8.6/§9.6 脚本建任务链路 + Kotlin WorkManagerNamespaceHandler）：
 * JS facade 的 wire 形状（create/cancel/list 载荷）与 Kotlin 侧解析器逐字段对齐；
 * 用 mock 宿主验证"发的出去、回的来能解析"。Kotlin 真语义由 WorkManagerNamespaceHandlerTest 覆盖。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default

/** mock workManager 宿主：内存任务表，按 Kotlin handler 响应形状回包。 */
const tasks = new Map()
function installMockWorkManager() {
  auto.install((ns, method, payloadJson, reqId) => {
    if (ns !== 'workManager') return undefined
    const p = payloadJson ? JSON.parse(payloadJson) : null
    const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
    const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
    if (method === 'create') {
      if (!p || !p.name || !p.projectId || !p.scriptPath || !p.schedule) {
        err('ERR_INVALID_PARAM', '缺字段'); return undefined
      }
      if (p.schedule.kind === 'cron') { err('ERR_NOT_IMPLEMENTED', 'cron P1 未落地'); return undefined }
      const id = p.id || `srv-${tasks.size + 1}`
      tasks.set(id, { ...p, id })
      ok(JSON.stringify({ id })); return undefined
    }
    if (method === 'cancel') { tasks.delete(p && p.id); ok('true'); return undefined }
    if (method === 'list') { ok(JSON.stringify([...tasks.values()])); return undefined }
    err('ERR_NOT_IMPLEMENTED', `未知 workManager 方法: ${method}`)
    return undefined
  })
}

test('workManager 桥：create → list → cancel 全链路', async () => {
  installMockWorkManager()
  const { id } = await auto.workManager.createTimedTask({
    name: '早安', projectId: 'p', scriptPath: 'a.js', schedule: auto.workManager.daily(8, 30),
  })
  assert.ok(id, '服务端回 id')
  const listed = await auto.workManager.listTasks()
  assert.strictEqual(listed.length, 1)
  assert.strictEqual(listed[0].schedule.kind, 'daily')
  assert.strictEqual(await auto.workManager.cancelTask(id), true)
  assert.deepEqual(await auto.workManager.listTasks(), [])
})

test('workManager 桥：cron 如实拒绝（P1 未落地不伪装）', async () => {
  await assert.rejects(
    auto.workManager.createTimedTask({
      name: 'n', projectId: 'p', scriptPath: 'a.js',
      schedule: { kind: 'cron', expr: '0 9 * * *' },
    }),
    (e) => e.code === 'ERR_NOT_IMPLEMENTED',
  )
})
