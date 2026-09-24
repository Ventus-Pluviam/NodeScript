'use strict'
/**
 * power_manager 双侧契约测试（§8.7 + Kotlin PowerManagerNamespaceHandlerTest）：
 * mock 宿主**逐字复刻** Kotlin handler 的回包，钉住四处最易两侧走偏的地方：
 * 1. acquire 的 wire 形状是 {timeoutMillis} —— 正整数必填，无期限/0/负数/缺参
 *    即 ERR_INVALID_PARAM（脚本无期限 = 卡死的持有方让 CPU 永远不休眠）；
 * 2. token **服务端分配**（`script-` 前缀），回包是 {token} 对象 —— 不是裸字符串、
 *    不是客户端自带；
 * 3. release 回裸 boolean（false = 该 token 当时并未持有，如实不对账成功）；
 * 4. status 回 {held,holders} 双值（held = 门禁判据，holders = 账本席位数，不折叠）。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default

let installed = false
function installMockPower() {
  if (installed) return
  installed = true
  const seen = []
  installMockPower.seen = seen
  const held = new Set(['framework:keepalive'])   // 复刻账本：框架先占一席
  let lockAvailable = true
  installMockPower.setLockAvailable = (v) => { lockAvailable = v }
  let seq = 0
  auto.install((ns, method, payloadJson, reqId) => {
    if (ns !== 'power_manager') return undefined
    const p = payloadJson ? JSON.parse(payloadJson) : null
    seen.push({ ns, method, p })
    const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
    const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
    switch (method) {
      case 'acquire': {
        // 只做形状门：正整数必填（Kotlin 侧 hold 的 >0 require 同口径）
        if (typeof p?.timeoutMillis !== 'number' || !Number.isInteger(p.timeoutMillis) || p.timeoutMillis <= 0) {
          return err('ERR_INVALID_PARAM', 'acquire 需要正整数 timeoutMillis（脚本锁必须限时，无期限只属框架）')
        }
        if (!lockAvailable) return err('ERR_SERVICE_DISABLED', '唤醒锁取不到（无 PowerManager / 系统拒绝）：未记账')
        seq += 1
        const token = `script-mock${seq}`
        held.add(token)
        return ok(JSON.stringify({ token }))
      }
      case 'release': {
        if (typeof p?.token !== 'string' || p.token.trim() === '') {
          return err('ERR_INVALID_PARAM', 'token 不得为空白（空白 token 找不到持有方）')
        }
        return ok(held.delete(p.token) ? 'true' : 'false')
      }
      case 'status':
        return ok(JSON.stringify({ held: lockAvailable && held.size > 0, holders: held.size }))
      default:
        return err('ERR_NOT_IMPLEMENTED', `未知 power_manager 方法: ${method}`)
    }
  })
}

function lastCall() {
  const seen = installMockPower.seen
  return seen[seen.length - 1]
}

test('acquire 的 wire 形状是 {timeoutMillis}，回包是 {token} 对象', async () => {
  installMockPower()
  const token = await auto.power.acquire(60_000)
  assert.ok(token.startsWith('script-'), `token 服务端分配：${token}`)
  assert.deepEqual(lastCall().p, { timeoutMillis: 60_000 })
  const st = await auto.power.status()
  assert.strictEqual(st.holders, 2, '框架一席 + 脚本一席')
  assert.strictEqual(st.held, true)
})

test('acquire 缺超时/非正整数即 ERR_INVALID_PARAM（宿主判，不在 JS 侧复述第二套校验）', async () => {
  installMockPower()
  for (const bad of [undefined, 0, -5, 1.5, '一小时']) {
    await assert.rejects(() => auto.power.acquire(bad), (e) => e.code === 'ERR_INVALID_PARAM', `timeoutMillis=${bad}`)
  }
})

test('取不到锁是 ERR_SERVICE_DISABLED（未记账），不是 INVALID_PARAM', async () => {
  installMockPower()
  installMockPower.setLockAvailable(false)
  await assert.rejects(() => auto.power.acquire(60_000), (e) => e.code === 'ERR_SERVICE_DISABLED')
  const st = await auto.power.status()
  assert.strictEqual(st.held, false, '门禁读 held —— 必须为 false')
  installMockPower.setLockAvailable(true)
})

test('release 只放自己那一份：重复放/陌生 token 回 false', async () => {
  installMockPower()
  const token = await auto.power.acquire(60_000)
  assert.strictEqual(await auto.power.release(token), true)
  assert.strictEqual(await auto.power.release(token), false, '重复放如实 false')
  assert.strictEqual(await auto.power.release('script-从没见过'), false)
})

test('status 回 held/holders 双值，不折叠', async () => {
  installMockPower()
  const st = await auto.power.status()
  assert.strictEqual(typeof st.held, 'boolean')
  assert.strictEqual(typeof st.holders, 'number')
})
