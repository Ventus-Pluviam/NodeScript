'use strict'
/**
 * npm namespace 双侧契约测试（§10.8 + Kotlin NpmBridgeHandler / NpmBridgeJson）：
 * JS facade 的 wire 形状与 Kotlin 侧解析/编码逐字段对齐。**mock 的形状必须抄 Kotlin
 * handler 的真实回包**——a11y.waitFor 那次事故的根因就是 JS mock 自己回了一个宿主
 * 从不发的形状，于是两侧各自的测试全绿，而生产路径恒 false/恒 undefined。
 *
 * 对 dist/ 产物断言（npm test 先 tsc）。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default
const { npm } = require(path.resolve(__dirname, '..', 'dist', 'npm.js'))

/** mock npm 宿主：按 Kotlin NpmBridgeHandler.dispatch 的响应形状回包（逐字复刻）。 */
let installed = false
function installMockNpm() {
  if (installed) return
  installed = true
  auto.install((ns, method, payloadJson, reqId) => {
    if (ns !== 'npm') return undefined
    const p = payloadJson ? JSON.parse(payloadJson) : null
    const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
    const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
    switch (method) {
      // Kotlin：install → {handleId,projectId,enqueuedAtMillis}（:domain InstallHandle 上桥）
      case 'install':
        if (!p.spec) err('ERR_INVALID_PARAM', '缺字符串字段 spec')
        else ok(JSON.stringify({ handleId: 'inst-1', projectId: 'main', enqueuedAtMillis: 1 }))
        return undefined
      // Kotlin：remove/ci/prune/dedupe → Ok null
      case 'remove':
      case 'ci':
      case 'prune':
      case 'dedupe':
        ok(null)
        return undefined
      // Kotlin：list → [{name,version}]（无 sizeBytes：lockfile 量不到尺寸）
      case 'list':
        ok(JSON.stringify([{ name: 'lodash', version: '4.17.21' }]))
        return undefined
      // Kotlin：offlineGap → [{name,version,size}]
      case 'offlineGap':
        ok(JSON.stringify([{ name: 'a', version: '1.0.0', size: 1234 }]))
        return undefined
      // Kotlin：audit → {vulns:[],level,offline}（键名 vulns，不是 vulnerabilities）
      case 'audit':
        ok(JSON.stringify({ vulns: [], level: 'none', offline: true }))
        return undefined
      case 'setRegistry':
        if (p.registry == null) err('ERR_INVALID_PARAM', '缺字符串字段 registry')
        else ok(null)
        return undefined
      // Kotlin：requestApprove → {requestId,status,scripts}（scripts 回显）
      case 'requestApprove':
        if (!p.pkg) err('ERR_INVALID_PARAM', '缺字符串字段 pkg')
        else if (p.scripts != null && !Array.isArray(p.scripts)) {
          err('ERR_INVALID_PARAM', '字段 scripts 必须是数组')
        } else ok(JSON.stringify({ requestId: 'apr-1', status: 'pending', scripts: p.scripts ?? [] }))
        return undefined
      default:
        err('ERR_NOT_IMPLEMENTED', `未知 npm 方法: ${method}`)
        return undefined
    }
  })
}

test('npm.install 回包 = InstallHandle（不是包体：宿主此刻还不知道会装出什么版本）', async () => {
  installMockNpm()
  const queued = await npm.install('lodash@4.17.21')
  // Kotlin 把 :domain 的 InstallHandle 原样上桥。这里钉的是**形状**：回包里没有任何
  // 包体字段——facade 曾把 install 声明成 Promise<InstallResult>{name,version,integrity,
  // linkedBins}，而宿主从不发这些键，于是 `pkg.version` 恒 undefined、类型面在说谎。
  assert.strictEqual(typeof queued, 'object', '回包须为对象（facade 声明的 InstallQueued）')
  assert.strictEqual(queued.handleId, 'inst-1')
  assert.strictEqual(queued.projectId, 'main')
  assert.strictEqual(typeof queued.enqueuedAtMillis, 'number')
  assert.strictEqual(queued.version, undefined, '排队结果不含 version：装什么要等 npm 解析')
  assert.strictEqual(queued.name, undefined)
  assert.strictEqual(queued.integrity, undefined, '不得伪造 integrity')
})

test('npm.install 缺 spec → ERR_INVALID_PARAM（不伪造成功）', async () => {
  installMockNpm()
  await assert.rejects(() => auto.bridge.invoke('npm', 'install', {}), (e) => e.code === 'ERR_INVALID_PARAM')
})

test('npm.list 不带 sizeBytes（lockfile 量不到；JS PkgNode 无此字段）', async () => {
  installMockNpm()
  const list = await npm.list()
  assert.strictEqual(list.length, 1)
  assert.strictEqual(list[0].name, 'lodash')
  assert.strictEqual(list[0].version, '4.17.21')
  assert.strictEqual(
    list[0].sizeBytes,
    undefined,
    '宿主不发 sizeBytes；JS 侧若声明它，脚本会拿 undefined 当字节数算「还要下多少」',
  )
})

test('npm.offlineGap 带 version + size（JS MissingPkg 曾漏声明 version）', async () => {
  installMockNpm()
  const gap = await npm.offlineGap()
  assert.deepStrictEqual(gap, [{ name: 'a', version: '1.0.0', size: 1234 }])
  assert.strictEqual(gap[0].version, '1.0.0', '缺哪个版本要说清，不能只给名字')
})

test('npm.audit 读 vulns 键（宿主曾发 vulnerabilities → report.vulns 恒 undefined）', async () => {
  installMockNpm()
  const report = await npm.audit({ offline: true })
  assert.ok(Array.isArray(report.vulns), 'vulns 必须是数组：' + JSON.stringify(report))
  assert.strictEqual(report.level, 'none')
  // 旧键名不得出现（宿主侧 NpmBridgeHandlerTest 反向钉同一件事）
  assert.strictEqual(report.vulnerabilities, undefined)
})

test('npm.requestApprove 回包带 requestId/status/scripts 回显', async () => {
  installMockNpm()
  const ticket = await npm.requestApprove('esbuild', { scripts: ['postinstall'] })
  assert.strictEqual(ticket.requestId, 'apr-1')
  assert.strictEqual(ticket.status, 'pending')
  assert.deepStrictEqual(ticket.scripts, ['postinstall'], '声明的脚本须回显（宿主不校验 = 静默丢弃）')
})

test('npm.requestApprove 的 scripts 形态不对即 ERR_INVALID_PARAM', async () => {
  installMockNpm()
  await assert.rejects(
    () => auto.bridge.invoke('npm', 'requestApprove', { pkg: 'esbuild', scripts: 'postinstall' }),
    (e) => e.code === 'ERR_INVALID_PARAM',
  )
})

test('npm.setRegistry 带 scope；未知方法 → ERR_NOT_IMPLEMENTED', async () => {
  installMockNpm()
  await npm.setRegistry('https://registry.npmmirror.com', { scope: '@my' })
  await assert.rejects(() => auto.bridge.invoke('npm', 'explode', {}), (e) => e.code === 'ERR_NOT_IMPLEMENTED')
})

test('npm 无参方法（prune/dedupe）与 remove 均成功收尾', async () => {
  installMockNpm()
  await npm.prune()
  await npm.dedupe()
  await npm.remove('dayjs')
})
