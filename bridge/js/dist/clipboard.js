"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.clipboard = void 0;
const runtime_1 = require("./runtime");
/**
 * `clipboard` 命名空间（§12.2 系统剪贴板面；Kotlin 对偶 `ClipboardNamespaceHandler`）。
 *
 * wire 形状与 handler 逐字段对齐（`clipboard.test.cjs` 的 mock 宿主复刻）：
 * `getText` 空参 → 裸 `string | null`、`setText` 发 `{text}` → 裸 boolean 真值。
 * 未约定的别名（`get`/`set`/`clear`/`hasText`）两侧都不提供，宿主如实
 * `ERR_NOT_IMPLEMENTED`，本层也不发。
 *
 * 诚实口径（与 `:domain` `Clipboard` KDoc 同步，别各自漂移）：
 * - 读侧 null 是常态答案（空剪贴板 / 后台受限时系统的 null），不是"查不到" ——
 *   与 `settings.getString` 缺键回 null 同形（值面 `String?`，`null` 不与合法值撞，
 *   不需要 `datastore.get` 那种 `{found,value}` 信封）；空串是真值（a11y `copy`
 *   空节点记空串），一律不拿来冒充缺失；
 * - 写侧无门禁（`setPrimaryClip` 后台可调，无门可禁）—— 空串原样存，不拒；
 *   缺参/非串 → 宿主 `ERR_INVALID_PARAM`，且一次写都没发出去。
 *
 * 范围：只做纯文本（HTML/Uri 流是富剪贴面，等有真实消费方再开；不带 `clear`/`hasText`）。
 * 与 a11y `copy`/`paste` 的分工：那是"节点文本 ⇄ 剪贴板"的中转动作（要服务在场），
 * 这里是系统剪贴板的直读写（无障碍服务不在场也能用）。
 */
exports.clipboard = {
    /** 读剪贴板文本；null = 无内容（空剪贴板 / 后台受限时系统的 null 答案）。 */
    async getText(opts = {}) {
        return (await runtime_1.runtimeBridge.invoke('clipboard', 'getText', null, { ttl: opts.timeout ?? 5_000 }));
    },
    /** 写剪贴板文本（空串是合法内容，原样存）。 */
    async setText(text, opts = {}) {
        await runtime_1.runtimeBridge.invoke('clipboard', 'setText', { text }, { ttl: opts.timeout ?? 5_000 });
    },
};
