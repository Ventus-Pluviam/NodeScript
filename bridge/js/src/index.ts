/**
 * 命名空间根（docs §12.1 唯一入口）：脚本 `require('auto')` 返回
 * 结构化命名空间对象；模块层各自走 runtimeBridge 到 :main Router。
 *
 * 导入形态（实测契约，勿"顺手统一"）：
 * - CJS（:nodeN 内脚本/E2E/全部测试）：`const { auto } = require('auto')` —— 具名解构；
 * - ESM `import auto from` 拿的是 CJS 整包（Node16 互操作不认 `export default`），
 *   `default.a11y` 为 undefined —— ESM 脚本请用 `import { auto } from` 具名导入。
 */

import { ErrCode, ErrPayload, AutojsError, NotFoundError, ERROR_CODES, errFromPayload } from './errors'
import { runtimeBridge } from './runtime'
import { a11y } from './a11y'
import { engines } from './engines'
import { BridgeEnvelope } from './bridge'
import { daily, once, cron, fromInput, nextFireAfter, nextCronFireAfter, TimedSchedule, TimedScheduleInput, createTimedTask, cancelTask, listTasks, CreateTimedTaskInput, TimedTaskInfo, ScreenGuarantee } from './workManager'
import { screen, images } from './images'
import { npm } from './npm'
import { consoleSink } from './console'
import { dialogs, shell, device, app, floatingWindow } from './extras'
import { datastore } from './datastore'
import { zip } from './zip'
import { settings } from './settings'
import { notification } from './notification'
import { clipboard } from './clipboard'
import { sensors } from './sensors'
import { power } from './power'
import { BridgeResponse, InvokeHandler } from './bridge'
export { ErrCode, AutojsError, NotFoundError, ERROR_CODES, errFromPayload }
export type { ErrPayload, TimedSchedule, TimedScheduleInput, CreateTimedTaskInput, TimedTaskInfo, ScreenGuarantee }

/** workManager 命名空间（scheduler 面：每日/一次性/cron 排期工具函数，运行态挂全局任务表）。 */
export const workManagerNS = { daily, once, cron, fromInput, nextFireAfter, nextCronFireAfter, createTimedTask, cancelTask, listTasks }

/**
 * `auto.*` 命名空间根：脚本 `require('auto')` 拿到的就是它（docs §12.1 唯一入口）。
 */
export interface AutoNamespace {
  readonly bridge: typeof runtimeBridge
  readonly a11y: typeof a11y
  readonly engines: typeof engines
  readonly workManager: typeof workManagerNS
  readonly screen: typeof screen
  readonly images: typeof images
  readonly npm: typeof npm
  readonly console: typeof consoleSink
  readonly dialogs: typeof dialogs
  readonly shell: typeof shell
  readonly device: typeof device
  readonly app: typeof app
  readonly floatingWindow: typeof floatingWindow
  readonly datastore: typeof datastore
  readonly zip: typeof zip
  readonly settings: typeof settings
  readonly notification: typeof notification
  readonly clipboard: typeof clipboard
  readonly sensors: typeof sensors
  readonly power: typeof power
  readonly envelope: typeof BridgeEnvelope
  /** 安装桥宿主（单例；重复安装抛错）。 */
  install(handler: InvokeHandler): void
  /** 宿主把 ok/err 响应回投给桥（Kotlin Router → TSF → JS）。配合 install 的第 4 参 [reqId] 使用。 */
  handleResponse(resp: BridgeResponse): void
  readonly installed: boolean
}

/** 命名空间根对象：挂各类能力；`install` 由 bootstrap/宿主在引擎就绪时注入桥 handler。 */
// 具名 interface 存在的理由是**文档面**（backlog C7）：`auto` 原本是匿名对象字面量，
// typedoc 只能把它渲染成一串 `__type`，用户向 API 参考（`npm run docs:api` →
// `docs/api/`）于是没有可链接的入口。**不引入第二份事实** —— 字段类型全部是
// `typeof <现成的 namespace 对象>`，与下面这份实现同源（手写一遍签名才会漂移）。
// 文档入口面 = 本文件的 export 面（作者划的边界）；内部件（`runtime`/`bridge` 的
// 实现细节、各 namespace 里的 `pump*` 之类）刻意不进 entryPoints，免得把内部件
// 抬成"文档上的 API"。
export const auto: AutoNamespace = {
  get bridge(): typeof runtimeBridge { return runtimeBridge },
  get a11y(): typeof a11y { return a11y },
  get engines(): typeof engines { return engines },
  get workManager(): typeof workManagerNS { return workManagerNS },
  get screen(): typeof screen { return screen },
  get images(): typeof images { return images },
  get npm(): typeof npm { return npm },
  get console(): typeof consoleSink { return consoleSink },
  get dialogs(): typeof dialogs { return dialogs },
  get shell(): typeof shell { return shell },
  get device(): typeof device { return device },
  get app(): typeof app { return app },
  get floatingWindow(): typeof floatingWindow { return floatingWindow },
  get datastore(): typeof datastore { return datastore },
  get zip(): typeof zip { return zip },
  get settings(): typeof settings { return settings },
  get notification(): typeof notification { return notification },
  get clipboard(): typeof clipboard { return clipboard },
  get sensors(): typeof sensors { return sensors },
  get power(): typeof power { return power },
  get envelope(): typeof BridgeEnvelope { return BridgeEnvelope },

  /** 安装桥宿主（单例；重复安装抛错）。 */
  install(handler: InvokeHandler): void {
    runtimeBridge.install(handler)
  },

  /** 宿主把 ok/err 响应回投给桥（Kotlin Router → TSF → JS）。配合 install 的第 4 参 [reqId] 使用。 */
  handleResponse(resp: BridgeResponse): void {
    runtimeBridge.handleResponse(resp)
  },

  get installed(): boolean {
    return runtimeBridge.installed
  },
}

export default auto