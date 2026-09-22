"use strict";
/**
 * 命名空间根（docs/framework-design.md §12.1 唯一入口）：脚本 `require('auto')` 返回
 * 结构化命名空间对象；模块层各自走 runtimeBridge 到 :main Router。
 *
 * 导入形态（实测契约，勿"顺手统一"）：
 * - CJS（:nodeN 内脚本/E2E/全部测试）：`const { auto } = require('auto')` —— 具名解构；
 * - ESM `import auto from` 拿的是 CJS 整包（Node16 互操作不认 `export default`），
 *   `default.a11y` 为 undefined —— ESM 脚本请用 `import { auto } from` 具名导入。
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.auto = exports.workManagerNS = exports.errFromPayload = exports.ERROR_CODES = exports.NotFoundError = exports.AutojsError = void 0;
const errors_1 = require("./errors");
Object.defineProperty(exports, "AutojsError", { enumerable: true, get: function () { return errors_1.AutojsError; } });
Object.defineProperty(exports, "NotFoundError", { enumerable: true, get: function () { return errors_1.NotFoundError; } });
Object.defineProperty(exports, "ERROR_CODES", { enumerable: true, get: function () { return errors_1.ERROR_CODES; } });
Object.defineProperty(exports, "errFromPayload", { enumerable: true, get: function () { return errors_1.errFromPayload; } });
const runtime_1 = require("./runtime");
const a11y_1 = require("./a11y");
const engines_1 = require("./engines");
const bridge_1 = require("./bridge");
const workManager_1 = require("./workManager");
const images_1 = require("./images");
const npm_1 = require("./npm");
const console_1 = require("./console");
const extras_1 = require("./extras");
const datastore_1 = require("./datastore");
const zip_1 = require("./zip");
/** workManager 命名空间（scheduler 面：P0 每日/一次性排期工具函数，运行态挂全局任务表）。 */
exports.workManagerNS = { daily: workManager_1.daily, once: workManager_1.once, fromInput: workManager_1.fromInput, nextFireAfter: workManager_1.nextFireAfter, createTimedTask: workManager_1.createTimedTask, cancelTask: workManager_1.cancelTask, listTasks: workManager_1.listTasks };
/** 命名空间根对象：挂各类能力；`install` 由 bootstrap/宿主在引擎就绪时注入桥 handler。 */
exports.auto = {
    get bridge() { return runtime_1.runtimeBridge; },
    get a11y() { return a11y_1.a11y; },
    get engines() { return engines_1.engines; },
    get workManager() { return exports.workManagerNS; },
    get screen() { return images_1.screen; },
    get images() { return images_1.images; },
    get npm() { return npm_1.npm; },
    get console() { return console_1.consoleSink; },
    get dialogs() { return extras_1.dialogs; },
    get shell() { return extras_1.shell; },
    get device() { return extras_1.device; },
    get app() { return extras_1.app; },
    get floatingWindow() { return extras_1.floatingWindow; },
    get datastore() { return datastore_1.datastore; },
    get zip() { return zip_1.zip; },
    get envelope() { return bridge_1.BridgeEnvelope; },
    /** 安装桥宿主（单例；重复安装抛错）。 */
    install(handler) {
        runtime_1.runtimeBridge.install(handler);
    },
    /** 宿主把 ok/err 响应回投给桥（Kotlin Router → TSF → JS）。配合 install 的第 4 参 [reqId] 使用。 */
    handleResponse(resp) {
        runtime_1.runtimeBridge.handleResponse(resp);
    },
    get installed() {
        return runtime_1.runtimeBridge.installed;
    },
};
exports.default = exports.auto;
