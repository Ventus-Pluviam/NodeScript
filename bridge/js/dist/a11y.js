"use strict";
/**
 * 无障碍选择器（docs/framework-design.md §9.1 / §12.3 a11y 选择器）：
 * Promise + 超时；findOne 无匹配抛 NotFoundError。
 * 类型面对应 :domain:automation。P0 实现走 RuntimeBridge.invoke 到 :main Router。
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.a11y = void 0;
exports.conditionsOf = conditionsOf;
const runtime_1 = require("./runtime");
const errors_1 = require("./errors");
/** 无障碍命名空间（auto.a11y）。 */
exports.a11y = {
    /** 创建选择器：链式条件，findOne 失败抛 NotFoundError。 */
    selector() {
        return new UiSelectorBuilder();
    },
    /** 等待某条件出现（一定次数内触发则成功；§12.3 waitFor）。载荷键见下：`conditions`。 */
    async waitFor(sel, opts = {}) {
        // 载荷键是 conditions（与 findOne 同构）：Kotlin A11yNamespaceHandler 的
        // waitFor 复用同一条选择器解析路径，只认这一个键；发 selector 会被拒为
        // ERR_INVALID_PARAM（白名单外字段，诚实失败，不静默变全量匹配）。
        const result = await runtime_1.runtimeBridge.invoke('a11y', 'waitFor', {
            conditions: conditionsOf(sel),
            timeout: opts.timeout,
            interval: opts.interval,
        });
        return result === true;
    },
    /**
     * 事件流拉取（§9.1 节流拉取式，seq 游标；对偶 Kotlin `a11y.events`）。
     * 空增量回 `{first:sinceSeq,last:sinceSeq,events:[]}`——调用方以前进游标为准。
     */
    async events(opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('a11y', 'events', {
            sinceSeq: opts.sinceSeq ?? 0,
            batch: opts.batch ?? 32,
        }, { ttl: opts.timeout ?? 10_000 }));
    },
    /** 手势能力门（§9.1 canPerformGestures；false 时走能力中心引导，不发手势）。 */
    async canPerformGestures(opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('a11y', 'canPerformGestures', null, {
            ttl: opts.timeout ?? 5_000,
        })) === true;
    },
    /**
     * 手势派发（§9.1 dispatchGesture；对偶 Kotlin `a11y.gesture`）。
     * 关门回 false（不抛错）；非法手势（空笔画/负坐标/非正 duration）抛 ERR_INVALID_PARAM。
     */
    async gesture(input, opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('a11y', 'gesture', input, {
            ttl: opts.timeout ?? 10_000,
            signal: opts.signal,
        })) === true;
    },
};
/** 条件提取（module 层 JSON 序列化的最小形态；运行时实现负责还原）。 */
function conditionsOf(sel) {
    // UiSelector 实现自持条件表；此函数作为兼容入口，运行时走 invoke 时无需调用方提取。
    return sel.__conditions__;
}
class UiSelectorBuilder {
    __conditions__ = {};
    ttl = 5_000;
    interval = 300;
    text(v) { this.__conditions__.text = v; return this; }
    desc(v) { this.__conditions__.desc = v; return this; }
    id(v) { this.__conditions__.id = v; return this; }
    className(v) { this.__conditions__.className = v; return this; }
    packageName(v) { this.__conditions__.packageName = v; return this; }
    clickable(v) { this.__conditions__.clickable = v; return this; }
    time(ms) { this.ttl = ms; return this; }
    async findOne(opts = {}) {
        const found = await this.findOneOrNull(opts);
        if (!found)
            throw new errors_1.NotFoundError('选择器无匹配');
        return found;
    }
    async findOneOrNull(opts = {}) {
        let result;
        try {
            result = await runtime_1.runtimeBridge.invoke('a11y', 'findOne', {
                conditions: this.__conditions__,
                timeout: opts.timeout ?? this.ttl,
                interval: opts.interval ?? this.interval,
            }, { ttl: opts.timeout ?? this.ttl, signal: opts.signal });
        }
        catch (e) {
            // 无匹配是正常控制流（Kotlin 回 ERR_NOT_FOUND），不是异常：折叠为 null。
            // 其他错误（TTL/ENGINE_STOPPED/INVALID_PARAM）如实上抛。
            if (e instanceof errors_1.AutojsError && e.code === "ERR_NOT_FOUND" /* ErrCode.NOT_FOUND */)
                return null;
            throw e;
        }
        const node = result;
        return node == null ? null : wrapUiObject(node.ref);
    }
    async findAll(opts = {}) {
        const result = await runtime_1.runtimeBridge.invoke('a11y', 'findAll', {
            conditions: this.__conditions__,
            timeout: opts.timeout ?? this.ttl,
            max: opts.max,
        }, { ttl: opts.timeout ?? this.ttl, signal: opts.signal });
        const nodes = (result ?? []);
        return nodes.map((n) => wrapUiObject(n.ref));
    }
}
/** 句柄代理：Kotlin 回包是 {ref} 纯数据，动作经 invoke 回桥（携带 generation 校验）。 */
function wrapUiObject(ref) {
    const call = (method, params, ttl = 10_000) => runtime_1.runtimeBridge.invoke('a11y', method, { ref, ...params }, { ttl });
    return {
        ref,
        click: async () => (await call('click', null)) === true,
        longClick: async () => (await call('longClick', null)) === true,
        scroll: async (direction = 'forward', opts = {}) => (await runtime_1.runtimeBridge.invoke('a11y', 'scroll', { ref, direction }, {
            ttl: opts.timeout ?? 10_000,
            signal: opts.signal,
        })) === true,
        setText: async (text) => (await call('setText', { text })) === true,
        copy: async () => (await call('copy', null)) === true,
        paste: async () => (await call('paste', null)) === true,
        get bounds() {
            return call('bounds', null);
        },
        get text() {
            return call('text', null);
        },
        get desc() {
            return call('desc', null);
        },
        children: async () => {
            const kids = (await call('children', null));
            return (kids ?? []).map((k) => wrapUiObject(k.ref));
        },
        parent: async () => {
            const p = (await call('parent', null));
            return p == null ? null : wrapUiObject(p.ref);
        },
        dispose: () => {
            // fire-and-forget（幂等释放，不阻塞脚本；失败走 console queueError 面，不抛）。
            runtime_1.runtimeBridge.invoke('a11y', 'dispose', { ref }, { ttl: 2_000 }).catch(() => undefined);
        },
    };
}
