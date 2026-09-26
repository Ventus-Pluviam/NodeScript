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
 * 不做的：不验回包其它字段名（那是 drain 回包形状测试的活）、不验 seq/游标语义
 * （`npm-events.test.cjs` 的活）。
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
