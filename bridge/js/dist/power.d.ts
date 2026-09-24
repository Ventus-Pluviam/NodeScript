/**
 * `power_manager` 命名空间（§8.7 保活与电源；Kotlin 对偶 `:app` 的 `PowerManagerNamespaceHandler`）。
 *
 * wire 形状与 handler 逐字对齐（`power.test.cjs` 的 mock 宿主复刻 handler 回包）：
 * - `acquire` 发 `{timeoutMillis}`（正整数，必填）→ 回 `{token}`（`script-` 前缀，
 *   **服务端分配** —— 脚本自带 token 会互撞/互释）；
 * - `release` 发 `{token}` → 回裸 boolean（false = 该 token 当时并未持有，如实不对账成功）；
 * - `status` 空参 → 回 `{held,holders}`（`held` = 门禁读的账本与系统双真值，
 *   `holders` = 账本 token 数；分歧时 held=false 而 holders>0，不折叠）。
 *
 * 诚实口径（与 `:domain` 无新契约，判据全在宿主侧）：
 * - 脚本锁**必须限时**：无 `timeoutMillis`/0/负数 → 宿主 `ERR_INVALID_PARAM`
 *   （无期限只属框架保活；脚本无期限 = 卡死的持有方让 CPU 永远不休眠）；
 * - 取不到锁 → 宿主 `ERR_SERVICE_DISABLED`（**未记账**：此时 `SCREEN_ON` 任务被门禁
 *   如实拒绝，而不是"以为有锁、跑在会休眠的 CPU 上"）；
 * - 到期自动释放由宿主 ticker 驱动（`status` 的 holders 会自己变小；`release` 一个
 *   已过期的 token 回 false —— 它已经不在账上了）。
 *
 * 范围：只做"CPU 不休眠"（PARTIAL_WAKE_LOCK 语义），不碰屏幕亮灭（那是用户/系统的事，
 * 门禁侧另有 `isInteractive` 判据）与前台服务起停（框架保活的生命周期，不归脚本调）。
 */
export declare const power: {
    /**
     * 持一把限时唤醒锁；回服务端分配的 token（用完记得 `release`，到期宿主也会自动收）。
     */
    acquire(timeoutMillis: number, opts?: {
        timeout?: number;
    }): Promise<string>;
    /**
     * 放自己那一份；false = 该 token 当时并未持有（重复放/陌生 token/已过期）。
     */
    release(token: string, opts?: {
        timeout?: number;
    }): Promise<boolean>;
    /** 锁现状：held（门禁判据）+ holders（账本席位数）。 */
    status(opts?: {
        timeout?: number;
    }): Promise<{
        held: boolean;
        holders: number;
    }>;
};
