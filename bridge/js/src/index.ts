/**
 * 命名空间根（docs/framework-design.md §12.1 唯一入口）：脚本 `require('auto')` 返回
 * 结构化命名空间对象；模块层各自走 runtimeBridge 到 :main Router。
 */

import { ErrCode, ErrPayload, AutojsError, NotFoundError, ERROR_CODES, errFromPayload } from './errors'
import { runtimeBridge } from './runtime'
import { a11y } from './a11y'
import { engines } from './engines'
import { BridgeEnvelope } from './bridge'
import { daily, once, fromInput, nextFireAfter, TimedSchedule } from './workManager'
import { screen, images } from './images'
import { npm } from './npm'
import { consoleSink } from './console'
import { dialogs, shell, device, app, floatingWindow } from './extras'
import { InvokeHandler } from './bridge'
export { ErrCode, AutojsError, NotFoundError, ERROR_CODES, errFromPayload }
export type { ErrPayload, TimedSchedule }

/** workManager 命名空间（scheduler 面：P0 每日/一次性排期工具函数，运行态挂全局任务表）。 */
export const workManagerNS = { daily, once, fromInput, nextFireAfter }

/** 命名空间根对象：挂各类能力；`install` 由 bootstrap/宿主在引擎就绪时注入桥 handler。 */
export const auto = {
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
  get envelope(): typeof BridgeEnvelope { return BridgeEnvelope },

  /** 安装桥宿主（单例；重复安装抛错）。 */
  install(handler: InvokeHandler): void {
    runtimeBridge.install(handler)
  },

  /** 宿主把 ok/err 响应回投给桥（Kotlin Router → TSF → JS）。配合 install 的第 4 参 [reqId] 使用。 */
  handleResponse(resp: import('./bridge').BridgeResponse): void {
    runtimeBridge.handleResponse(resp)
  },

  get installed(): boolean {
    return runtimeBridge.installed
  },
}

export default auto