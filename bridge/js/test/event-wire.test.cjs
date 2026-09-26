'use strict'
/**
 * npm 事件面 wire 逐字对账门（方法表之外的另一张表：事件/警告/动作/类型字面量）。
 *
 * 宿主 `NpmBridgeHandler` 用三个显式 `when` 映射把枚举折成 wire 串（`POST_CHECK` →
 * `post-check`，故意不走 `.name.lowercase()`——那是 `post_check`，差一个连字符脚本的
 * switch 整段落 default）；JS 侧 `npm.ts` 用 PHASES / WARNING_KINDS / APPROVAL_ACTIONS
 * 三张白名单 + `routeInstallEvent` 的 type 分支逐字校验，认不出就响亮抛。两侧各自都有
 * 单测钉自己的表（`NpmEventDrainTest` / `npm-events.test.cjs`），但**各自硬编码不构成
 * 对账**：Kotlin 改串、两边测试各自跟着改，运行期才炸——与 `vulnerabilities`/`vulns`
 * 是同一类事故。这道门直接读两份源码做双向集合相等。
 *
 * 同一文件顺带钉**键名面**：条目/信封的每个键都是两侧各写一半（Kotlin `mapOf` 发、
 * JS `w.x` 读），mock 测试永远发 JS 自己认识的键，所以「宿主改了键名、两侧单测照绿、
 * 运行期读出 undefined」这类漂移只有源码对账能抓。方向纪律与 wire-reconcile 相同：
 * JS 读了宿主不发的键 = 红；宿主发了 JS 不读的键 = 须登记在 UNREAD 并写明为什么。
 *
 * 不做的：不验 seq/游标语义与丢帧行为（`npm-events.test.cjs` 的活）。
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

const KT = fs.readFileSync(
  path.join(
    ROOT,
    'app-service/packager/src/main/kotlin/com/autoscript/appservice/packager/npm/NpmBridgeHandler.kt',
  ),
  'utf8',
)
const TS = fs.readFileSync(path.resolve(__dirname, '..', 'src', 'npm.ts'), 'utf8')

/** Kotlin `internal fun <name>(...): String = when (...) { ... }` 里的 `-> "串"`。 */
function ktWhenWire(src, fn) {
  const start = src.indexOf(`internal fun ${fn}(`)
  assert.ok(start >= 0, `Kotlin 找不到 ${fn}——NpmBridgeHandler 结构漂了`)
  const open = src.indexOf('{', start)
  const body = src.slice(open, src.indexOf('\n    }', open))
  return [...new Set([...body.matchAll(/->\s*"([a-z0-9_-]+)"/g)].map((m) => m[1]))].sort()
}

/** Kotlin `encodeEvent` 里的 `"type" to "字面量"`。 */
function ktEventTypes(src) {
  const start = src.indexOf('internal fun encodeEvent(')
  assert.ok(start >= 0, 'Kotlin 找不到 encodeEvent')
  const open = src.indexOf('{', start)
  const body = src.slice(open, src.indexOf('\n    }', open))
  return [...new Set([...body.matchAll(/"type"\s+to\s*"([a-z0-9_-]+)"/g)].map((m) => m[1]))].sort()
}

/** TS `const <name> ... = [ 'a', ... ]` 里的串字面量。 */
function tsArrayWire(src, name) {
  const decl = src.indexOf(`const ${name}`)
  assert.ok(decl >= 0, `npm.ts 找不到 ${name}`)
  const open = src.indexOf('= [', decl)
  const body = src.slice(open, src.indexOf(']', open))
  return [...new Set([...body.matchAll(/'([a-z0-9_-]+)'/g)].map((m) => m[1]))].sort()
}

/** TS `routeInstallEvent` 的 `case '字面量':`。 */
function tsRouteCases(src) {
  const start = src.indexOf('function routeInstallEvent(')
  assert.ok(start >= 0, 'npm.ts 找不到 routeInstallEvent')
  const open = src.indexOf('{', start)
  const body = src.slice(open, src.indexOf('\n}', start))
  return [...new Set([...body.matchAll(/case\s+'([a-z0-9_-]+)':/g)].map((m) => m[1]))].sort()
}

function bothWays(name, ktSide, jsSide) {
  const ktOnly = ktSide.filter((x) => !jsSide.includes(x))
  const jsOnly = jsSide.filter((x) => !ktSide.includes(x))
  assert.deepStrictEqual(ktOnly, [], `${name}: 宿主会发、JS 白名单不认（拉到即抛）: ${ktOnly.join(', ')}`)
  assert.deepStrictEqual(jsOnly, [], `${name}: JS 认、宿主不发（白名单虚位）: ${jsOnly.join(', ')}`)
}

// ══════════ 键名面：Kotlin `mapOf` 发什么 ⇄ JS `w.x` 读什么 ══════════

/**
 * 宿主发了、facade 不读的键 → 为什么可以不读。
 * 与 wire-reconcile 的 ALIASES 同纪律：登记即承诺，键真被读走或真消失都要红（见下）。
 */
const UNREAD = {
  seq: '条目上的 seq 是宿主环位置的冗余标注：游标推进只看信封的 last，丢帧判据是 first > cursor+1（见 pumpInstallEvents KDoc），故条目 seq 无人读',
}

/** Kotlin `encodeEvent` 各分支 mapOf 的键（分支名 → 键集）。 */
function ktEventKeysByBranch(src) {
  const start = src.indexOf('internal fun encodeEvent(')
  assert.ok(start >= 0, 'Kotlin 找不到 encodeEvent')
  const open = src.indexOf('{', start)
  const body = src.slice(open, src.indexOf('\n    }', open))
  const out = new Map()
  const parts = body.split(/is com\.autoscript\.domain\.npm\.InstallEvent\./).slice(1)
  for (const part of parts) {
    const name = part.slice(0, part.indexOf(' ')).toLowerCase() // Progress/Warning/Finished
    const m = part.match(/mapOf\(([\s\S]*?)\n        \)/)
    assert.ok(m, `encodeEvent ${name} 分支的 mapOf 没解析到`)
    out.set(name, [...new Set([...m[1].matchAll(/"([A-Za-z_][\w]*)"?\s+to\s/g)].map((x) => x[1]))].sort())
  }
  return out
}

/** `routeInstallEvent` 各 case 读的键（case 名 → 键集）；default 不算读。 */
function tsReadKeysByCase(src) {
  const start = src.indexOf('function routeInstallEvent(')
  assert.ok(start >= 0, 'npm.ts 找不到 routeInstallEvent')
  const open = src.indexOf('{', start)
  const body = src.slice(open, src.indexOf('\n}', start))
  const out = new Map()
  // switch 的判别表达式（w.type）在 case 体之前，对每个 case 都算读过
  const disc = body.match(/switch\s*\(\s*w\.([A-Za-z_][\w]*)\s*\)/)
  const discriminant = disc ? [disc[1]] : []
  const chunks = body.split(/case\s+'([a-z]+)':|default:/)
  for (let i = 1; i < chunks.length; i += 2) {
    const name = chunks[i]
    if (!name) continue // default: 捕获组为空
    const keys = [...discriminant, ...[...chunks[i + 1].matchAll(/\bw\.([A-Za-z_][\w]*)/g)].map((x) => x[1])]
    out.set(name, [...new Set(keys)].sort())
  }
  return out
}

/** Kotlin `"requests" ->` 嵌套 mapOf 的键（审批条目）。 */
function ktApprovalKeys(src) {
  const start = src.indexOf('"requests" to got.requests')
  assert.ok(start >= 0, 'Kotlin 找不到 approvals 回包')
  const open = src.indexOf('mapOf(', start)
  const body = src.slice(open + 'mapOf('.length, src.indexOf('\n                            )', open))
  return [...new Set([...body.matchAll(/"([A-Za-z_][\w]*)"?\s+to\s/g)].map((x) => x[1]))].sort()
}

/** JS `pumpApprovals` 条目分支读的键。 */
function tsApprovalReads(src) {
  const start = src.indexOf('async function pumpApprovals(')
  assert.ok(start >= 0, 'npm.ts 找不到 pumpApprovals')
  const open = src.indexOf('for (const w of items)', start)
  const body = src.slice(open, src.indexOf('if (last > approvalSeq)', open))
  return [...new Set([...body.matchAll(/\bw\.([A-Za-z_][\w]*)/g)].map((x) => x[1]))].sort()
}

function keyParity(name, emitted, read) {
  const unread = read.filter((k) => !emitted.includes(k))
  const unreadByHost = emitted.filter((k) => !read.includes(k))
  assert.deepStrictEqual(unread, [], `${name}: JS 读了宿主不发的键（运行期 undefined）: ${unread.join(', ')}`)
  const stale = unreadByHost.filter((k) => !(k in UNREAD))
  assert.deepStrictEqual(stale, [], `${name}: 宿主发了没人读的键——要么 facade 漏用，要么登记进 UNREAD: ${stale.join(', ')}`)
  // 过期 = 有人读走了它，或宿主压根不再发它（登记时承诺的前提消失）
  const obsolete = Object.keys(UNREAD).filter((k) => read.includes(k) || !emitted.includes(k))
  assert.deepStrictEqual(obsolete, [], `${name}: UNREAD 登记过期（键已被人读）: ${obsolete.join(', ')}`)
}

test('事件条目键：encodeEvent 各分支 ⇄ routeInstallEvent 各 case 逐分支对账', () => {
  const kt = ktEventKeysByBranch(KT)
  const ts = tsReadKeysByCase(TS)
  assert.deepStrictEqual([...kt.keys()].sort(), [...ts.keys()].sort(), '分支集合不一致')
  for (const [branch, emitted] of kt) {
    keyParity(`事件 ${branch}`, emitted, ts.get(branch))
  }
})

test('审批条目键：宿主 mapOf ⇄ pumpApprovals 读集对账', () => {
  keyParity('审批', ktApprovalKeys(KT), tsApprovalReads(TS))
})

test('信封键：first/last/events/requests 两侧齐全（JS 只认这四个）', () => {
  const ktEnvelope = [...new Set([...KT.matchAll(/"([A-Za-z_][\w]*)"?\s+to\s/g)].map((x) => x[1]))]
  for (const k of ['first', 'last', 'events', 'requests']) {
    assert.ok(ktEnvelope.includes(k), `宿主回包缺信封键 ${k}`)
  }
  const jsReads = tsReadKeysByCase(TS).size > 0 ? ['first', 'last'] : []
  assert.ok(jsReads.length > 0, '解析失灵')
  // drainBatchOf 读 first/last + 动态 key；keys 字面量在 pump 调用处
  assert.ok(TS.includes("drainBatchOf(payload, 'events')"), 'JS 少了 events 信封')
  assert.ok(TS.includes("drainBatchOf(payload, 'requests')"), 'JS 少了 requests 信封')
})

test('解析有牙：逐分支键数与 UNREAD 只有 seq', () => {
  const kt = ktEventKeysByBranch(KT)
  const ts = tsReadKeysByCase(TS)
  assert.equal(kt.get('progress').length, 7, 'progress 分支应发 7 键（含 seq）')
  assert.equal(ts.get('progress').length, 6, 'progress case 应读 6 键')
  assert.deepStrictEqual(Object.keys(UNREAD), ['seq'], 'UNREAD 登记项变了——重审注释')
  assert.ok(kt.get('progress').includes('percent'), '缺 percent——键名漂了')
  assert.ok(!kt.get('progress').includes('pct'), '出现 pct——改名未同步')
})

test('phase：phaseWire ⇄ PHASES 双向相等（连字符陷阱在两侧都钉住）', () => {
  bothWays('phase', ktWhenWire(KT, 'phaseWire'), tsArrayWire(TS, 'PHASES'))
})

test('warning kind：kindWire ⇄ WARNING_KINDS 双向相等（feedWarning 的抛错面不虚报）', () => {
  bothWays('kind', ktWhenWire(KT, 'kindWire'), tsArrayWire(TS, 'WARNING_KINDS'))
})

test('approval action：actionWire ⇄ APPROVAL_ACTIONS 双向相等', () => {
  bothWays('action', ktWhenWire(KT, 'actionWire'), tsArrayWire(TS, 'APPROVAL_ACTIONS'))
})

test('事件 type：encodeEvent 发出的 type ⇄ routeInstallEvent 的 case 双向相等', () => {
  bothWays('type', ktEventTypes(KT), tsRouteCases(TS))
})

test('解析有牙：钉住数量与 post-check 本字（小写折叠变体必须不在）', () => {
  const phases = ktWhenWire(KT, 'phaseWire')
  const kinds = ktWhenWire(KT, 'kindWire')
  const actions = ktWhenWire(KT, 'actionWire')
  const types = ktEventTypes(KT)
  assert.equal(phases.length, 6, `phase 应 6 个，解析到 ${phases.length}`)
  assert.equal(kinds.length, 5, `kind 应 5 个，解析到 ${kinds.length}`)
  assert.equal(actions.length, 3, `action 应 3 个，解析到 ${actions.length}`)
  assert.deepStrictEqual(types, ['finished', 'progress', 'warning'])
  assert.ok(phases.includes('post-check'), '缺 post-check——连字符写法漂了')
  assert.ok(!phases.includes('post_check'), '出现 post_check=.name.lowercase() 回潮')
})
