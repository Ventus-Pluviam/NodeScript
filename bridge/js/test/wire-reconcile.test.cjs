'use strict'
/**
 * wire 双向对账门（§12.2 接线现状表的机械化版，npm 事件面那次复核的方法级重演）。
 *
 * 为什么要这道门：wire 层的落差历史上咬过两次——`audit` 发 `vulnerabilities` 而宿主读
 * `vulns`、`a11y.waitFor` 的 mock 自己回了个宿主从不发的形状。两次都是「两侧各自绿、
 * 合起来不通」。JS mock 是手写的，它**跟着 JS 的想象走**，抓不住「facade 发了宿主不认识
 * 的方法」；这道门直接对账两份源码，不经过任何 mock：
 *
 * 1. facade 发的每个命名空间，宿主必须 `register` 过（没 register = 整个命名空间 404）；
 * 2. facade 发的每个方法名，宿主的方法表里必须有分支（没有 = 运行期 NOT_IMPLEMENTED，
 *    而文档/类型面在说谎）；
 * 3. 宿主方法表里的每个分支，必须有人发：要么 facade 真发，要么显式登记在 [ALIASES]
 *    （宿主收一个 facade 不发的 wire 名 = 死分支，读代码的人会以为 facade 有这个方法）；
 * 4. [ALIASES] 本身不许虚报：登记的必须「确实存在于宿主方法表、且确实无人发」，
 *    否则过期条目比没有这条规则更坏。
 *
 * 解析范围与限度（明写，别把它当形式验证）：
 * - JS 侧取 `invoke('<ns>','<m>')` 的字面量；a11y 的选择器动作经 `call('<m>')` 代理，
 *   故 a11y 额外认 `call(` 字面量（[DYNAMIC_SINKS]）。动态构造的方法名/命名空间不在
 *   解析范围——本仓目前没有这种写法，新增即红，逼着要么改回字面量、要么更新这张表；
 * - Kotlin 侧取 `when (request.method)` 块里的标签（含 `"a", "b" ->` 多标签行）、
 *   `method != "x"` / `method == "x"` 的字面量守卫；对账是**全局方法名集合**（不追
 *   ns→handler 文件映射），够抓住「发了没人认 / 认了没人发」，不声称能定位到具体 handler。
 */
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

/** 仓库根（向上找 settings.gradle.kts；对 dist 产物测试也成立，不依赖 CWD）。 */
function repoRoot() {
  let p = path.resolve(__dirname, '..', '..')
  for (let i = 0; i < 8; i += 1) {
    if (fs.existsSync(path.join(p, 'settings.gradle.kts'))) return p
    p = path.dirname(p)
  }
  throw new Error('找不到仓库根（settings.gradle.kts）')
}
const ROOT = repoRoot()

const SKIP_DIRS = new Set(['node_modules', 'build', '.git', 'dist', 'generated', 'intermediates', '.gradle', '.kotlin'])

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) {
      if (SKIP_DIRS.has(e.name) || e.name === 'module-stubs') continue
      walk(path.join(dir, e.name), out)
    } else if (e.isFile()) out.push(path.join(dir, e.name))
  }
  return out
}

/** 额外的 JS 方法字面量入口（按文件名登记；动态 sink 越多这道门越松，写清理由再加）。 */
const DYNAMIC_SINKS = {
  // a11y：wrapUiObject 的代理把动作名当字面量传给 call(method, …)，不是第二个 invoke 实参
  'a11y.ts': [/\bcall\(\s*'([A-Za-z0-9_]+)'/g],
}

/** 宿主方法表收、facade 从不发的 wire 名 → 为什么（第 3 条检查的白名单，第 4 条会验真）。 */
const ALIASES = {
  shell: 'extras.ts 的 shell.shell() 是 exec 的 JS 别名，wire 只发 shell/exec（SystemNamespaces 两个标签同体）',
  findOneOrNull:
    '宿主保留的同名分支；facade 实际发 findOne 并把 ERR_NOT_FOUND 本地折成 null（a11y.ts findOneOrNull），wire 上不会出现这个名字',
}

function jsSide() {
  const byNs = new Map() // ns -> Set<method>
  const add = (ns, m) => {
    if (!byNs.has(ns)) byNs.set(ns, new Set())
    byNs.get(ns).add(m)
  }
  for (const f of walk(path.join(ROOT, 'bridge/js/src'), [])) {
    if (!f.endsWith('.ts')) continue
    const t = fs.readFileSync(f, 'utf8')
    for (const [, ns, m] of t.matchAll(/invoke\(\s*['"]([A-Za-z0-9_.]+)['"]\s*,\s*['"]([A-Za-z0-9_]+)['"]/g)) add(ns, m)
    const sinks = DYNAMIC_SINKS[path.basename(f)] ?? []
    for (const re of sinks) for (const [, m] of t.matchAll(re)) add('a11y', m)
  }
  return byNs
}

function kotlinSide() {
  const methods = new Map() // method -> [文件相对路径]
  const registered = new Set()
  const push = (m, f) => {
    if (!methods.has(m)) methods.set(m, [])
    methods.get(m).push(path.relative(ROOT, f))
  }
  for (const f of walk(ROOT, [])) {
    if (!f.endsWith('.kt')) continue
    const t = fs.readFileSync(f, 'utf8')
    for (const m of t.matchAll(/\.register\(\s*"([A-Za-z0-9_.]+)"|(?<![A-Za-z0-9_])register\(\s*"([A-Za-z0-9_.]+)"/g)) {
      registered.add(m[1] ?? m[2])
    }
    for (const blk of t.matchAll(/when\s*\(\s*(?:request\.)?method\s*\)\s*\{/g)) {
      let i = blk.index + blk[0].length
      let depth = 1
      let body = ''
      while (i < t.length && depth > 0) {
        const c = t[i]
        if (c === '{') depth += 1
        else if (c === '}') depth -= 1
        else if (depth === 1) body += c
        i += 1
      }
      for (const line of body.split('\n')) {
        const head = line.split('->')[0]
        if (!head) continue
        for (const m of head.matchAll(/"([A-Za-z0-9_]+)"/g)) push(m[1], f)
      }
    }
    for (const m of t.matchAll(/request\.method\s*[!=]=\s*"([A-Za-z0-9_]+)"/g)) push(m[1], f)
  }
  return { methods, registered }
}

const js = jsSide()
const kt = kotlinSide()

test('facade 发的每个命名空间宿主都 register 过（没 register = 整段命名空间 404）', () => {
  const missing = [...js.keys()].filter((ns) => !kt.registered.has(ns)).sort()
  assert.deepStrictEqual(missing, [], `这些命名空间 JS 在调但宿主没注册：${missing.join(', ')}`)
})

test('facade 发的每个方法宿主方法表都有分支（否则运行期 NOT_IMPLEMENTED = 类型面在说谎）', () => {
  const missing = []
  for (const [ns, methods] of js) {
    for (const m of methods) {
      if (!kt.methods.has(m)) missing.push(`${ns}/${m}`)
    }
  }
  assert.deepStrictEqual(missing.sort(), [], `宿主不认识这些 wire 方法：${missing.join(', ')}`)
})

test('宿主方法表的每个分支都有人发（死分支须登记进 ALIASES 并写清为什么）', () => {
  const sent = new Set()
  for (const methods of js.values()) for (const m of methods) sent.add(m)
  const orphan = [...kt.methods.keys()].filter((m) => !sent.has(m) && !(m in ALIASES)).sort()
  assert.deepStrictEqual(
    orphan,
    [],
    `宿主认、facade 不发的 wire 方法（要么 facade 漏调，要么登记进 ALIASES）：${orphan.join(', ')}`,
  )
})

test('ALIASES 不许虚报：条目必须真实存在且确实无人发（过期白名单比没规则更坏）', () => {
  const sent = new Set()
  for (const methods of js.values()) for (const m of methods) sent.add(m)
  for (const [m, why] of Object.entries(ALIASES)) {
    assert.ok(why && why.length > 10, `ALIASES.${m} 要写清为什么（一行注释都算）`)
    assert.ok(kt.methods.has(m), `ALIASES.${m} 已过期：宿主方法表里没有这个分支了`)
    assert.ok(!sent.has(m), `ALIASES.${m} 已过期：facade 现在真发它了，从表里删掉`)
  }
})

test('对账解析本身有牙（源码里手写一个不存在的方法名，门必须看得见）', () => {
  // 反证：以上四条都用同一份 kt.methods —— 若解析器把 when 块整体读空，第 2/3 条会假绿。
  assert.ok(kt.methods.has('install') && kt.methods.has('waitFor') && kt.methods.has('findOne'),
    'Kotlin 方法表解析失效（when(request.method) 块没被读到）')
  assert.ok(js.get('npm') && js.get('npm').has('install'), 'JS invoke 解析失效')
  assert.ok(kt.registered.has('npm'), 'register 解析失效')
})
