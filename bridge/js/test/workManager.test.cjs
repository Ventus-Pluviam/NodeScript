'use strict'
/**
 * workManager 桥面测试（§8.6/§9.6 脚本建任务链路 + Kotlin WorkManagerNamespaceHandler）：
 * JS facade 的 wire 形状（create/cancel/list 载荷）与 Kotlin 侧解析器逐字段对齐；
 * 用 mock 宿主验证"发的出去、回的来能解析"。Kotlin 真语义由 WorkManagerNamespaceHandlerTest 覆盖。
 * mock 宿主对 cron 只做形状门（kind==='cron' 须带非空 expr，否则 ERR_INVALID_PARAM）——
 * 段内合法性（范围/名字/步长）是宿主 CronTab.parse 的事，mock 不复述第二套校验。
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
      if (p.schedule.kind === 'cron' && (!p.schedule.expr || typeof p.schedule.expr !== 'string')) {
        err('ERR_INVALID_PARAM', 'cron 需要字符串 expr'); return undefined
      }
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

test('workManager 桥：cron 登记放行（wire 形状 kind+expr）', async () => {
  // mock 宿主由本文件首个用例安装（RuntimeBridge 单例禁重复 install），这里直接复用。
  const { id } = await auto.workManager.createTimedTask({
    name: 'n', projectId: 'p', scriptPath: 'a.js',
    schedule: auto.workManager.cron('0 9 * * 1'),
  })
  assert.ok(id, '服务端回 id')
  const listed = await auto.workManager.listTasks()
  assert.strictEqual(listed[listed.length - 1].schedule.kind, 'cron')
  assert.strictEqual(await auto.workManager.cancelTask(id), true)
})

test('workManager 桥：cron 缺 expr 被拒（形状门）', async () => {
  await assert.rejects(
    auto.workManager.createTimedTask({
      name: 'n', projectId: 'p', scriptPath: 'a.js',
      schedule: { kind: 'cron' },
    }),
    (e) => e.code === 'ERR_INVALID_PARAM',
  )
})
