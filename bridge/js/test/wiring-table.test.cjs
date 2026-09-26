'use strict'
/**
 * §12.2 接线现状表对账门（文档声明的「单一事实来源」机械化）。
 *
 * §19 明写「已落地的按 §12.2 接线现状表为准，勿按上表臆造」，表前也写死「未列出的
 * 命名空间在两侧都还没有 handler」——两句都是**可证伪的事实声明**，但表是手写的：
 * handler 改名/删除后行不会自己红，新注册的命名空间也没人回头补行。三向对账：
 *
 * 1. JS facade 列（`images.ts` 这类）→ 文件必须真在 `bridge/js/src/`；
 * 2. Kotlin handler 列（含 `SystemNamespaces.{A,B}Suffix` 花括号展开）→
 *    `class|object|interface` 必须真在某处 `src/**.kt` 里；
 * 3. **宿主注册集 ⊆ 表首列**（表的反向声明）：host `register("ns"` 有的，表必须列；
 *    外加状态列写了「已挂/已可挂」的行，其命名空间必须真在注册集里（不许口头已挂）。
 *
 * 不做的：不验 §12.2 开头那份「对应 AutoJsPro v9」的命名空间愿望清单（`auto.ui.*` /
 * `auto.media` / `auto.ocr` 是设计面，未实现是常态——照事实面扫会常年假红）；
 * 不验挂载状态的措辞（只认「已挂/已可挂」两词与注册集的相容性）。
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

const DOC = fs.readFileSync(path.join(ROOT, 'docs/framework-design.md'), 'utf8')

/** §12.2 接线现状表：从表头到第一个空行（后面是散文，混不得）。 */
function wiringTable(md) {
  const start = md.indexOf('| 命名空间 | JS facade |')
  assert.ok(start >= 0, '找不到 §12.2 接线现状表表头——文档结构漂了')
  const end = md.indexOf('\n\n', start)
  const rows = []
  for (const line of md.slice(start, end).split('\n')) {
    const m = line.match(/^\| (.+?) \| (.+?) \| (.+?) \| (.+?) \|$/)
    if (!m || m[1] === '命名空间' || m[1].startsWith('---')) continue
    rows.push({ ns: m[1], js: m[2], kt: m[3], status: m[4] })
  }
  return rows
}

/** 首列 → 命名空间 token（去掉 `（decode/…）` 注释与反引号后按 / 切）。 */
function firstColNamespaces(cell) {
  const head = cell.split(/[（(]/)[0].replace(/`/g, '')
  return head.split(/[/\s]+/).filter((t) => /^[A-Za-z_]\w*$/.test(t))
}

/** 全仓宿主注册集（与 wire-reconcile 同源：`.register("ns")` / 裸 `register("ns")`）。 */
function registeredNamespaces(root) {
  const out = new Set()
  const walk = (dir) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name)
      if (e.isDirectory()) {
        if (e.name === 'build' || e.name === 'node_modules') continue
        walk(p)
      } else if (e.name.endsWith('.kt') && p.includes(path.sep + 'main' + path.sep)) { // 只认生产源，测试里的假注册器不算
        const src = fs.readFileSync(p, 'utf8')
        for (const m of src.matchAll(/(?:\.|(?<![\w.]))register\(\s*"([A-Za-z_]\w*)"/g)) out.add(m[1])
      }
    }
  }
  walk(root)
  return out
}

/** Kotlin handler 列 → 类名候选（花括号组展开 + 独立 XxxHandler/XxxCollector）。 */
function handlerClasses(cell) {
  let rest = cell
  const names = new Set()
  for (const m of cell.matchAll(/([A-Z][A-Za-z0-9]*)\.\{([^}]+)\}([A-Z][A-Za-z0-9]*Handler)/g)) {
    for (const part of m[2].split(',')) names.add(part.trim() + m[3])
    rest = rest.replace(m[0], '')
  }
  for (const m of rest.matchAll(/\b([A-Z][A-Za-z0-9]*(?:Handler|Collector))\b/g)) names.add(m[1])
  return [...names]
}

function kotlinClassExists(root, name) {
  const dirs = ['domain', 'bridge', 'app-service', 'platform', 'engine', 'app', 'ui']
  const re = new RegExp(`(?:class|object|interface)\\s+${name}\\b`)
  for (const d of dirs) {
    const base = path.join(root, d)
    if (!fs.existsSync(base)) continue
    const stack = [base]
    while (stack.length > 0) {
      const dir = stack.pop()
      for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name)
        if (e.isDirectory()) {
          if (e.name === 'build') continue
          stack.push(p)
        } else if (e.name.endsWith('.kt') && re.test(fs.readFileSync(p, 'utf8'))) return true
      }
    }
  }
  return false
}

const ROWS = wiringTable(DOC)
const REGISTERED = registeredNamespaces(ROOT)

test('JS facade 列：表里点名的每个 .ts 都真在 bridge/js/src/', () => {
  const missing = []
  for (const r of ROWS) {
    for (const ts of r.js.matchAll(/`([a-z][a-z0-9_]*\.ts)`/g)) {
      if (!fs.existsSync(path.join(ROOT, 'bridge/js/src', ts[1]))) missing.push(`${ts[1]}（${r.ns.slice(0, 20)} 行）`)
    }
  }
  assert.deepStrictEqual(missing, [], `表点名了不存在的 facade 文件: ${missing.join(', ')}`)
})

test('Kotlin handler 列：点名的类/对象/接口都在 src 里（改名/删除必红）', () => {
  const missing = []
  const noCandidate = []
  for (const r of ROWS) {
    const classes = handlerClasses(r.kt)
    // 每行至少要认出一个类名候选——否则把类改成不带 Handler/Collector 后缀的
    // 字符串，这一行就变成「无候选可查」，改名检测被静默绕过（变异验证抓过）。
    if (classes.length === 0) noCandidate.push(r.ns.slice(0, 24))
    for (const cls of classes) {
      if (!kotlinClassExists(ROOT, cls)) missing.push(`${cls}（${r.ns.slice(0, 20)} 行）`)
    }
  }
  assert.deepStrictEqual(noCandidate, [], `这些行没解析出任何类名候选（Handler/Collector 后缀丢了？）: ${noCandidate.join(', ')}`)
  assert.deepStrictEqual(missing, [], `表点名了不存在的 Kotlin 类: ${missing.join(', ')}`)
})

test('反向声明：宿主注册了的命名空间，表必须列（未列出 = 两侧都没有 handler）', () => {
  const tableNs = new Set(ROWS.flatMap((r) => firstColNamespaces(r.ns)))
  const unlisted = [...REGISTERED].filter((ns) => !tableNs.has(ns)).sort()
  assert.deepStrictEqual(unlisted, [], `宿主已 register 但接线表没列: ${unlisted.join(', ')}`)
})

test('状态列写「已挂/已可挂」的行，其命名空间必须真在注册集里', () => {
  const lies = []
  for (const r of ROWS) {
    if (!/已挂|已可挂/.test(r.status)) continue
    for (const ns of firstColNamespaces(r.ns)) {
      if (!REGISTERED.has(ns)) lies.push(ns)
    }
  }
  assert.deepStrictEqual(lies, [], `状态列口头已挂、宿主实际没 register: ${lies.join(', ')}`)
})

test('解析有牙：行数/注册数下限与关键行在场', () => {
  assert.ok(ROWS.length >= 15, `接线表只解析到 ${ROWS.length} 行（预期 ≥15）——文档结构漂了`)
  assert.ok(REGISTERED.size >= 19, `只解析到 ${REGISTERED.size} 个注册命名空间（预期 ≥19）——register 解析漂了`)
  const ns = ROWS.flatMap((r) => firstColNamespaces(r.ns))
  for (const must of ['a11y', 'npm', 'sensors', 'power_manager', 'floatingWindow']) {
    assert.ok(ns.includes(must), `接线表缺 ${must} 行`)
  }
  assert.ok(ns.includes('shell') && ns.includes('device'), '五连组行没解析全')
})
