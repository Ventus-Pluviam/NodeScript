'use strict'
/**
 * 全局 `console.*` → 桥的包装（docs §7.3 数据面；2026-10-15 用户令
 * 「js 的语法都要和原来的调用方式一样，不然写脚本还得兼容语法」）。
 *
 * 钉的是「包装之后脚本侧语义不变、可见面变了」这四件：
 * 1. **落到同一条数据面**：`console.log(...)` 与 `auto.console.log(...)` 都经
 *    `runtimeBridge.invoke('console','log',…)` 出 —— 这正是"零改动语法"的全部含义；
 * 2. **五档全接**（log/info/warn/error/debug）；
 * 3. **保真**：`util.format` 的格式化（`%s=%d`）、返回 `undefined`（不是 Promise；
 *    原样保留"`console.log(x).then` 当场 TypeError"这条原生语义）；
 * 4. **双写**：`warn`/`error` 除桥之外**仍写原生 stderr**（诊断面不能被包装掐断 ——
 *    §8.3 的 `crashSummary` 尾摘要与 kBootstrap 自己的诊断都读 stderr），
 *    而 `log`/`info`/`debug` 不写（它们的原生去处 stdout 本就被排空丢弃）。
 *
 * **装一次就够**：`installConsoleBridge` 带 `Symbol.for` 幂等标记，装完本文件里
 * 全局 console 就一直是包装版 —— 所以断言集中在前面几个 test 里按序做完，
 * 后面的 test 只做「再装一次不翻倍」这一条（顺序即用例设计，见文件末注）。
 *
 * 独立进程跑（node --test 每文件一进程）：本文件动的是**全局 console**，
 * 与别的用例文件共进程会互相污染。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default
const { installConsoleBridge } = require(path.resolve(__dirname, '..', 'dist', 'console-bridge.js'))

/** 捕获桥调用（install 一次即够：本文件后面还要用它数次数）。 */
const calls = []
auto.install((ns, method, payloadJson, reqId) => {
  calls.push({ ns, method, payload: payloadJson === null ? null : JSON.parse(payloadJson) })
  return {} // 内联答案：包装体是 fire-and-forget，不关心回包形状
})

/** 原生的 warn/error（双写断言要盯的就是它们）。 */
const nativeWarn = console.warn
const nativeError = console.error

test('包装：五档都经桥出，log/info/debug 不写原生', () => {
  const seen = []
  console.warn = (...a) => seen.push(['warn', ...a])
  console.error = (...a) => seen.push(['error', ...a])
  try {
    installConsoleBridge()
    calls.length = 0

    console.log('hello')
    console.info('info-line')
    console.debug('debug-line')
    console.warn('warn-line')
    console.error('error-line')

    assert.deepEqual(
      calls.map((c) => [c.ns, c.method, c.payload.level, c.payload.text]),
      [
        ['console', 'log', 'log', 'hello'],
        ['console', 'log', 'info', 'info-line'],
        ['console', 'log', 'debug', 'debug-line'],
        ['console', 'log', 'warn', 'warn-line'],
        ['console', 'log', 'error', 'error-line'],
      ],
      '五档全走 bridge 的 console/log，level 逐档对齐',
    )
    assert.deepEqual(
      seen,
      [['warn', 'warn-line'], ['error', 'error-line']],
      '只有 warn/error 留原生旁路（诊断面）；log/info/debug 不写',
    )
  } finally {
    console.warn = nativeWarn
    console.error = nativeError
  }
})

test('保真：util.format 语义 + 返回 undefined（不是 Promise）', () => {
  calls.length = 0
  const ret = console.log('%s=%d', 'a', 1)
  assert.strictEqual(ret, undefined, '返回值与原生同形（Promise 会让 .then 从 TypeError 变成能跑）')
  assert.equal(calls[0].payload.text, 'a=1', '格式化走 util.format（原生 Console 内部同一份）')

  const cyclic = {}
  cyclic.self = cyclic
  console.log('循环', cyclic)               // inspect 不抛 → 不降级
  console.log('大整数', 10n, () => {})       // BigInt/函数：inspect 形态
  // 循环引用按 inspect 展开（不抛、不降级成 "[object Object]"）：node 的写法是
  // `<ref *1> { self: [Circular *1] }` —— 只钉「展开了对象本体」这一个事实，
  // 不去逐字复刻 node 的 ref 编号（那是 node 版本的事，不是本包装的承诺）。
  assert.match(calls[1].payload.text, /^循环 .*\{ self: \[Circular/)
  assert.match(calls[2].payload.text, /^大整数 10n \[Function/)

  assert.equal(calls[0].payload.text.includes('\n'), false, '单行文本（桥的行语义，§7.3）')
})

test('幂等：再装一次不翻倍（Symbol.for 标记）', () => {
  installConsoleBridge()
  installConsoleBridge()
  calls.length = 0
  console.log('once')
  assert.equal(calls.length, 1, '重复安装必须只留一层包装 —— 套两层会让每条日志出两份')
})

test('脚本侧仍可 await auto.console.log —— 与裸 console.log 落到同一条面', async () => {
  calls.length = 0
  await auto.console.log('via-auto')
  console.log('via-bare')
  assert.deepEqual(
    calls.map((c) => c.payload.text),
    ['via-auto', 'via-bare'],
    '两条写法落到同一条数据面（这就是「语法零改动」的全部含义）',
  )
})
