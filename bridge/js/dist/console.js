"use strict";
/**
 * console 回传（docs/framework-design.md §7.3 tsf_data / §14 P0 最小桥）。
 *
 * 数据面语义：可丢包（丢包统计/背压，溢出时回调 JS 层 `queueError`）。
 * 因此 log 系列调用永不抛错给脚本——发送失败（未安装/TTL/宿主拒绝）一律吞掉，
 * 只经 `onQueueError` 通知（默认无监听不抛，对齐 SocketBootstrap.onSocketError）。
 *
 * 宿主侧：N-API addon 经 tsf_data 把行送到 :main 控制台；dev/CI socket 面走同一
 * `console.log` namespace（Kotlin Router 侧注册 console handler 落盘/EventBus）。
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.consoleSink = void 0;
const runtime_1 = require("./runtime");
const queueErrorListeners = new Set();
function emitQueueError(level, reason) {
    if (queueErrorListeners.size === 0)
        return;
    const e = { level, reason };
    for (const l of [...queueErrorListeners])
        l(e);
}
/** 参数序列化：JSON 可序列化原样走；其余（循环/函数/BigInt）降级为 String()，绝不抛。 */
function serializeArgs(args) {
    const parts = [];
    for (const a of args) {
        try {
            if (typeof a === 'string') {
                parts.push(a);
            }
            else {
                const j = JSON.stringify(a);
                parts.push(j === undefined ? String(a) : j);
            }
        }
        catch {
            parts.push(String(a));
        }
    }
    return parts.join(' ');
}
async function send(level, args) {
    const text = serializeArgs(args);
    try {
        // 数据面 fire-and-forget：短 TTL，失败即丢（不阻塞脚本事件循环）。
        await runtime_1.runtimeBridge.invoke('console', 'log', { level, text }, { ttl: 2_000 });
    }
    catch (e) {
        emitQueueError(level, e instanceof Error ? e.message : String(e));
    }
}
exports.consoleSink = {
    log(...args) { return send('log', args); },
    info(...args) { return send('info', args); },
    warn(...args) { return send('warn', args); },
    error(...args) { return send('error', args); },
    debug(...args) { return send('debug', args); },
    /** 背压/丢包回调（§7.3 queueError）。返回退订函数。 */
    onQueueError(listener) {
        queueErrorListeners.add(listener);
        return () => { queueErrorListeners.delete(listener); };
    },
};
