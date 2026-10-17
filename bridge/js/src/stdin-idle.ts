/// <reference types="node" />

/**
 * 「stdin 上没有读者了」→ 报宿主 → 宿主关写端发 **EOF**（2026-10-16 用户令）。
 *
 * 出处（2026-10-16 用户问「为什么它输出 2 之后，过了很久才自动关闭」）：脚本答完一个问题、
 * `rl.close()` 之后**进程不结束**，悬浮窗一直显示在跑。实测（本机 node 22.23.1；运行时是
 * Node 24，下面三条都是流语义，同族同口径）：
 * - `rl.close()` 把 readline 自己挂在 stdin 上的 `data`/`end` 监听器**撤得干干净净**、
 *   `readableFlowing` 回 `false`、并发一次 `'pause'` —— **但进程照样挂着**：挂住的不是
 *   readline，是那个还 ref 着的 stdin 管道句柄（`rl.close()` / `process.stdin.pause()`
 *   都不放开它，本地实测两者都挂）；
 * - **引擎关掉写端（真 EOF）**：脚本收到 `stdin 'end'` → readline 发 `'close'` →
 *   **408ms、code 0 干净退出**。这是**标准语义**：脚本自己能感知输入流结束
 *   （`process.stdin.on('end')`、`rl.on('close')` 都照常触发）。
 *
 * **为什么由宿主发 EOF，而不是脚本自己 `process.stdin.unref()`**（同日被推翻的上一版，
 * 提交 `91929b1d` 已 revert）：unref 让脚本**看不见 `end`**（`unref` 只影响"事件循环要不要
 * 退"，不产生流结束事件），而且要靠换掉全局 `readline.createInterface` 才数得清读者。
 * 改成报宿主之后，脚本侧只剩"报事实"这一件事，关写端是宿主的事，脚本收到的是标准流结束。
 *
 * **判据**：`'pause'` 是本进程内唯一能观察到"读的人走了"的时刻（关 readline、
 * `for await` 读空、显式 `unref` 都会让它出现），但**真正的判据是监听器计数** ——
 * `data`/`readable` 两个都归零才叫没有读者（readline 在 `close()` 时撤得干净：本地实测
 * `dataL 1→0`）。两者分工：`'pause'` 当闹钟、计数当判据。副作用是脚本自己
 * `rl.pause()`（流控，监听器还在）**不会**被误判成"读完了"。
 *
 * **只往 `process.stdin` 挂一个监听，不碰 `readline` 模块**（不换任何全局函数）——
 * 上一版那条"改全局"正是被推翻的原因之一。
 *
 * **报一次就够**：EOF 不可逆，报过就置位（宿主那条链本身也幂等）。
 *
 * **同一 tick 里刚发生的事不碰**：`'pause'` 之后推到 `setImmediate` 再判 ——
 * "关掉这个接口、在同一个 tick 里立刻开下一个"（`rl1.close(); rl2 = createInterface(…)`）
 * 是常见写法，同步就判会把那次重开当成"没读者"而**提前发 EOF**。提前的代价是实测过的：
 * EOF 到得早时**挂着的 `question` 回调永远不触发**，脚本剩下的活静默丢掉。
 *
 * **代价如实说**（边界，不粉饰）：EOF 一次性、不可逆 —— 关掉读口之后**过一会儿**才重开的
 * 脚本（`setTimeout(() => rl2.question(…), 500)`）救不回来，那个问题会静默丢掉。
 * 判据能答的只是"这一刻确实没有读者"，答不了"以后还要不要读"——后者只有脚本自己知道，
 * 而本仓的口径是脚本的写法**保持原生**（不加一套专用 API，2026-10-15 用户令）。
 *
 * **只在生产 spawn 路径装**：由 main.cpp 的 kBootstrap 调（与 `installReadlineShim`
 * 同一段装配）—— **不**挂在 `attachNative` 里：后者在 JVM/桌面测试进程里也被调，
 * 在那儿报一下就会去关**测试进程自己**的 stdin 写端。
 *
 * **失败不炸**：桥投不出去（离线 / 宿主已走 / 未接线）一律吞掉 —— 最坏退化成
 * "今天这个挂着的形态"，不杀脚本（与 `installReadlineShim` 同一条纪律）。
 */

import { runtimeBridge } from './runtime'

/** 幂等标记（`Symbol.for`：同进程里重复 require / 重复调用都认同一个）。 */
const INSTALLED = Symbol.for('autoscript.stdinIdleWatchInstalled')

/** 送桥 TTL（与 console 数据面同档：fire-and-forget 的短 TTL，过期即丢，不阻塞脚本）。 */
const REPORT_TTL_MILLIS = 2_000

/** 还有人在读 stdin 吗（readline 关掉时撤 `data`；`for await` / `on('readable')` 走另一条）。 */
function hasReader(stdin: NodeJS.ReadStream): boolean {
  return stdin.listenerCount('data') > 0 || stdin.listenerCount('readable') > 0
}

/**
 * 装上"没人读 stdin 了 → 报宿主"的观察者（幂等；见文件头 KDoc）。
 *
 * 报的是**事实**（这次执行此刻 stdin 上没有读者），由宿主决定动作（关写端发 EOF）——
 * 脚本侧不自己 unref、不自己退，退出的时机由标准流语义给出。
 */
export function installStdinIdleWatch(): void {
  const stdin = process.stdin
  const marker = stdin as unknown as Record<PropertyKey, unknown>
  if (marker[INSTALLED] === true) return
  marker[INSTALLED] = true
  let reported = false
  try {
    stdin.on('pause', () => {
      // 推到下一个宏任务再判（见文件头「同一 tick 里刚发生的事不碰」）。
      setImmediate(() => {
        if (reported || hasReader(stdin)) return
        reported = true
        // fire-and-forget：投不出去也不杀脚本（文件头「失败不炸」）。`invoke` 是 async，
        // 未接线时回一条 rejected promise —— `.catch` 兜住（Node 15+ 没人接的 rejection 是 throw）。
        runtimeBridge.invoke('engines', 'stdinIdle', {}, { ttl: REPORT_TTL_MILLIS }).catch(() => { /* 见上 */ })
      })
    })
  } catch {
    // 挂不上监听（流被冻结/替身流）→ 保持原状：最坏退化成"今天这个挂着的形态"。
  }
}
