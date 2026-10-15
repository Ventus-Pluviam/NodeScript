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
 *    而 `log`/`info`/`debug` 不写（它们的原生去处 stdout 本就被排空丢弃）；
 * 5. **"只换这几条" ≠ "可见面就这几条"**：Node 的 `Console` 里一批方法**转发**到五档上
 *    （`table`/`group`/`time`/`count` → `log`，`assert(false)` → `warn`，`trace` → `error`），
 *    它们跟着一起可见；`dir`/`dirxml` 原本**直写 stdout**（不经五档），但入口仍是 console
 *    对象上的自有可写属性，所以单独换得掉（2026-10-15 用户令追加）。
 *    这两条**必须钉**：包装前 `table` 写 stdout（被排空）、包装后它进桥 —— 谁把包装改成
 *    "只拦脚本直接调的五个属性"，这两条就红。
 * 6. **`dir` 的渲染保真**：`util.inspect(obj, {customInspect:false, ...options})` 与原生
 *    `Console.prototype.dir` 的源是同一行（原生那句还多一个 `inspectOptions(stream)`，
 *    在引擎里同值 `{colors:false}`）；族里挑几种（嵌套/深度/选项/字符串）对拍。
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

/**
 * 已经装好的包装版 warn/error —— 第一个用例的探针要从它们**外面**套一层，
 * 收尾时也必须还原成**包装版**（不是装之前的原生版）。
 *
 * 这里踩过一次：初版把 `console.warn/error` 在装之前存成 `nativeWarn/nativeError`、
 * 在 `finally` 里还原回去 —— 于是本文件从第二个用例起，全局的 warn/error **就不再是
 * 包装版了**（探针自己把包装摘了）。前三个用例不碰 warn/error 所以没露头，
 * 「转发链」那条（`assert(false)` → warn、`trace` → error）一上来就红。
 * 探针的写法因此改成：**先装、再在包装版外面套**，收尾还原包装版。
 */
installConsoleBridge()
const wrappedWarn = console.warn
const wrappedError = console.error

test('包装：五档都经桥出，log/info/debug 不写原生', () => {
  const seen = []
  // 探针套在**包装版外面**并原样转发：包装体调到的仍是它，桥那一路与原生那一路都照走
  // （只记账不转发的话，warn/error 就压根到不了桥上，这个用例反而假红）。
  console.warn = (...a) => { seen.push(['warn', ...a]); wrappedWarn(...a) }
  console.error = (...a) => { seen.push(['error', ...a]); wrappedError(...a) }
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
    console.warn = wrappedWarn
    console.error = wrappedError
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

test('范围：转发到五档的方法跟着可见，dir/dirxml 也接上了', () => {
  calls.length = 0
  // NativeConsole 把 table/group/time/count 转发到 log、assert(false) 到 warn、trace 到 error
  // —— 包装的是**属性**，转发链自然一起收编（见文件头第 5 条）。
  console.table([{ a: 1 }])
  console.group('g')
  console.time('t'); console.timeEnd('t')
  console.count('c')
  console.assert(false, 'boom')
  console.trace('tr')
  const forwarded = calls.map((c) => c.payload.level)
  assert.equal(forwarded.length >= 6, true, '转发进来的那几条都该在桥上：' + JSON.stringify(calls))
  assert.equal(forwarded.includes('warn'), true, 'assert(false) 走 warn 档')
  assert.equal(forwarded.includes('error'), true, 'trace 走 error 档')
  assert.equal(
    forwarded.filter((l) => l === 'log').length >= 4, true,
    'table/group/time/count 走 log 档：' + JSON.stringify(forwarded),
  )
})

test('dir/dirxml：原本直写 stdout 的两条也接上了（用户令）', () => {
  calls.length = 0
  console.dir({ b: 2 })
  console.dirxml({ c: 3 })
  assert.deepEqual(
    calls.map((c) => [c.payload.level, c.payload.text]),
    [['log', '{ b: 2 }'], ['log', '{ c: 3 }']],
    'dir 与 dirxml 都走 log 档（dirxml 在 Node 里就是 log；dir 的原生去处同样是 stdout）',
  )
})

test('dir 保真：与原生 Console.prototype.dir 同一份 inspect（嵌套/深度/选项/字符串）', () => {
  // 原生 dir 的源就是 inspect(obj, {customInspect:false, ...inspectOptions(stream), ...options})；
  // 这里挑几族对拍**文本**，不是"跑通就行"：渲染换了实现（比如误用 format）当场红。
  const cases = [
    [{ a: 1, b: { c: [1, 2, 3] } }, undefined, '{ a: 1, b: { c: [ 1, 2, 3 ] } }'],
    [{ a: 1, b: { c: [1, 2, 3] } }, { depth: 0 }, '{ a: 1, b: [Object] }'],
    [{ a: 1, b: { c: [1, 2, 3] } }, { depth: null }, '{ a: 1, b: { c: [ 1, 2, 3 ] } }'],
    ['hi', undefined, "'hi'"],                       // dir 对字符串加引号；format 不加
    [42, undefined, '42'],
    [null, undefined, 'null'],
    [{ a: 1 }, 'x', '{ a: 1 }'],                     // 非对象 options：被展开成空，不抛
    [{ a: 1 }, null, '{ a: 1 }'],
  ]
  for (const [obj, opts, want] of cases) {
    calls.length = 0
    if (opts === undefined) console.dir(obj)
    else console.dir(obj, opts)
    assert.equal(calls.length, 1, '每次 dir 只出一行：' + JSON.stringify(calls))
    assert.equal(calls[0].payload.text, want, 'dir 渲染与原生同形（obj=' + JSON.stringify(obj) + '）')
  }
})

test('dir 的 options 是第二个参数（不是第二个值）—— 与 log 的分野', () => {
  calls.length = 0
  console.dir({ x: 1 }, { depth: null })   // 第二参是配置，不出现在文本里
  console.log({ x: 1 }, { depth: null })   // log 的两个都打
  assert.equal(calls[0].payload.text, '{ x: 1 }', 'dir 只渲染第一参')
  assert.equal(calls[1].payload.text, '{ x: 1 } { depth: null }', 'log 两个都渲染（这条分野要保住）')
})

test('dir 不抛：inspect 自己炸（自定义 inspect 抛错）时降级，不当成脚本异常', () => {
  const evil = {}
  Object.defineProperty(evil, Symbol.for('nodejs.util.inspect.custom'), {
    value() { throw new Error('evil') },
    enumerable: false,
  })
  calls.length = 0
  console.dir(evil)   // 原生 dir 默认 customInspect:false，本来也不炸；这里证的是**永不炸**
  assert.equal(calls.length, 1, '降级也要出一行：' + JSON.stringify(calls))
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
