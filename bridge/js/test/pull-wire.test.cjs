'use strict'
/**
 * 拉取面 wire 对账门：a11y.events 与 sensors.drain 的**回包键名 + 入参键名**。
 *
 * 这两条是 npm 之外仅有的两条游标拉取环（§9.1 节流拉取、sensors 批量 drain），
 * 结构与 npm 事件面同形：Kotlin `mapOf` 发一半键、TS 接口/内联转型声明另一半，
 * 两侧 mock 测试各发各认，键名漂了照样全绿。两类失败面：
 * - 回包键漂 → 脚本读出 undefined（`vulnerabilities`/`vulns` 同族）；
 * - **入参键漂更隐蔽**：`sinceSeq` 改名后宿主读不到、落默认 0，游标永远从头拉，
 *   事件重复投递——不报错，只是数据错。
 *
 * 判据（两侧各自都是硬事实）：宿主发出的键集 ⇄ TS 声明/读取的键集双向相等；
 * JS 发的入参键 ⇄ 宿主在该分支读的入参键双向相等。键集按结构拍平后比
 * （条目内的嵌套 node/refId/generation 并入条目集——层级判别留给各命名空间的契约单测）。
 *
 * 不做的：不验语义（丢帧/游标推进），那是 pull 环语义测试的活；npm 面已在
 * event-wire.test.cjs。
 */
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

const ROOT = (() => {
  let p = path.resolve(__dirname, '..', '..')
  for (let i = 0; i < 8; i += 1) {
    if (fs.existsSync(path.join(p, 'settings.gradle.kts'))) return p
    p = path.dirname(p)
  }
  throw new Error('找不到仓库根')
})()

function read(rel) {
  return fs.readFileSync(path.join(ROOT, rel), 'utf8')
}
const A11Y_KT = read('platform/capabilities/src/main/kotlin/com/autoscript/platform/capabilities/A11yNamespaceHandler.kt')
const SENSORS_KT = read('platform/capabilities/src/main/kotlin/com/autoscript/platform/capabilities/SensorsNamespaceHandler.kt')
const A11Y_TS = fs.readFileSync(path.resolve(__dirname, '..', 'src', 'a11y.ts'), 'utf8')
const SENSORS_TS = fs.readFileSync(path.resolve(__dirname, '..', 'src', 'sensors.ts'), 'utf8')

/** Kotlin `A11yBridgeJson.encode(mapOf(...))` 回包块（从 `"first" to` 锚点反查，括号配平截取）。 */
function ktBatchBlock(src) {
  const anchor = src.indexOf('"first" to got.firstSeq')
  assert.ok(anchor >= 0, '找不到回包锚点 "first" to got.firstSeq')
  const start = src.lastIndexOf('mapOf(', anchor)
  let depth = 0
  let end = start
  for (let i = start; i < src.length; i += 1) {
    if (src[i] === '(') depth += 1
    else if (src[i] === ')') {
      depth -= 1
      if (depth === 0) { end = i + 1; break }
    }
  }
  return src.slice(start, end)
}

/** 回包块里的键：按行缩进分层——最浅层 = 信封，其余（含嵌套 node）= 条目拍平集。 */
function ktBatchKeys(block) {
  const hits = [...block.matchAll(/"([A-Za-z_][\w]*)" to /g)].map((m) => {
    const lineStart = block.lastIndexOf('\n', m.index) + 1
    const line = block.slice(lineStart, block.indexOf('\n', m.index))
    return { k: m[1], i: line.length - line.trimStart().length }
  })
  const min = Math.min(...hits.map((h) => h.i))
  const envelope = [...new Set(hits.filter((h) => h.i === min).map((h) => h.k))].sort()
  const item = [...new Set(hits.filter((h) => h.i > min).map((h) => h.k))].sort()
  return { envelope, item }
}

/** TS `interface <name> { ... }` 字段名（含 readonly 前缀）。 */
function tsInterfaceFields(src, name) {
  const start = src.indexOf(`interface ${name} {`)
  assert.ok(start >= 0, `找不到 interface ${name}`)
  const open = src.indexOf('{', start)
  const body = src.slice(open, src.indexOf('\n}', open))
  const fields = [...body.matchAll(/^[ \t]*(?:readonly[ \t]+)?([A-Za-z_][\w]*)[ \t]*:/gm)].map((m) => m[1])
  // 条目里的内联嵌套对象（a11y 的 node: { refId; generation }）并入条目集
  for (const nest of body.matchAll(/:\s*\{([^}]*)\}/g)) {
    for (const f of nest[1].matchAll(/([A-Za-z_][\w]*)[ \t]*:/g)) fields.push(f[1])
  }
  return [...new Set(fields)].sort()
}

/** JS `invoke('<ns>','<m>', { ... })` 的 payload 键（取第一次出现）。 */
function tsInvokePayloadKeys(src, ns, method) {
  const anchor = src.indexOf(`invoke('${ns}', '${method}', {`)
  assert.ok(anchor >= 0, `找不到 invoke('${ns}', '${method}')`)
  const open = src.indexOf('{', anchor)
  const body = src.slice(open, src.indexOf('}', open))
  return [...new Set([...body.matchAll(/^[ \t]*([A-Za-z_][\w]*)[ \t]*:/gm)].map((m) => m[1]))].sort()
}

/** Kotlin 分支读的入参键：函数区内 `optLong(<o>, "key")` 等。 */
function ktParamKeys(src, fnSig, extra) {
  const start = src.indexOf(fnSig)
  assert.ok(start >= 0, `找不到 ${fnSig}`)
  const open = src.indexOf('{', start)
  let depth = 0
  let end = open
  for (let i = open; i < src.length; i += 1) {
    if (src[i] === '{') depth += 1
    else if (src[i] === '}') {
      depth -= 1
      if (depth === 0) { end = i + 1; break }
    }
  }
  const body = src.slice(open, end)
  const keys = [...body.matchAll(/optLong\([^,]+,\s*"([A-Za-z_][\w]*)"\)/g)].map((m) => m[1])
  return [...new Set([...keys, ...extra.filter((e) => body.includes(e.mark)).map((e) => e.key)])].sort()
}

function bothWays(name, ktSide, tsSide) {
  const ktOnly = ktSide.filter((x) => !tsSide.includes(x))
  const tsOnly = tsSide.filter((x) => !ktSide.includes(x))
  assert.deepStrictEqual(ktOnly, [], `${name}: 宿主发/读、TS 不认: ${ktOnly.join(', ')}`)
  assert.deepStrictEqual(tsOnly, [], `${name}: TS 发/读、宿主不认: ${tsOnly.join(', ')}`)
}

const a11yBatch = ktBatchKeys(ktBatchBlock(A11Y_KT))

test('a11y.events 回包：信封 first/last/events ⇄ UiEventBatch，条目 ⇄ UiEvent（含 node 嵌套）', () => {
  bothWays('a11y 信封', a11yBatch.envelope, tsInterfaceFields(A11Y_TS, 'UiEventBatch'))
  bothWays('a11y 条目', a11yBatch.item, tsInterfaceFields(A11Y_TS, 'UiEvent'))
})

test('a11y.events 入参：JS 发的 sinceSeq/batch ⇄ 宿主读的（改名即游标失灵/重复投递）', () => {
  bothWays(
    'a11y 入参',
    ktParamKeys(A11Y_KT, 'private suspend fun events(', []),
    tsInvokePayloadKeys(A11Y_TS, 'a11y', 'events'),
  )
})

test('sensors.drain 回包：信封 + 条目 ⇄ JS 内联转型与 SensorEvent', () => {
  const batch = ktBatchKeys(ktBatchBlock(SENSORS_KT))
  const drainAt = SENSORS_TS.indexOf("invoke('sensors', 'drain'")
  assert.ok(drainAt >= 0, '找不到 sensors drain 调用')
  const asAt = SENSORS_TS.indexOf('as {', drainAt)
  const inline = SENSORS_TS.slice(asAt, SENSORS_TS.indexOf('}', asAt) + 1)
  const envelope = [...new Set([...inline.matchAll(/([A-Za-z_][\w]*)[ \t]*:/g)].map((m) => m[1]))].sort()
  bothWays('sensors 信封', batch.envelope, envelope)
  bothWays('sensors 条目', batch.item, tsInterfaceFields(SENSORS_TS, 'SensorEvent'))
})

test('sensors.drain 入参：JS 发 ref/sinceSeq/max ⇄ 宿主读的（游标键同上）', () => {
  const start = SENSORS_TS.indexOf("invoke('sensors', 'drain'")
  assert.ok(start >= 0, '找不到 sensors drain 调用')
  const decl = SENSORS_TS.slice(SENSORS_TS.lastIndexOf('const p:', start), start)
  // 类型注解里嵌套的 ref: { refId; generation } 只取顶层字段（深度 1）
  const fields = []
  let depth = 0
  for (const m of decl.matchAll(/([A-Za-z_][\w]*)[ \t]*\??:/g)) {
    const before = decl.slice(0, m.index)
    depth = [...before].reduce((d, c) => d + (c === '{' ? 1 : c === '}' ? -1 : 0), 0)
    if (depth === 1) fields.push(m[1])
  }
  const jsKeys = [...new Set(fields)].sort()
  const ktKeys = ktParamKeys(SENSORS_KT, 'private suspend fun drain(', [{ mark: 'requiredRef(fields)', key: 'ref' }])
  bothWays('sensors 入参', ktKeys, jsKeys)
})

test('解析有牙：逐面键数与本字', () => {
  assert.deepStrictEqual(a11yBatch.envelope, ['events', 'first', 'last'])
  assert.deepStrictEqual(a11yBatch.item, ['generation', 'node', 'payload', 'refId', 'seq', 'type'])
  assert.deepStrictEqual(tsInterfaceFields(A11Y_TS, 'UiEventBatch'), ['events', 'first', 'last'])
  assert.ok(tsInterfaceFields(A11Y_TS, 'UiEvent').includes('payload'), 'UiEvent 少 payload')
  const s = ktBatchKeys(ktBatchBlock(SENSORS_KT))
  assert.deepStrictEqual(s.item, ['accuracy', 'seq', 'timestamp', 'values'], 'sensors 条目键集漂了')
  assert.ok(!a11yBatch.item.includes('body'), '出现 body——payload 改名未同步')
})
