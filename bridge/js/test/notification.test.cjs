'use strict'
/**
 * notification 双侧契约测试（§12.2 + Kotlin NotificationNamespaceHandlerTest）：
 * mock 宿主**逐字复刻** Kotlin handler 的回包，钉住四处最易两侧走偏的地方：
 * 1. post 的 wire 形状 {id,text,title} —— title 省略即键不在场（与显式 null 同义）；
 * 2. 未授权是**抛 ERR_PERMISSION_DENIED**，不是 resolve（Android 被拒时静默丢弃，
 *    facade 再静默成功就是最坏的谎）；
 * 3. cancel 无回执 → void，不编一个"撤销成功"的布尔；
 * 4. canPost 回裸 boolean。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default

let installed = false
function installMockNotification() {
  if (installed) return
  installed = true
  const seen = []
  installMockNotification.seen = seen
  const live = new Map()          // id → spec（复刻同 id 覆盖）
  let postable = true
  installMockNotification.setPostable = (v) => { postable = v }
  auto.install((ns, method, payloadJson, reqId) => {
    if (ns !== 'notification') return undefined
    const p = payloadJson ? JSON.parse(payloadJson) : null
    seen.push({ ns, method, p })
    const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
    const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
    const idOf = () => {
      if (typeof p?.id !== 'number' || !Number.isInteger(p.id)) return err('ERR_INVALID_PARAM', '缺数字字段 id')
      if (p.id < -2147483648 || p.id > 2147483647) return err('ERR_INVALID_PARAM', `id 超出 Int 范围: ${p.id}`)
      return null
    }
    switch (method) {
      case 'canPost':
        return ok(postable ? 'true' : 'false')
      case 'post': {
        const badId = idOf()
        if (badId) return badId
        if (typeof p?.text !== 'string' || p.text.trim() === '') {
          return err('ERR_INVALID_PARAM', typeof p?.text === 'string' ? 'notification text 不得为空白' : '缺字符串字段 text')
        }
        if (p.title !== undefined && typeof p.title !== 'string') {
          return err('ERR_INVALID_PARAM', '字段 title 必须是字符串')
        }
        if (!postable) return err('ERR_PERMISSION_DENIED', `通知未授权发送（POST_NOTIFICATIONS / 应用通知未开）: id=${p.id}`)
        live.set(p.id, { id: p.id, text: p.text, title: p.title ?? null })
        return ok('true')
      }
      case 'cancel': {
        const badId = idOf()
        if (badId) return badId
        live.delete(p.id)
        return ok('true')          // 只表示"这次调用发出去了"
      }
      default:
        return err('ERR_NOT_IMPLEMENTED', `未知 notification 方法: ${method}`)
    }
  })
}

function lastCall() {
  const seen = installMockNotification.seen
  return seen[seen.length - 1]
}

test('post 的 wire 形状是 {id,text,title}——title 省略即键不在场', async () => {
  installMockNotification()
  await auto.notification.post({ id: 7, text: '跑完了', title: '任务' })
  assert.equal(lastCall().method, 'post')
  assert.deepEqual(lastCall().p, { id: 7, text: '跑完了', title: '任务' })

  await auto.notification.post({ id: 8, text: '无标题' })
  assert.deepEqual(lastCall().p, { id: 8, text: '无标题' }, '省略 title → 键不在场，不发 undefined/null 占位')

  await auto.notification.post({ id: 9, text: '显式 null', title: null })
  assert.deepEqual(lastCall().p, { id: 9, text: '显式 null' }, '显式 null 与省略同义')
})

test('未授权是抛 ERR_PERMISSION_DENIED，不是 resolve', async () => {
  installMockNotification()
  installMockNotification.setPostable(false)
  assert.strictEqual(await auto.notification.canPost(), false)
  await assert.rejects(
    () => auto.notification.post({ id: 1, text: '跑完了' }),
    (e) => e.code === 'ERR_PERMISSION_DENIED' && e.message.includes('未授权'),
  )
  installMockNotification.setPostable(true)
  assert.strictEqual(await auto.notification.canPost(), true)
})

test('cancel 无回执 → void，不编撤销成功的布尔', async () => {
  installMockNotification()
  await auto.notification.post({ id: 42, text: '先发一条' })
  assert.strictEqual(await auto.notification.cancel(42), undefined, 'cancel 回 void')
  assert.equal(lastCall().method, 'cancel')
  assert.deepEqual(lastCall().p, { id: 42 })
  assert.strictEqual(await auto.notification.cancel(999), undefined, '没发过的 id 同样只是发一次撤销')
})

test('参数口径由宿主判——非法 id/text 上线即被拒', async () => {
  installMockNotification()
  await assert.rejects(
    () => auto.notification.post({ id: '7', text: 'x' }),
    (e) => e.code === 'ERR_INVALID_PARAM',
  )
  await assert.rejects(
    () => auto.notification.post({ id: 1, text: '   ' }),
    (e) => e.code === 'ERR_INVALID_PARAM',
  )
  await assert.rejects(
    () => auto.notification.cancel(undefined),
    (e) => e.code === 'ERR_INVALID_PARAM',
  )
})

test('诚实缺位：没有 notify/show/cancelAll 等未约定别名', async () => {
  installMockNotification()
  assert.equal(typeof auto.notification.notify, 'undefined')
  assert.equal(typeof auto.notification.show, 'undefined')
  assert.equal(typeof auto.notification.cancelAll, 'undefined')
})
