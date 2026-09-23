'use strict'
/**
 * 真 addon 全环（env 门禁；docs §7.8 启动序③ + §12.4 接入面 1 的端到端实证）：
 *   fd 注入（扮宿主 kBootstrap）→ setup 前回包计入 droppedData（诚实不静默）
 *   → attachNative → invoke 经 unix socket 到宿主、按 id 结算回 JS。
 *
 * 门禁：AUTOSCRIPT_TEST_ADDON = 已编译 addon 路径（本机 host 编译见下）。
 * CI 无 g++/node 头文件路径 → skip（node --test skip 不算失败）。
 * 本机重建：
 *   g++ -std=c++17 -shared -fPIC -DNAPI_VERSION=10 -I $NODE_SRC/src \
 *     bridge/native/src/main/cpp/bridge_addon.cc -o /tmp/bridge_native_host.node -lpthread
 *
 * fd 细节：客户端 net.Socket **不挂 data 监听（保持 paused）** —— 数据留在内核缓冲，
 * 只有 addon 读线程 recv，不与 libuv 抢（挂着 data 监听会两边抢单）。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const net = require('node:net')
const os = require('node:os')
const path = require('node:path')
const fs = require('node:fs')

const REAL_ADDON = process.env.AUTOSCRIPT_TEST_ADDON
const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default
const { attachNative } = require(path.resolve(__dirname, '..', 'dist', 'bootstrap.js'))

function delay(ms) { return new Promise((r) => setTimeout(r, ms)) }

test(
  '真 addon：droppedData 前账 + attach 结算 + 心跳负 id 不撞在途（env 门禁）',
  { skip: REAL_ADDON ? false : '设 AUTOSCRIPT_TEST_ADDON=已编译 addon 路径（见文件头）以启用；CI 跳过' },
  async () => {
    assert.ok(fs.existsSync(REAL_ADDON), `addon 产物缺失: ${REAL_ADDON}`)
    const sockPath = path.join(os.tmpdir(), `autoscript-native-${process.pid}-${Date.now()}.sock`)
    const seen = []                          // 宿主侧收到的请求帧
    const server = net.createServer((sock) => {
      let buf = Buffer.alloc(0)
      sock.on('data', (chunk) => {
        buf = buf.length === 0 ? chunk : Buffer.concat([buf, chunk])
        let nl
        while ((nl = buf.indexOf(0x0a)) !== -1) {
          const line = buf.subarray(0, nl).toString('utf8')
          buf = buf.subarray(nl + 1)
          if (!line) continue
          const req = JSON.parse(line)
          seen.push(req)
          // 信封合同：payload 必须是 JSON 字符串或 null（裸嵌对象 = 宿主整帧拒收）
          assert.ok(req.payload === null || typeof req.payload === 'string', `payload 形状: ${typeof req.payload}`)
          sock.write(`${JSON.stringify({ t: 'ok', id: req.id, payload: req.payload })}\n`)
        }
      })
    })
    await new Promise((resolve) => server.listen(sockPath, resolve))

    const client = net.connect(sockPath)
    await new Promise((resolve, reject) => {
      client.once('connect', resolve)
      client.once('error', reject)
    })
    // 不挂 data 监听：socket 保持 paused，内核缓冲只由 addon 读线程消费

    const addon = require(REAL_ADDON)
    try {
      // ── ① 扮宿主：注入已连 fd（kBootstrap 同款）──────────────────────────
      assert.equal(typeof client._handle.fd, 'number', 'node socket fd 可取（本机 node 已验）')
      addon.setSocketFd(client._handle.fd)

      // ── ② setup 前：回包计入 droppedData（诚实可查，不静默吞）────────────
      addon.invoke('engines', 'heartbeat', JSON.stringify({ runId: 1, seq: 1 }), -1, 2_000)
      const deadline = Date.now() + 3_000
      while (Number(addon.droppedData()) < 1 && Date.now() < deadline) await delay(25)
      assert.ok(Number(addon.droppedData()) >= 1, 'setup 前到达的响应必须进 droppedData')

      // ── ③ facade 接入：setup + install（负 id 响应此后走 onFrame → 查不到即丢）──
      const boot = attachNative({ addon })
      assert.ok(boot)

      // ── ④ 正数在途：invoke → socket → 宿主回包 → onFrame 结算 ────────────
      const got = await auto.bridge.invoke('probe', 'echo', { hello: 'native' }, { ttl: 5_000 })
      assert.deepEqual(got, { hello: 'native' }, '真 addon 全环按 id 结算')

      // 无参形态
      assert.equal(await auto.bridge.invoke('probe', 'echo', null, { ttl: 5_000 }), null)

      // ── ⑤ 心跳负 id 回包（setup 后）：onFrame 收到 → handleResponse 丢弃，不炸 ──
      addon.invoke('engines', 'heartbeat', JSON.stringify({ runId: 1, seq: 2 }), -2, 2_000)
      await delay(150)   // 读线程 50ms poll + TSF 投递
      // 在途仍可正常结算 = 负 id 没把桥搞坏
      assert.deepEqual(await auto.bridge.invoke('probe', 'after', { ok: 1 }, { ttl: 5_000 }), { ok: 1 })

      // 宿主侧心跳帧账（setup 前 -1 + setup 后 -2 都该在；断言**后一拍**：
      // find 首个会拿到 setup 前的 seq:1，那是 droppedData 用例的帧，不是本断言对象）
      const beats = seen.filter((f) => f.ns === 'engines' && f.m === 'heartbeat')
      assert.equal(beats.length, 2, `前后两拍心跳都到宿主: ${beats.map((b) => b.id)}`)
      const beat = beats[beats.length - 1]
      assert.ok(beat.id < 0, `-seq 负数命名空间: ${beat.id}`)
      assert.equal(typeof beat.payload, 'string', '心跳 payload 信封内是字符串')
      assert.deepEqual(JSON.parse(beat.payload), { runId: 1, seq: 2 })
    } finally {
      try { client.destroy() } catch { /* 幂等 */ }
      await new Promise((resolve) => server.close(resolve))
      fs.rmSync(sockPath, { force: true })
    }
  },
)
