'use strict'
/**
 * 「stdin 没有读者了 → 报宿主」（`stdin-idle.ts`；2026-10-16 用户令「答完自己退」）。
 *
 * 这个观察者**只能装在被观察的那个进程里**（它挂的是那个进程的 `process.stdin`），
 * 所以用例全是**子进程**：父进程写一个临时脚本、spawn `node <脚本>`、从 stdout 读回执。
 * 子进程里的"宿主"是一条假 handler（`runtimeBridge.install`），把收到的调用打成一行
 * `CALL <ns>.<method>` —— 父进程据此断言报了几次、什么时候报的。
 *
 * 逐条钉的是"判据"而不是"实现"：
 * 1. **答完（`rl.close()`）→ 报一次**：这是全部意义的所在（不报 = 脚本答完挂着不退）；
 * 2. **只报一次**：EOF 不可逆，报两遍毫无价值（第二遍也会被宿主的幂等挡掉，但不该发）；
 * 3. **还有读者就不报**：接口没关、或脚本自己还挂着 `stdin.on('data')` —— 这时报上去
 *    会让宿主提前关写端，脚本**正在等的那一行永远收不到**（实测：EOF 到得早时挂着的
 *    `question` 回调永不触发）；
 * 4. **同一个 tick 里"关一个立刻开下一个"不误判**：`rl1.close(); rl2 = createInterface(…)`
 *    是常见写法，报早了第二个问题就废了（判据推到 `setImmediate` 就是为了这个）。
 *
 * 输入用**父进程的管道**（`spawn` 的 stdin 本来就是管道 —— 与引擎里脚本的 stdin 同形）。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const { spawn } = require('node:child_process')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const DIST = path.resolve(__dirname, '..', 'dist')
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'stdin-idle-'))

/** 每个用例一个临时脚本（内容 = 用例正文 + 这段公共前缀）。 */
let seq = 0
function runChild(body) {
  const file = path.join(TMP, `case-${seq++}.cjs`)
  fs.writeFileSync(
    file,
    `'use strict'
const { runtimeBridge } = require(${JSON.stringify(path.join(DIST, 'runtime.js'))})
runtimeBridge.install((ns, method) => { console.log('CALL ' + ns + '.' + method); return {} })
require(${JSON.stringify(path.join(DIST, 'stdin-idle.js'))}).installStdinIdleWatch()
const readline = require('node:readline')
${body}
`,
  )
  const child = spawn(process.execPath, [file], { stdio: ['pipe', 'pipe', 'pipe'] })
  const out = []
  const err = []
  child.stdout.on('data', (b) => out.push(String(b)))
  child.stderr.on('data', (b) => err.push(String(b)))
  return {
    child,
    lines: () => out.join('').split('\n').filter((l) => l.length > 0),
    calls: () => out.join('').split('\n').filter((l) => l.startsWith('CALL ')),
    stderr: () => err.join(''),
  }
}

/** 等一段真实时间（判据是"过一会儿有没有报"，只能等）。 */
const settle = (ms) => new Promise((r) => setTimeout(r, ms))

async function stop(child) {
  if (child.exitCode === null) {
    child.kill('SIGKILL')
    await new Promise((r) => child.once('exit', r))
  }
}

test('答完（rl.close）→ 报一次 engines.stdinIdle', async () => {
  const c = runChild(`
const rl = readline.createInterface({ input: process.stdin })
rl.question('q> ', (a) => { console.log('ANS ' + a); rl.close() })
`)
  // 子进程起来、接口挂上之后再喂（否则这一行可能早于 resume 到达）
  await settle(150)
  assert.deepEqual(c.calls(), [], '还没答之前不该报（读者还在）')
  c.child.stdin.write('1\n')
  await settle(400)
  assert.ok(c.lines().includes('ANS 1'), `回调照常收到那一行（实际输出：${JSON.stringify(c.lines())}）`)
  assert.deepEqual(c.calls(), ['CALL engines.stdinIdle'], '关掉读口之后报一次（宿主据此关写端发 EOF）')
  await stop(c.child)
})

test('接口没关就还在读：不报', async () => {
  const c = runChild(`
const rl = readline.createInterface({ input: process.stdin })
rl.question('q> ', (a) => { console.log('ANS ' + a) })  // 刻意不 close
`)
  await settle(150)
  c.child.stdin.write('1\n')
  await settle(400)
  assert.ok(c.lines().includes('ANS 1'))
  assert.deepEqual(c.calls(), [], '题答完了但接口还开着 → 读者还在，不许报（报早了下一问就废）')
  await stop(c.child)
})

test('脚本自己还挂着 stdin.on(data)：接口关掉也不报', async () => {
  const c = runChild(`
process.stdin.on('data', (b) => console.log('OWN ' + String(b).trim()))
const rl = readline.createInterface({ input: process.stdin })
rl.question('q> ', (a) => { console.log('ANS ' + a); rl.close() })
`)
  await settle(150)
  c.child.stdin.write('1\n')
  await settle(400)
  assert.ok(c.lines().some((l) => l.startsWith('OWN ')), '脚本自己的监听照常收到数据')
  assert.deepEqual(c.calls(), [], 'readline 关了，但脚本自己还在读 → 判据（监听器计数）不该说"没人读"')
  await stop(c.child)
})

test('同一个 tick 里关一个立刻开下一个：不误报', async () => {
  const c = runChild(`
const rl1 = readline.createInterface({ input: process.stdin })
rl1.question('one> ', (a) => {
  console.log('ANS1 ' + a)
  rl1.close()
  // 同一个 tick 里立刻开下一个（判据若同步做，就会把这里当成"没读者"而提前报）
  const rl2 = readline.createInterface({ input: process.stdin })
  rl2.question('two> ', (b) => { console.log('ANS2 ' + b); rl2.close() })
})
`)
  await settle(150)
  c.child.stdin.write('1\n')
  await settle(400)
  assert.deepEqual(c.calls(), [], '第二个接口在同一个 tick 里接上了 → 一次都不该报')
  c.child.stdin.write('2\n')
  await settle(400)
  assert.ok(c.lines().includes('ANS2 2'), `第二个问题照常收到答案（实际输出：${JSON.stringify(c.lines())}）`)
  assert.deepEqual(c.calls(), ['CALL engines.stdinIdle'], '两个都关掉之后才报，且只报一次')
  await stop(c.child)
})

test('没动过 stdin 的脚本：一次都不报（不误伤）', async () => {
  const c = runChild(`console.log('NO-STDIN')`)
  await settle(350)
  assert.ok(c.lines().includes('NO-STDIN'))
  assert.deepEqual(c.calls(), [], '没读过 stdin 就没有"读者走了"这件事 —— 不许无中生有地报')
  await stop(c.child)
})

test('闭环：报上来之后宿主关写端（EOF）→ 脚本自己退，code 0', async () => {
  // 引擎那一半的动作在这里由**父进程**扮演（宿主关子进程的 stdin 写端 —— 就是
  // `Process.outputStream.close()` 在管道上的那件事）。钉的是用户看得见的那句
  // 「答完自己退」：不是 report 本身，是 report → EOF → 脚本按标准流语义退出。
  const c = runChild(`
const rl = readline.createInterface({ input: process.stdin })
rl.question('q> ', (a) => { console.log('ANS ' + a); rl.close() })
// 标准流语义的两条出口都该被触发（脚本能自己感知"输入结束了"）
process.stdin.on('end', () => console.log('EV end'))
rl.on('close', () => console.log('EV close'))
`)
  let reported = false
  c.child.stdout.on('data', () => {
    if (!reported && c.calls().length > 0) {
      reported = true
      c.child.stdin.end() // ← 宿主那一半：关写端发 EOF（进程仍然活着、还在读）
    }
  })
  await settle(150)
  c.child.stdin.write('1\n')
  const code = await new Promise((r) => c.child.once('exit', (code2) => r(code2)))
  assert.equal(code, 0, `脚本自己干净退出（实际输出：${JSON.stringify(c.lines())}）`)
  assert.ok(c.lines().includes('ANS 1'))
  assert.ok(c.lines().includes('EV end'), 'stdin 收到流结束（unref 那套给不了的语义）')
  assert.ok(c.lines().includes('EV close'), 'readline 随之关掉')
  assert.deepEqual(c.calls(), ['CALL engines.stdinIdle'])
})
