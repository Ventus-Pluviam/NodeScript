"use strict";
/// <reference types="node" />
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.SocketBootstrap = void 0;
exports.connectBootstrap = connectBootstrap;
const node_net_1 = __importDefault(require("node:net"));
const bridge_1 = require("./bridge");
const errors_1 = require("./errors");
const runtime_1 = require("./runtime");
const DEFAULT_MAX_FRAME = 64 * 1024 * 1024;
const FALLBACK_CONNECT_TIMEOUT = 10_000;
class SocketBootstrap {
    socketPath;
    maxFrameBytes;
    connectTimeout;
    socket = null;
    buffer = Buffer.alloc(0);
    queue = [];
    draining = false;
    closed = false;
    constructor(opts = {}) {
        const p = opts.socketPath ?? process.env.AUTOSCRIPT_HOST_SOCKET;
        if (!p)
            throw new Error('SocketBootstrap 需要 socketPath 或 AUTOSCRIPT_HOST_SOCKET');
        this.socketPath = p;
        this.maxFrameBytes = opts.maxFrameBytes ?? DEFAULT_MAX_FRAME;
        this.connectTimeout = opts.connectTimeout ?? FALLBACK_CONNECT_TIMEOUT;
    }
    onSocketError = null;
    emitSocketError(e) {
        // 桥断链：宿主依赖方（看门狗/连接熔断）监听；默认无监听不抛。
        if (this.onSocketError)
            this.onSocketError(e);
    }
    get connected() {
        return this.socket !== null && !this.closed;
    }
    /** 建立连接（幂等；后续重连由宿主层决定，不自动重试 —— 对齐桥连接熔断）。 */
    connect() {
        if (this.closed)
            return Promise.reject(new Error('SocketBootstrap 已关闭'));
        if (this.socket)
            return Promise.resolve();
        return new Promise((resolve, reject) => {
            const sock = node_net_1.default.connect(this.socketPath);
            const timer = setTimeout(() => {
                reject(new errors_1.AutojsError({ code: 'ERR_ENGINE_STOPPED', detail: `socket 连接超时: ${this.socketPath}` }));
                sock.destroy();
            }, this.connectTimeout);
            sock.on('connect', () => {
                clearTimeout(timer);
                this.socket = sock;
                resolve();
            });
            sock.on('data', (chunk) => this.onData(chunk));
            sock.on('error', (e) => {
                clearTimeout(timer);
                this.close();
                this.emitSocketError(e);
            });
            sock.on('close', () => {
                clearTimeout(timer);
                this.close();
            });
        });
    }
    /**
     * [InvokeHandler]：把请求编码成信封入队。返回 undefined = 已投递、等 [handleResponse]。
     * 未连接时同步抛错（对齐 invoke 的 ERR_ENGINE_STOPPED 快速拒绝）。
     */
    handler = (ns, method, payload, reqId, ttl) => {
        this.checkConnected();
        this.enqueue(bridge_1.BridgeEnvelope.encodeRequest({ id: reqId, ns, m: method, payload, ttl, side: null }));
        return undefined;
    };
    checkConnected() {
        if (!this.socket || this.closed) {
            throw new errors_1.AutojsError({ code: 'ERR_ENGINE_STOPPED', detail: '桥 socket 未连接' });
        }
    }
    /** 安装到单例桥（重复 install 由 RuntimeBridge 拒绝）。 */
    install() {
        runtime_1.runtimeBridge.install(this.handler);
    }
    enqueue(req) {
        this.queue.push(req);
        if (!this.draining)
            this.drain();
    }
    /** 队列续写：只在一个 drain 循环里动队列，socket 写满即等 drain 再回。 */
    drain() {
        if (this.draining)
            return;
        const sock = this.socket;
        if (!sock || this.closed)
            return;
        this.draining = true;
        const frame = Buffer.concat([Buffer.from(JSON.stringify(this.queue[0]), 'utf8'), Buffer.from([0x0a])]);
        if (!sock.write(frame)) {
            sock.once('drain', () => {
                this.draining = false;
                this.drain();
            });
            return;
        }
        this.queue.shift();
        if (this.queue.length > 0) {
            process.nextTick(() => this.drain());
        }
        else {
            this.draining = false;
        }
    }
    onData(chunk) {
        this.buffer = this.buffer.length === 0 ? chunk : Buffer.concat([this.buffer, chunk]);
        if (this.buffer.length > this.maxFrameBytes) {
            this.close();
            this.emitSocketError(new Error(`桥帧超过上限 ${this.maxFrameBytes} 字节`));
            return;
        }
        let nl;
        while ((nl = this.buffer.indexOf(0x0a)) !== -1) {
            const line = this.buffer.subarray(0, nl);
            this.buffer = this.buffer.subarray(nl + 1);
            if (line.length === 0)
                continue;
            let frame;
            try {
                frame = JSON.parse(line.toString('utf8'));
            }
            catch {
                this.emitSocketError(new Error('桥响应帧非法 JSON'));
                continue;
            }
            if (frame && (frame.t === 'ok' || frame.t === 'err')) {
                runtime_1.runtimeBridge.handleResponse(frame);
            }
            // 其他 t 值（事件帧）由事件订阅层处理（P1）；本层忽略。
        }
    }
    close() {
        if (this.closed)
            return;
        this.closed = true;
        if (this.socket) {
            const s = this.socket;
            this.socket = null;
            s.end(() => s.destroy());
        }
    }
}
exports.SocketBootstrap = SocketBootstrap;
/** 便捷入口：按 opts 创建 + 连接 + 安装。 */
async function connectBootstrap(opts = {}) {
    const b = new SocketBootstrap(opts);
    await b.connect();
    b.install();
    return b;
}
