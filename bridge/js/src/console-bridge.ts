/// <reference types="node" />

/**
 * 全局 `console.*` → 桥（docs §7.3 console 数据面）的**引导期包装**。
 *
 * 出处（2026-10-15 用户令）：「js 的语法都要和原来的调用方式一样，不然写脚本还得兼容语法」。
 *
 * **病**：脚本写裸 `console.log('x')` 走的是 Node 原生 stdout，而引擎宿主对子进程 stdout
 * **读即弃**（`ProcessBuilderLauncher` 的排水线程 —— 它存在的唯一目的是防管道写满反压，
 * 见 §8.3）。行打进了没人看的管道：进程照跑、事件循环照转，日志在悬浮窗/控制台一处也
 * 看不见 —— 要脚本处处写 `auto.` 前缀才看得见输出，等于让每个脚本迁就通道实现。
 * 设备实测（2026-10-15）：同一段倒数脚本，裸 `console.log` 版窗口显示「本次执行没有
 * 输出（已结束）」且日志文件 0 字节；只把五处换成 `auto.console.log` 的版本五行齐全。
 *
 * **药**：引导期（真脚本 `require` 之前 —— 落点在 main.cpp 的 kBootstrap，与
 * `attachNative` 同一段装配）把全局 console 五档换成走桥的实现，于是
 * **`console.log(...)` 与 `auto.console.log(...)` 落到同一条数据面**，脚本侧零改动。
 *
 * **保真与边界**（逐条说清，免得读的人以为"全等"）：
 * - **格式化**用 `node:util.format`（原生 `Console` 内部就是它）：`console.log('%s=%d','a',1)`
 *   照样出 `a=1`、对象按 inspect 展开、循环引用不抛（真抛了降级为字符串拼接）；
 * - **返回 `undefined`**（不是 Promise）：与原生 console 同形 —— 脚本侧
 *   `console.log(x).then(…)` 包装前后都是当场 TypeError，不"多出一个可 await 的面"；
 * - **`warn`/`error` 双写**：桥一份（悬浮窗/控制台可见）**加上**原生 stderr 一份 ——
 *   stderr 那条是**诊断面**（§8.3 `RunSummary.crashSummary` 的有界尾摘要、kBootstrap
 *   自己的 `console.error` 诊断都读它），包装不能把它掐断；
 * - **`log`/`info`/`debug` 纯走桥**：它们的原生去处（stdout）本来就被排空丢弃，没有第二读者；
 * - **异步**：桥是 fire-and-forget 数据面（§7.3 可丢包、TTL 2s、失败经 `onQueueError`），
 *   行不保证在 `process.exit` 之前送达 —— 与 `auto.console.*` 同一条契约，不是新缺口；
 * - **颜色**：原生的 `log`/`dir` 会按**输出流的能力**自动上色（TTY / `FORCE_COLOR`），
 *   本包装不上色（`format`/`inspect` 的缺省就是 `colors:false`）。**引擎里两者逐字节一致**
 *   —— stdout 是管道、脚本 env 不带 `FORCE_COLOR`（本机 640 组入参 × 选项对拍 0 差异），
 *   所以这条差别在生产形态下不可见；真给脚本注入了 `FORCE_COLOR` 才会看到「没有 ANSI 色」。
 *   `dir` 的第二参显式写 `{colors:true}` 时两条路都上色，不受影响；
 * - **范围**：换的是 console 对象上的**属性**，一共七条 —— 五档（`log/info/warn/error/debug`）
 *   加上 `dir`/`dirxml`（2026-10-15 用户令追加）。但**"换了这几条"不等于"可见面就这几条"**：
 *   · Node 的 `Console` 里有一批方法**转发**到这五档上（`table`/`group`/`groupCollapsed`/
 *     `time`/`timeLog`/`timeEnd`/`count` 走 `log`，`assert(false)` 走 `warn`，`trace` 走
 *     `error`），它们因此**跟着一起可见**；
 *   · `dir`/`dirxml` 原本**直写 stdout**（不经五档），但**入口仍是 console 对象上的自有
 *     可写属性**（实测 `own=true writable=true`），所以单独换得掉：`dir` 用
 *     `util.inspect(obj, {customInspect:false, ...options})`（原生 `Console.prototype.dir`
 *     的源就是这一行），`dirxml` 在 Node 里**就是** `log`（`Console.prototype.dirxml ===
 *     Console.prototype.log`，9 组入参实测与 `console.log` 逐字节一致）；
 *   · **仍然看不见的只剩流对象直写**（`process.stdout.write`）：它的入口不在 console
 *     对象上，本包装碰不到 —— 要收编得给流 API 造一份假的背压/二进制契约（`write` 返回
 *     布尔、收 Buffer、可挂 `drain`），**不做**：脚本显式写 stdout 是它的选择，劫到悬浮窗
 *     正是这次要消灭的那个毛病；
 *   `profile`/`profileEnd`/`clear`/`countReset`/`groupEnd` 本就不产出行。
 *   **设备实测（2026-10-15，装真形态 APK 逐个方法跑一遍）**：`table`/`trace`/`assert`/
 *   `group`/`time`/`count` 全部按上面的档位出现在悬浮窗与落盘日志里；`dir` 在只包五档的
 *   `80959ac` 上不出现（当时未包）—— 本节即其收编；`process.stdout.write` 不出现，
 *   且**将来也不会**（见上条）。`console-bridge.test.cjs` 把转发链与 `dir` 都钉成了用例；
 * - **Node 自身的输出分两类**：**进程级 warning**（`process.emitWarning`，如
 *   "No such label 'T' for console.timeLog()"）走的是全局 `console.error`，因此**会**
 *   进桥 —— 同时经双写那条留在 stderr，诊断面无损（设备实测确认）；而 **stdout 直写**
 *   路径（内部 `_rawDebug`、启动噪声、脚本的 `dir`/`stdout.write`）不经全局 console
 *   对象，照旧排空。「窗口里绝不混进非脚本输出」这句因此**不成立**：warning 会进来，
 *   这是有意的取舍 —— 它是诊断，且 stderr 那份仍在。
 * - **只在生产 spawn 路径装**：本函数由 main.cpp 的 kBootstrap 调，**不挂在 [attachNative]
 *   里** —— 后者在 JVM/桌面测试进程里也被调（`native-bootstrap.test.cjs` 等），在那儿包装
 *   会把测试进程自己的 `console.log`（`CHILD_DONE` 那类 stdout 标记）也劫走。
 *   桌面 `connectBootstrap` 同理不装：那条路 stdout 有人看（终端），没有要解决的问题。
 *
 * **幂等**：重复调用只装一次（`Symbol.for` 标记；重复装会把已包装的当原生再包一层 ——
 * 那才是真会翻车的情形）。**失败不炸**：拿不到 console 对象就原样返回；装的过程里任一档
 * 出问题只跳过那一档（脚本照跑，最坏退化成今天的可见面）。
 */

import { format, inspect } from 'node:util'
import { consoleSink } from './console'

/** 幂等标记（`Symbol.for`：同进程里重复 require / 重复调用都认同一个）。 */
const INSTALLED = Symbol.for('autoscript.consoleBridgeInstalled')

/** 包装表的一条：换哪个属性、原生旁路留不留、参数怎么渲染成一行、送桥哪一路。 */
interface Wiring {
  readonly name: string
  /** 原生旁路（stderr）留不留 —— 见文件头「双写」那条。 */
  readonly mirrorToStderr: boolean
  readonly render: (args: readonly unknown[]) => string
  readonly send: (text: string) => Promise<void>
}

/**
 * 五档的接线表：桥的级别 + 原生旁路留不留 + 送桥那一路。
 *
 * 送桥写成显式函数而不是按名字索引 `consoleSink`：后者在 TS 里是联合签名调用，得靠断言
 * 压平；这里是被换属性的**唯一一份**账，摊开写反而好读也好改。
 */
const LEVELS: ReadonlyArray<Wiring> = [
  { name: 'log', mirrorToStderr: false, render: formatArgs, send: (t) => consoleSink.log(t) },
  { name: 'info', mirrorToStderr: false, render: formatArgs, send: (t) => consoleSink.info(t) },
  { name: 'warn', mirrorToStderr: true, render: formatArgs, send: (t) => consoleSink.warn(t) },
  { name: 'error', mirrorToStderr: true, render: formatArgs, send: (t) => consoleSink.error(t) },
  { name: 'debug', mirrorToStderr: false, render: formatArgs, send: (t) => consoleSink.debug(t) },
]

/**
 * 五档**之外**、入口仍在 console 对象上的两条（2026-10-15 用户令追加；见文件头「范围」）：
 * - `dir` 走 [formatDir]（`util.inspect` 那一份，不是 `%` 格式化）；
 * - `dirxml` 在 Node 里就是 `log`（原型上同一个函数），因此渲染与送桥都照 `log` 抄；
 * 两条的原生去处都是 stdout（`kWriteToConsole(kUseStdout, …)`），所以都送 `log` 档、
 * 都不留 stderr 旁路（stdout 本就被排空，没有第二读者）。
 */
const EXTRA: ReadonlyArray<Wiring> = [
  { name: 'dir', mirrorToStderr: false, render: formatDir, send: (t) => consoleSink.log(t) },
  { name: 'dirxml', mirrorToStderr: false, render: formatArgs, send: (t) => consoleSink.log(t) },
]

/** 全部被换掉的属性（五档 + 直写 stdout 的 `dir`/`dirxml`）。 */
const WRAPPED: ReadonlyArray<Wiring> = [...LEVELS, ...EXTRA]

/** 参数 → 一行文本：与原生 Console 同一份格式化（`util.format`），失败降级为字符串拼接。 */
function formatArgs(args: readonly unknown[]): string {
  try {
    return format(...args)
  } catch {
    // util.format 对循环引用/BigInt 都是安全的，走到这里说明遇到 inspect 自身抛的极端形状
    // （自定义 inspect 抛错之类）—— 降级拼接，绝不把一次日志调用变成脚本里的异常。
    try {
      return args.map((a) => (typeof a === 'string' ? a : String(a))).join(' ')
    } catch {
      return ''
    }
  }
}

/**
 * `dir(obj, options)` 的一行文本：与原生 `Console.prototype.dir` 同一份 —— 它自己的源就是
 * `inspect(object, {customInspect:false, ...inspectOptions(stream), ...options})`。
 *
 * 两句差别如实记（都在文件头「范围」「颜色」里）：
 * ① 省掉 `...inspectOptions(stream)` —— 引擎里那份是 `{colors:false}`，与 `inspect` 缺省同值；
 * ② 原生让 `inspect` 的异常**冒出去**（实测 `dir(evil,{customInspect:true})` 抛），本包装
 *    降级成字符串（与 [formatArgs] 同一条纪律：一次日志调用不该变成脚本里的异常）。
 */
function formatDir(args: readonly unknown[]): string {
  const options = args[1]
  try {
    return inspect(args[0], { customInspect: false, ...(options as object | undefined) })
  } catch {
    return formatArgs(args)
  }
}

/** 把全局 console 的七条属性（五档 + `dir`/`dirxml`）接到桥上（幂等；见文件头 KDoc）。 */
export function installConsoleBridge(): void {
  const raw = (globalThis as { console?: unknown }).console
  if (!raw || typeof raw !== 'object') return
  const target = raw as Record<PropertyKey, unknown>
  if (target[INSTALLED] === true) return
  // 标记先落：装的途中某档抛出去，下次调用也不该重装（重复包装才是真会翻车的形态）。
  target[INSTALLED] = true
  for (const { name, mirrorToStderr, render, send } of WRAPPED) {
    const native = target[name]
    if (typeof native !== 'function') continue // 原生没这一档：跳过（脚本用它照样 TypeError，与原生同）
    // 原生方法**先绑好**：包完再读 target[name] 拿到的就是包装版，那是自己套自己。
    const nativeFn = (native as (...a: unknown[]) => void).bind(target)
    try {
      target[name] = (...args: unknown[]): void => {
        const text = render(args)
        if (mirrorToStderr) {
          try {
            nativeFn(text)
          } catch {
            // 诊断旁路自己失败不该拖累数据面（连写 stderr 都炸时，日志面也救不了它）
          }
        }
        // 数据面 fire-and-forget：不 await、不返回 Promise（与原生 console 同形）。
        // `send` 内部已吞掉桥侧一切失败（§7.3）；这里的 catch 兜的是另一条罕见路径 ——
        // onQueueError 监听器自己抛，那异常会从 send 的 catch 块里冒出去把它变成 rejected
        // promise，而没人接的 rejected promise 在 Node 15+ 默认是 throw：一个诊断回调
        // 不该反过来把脚本打成未处理拒绝。
        send(text).catch(() => { /* 见上：日志面永不抛给脚本 */ })
      }
    } catch {
      // 单档装不上（属性只读之类）就保持原生 —— 最坏退化成今天的可见面，不杀脚本。
    }
  }
}
