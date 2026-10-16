/// <reference types="node" />

/**
 * `readline` 的提示语 → 桥（docs §7.3 console 数据面）的**引导期接管**。
 *
 * 出处（2026-10-16 用户报）：脚本
 * ```js
 * const rl = require('readline').createInterface({ input: process.stdin })
 * rl.question('请输入：', (input) => { ... })
 * ```
 * 在悬浮窗里**看不到「请输入：」** —— 用户不知道脚本在等输入。
 *
 * **病**：`readline` 的 `question()` 把提示语写进 `options.output`，而这份脚本**根本没给
 * output**（给 `process.stdout` 也一样，见下）——没有 output 的 `Interface` 在
 * `terminal:false` 下把提示语写进**虚空**：本地对拍（node 22.23.1）里
 * `createInterface({input})` 的 `rl.question('请输入：')` 一个字节都没落到任何流上。
 * 就算脚本改写成 `{ input: process.stdin, output: process.stdout }`，在引擎里照样看不见：
 * 子进程 stdout 由宿主排水线程**读即弃**（`ProcessBuilderLauncher`，§8.3 防反压），
 * 提示语进了没人看的管道 —— 与 2026-10-15「裸 console.log 看不见」是同一个病根，
 * 只是入口从 `console.log` 换成了 `readline` 的 output。
 *
 * **药**：引导期（真脚本 `require` 之前 —— 落点在 main.cpp 的 kBootstrap，与
 * `installConsoleBridge` 同一段装配）给 `createInterface` 包一层：**脚本没给 output 时**
 * 补一个假的 output，它的 `write` 把文本送 `consoleSink.log`（= 悬浮窗/控制台那条数据面）；
 * 给了 output 的调用**原样放行**（见「边界」）。
 *
 * **保真与边界**（逐条说清，免得读的人以为"全等"）：
 * - **假 output 是 `EventEmitter`**（`node:events`）：`readline` 在构造函数里就对
 *   `output` 挂 `'resize'`/`'error'` 监听 —— 裸对象会在 `output.on is not a function`
 *   上当场抛（本地实测踩过）。它是**结构替身**，不是真 `Writable`：
 *   只实现 `write`（返回 `true`，从不背压 —— 提示语一行几十字节，没有可背压的量）；
 * - **只有脚本没给 output 时才接管**。给了的（`output: process.stdout`、
 *   自己的流、socket）**一概不碰**：那是脚本显式选的目的地，劫下来等于替它换语义 ——
 *   本仓 2026-10-15 那条「`process.stdout.write` 不劫」的取舍同源
 *   （见 `console-bridge.ts` 文件头「范围」）。代价如实记：`output: process.stdout`
 *   那份提示语在引擎里**仍然看不见**（进了排水管道），要看见得由脚本自己改写法；
 * - **`terminal` 不进 `true`**：注入的 output 没有 `isTTY`（显式 `isTTY === false`），
 *   于是 `readline` 走 `terminal:false` 分支 —— 写出来的就是提示语原文，**没有**
 *   光标移动/擦除那套 ANSI 控制序列（`terminal:true` 才会写那些，进日志面就是乱码）。
 *   脚本自己显式 `terminal: true` 时不改它的选择（那种写法本来就要一个真 TTY 的输出）；
 * - **回显不在这里**：`terminal:false` 下 `readline` 从不回显用户敲的行（那份回显本是
 *   TTY 驱动干的）—— 用户敲的那一行由**窗口侧**回显（`LogWindowState.echoes`，
 *   2026-10-16 同批）。
 * - **提示语按"行"送桥**：尾部换行去掉再送（数据面是行）；中间夹换行的写入原样送
 *   （`Text` 会照画），不拆行 —— 拆行会让"一句提示语"在日志里变成两条无头无尾的记录；
 * - **范围**：只包 `createInterface` 这一条工厂 —— CJS `readline` 与
 *   `readline/promises` 各包一次（两者是**两个不同的函数对象**，本地实测
 *   `readline.createInterface !== readline.promises.createInterface`，且 promises 那条
 *   **不**经过 CJS 那条）。`new readline.Interface(...)` 直用类构造**不覆盖**：换类导出会
 *   破坏 `instanceof readline.Interface`，而那条写法要自己接 `output` 才有意义；
 * - **只在生产 spawn 路径装**：由 main.cpp 的 kBootstrap 调，**不挂在 [attachNative] 里**
 *   —— 后者在 JVM/桌面测试进程里也被调（`native-bootstrap.test.cjs` 等），在那儿换掉
 *   `createInterface` 会波及测试进程自己的 readline 用法。
 *
 * **幂等**：每个被包的模块对象上一个 `Symbol.for` 标记；**失败不炸**：拿不到模块 /
 * `createInterface` 不是函数 / 赋值抛错，一律原样返回（脚本照跑，最坏退化成今天看不见
 * 提示语的形态）。送桥失败也不抛给脚本：`consoleSink` 本身吞掉一切桥侧失败（§7.3），
 * 这里的 `catch` 只兜"没人接的 rejected promise"那条（与 `installConsoleBridge` 同款）。
 */

import { EventEmitter } from 'node:events'
import { consoleSink } from './console'

/** 幂等标记（`Symbol.for`：同进程里重复 require / 重复调用都认同一个；两个模块各挂一份）。 */
const INSTALLED = Symbol.for('autoscript.readlineBridgeInstalled')

/**
 * 假 output：`readline` 只要求它是个能 `write` 的 `EventEmitter`
 * （挂 `'resize'`/`'error'`、调 `output.write(text)`）——见文件头「保真与边界」。
 */
class BridgeOutput extends EventEmitter {
  /** 显式非 TTY：让 `readline` 走 `terminal:false` 分支（不写 ANSI 控制序列）。 */
  readonly isTTY = false

  /** 写入一行文本 → 桥（[consoleSink.log]，即悬浮窗/控制台那条数据面）。返回 `true`：不背压。 */
  write(chunk: unknown): boolean {
    let text: string
    if (typeof chunk === 'string') {
      text = chunk
    } else if (Buffer.isBuffer(chunk)) {
      text = chunk.toString('utf8')
    } else {
      text = String(chunk)
    }
    const line = text.replace(/[\r\n]+$/, '')
    if (line.length > 0) {
      // fire-and-forget：日志面永不抛给脚本（`send` 内部已吞掉桥侧失败，这里兜的是
      // "onQueueError 监听器自己抛"那条 —— 没人接的 rejected promise 在 Node 15+ 是 throw）。
      consoleSink.log(line).catch(() => { /* 见上 */ })
    }
    return true
  }
}

/** 脚本没给 output → 补一个 [BridgeOutput]；给了/options 不是对象 → 原样透传。 */
function withBridgeOutput(options: unknown): unknown {
  if (options === null || typeof options !== 'object') return options
  const opts = { ...(options as Record<string, unknown>) }
  if (!opts.output) opts.output = new BridgeOutput()
  return opts
}

/** 包一个模块的 `createInterface`（幂等 + 失败不炸；见文件头 KDoc）。 */
function patchFactory(mod: unknown): void {
  if (!mod || typeof mod !== 'object') return
  const target = mod as Record<PropertyKey, unknown>
  if (target[INSTALLED] === true) return
  const native = target.createInterface
  if (typeof native !== 'function') return
  // 标记先落：装配途中抛出去，下次调用也不该重装（重复包装才是真会翻车的形态）。
  target[INSTALLED] = true
  const nativeFn = native as (options?: unknown) => unknown
  try {
    target.createInterface = function createInterface(options?: unknown): unknown {
      return nativeFn(withBridgeOutput(options))
    }
  } catch {
    // 只读属性之类 → 保持原生（最坏退化成"提示语看不见"的今天形态，不杀脚本）。
  }
}

/**
 * 把 `readline` 的提示语接到桥上（幂等；见文件头 KDoc）。
 *
 * 包两个模块对象：CJS `node:readline` 与 `node:readline/promises`（`readline.promises`
 * 就是后者那个对象，包一次两处都变 —— 本地实测 `readline.promises.createInterface ===
 * require('node:readline/promises').createInterface`）。
 */
export function installReadlineShim(): void {
  for (const spec of ['node:readline', 'node:readline/promises'] as const) {
    try {
      // eslint-disable-next-line @typescript-eslint/no-require-imports -- 动态 require 内建模块：静态 import 拿不到"脚本 require 到的那个模块对象"
      patchFactory(require(spec))
    } catch {
      // 取不到模块（旧 Node / 打包器裁掉）就跳过那一条：另一条照装。
    }
  }
}
