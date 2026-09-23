'use strict'
/**
 * addon JS 消费面（docs §7.8 启动序③ / §12.4 接入面 1）：
 * - attachNative：setup(onFrame) 结算 + addon.invoke 注入 runtimeBridge；不碰 setSocketFd；
 * - NAPI 同步抛错（普通 Error + `.code`）经 errFromThrown 保留 ERR_* 真码；
 * - kBootstrap 心跳负 id / 未知 id 响应：丢弃不撞在途正数 id；
 * - 非法帧走 onFrameError 钩子；setup 幂等、重复 attach 单例拒绝。
 * 独立进程跑（node --test 每文件一进程）：runtimeBridge.install 单例每进程一次。
 * 真 addon 环见 native-addon-roundtrip.test.cjs（env 门禁）。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default
const { AutojsError } = autoModule
const { NativeBootstrap, attachNative } = require(path.resolve(__dirname, '..', 'dist', 'bootstrap.js'))

/** 可脚本化假 addon：capture = 不回包，由测试手动驱动 onFrame。 */
class MockAddon {
  constructor() {
    this.setupCalls = 0
    this.fd = undefined          // setSocketFd 没被消费面碰过 → 保持 undefined（fd 注入归宿主）
    this.calls = []
    this.mode = 'capture'        // 'capture' | 'throw-stopped'
    this.onFrame = null
  }

  setSocketFd(fd) { this.fd = fd }

  setup(cb) {
    this.setupCalls += 1
    if (this.setupCalls > 1) throw new Error('setup 只能调一次')  // addon 侧合同
    this.onFrame = cb
  }

  invoke(ns, m, payload, id, ttl) {
    this.calls.push({ ns, m, payload, id, ttl })
    if (this.mode === 'throw-stopped') {
      const e = new Error('桥 socket 未连接')     // napi_throw_error 形态：普通 Error + .code
      e.code = 'ERR_ENGINE_STOPPED'
      throw e
    }
    // capture：等人手动 onFrame（模拟宿主回包时机）
  }
}

const mock = new MockAddon()
let boot = null

test('构造：无注入无 env → ERR_ENGINE_STOPPED 快速拒绝（不悬挂）', () => {
  delete process.env.AUTOSCRIPT_BRIDGE_ADDON
  assert.throws(
    () => new NativeBootstrap(),
    (e) => e instanceof AutojsError && e.code === 'ERR_ENGINE_STOPPED' && /AUTOSCRIPT_BRIDGE_ADDON/.test(e.detail),
  )
})

test('attachNative：setup+install 接线；不碰 setSocketFd；setup 幂等；重复 attach 拒绝', () => {
  boot = attachNative({ addon: mock })
  assert.strictEqual(auto.installed, true, 'runtimeBridge 已装宿主')
  assert.strictEqual(mock.setupCalls, 1, 'setup 恰好一次（TSF 一次性合同）')
  assert.strictEqual(mock.fd, undefined, '消费面绝不碰 setSocketFd —— fd 注入归宿主 kBootstrap（§7.5）')

  boot.setup()                    // 幂等：本实例二次 setup no-op（addon 侧再调会抛）
  assert.strictEqual(mock.setupCalls, 1)

  assert.throws(
    () => attachNative({ addon: new MockAddon() }),
    (e) => /已安装/.test(e.message),
    'install 单例：重复 attach 由 runtimeBridge 拒绝（与 SocketBootstrap 同口径）',
  )
})

test('往返：invoke 参数形状 + ok 按 id 结算 + err 信封折叠', async () => {
  const p = auto.bridge.invoke('mock', 'echo', { x: 1 }, { ttl: 5_000 })
  const call = mock.calls[mock.calls.length - 1]
  assert.equal(call.ns, 'mock')
  assert.equal(call.m, 'echo')
  assert.equal(call.payload, '{"x":1}', 'payload = JSON 文本（信封字符串合同）')
  assert.ok(call.id > 0, '正数 reqId（负数命名空间留 kBootstrap 心跳）')
  assert.equal(call.ttl, 5_000)

  boot.onFrame(JSON.stringify({ t: 'ok', id: call.id, payload: JSON.stringify({ got: 1 }) }))
  assert.deepEqual(await p, { got: 1 })

  const p2 = auto.bridge.invoke('mock', 'echo', null, { ttl: 5_000 })
  const call2 = mock.calls[mock.calls.length - 1]
  assert.equal(call2.payload, null, '无参 = null（不是 "null" 字符串）')
  boot.onFrame(JSON.stringify({ t: 'err', id: call2.id, code: 'ERR_NOT_IMPLEMENTED', detail: 'native 缺失' }))
  await assert.rejects(p2, (e) => e instanceof AutojsError && e.code === 'ERR_NOT_IMPLEMENTED' && e.detail === 'native 缺失')
})

test('NAPI 同步抛错保留 ERR_* 真码 —— 断链不得被折成参数错', async () => {
  mock.mode = 'throw-stopped'
  try {
    await assert.rejects(
      () => auto.bridge.invoke('mock', 'echo', null, { ttl: 1_000 }),
      (e) => e instanceof AutojsError && e.code === 'ERR_ENGINE_STOPPED' && /桥 socket 未连接/.test(e.detail),
    )
  } finally {
    mock.mode = 'capture'
  }
})

test('kBootstrap 心跳负 id / 未知 id 响应丢弃，不撞在途正数 id', async () => {
  const p = auto.bridge.invoke('mock', 'slow', null, { ttl: 5_000 })
  const id = mock.calls[mock.calls.length - 1].id

  // 心跳响应（-seq 负数命名空间）+ 带外未知正数：handleResponse 查不到即丢
  boot.onFrame(JSON.stringify({ t: 'ok', id: -1, payload: 'null' }))
  boot.onFrame(JSON.stringify({ t: 'ok', id: -7, payload: 'null' }))
  boot.onFrame(JSON.stringify({ t: 'ok', id: 999_999, payload: 'null' }))

  // 在途调用未被上述任一响应误结算：只有真 id 能收尾（若被 -1 抢先 resolve，下面 rejects 会炸）
  boot.onFrame(JSON.stringify({ t: 'err', id, code: 'ERR_TIMEOUT', detail: '按真 id 收尾' }))
  await assert.rejects(p, (e) => e.code === 'ERR_TIMEOUT' && e.detail === '按真 id 收尾')
})

test('非法 JSON 帧 → onFrameError 钩子，在途调用不受惊', async () => {
  let hookErr = null
  boot.onFrameError = (e) => { hookErr = e }

  const p = auto.bridge.invoke('mock', 'ok-then', null, { ttl: 5_000 })
  const id = mock.calls[mock.calls.length - 1].id
  boot.onFrame('not-json{{{')
  assert.ok(hookErr !== null, '非法帧必须走诊断钩子（默认不抛）')
  assert.match(hookErr.message, /非法 JSON/)

  boot.onFrame(JSON.stringify({ t: 'ok', id, payload: 'null' }))   // 在途仍可正常结算
  assert.equal(await p, null)
  boot.onFrameError = null
})
