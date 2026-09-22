/**
 * 命名空间根（docs/framework-design.md §12.1 唯一入口）：脚本 `require('auto')` 返回
 * 结构化命名空间对象；模块层各自走 runtimeBridge 到 :main Router。
 *
 * 导入形态（实测契约，勿"顺手统一"）：
 * - CJS（:nodeN 内脚本/E2E/全部测试）：`const { auto } = require('auto')` —— 具名解构；
 * - ESM `import auto from` 拿的是 CJS 整包（Node16 互操作不认 `export default`），
 *   `default.a11y` 为 undefined —— ESM 脚本请用 `import { auto } from` 具名导入。
 */
import { ErrCode, ErrPayload, AutojsError, NotFoundError, ERROR_CODES, errFromPayload } from './errors';
import { runtimeBridge } from './runtime';
import { a11y } from './a11y';
import { engines } from './engines';
import { BridgeEnvelope } from './bridge';
import { daily, once, fromInput, nextFireAfter, TimedSchedule, createTimedTask, cancelTask, listTasks, CreateTimedTaskInput, TimedTaskInfo, ScreenGuarantee } from './workManager';
import { screen, images } from './images';
import { npm } from './npm';
import { consoleSink } from './console';
import { dialogs, shell, device, app, floatingWindow } from './extras';
import { InvokeHandler } from './bridge';
export { ErrCode, AutojsError, NotFoundError, ERROR_CODES, errFromPayload };
export type { ErrPayload, TimedSchedule, CreateTimedTaskInput, TimedTaskInfo, ScreenGuarantee };
/** workManager 命名空间（scheduler 面：P0 每日/一次性排期工具函数，运行态挂全局任务表）。 */
export declare const workManagerNS: {
    daily: typeof daily;
    once: typeof once;
    fromInput: typeof fromInput;
    nextFireAfter: typeof nextFireAfter;
    createTimedTask: typeof createTimedTask;
    cancelTask: typeof cancelTask;
    listTasks: typeof listTasks;
};
/** 命名空间根对象：挂各类能力；`install` 由 bootstrap/宿主在引擎就绪时注入桥 handler。 */
export declare const auto: {
    readonly bridge: typeof runtimeBridge;
    readonly a11y: typeof a11y;
    readonly engines: typeof engines;
    readonly workManager: typeof workManagerNS;
    readonly screen: typeof screen;
    readonly images: typeof images;
    readonly npm: typeof npm;
    readonly console: typeof consoleSink;
    readonly dialogs: typeof dialogs;
    readonly shell: typeof shell;
    readonly device: typeof device;
    readonly app: typeof app;
    readonly floatingWindow: typeof floatingWindow;
    readonly envelope: typeof BridgeEnvelope;
    /** 安装桥宿主（单例；重复安装抛错）。 */
    install(handler: InvokeHandler): void;
    /** 宿主把 ok/err 响应回投给桥（Kotlin Router → TSF → JS）。配合 install 的第 4 参 [reqId] 使用。 */
    handleResponse(resp: import("./bridge").BridgeResponse): void;
    readonly installed: boolean;
};
export default auto;
