/**
 * 桥信封类型（docs/framework-design.md §7.1/§7.5）。
 * 与 :domain:bridge.BridgeRequest / BridgeResponse 对齐（JsonTransport 信封）：
 * 请求 {"t":"req","id":1,"ns":"a11y","m":"findOne","ttl":5000,"payload":<json>|null,"side":<long>|null}
 * 成功 {"t":"ok","id":1,"payload":<json>|null,"side":<long>|null}
 * 错误 {"t":"err","id":1,"code":"ERR_*","detail":<string>|null}
 */
/** 桥请求信封（与 Kotlin BridgeRequest 字段一一对应）。 */
export interface BridgeRequest {
    t: 'req';
    id: number;
    ns: string;
    m: string;
    ttl: number;
    /** JSON 编码的参数对象（可空 = 无参）。 */
    payload: string | null;
    /** 大二进制 side-channel 句柄（§7.4；P0 恒空）。 */
    side?: number | null;
}
/** 桥响应信封（成功/错误）。 */
export type BridgeResponse = {
    t: 'ok';
    id: number;
    payload: string | null;
    side?: number | null;
} | {
    t: 'err';
    id: number;
    code: string;
    detail?: string | null;
};
/**
 * 请求分发回调：模块实现方把 JSON payload 解析后交给对应能力实现。
 * - [reqId]：宿主投递时透传信封 id，用于「投递后由 [RuntimeBridgeImpl.handleResponse] 按 id 结算」的异步路径；
 * - [ttl]：本调用的 TTL（毫秒），宿主编码 BridgeRequest 信封时原样写入（§7.1 `ttl` 字段）。
 */
export interface InvokeHandler {
    (ns: string, method: string, payloadJson: string | null, reqId: number, ttl: number): Promise<unknown> | unknown;
}
/**
 * 信封编解码（与 :bridge:java JsonTransport 逐字段对齐）。
 * payload 是「JSON 文本」，本层不做 JSON 解析（由调用方/模块层诉求）。
 */
export declare const BridgeEnvelope: {
    readonly encodeRequest: (req: Omit<BridgeRequest, "t">) => BridgeRequest;
    readonly ok: (id: number, payload?: string | null) => BridgeResponse;
    readonly err: (id: number, code: string, detail?: string | null) => BridgeResponse;
};
