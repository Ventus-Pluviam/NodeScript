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
 * - **范围**：只换五档。`console.table/dir/trace/assert/group/time/count` 与脚本直接写的
 *   `process.stdout.write` **仍走原生**（stdout 仍被排空丢弃）—— 五档之外要可见需另行排期；
 * - **只包装脚本显式调用的 `console.*`**：Node 自身写 stdout 的路径（启动噪声、`--trace`
 *   之类）不经全局 console 对象，照旧走排空 —— 窗口里不会混进引擎/Node 的内部输出。
 * - **只在生产 spawn 路径装**：本函数由 main.cpp 的 kBootstrap 调，**不挂在 [attachNative]
 *   里** —— 后者在 JVM/桌面测试进程里也被调（`native-bootstrap.test.cjs` 等），在那儿包装
 *   会把测试进程自己的 `console.log`（`CHILD_DONE` 那类 stdout 标记）也劫走。
 *   桌面 `connectBootstrap` 同理不装：那条路 stdout 有人看（终端），没有要解决的问题。
 *
 * **幂等**：重复调用只装一次（`Symbol.for` 标记；重复装会把已包装的当原生再包一层 ——
 * 那才是真会翻车的情形）。**失败不炸**：拿不到 console 对象就原样返回；装的过程里任一档
 * 出问题只跳过那一档（脚本照跑，最坏退化成今天的可见面）。
 */

import { format } from 'node:util'
import { consoleSink } from './console'

/** 幂等标记（`Symbol.for`：同进程里重复 require / 重复调用都认同一个）。 */
const INSTALLED = Symbol.for('autoscript.consoleBridgeInstalled')

/**
 * 五档的接线表：桥的级别 + 原生旁路留不留 + 送桥那一路。
 *
 * 送桥写成显式函数而不是按名字索引 `consoleSink`：后者在 TS 里是联合签名调用，得靠断言
 * 压平；这里是五档的**唯一一份**账，摊开写反而好读也好改。
 */
const LEVELS: ReadonlyArray<{
  readonly name: 'log' | 'info' | 'warn' | 'error' | 'debug'
  /** 原生旁路（stderr）留不留 —— 见文件头「双写」那条。 */
  readonly mirrorToStderr: boolean
  readonly send: (text: string) => Promise<void>
}> = [
  { name: 'log', mirrorToStderr: false, send: (t) => consoleSink.log(t) },
  { name: 'info', mirrorToStderr: false, send: (t) => consoleSink.info(t) },
  { name: 'warn', mirrorToStderr: true, send: (t) => consoleSink.warn(t) },
  { name: 'error', mirrorToStderr: true, send: (t) => consoleSink.error(t) },
  { name: 'debug', mirrorToStderr: false, send: (t) => consoleSink.debug(t) },
]

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

/** 把全局 console 的五档接到桥上（幂等；见文件头 KDoc 的范围与失败口径）。 */
export function installConsoleBridge(): void {
  const raw = (globalThis as { console?: unknown }).console
  if (!raw || typeof raw !== 'object') return
  const target = raw as Record<PropertyKey, unknown>
  if (target[INSTALLED] === true) return
  // 标记先落：装的途中某档抛出去，下次调用也不该重装（重复包装才是真会翻车的形态）。
  target[INSTALLED] = true
  for (const { name, mirrorToStderr, send } of LEVELS) {
    const native = target[name]
    if (typeof native !== 'function') continue // 原生没这一档：跳过（脚本用它照样 TypeError，与原生同）
    // 原生方法**先绑好**：包完再读 target[name] 拿到的就是包装版，那是自己套自己。
    const nativeFn = (native as (...a: unknown[]) => void).bind(target)
    try {
      target[name] = (...args: unknown[]): void => {
        const text = formatArgs(args)
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
