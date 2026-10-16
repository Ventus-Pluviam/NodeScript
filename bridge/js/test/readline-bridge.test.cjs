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
 * 6. **接口关掉之后 stdin 被放开（真子进程实测，见文件末三条）**：用户 2026-10-16 问
 *    「为什么它输出 2 之后，过了很久才自动关闭」—— `process.stdin` 是被 ref 住的句柄，
 *    `rl.close()` 只是不再读它，**不放开**，事件循环因此永远不空（管道与真 TTY 都一样，
 *    真终端里要靠 Ctrl-D）。我们这个宿主没有 Ctrl-D，所以由 shim 记账：**最后一个**读 stdin 的
 *    接口被 close、且 stdin 上没有别的 `data`/`readable` 监听时 `unref()`；**新建接口 ref() 回来**
 *    （`resume()` 不会自己 ref 回来，实测 unref 后新建接口等输入进程 159ms 就退了）。
 *    这三条**必须起真子进程**：它钉的是"事件循环能不能空下来"这个进程级事实，
 *    在同进程里 `unref` 自己的 stdin 只会把测试进程自己搞乱。
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

/**
 * 下面三条**起真子进程**（stdin/stdout 都是管道，与引擎里的形态同源），钉的是
 * 「事件循环能不能空下来」这个进程级事实 —— 见文件头第 6 条。
 *
 * 子进程的脚本第一行就是 `installReadlineShim()`（跟 kBootstrap 同一次序：装完补丁再跑脚本）。
 */
const { spawn } = require('node:child_process')
const os = require('node:os')
const fs = require('node:fs')

/** 起一个装了本 shim 的真 node 子进程跑 [body]（返回句柄 + 收到的 stdout）。 */
function spawnScript(body) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rl-shim-'))
  const file = path.join(dir, 'script.cjs')
  fs.writeFileSync(
    file,
    "require(process.env.AUTOSCRIPT_RL_SHIM).installReadlineShim()\n" + body,
    'utf8',
  )
  const child = spawn(process.execPath, [file], {
    stdio: ['pipe', 'pipe', 'pipe'],
    env: {
      ...process.env,
      AUTOSCRIPT_RL_SHIM: path.resolve(__dirname, '..', 'dist', 'readline-bridge.js'),
    },
  })
  const out = { text: '' }
  child.stdout.on('data', (d) => { out.text += d.toString('utf8') })
  child.stderr.on('data', (d) => { out.text += d.toString('utf8') })
  return { child, out }
}

/** 等子进程退出；[ms] 内没退就回 null（并把它杀掉，免得挂住整个测试文件）。 */
function waitForExit(child, ms) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => { child.kill('SIGKILL'); resolve(null) }, ms)
    child.on('exit', (code) => { clearTimeout(timer); resolve(code) })
  })
}

/** 往子进程 stdin 写一行（已退出就什么都不做 —— 用例在意的是"它退了没"）。 */
function feed(child, line) {
  if (child.exitCode === null && child.stdin.writable) child.stdin.write(line + '\n')
}

test('答完就结束：最后一个接口 close 之后进程自己退出（用户问的那件事）', async () => {
  const { child, out } = spawnScript(`
    const readline = require('node:readline')
    const rl = readline.createInterface({ input: process.stdin })
    rl.question('请输入：', (input) => {
      console.log(Number(input) === 1 ? 2 : 3)
      rl.close()
    })
  `)
  setTimeout(() => feed(child, '1'), 120)
  const code = await waitForExit(child, 4000)
  assert.equal(code, 0, 'stdin 的写端还开着，close 之后也该自己结束 —— 否则用户看到的是"一直显示在跑"')
  assert.match(out.text, /2/, '结果照常打出来：' + out.text)
})

test('关一个再开一个不会被误杀：新建接口把 stdin ref 回来', async () => {
  const { child, out } = spawnScript(`
    const readline = require('node:readline')
    const rl = readline.createInterface({ input: process.stdin })
    rl.question('第一问', (a) => {
      console.log('got1:' + a)
      rl.close()
      const rl2 = readline.createInterface({ input: process.stdin })
      rl2.question('第二问', (b) => { console.log('got2:' + b); rl2.close() })
    })
  `)
  setTimeout(() => feed(child, '甲'), 120)
  // 第二问要等一会儿才喂：中间这段"只有 readline 在等"的时间里进程必须还活着
  setTimeout(() => feed(child, '乙'), 700)
  const code = await waitForExit(child, 4000)
  assert.match(out.text, /got1:甲/, '第一问照常：' + out.text)
  assert.match(out.text, /got2:乙/, '第二问也要收到 —— 提前退出就吃不到这一行：' + out.text)
  assert.equal(code, 0)
})

test('接口没关就还在读：不会提前退出', async () => {
  const { child } = spawnScript(`
    const readline = require('node:readline')
    const rl = readline.createInterface({ input: process.stdin })
    rl.question('等你：', () => { /* 故意不 close：脚本还在等下一行 */ })
  `)
  await new Promise((r) => setTimeout(r, 1200))
  assert.equal(child.exitCode, null, '还在读 stdin 的脚本不能被"放开 stdin"误杀')
  child.kill('SIGKILL')
  await waitForExit(child, 2000)
})
