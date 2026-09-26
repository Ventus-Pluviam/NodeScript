"use strict";
/**
 * dialogs / shell / device / app / floatingWindow 命名空间（docs/framework-design.md §9.4/§12.3）。
 * - dialog：BAL 安全路径 —— overlay 可见时弹窗，否则通知回调；回调只提交请求，人工在 UI 确认。
 * - shell：独立 Shell 实现（root 或 adb），Promise 封装；分级 DENIED 时抛 ERR_PERMISSION_DENIED。
 * P2 全形态（overlay/通知降级、root_automator/Shizuku）；P0 面只给类型 + 桥调用骨架。
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.floatingWindow = exports.app = exports.device = exports.shell = exports.dialogs = void 0;
const runtime_1 = require("./runtime");
exports.dialogs = {
    /** 输入框（overlay 可见时弹窗，否则通知回调）；取消 → { value: null, confirmed: false }。 */
    async prompt(title, opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('dialogs', 'prompt', {
            title,
            placeholder: opts.placeholder ?? null,
            mode: opts.mode ?? 'auto',
        }, { ttl: opts.timeout ?? 30_000 }));
    },
    /** 选择框（同步选项列表；返回选中索引，取消 -1）。 */
    async choose(title, options, opts = {}) {
        const r = await runtime_1.runtimeBridge.invoke('dialogs', 'choose', {
            title,
            options: [...options],
            mode: opts.mode ?? 'auto',
        }, { ttl: opts.timeout ?? 30_000 });
        return r ?? -1;
    },
};
exports.shell = {
    /** 执行 shell 命令（root/adb）；分级 DENIED → ERR_PERMISSION_DENIED。 */
    async exec(cmd, opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('shell', 'exec', { cmd }, { ttl: opts.timeout ?? 30_000 }));
    },
    async shell(cmd, opts = {}) {
        return this.exec(cmd, opts);
    },
};
/** 设备信息（§12.3 auto.device）。 */
exports.device = {
    /** 品牌/型号/系统（P0 最小集；其余 P2）。 */
    async model(opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('device', 'model', null, { ttl: opts.timeout ?? 5_000 }));
    },
    async sdkInt(opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('device', 'sdkInt', null, { ttl: opts.timeout ?? 5_000 }));
    },
};
/** 应用开关（§9.3 app）。 */
exports.app = {
    async launch(packageName, opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('app', 'launch', { packageName }, { ttl: opts.timeout ?? 15_000 })) === true;
    },
    async currentPackage(opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('app', 'currentPackage', null, { ttl: opts.timeout ?? 10_000 }));
    },
};
/** 悬浮窗（§9.4；P1 全形态，P0 类型面）。 */
exports.floatingWindow = {
    /**
     * 创建悬浮窗宿主（overlay 权限门禁在装配层，被拒 → ERR_PERMISSION_DENIED）。
     * **形状参数原样过桥**（§12.3.3 已收口）：`{title,width,height}` 进 payload，
     * 缺省字段发 null（handler 的 `optStr`/`optLong` 把 null 当缺席，不套错默认）。
     */
    async create(opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('floatingWindow', 'create', {
            title: opts.title ?? null,
            width: opts.width ?? null,
            height: opts.height ?? null,
        }, { ttl: opts.timeout ?? 10_000 }));
    },
    /**
     * 关闭（宿主侧透传；未知/跨代句柄回 ERR_STALE_HANDLE，释放不了的窗口不说成已关）。
     * 句柄必须来自 [create] —— 不猜 id、不自造 generation。
     */
    async close(ref, opts = {}) {
        await runtime_1.runtimeBridge.invoke('floatingWindow', 'close', { ref }, { ttl: opts.timeout ?? 5_000 });
    },
};
