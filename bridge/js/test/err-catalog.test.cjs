'use strict'
/**
 * 错误目录三面对账门（§7.6 单点风险的机械化版）。
 *
 * 目录有三处落字：`:domain` `core/Error.kt` 的 `ErrorCode` 枚举（宿主抛码的唯一出处）、
 * `src/errors.ts` 的 `ErrCode` const enum + `ERROR_CODES` 字面量表（同文件两份手工同步，
 * const enum 编译期内联所以运行期只剩 ERROR_CODES）、以及 `docs/framework-design.md`
 * 散文里提到的码（示例/口径都引用它）。三处都是手工维护，跨语言那次同步历史上
 * 已经漂过一次：`ERR_IO` 在 Kotlin 与全部宿主/文档里服役多时，JS 目录独缺——
 * 脚本 `ERROR_CODES.includes('ERR_IO')` 为 false，而宿主天天抛它。
 *
 * 判据：
 * - Kotlin 枚举 ⇄ `ERROR_CODES`（运行期表）**双向相等**；
 * - `ErrCode` 枚举 ⇄ `ERROR_CODES`（同文件两处）双向相等；
 * - 文档提到的每个码必须**两个目录都在**（文档允许子集——§7.6 自称「前 20 个中最关键」）。
 *
 * 不做的：不验 summary 中文（那是 Kotlin 侧自己的可读性）；不验文档没提的码
 * （目录比文档全是对的，文档是摘录）。
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
  path.join(ROOT, 'domain/src/main/kotlin/com/autoscript/domain/core/Error.kt'),
  'utf8',
)
const TS = fs.readFileSync(path.resolve(__dirname, '..', 'src', 'errors.ts'), 'utf8')
const DOC = fs.readFileSync(path.join(ROOT, 'docs/framework-design.md'), 'utf8')
const { ERROR_CODES } = require(path.resolve(__dirname, '..', 'dist', 'index.js'))

/** `ERR_X` / `ERR_X_1` 这种整码；`ERR_`、`ERR_NPM_` 这类前缀片段不算（词边界挡掉尾下划线）。 */
function docMentions(md) {
  return [...new Set([...md.matchAll(/\bERR_[A-Z0-9]+(?:_[A-Z0-9]+)*\b/g)].map((m) => m[0]))].sort()
}

function ktCodes(src) {
  const body = src.slice(src.indexOf('enum class ErrorCode'), src.indexOf('fun message'))
  return [...new Set([...body.matchAll(/\b(ERR_[A-Z0-9]+(?:_[A-Z0-9]+)*)\s*\(/g)].map((m) => m[1]))].sort()
}

function tsEnumCodes(src) {
  const body = src.slice(src.indexOf('export const enum ErrCode'), src.indexOf('}', src.indexOf('export const enum ErrCode')))
  return [...new Set([...body.matchAll(/=\s*'(ERR_[A-Z0-9]+(?:_[A-Z0-9]+)*)'/g)].map((m) => m[1]))].sort()
}

function tsArrayCodes(src) {
  const decl = src.indexOf('export const ERROR_CODES')
  const open = src.indexOf('= [', decl) // 类型注解 `string[]` 里也有 `]`，须先越过它
  const body = src.slice(open, src.indexOf(']', open))
  return [...new Set([...body.matchAll(/'(ERR_[A-Z0-9]+(?:_[A-Z0-9]+)*)'/g)].map((m) => m[1]))].sort()
}

const kt = ktCodes(KT)
const en = tsEnumCodes(TS)
const arr = tsArrayCodes(TS)
const runtime = [...ERROR_CODES].sort()
const doc = docMentions(DOC)

test('Kotlin ErrorCode ⇄ 运行期 ERROR_CODES 双向相等（跨语言目录不许单边增删）', () => {
  const ktOnly = kt.filter((c) => !runtime.includes(c))
  const jsOnly = runtime.filter((c) => !kt.includes(c))
  assert.deepStrictEqual(ktOnly, [], `:domain 有、JS 目录缺（宿主会抛、脚本判不出）: ${ktOnly.join(', ')}`)
  assert.deepStrictEqual(jsOnly, [], `JS 目录有、:domain 缺（幽灵码，宿主永远抛不出）: ${jsOnly.join(', ')}`)
})

test('ErrCode 枚举 ⇄ ERROR_CODES 字面量表双向相等（同文件两处手工同步）', () => {
  assert.deepStrictEqual(en.filter((c) => !arr.includes(c)), [], 'ErrCode 有、ERROR_CODES 缺')
  assert.deepStrictEqual(arr.filter((c) => !en.includes(c)), [], 'ERROR_CODES 有、ErrCode 缺')
})

test('文档提到的每个码两处目录都在（文档是摘录，但摘的必须是真码）', () => {
  const missing = doc.filter((c) => !kt.includes(c) || !runtime.includes(c))
  assert.deepStrictEqual(missing, [], `文档提了目录里没有的码: ${missing.join(', ')}`)
})

test('解析有牙 + ERR_IO 漂移不许回潮', () => {
  assert.ok(kt.length >= 20, `Kotlin 只解析到 ${kt.length} 个码——Error.kt 结构漂了`)
  assert.ok(en.length >= 19 && arr.length >= 19, `errors.ts 只解析到 ${en.length}/${arr.length}——结构漂了`)
  assert.ok(doc.length >= 15, `文档只解析到 ${doc.length} 个码——§7.6 结构漂了`)
  assert.ok(ERROR_CODES.includes('ERR_TIMEOUT'), 'ERROR_CODES 运行期读不到')
  // 2026-09-26 抓到的真漂移：宿主全线抛 ERR_IO（zip/settings/images/spawn/打包），JS 目录独缺。
  for (const [name, set] of [[':domain', kt], ['ErrCode', en], ['ERROR_CODES', arr], ['文档', doc]]) {
    assert.ok(set.includes('ERR_IO'), `${name} 缺 ERR_IO——上次抓到的漂移回潮了`)
  }
})
