"use strict";
/**
 * 桥信封类型（docs/framework-design.md §7.1/§7.5）。
 * 与 :domain:bridge.BridgeRequest / BridgeResponse 对齐（JsonTransport 信封）：
 * 请求 {"t":"req","id":1,"ns":"a11y","m":"findOne","ttl":5000,"payload":<json>|null,"side":<long>|null}
 * 成功 {"t":"ok","id":1,"payload":<json>|null,"side":<long>|null}
 * 错误 {"t":"err","id":1,"code":"ERR_*","detail":<string>|null}
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.BridgeEnvelope = void 0;
/**
 * 信封编解码（与 :bridge:java JsonTransport 逐字段对齐）。
 * payload 是「JSON 文本」，本层不做 JSON 解析（由调用方/模块层诉求）。
 */
exports.BridgeEnvelope = {
    encodeRequest(req) {
        return { t: 'req', ...req };
    },
    ok(id, payload = null) {
        return { t: 'ok', id, payload };
    },
    err(id, code, detail) {
        return { t: 'err', id, code, detail };
    },
};
