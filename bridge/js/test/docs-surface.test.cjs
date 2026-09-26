'use strict'
/**
 * 文档示例表面对账门（§12.3「照抄可跑」的机械化版）。
 *
 * §12.3.1 的口径是「每一行都在 bridge/js/dist 上真跑过」，而历史上手写示例清单最常犯的
 * 错不是跑错、是**调一个 facade 上不存在的方法**（`auto.shell('...')` 当场 TypeError、
 * `on('progress')` 这类凭 AutoJsPro 印象发明的名字）。真跑全部示例需要一个逐字复刻宿主
 * 的 mock（那是另一个自欺面），所以这道门只钉**表面积**：文档代码块里出现的每个
 * `auto.<ns>.<m>`，dist 导出的 `auto` 上必须真有这个方法；`require('auto')` 的具名导入
 * 必须真在具名导出里（§12.3.2 第 1 条：`auto.AutojsError` 是 `undefined`，导入名写错就是
 * 运行期 undefined 不是编译期红）。
 *
 * 刻意不做的：不执行示例（会引一个手写 mock，回到「mock 跟着 JS 想象走」的老坑）、
 * 不检查返回值形状（那是各命名空间契约测试的活）、**不扫散文**——§12.2 开头那份
 * 「对应 AutoJsPro v9」的清单含 `auto.ui.*`/`auto.media`/`auto.ocr` 等设计面，未实现是
 * 常态，扫了就常年假红（设计愿望与可运行示例的分界就是围栏）。反例如果**故意**写进
 * 代码块（教人别这么写），登记进 [INTENTIONAL_NOT_ON_FACADE] 并写清为什么。
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
const facadeModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = facadeModule.default

/** 文档里**故意**写的反例（不在 facade 上，教学用）→ 为什么。第 4 条会验真。 */
const INTENTIONAL_NOT_ON_FACADE = {}

function codeBlocks(md) {
  return [...md.matchAll(/```[a-zA-Z]*\n([\s\S]*?)```/g)].map((m) => m[1])
}

function autoRefs(md) {
  const seen = new Map() // 'ns.method' -> 出现次数
  for (const b of codeBlocks(md)) {
    for (const m of b.matchAll(/\bauto\.([A-Za-z_$][\w$]*)\.([A-Za-z_][\w]*)\b/g)) {
      const key = `${m[1]}.${m[2]}`
      seen.set(key, (seen.get(key) ?? 0) + 1)
    }
  }
  return seen
}

function requiredNames(md) {
  const names = new Set()
  for (const m of md.matchAll(/const\s*\{([^}]+)\}\s*=\s*require\(\s*['"]auto['"]\s*\)/g)) {
    for (const part of m[1].split(',')) {
      const n = part.split(':')[0].trim()
      if (n) names.add(n)
    }
  }
  return names
}

const refs = autoRefs(DOC)

test('文档代码块里的每个 auto.<ns>.<m> 在 dist 的 auto 上都存在（照抄可跑的表面积）', () => {
  const missing = []
  for (const key of [...refs.keys()].sort()) {
    if (key in INTENTIONAL_NOT_ON_FACADE) continue
    const [ns, m] = key.split('.')
    const obj = auto[ns]
    if (obj == null) { missing.push(`${key}（命名空间 auto.${ns} 不存在）`); continue }
    if (typeof obj[m] !== 'function') missing.push(`${key}（auto.${ns}.${m} 不是可调用方法）`)
  }
  assert.deepStrictEqual(missing, [], `示例调了 facade 上没有的方法（会 TypeError）：\n  ${missing.join('\n  ')}`)
})

test('require(\'auto\') 的具名导入在 dist 具名导出里（不在 auto 根上，§12.3.2 第 1 条）', () => {
  const names = requiredNames(DOC)
  const missing = [...names].filter((n) => !(n in facadeModule)).sort()
  assert.deepStrictEqual(missing, [], `文档教人具名导入、dist 没导出：${missing.join(', ')}`)
})

test('解析有牙：文档里这类引用足够多，解析器失灵不会假绿', () => {
  assert.ok(refs.size >= 40, `只解析到 ${refs.size} 处 auto.<ns>.<m>（预期 ≥40）——文档结构或正则漂了`)
  assert.ok(requiredNames(DOC).size >= 2, '具名导入解析失灵')
  // 抽查一个确实存在的引用，确认不是把所有东西都判缺失/判通过的死循环
  assert.ok(refs.has('npm.onProgress'), 'npm.onProgress 应在示例里')
})

test('INTENTIONAL_NOT_ON_FACADE 不许虚报（必须真是反例、facade 上确实没有）', () => {
  for (const [key, why] of Object.entries(INTENTIONAL_NOT_ON_FACADE)) {
    assert.ok(why && why.length > 10, `反例 ${key} 要写清为什么`)
    const [ns, m] = key.split('.')
    assert.ok(!(auto[ns] && typeof auto[ns][m] === 'function'), `反例 ${key} 已过期：facade 现在有了`)
    assert.ok(refs.has(key), `反例 ${key} 已过期：文档代码块里不再出现它`)
  }
})
