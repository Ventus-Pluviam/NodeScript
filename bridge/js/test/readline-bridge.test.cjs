'use strict'
/**
 * `readline` 提示语 → 桥的接管（docs §7.3 数据面；2026-10-16 用户报
 * 「悬浮窗不会显示请输入这三个字」）。
 *
 * 钉的是"用户的写法一行不改就看得到提示语"这件事，逐条：
 * 1. **用户报的那个写法**（`createInterface({ input })`，**没给 output**）：
 *    `rl.question('请输入：')` 的提示语经桥出（`console/log` 档），而回调照常收到那一行
 *    —— 接管只改"提示语去哪"，不改 readline 的任何输入语义；
 * 2. **`terminal` 仍是 false、文本是原文**：注入的 output 显式非 TTY，写出来的就是提示语
 *    本身，**没有**光标移动/擦除那套 ANSI 控制序列（那是 `terminal:true` 才有的东西，
 *    进了日志面就是乱码）；
 * 3. **给了 output 的调用一概不碰**：脚本自己选了目的地（`output: 自己的流`），劫下来
 *    等于替它换语义 —— 提示语必须原样落在那条流上、桥里一条都不能多；
 * 4. **幂等**：重复装只留一层（套两层会让每条提示语出两份）；
 * 5. **`readline/promises` 那条工厂也接管**：两个模块是**两个不同的函数对象**
 *    （本地实测 `readline.createInterface !== readline.promises.createInterface`，
 *    且 promises 那条不经过 CJS 那条），漏包一个就等于"用 promises 写脚本的人看不见提示语"。
 *
 * 输入用 `PassThrough` 而不是 `process.stdin`：本文件要的是"管道形态的 stdin"（引擎里
 * 就是管道），借宿主自己的 stdin 会把用例挂到跑测试那个进程的输入上（本仓踩过
 * "测试别借宿主资源"那一类）。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const { EventEmitter } = require('node:events')
const { PassThrough } = require('node:stream')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default
const { installReadlineShim } = require(path.resolve(__dirname, '..', 'dist', 'readline-bridge.js'))

/** 捕获桥调用（装一次即够：本文件后面还要用它数次数）。 */
const calls = []
auto.install((ns, method, payloadJson) => {
  calls.push({ ns, method, payload: payloadJson === null ? null : JSON.parse(payloadJson) })
  return {} // 内联答案：数据面是 fire-and-forget，不关心回包形状
})
installReadlineShim()

/** 桥上收到的那些文本（顺序即送出顺序）。 */
const sent = () => calls.filter((c) => c.ns === 'console' && c.method === 'log').map((c) => c.payload.text)

/** 造一个"管道形态"的 stdin（见文件头：不借宿主自己的 stdin）。 */
function pipeInput() {
  return new PassThrough()
}

test('用户报的写法：没给 output → 提示语经桥出，回调照常收到那一行', () => {
  calls.length = 0
  const input = pipeInput()
  const readline = require('node:readline')
  const rl = readline.createInterface({ input })
  const answers = []
  rl.question('请输入：', (answer) => { answers.push(answer); rl.close() })
  input.write('1\n')
  assert.deepEqual(answers, ['1'], '输入语义一字未改：回调拿到的还是用户敲的那一行（不带换行）')
  assert.deepEqual(sent(), ['请输入：'], '提示语走桥的 console/log 档（悬浮窗与落盘日志同一条面）')
  assert.deepEqual(rl.terminal, false, '注入的 output 非 TTY → 仍是 terminal:false（没有 ANSI 控制序列的前提）')
})

test('重复装不翻倍（Symbol.for 标记）', () => {
  calls.length = 0
  installReadlineShim()
  installReadlineShim()
  const input = pipeInput()
  const rl = require('node:readline').createInterface({ input })
  rl.question('再来：', () => rl.close())
  assert.deepEqual(sent(), ['再来：'], '套两层会让同一句提示语出两份')
})

test('给了 output 的调用一概不碰：提示语落在脚本自己那条流上，桥里没有', () => {
  calls.length = 0
  const input = pipeInput()
  // 脚本自己给的 output：只要是个能 write 的 EventEmitter，readline 就认（与注入的那个同形）
  class Own extends EventEmitter {
    written = []
    write(chunk) { this.written.push(String(chunk)); return true }
  }
  const own = new Own()
  const rl = require('node:readline').createInterface({ input, output: own })
  rl.question('自备输出：', () => rl.close())
  assert.deepEqual(own.written, ['自备输出：'], '脚本选了目的地就照它说的走')
  assert.deepEqual(sent(), [], '接管只发生在"没给 output"时 —— 给了的劫下来就是替脚本换语义')
})

test('空写入不产生空行（桥的数据面按行算）', () => {
  calls.length = 0
  const input = pipeInput()
  const rl = require('node:readline').createInterface({ input })
  rl.prompt()      // 缺省提示语 '> '
  rl.close()       // terminal:false 下 close 不写输出，但"只写换行"的形态也不该造出空行
  assert.equal(sent().every((t) => t.length > 0), true, '送出去的每一行都得有内容：' + JSON.stringify(sent()))
  assert.equal(sent().includes('> '), true, 'rl.prompt() 的缺省提示语同样走桥')
})

test('readline/promises 那条工厂也接管（两个函数对象，必须各包一次）', async () => {
  calls.length = 0
  const input = pipeInput()
  const rlp = require('node:readline/promises')
  const rl = rlp.createInterface({ input })
  const asked = rl.question('请输入：')
  input.write('3\n')
  assert.equal(await asked, '3')
  rl.close()
  assert.deepEqual(sent(), ['请输入：'], 'promises 写法的人同样看得见提示语')
})
