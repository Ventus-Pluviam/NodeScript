/**
 * 无障碍选择器（docs/framework-design.md §9.1 / §12.3 a11y 选择器）：
 * Promise + 超时；findOne 无匹配抛 NotFoundError。
 * 类型面对应 :domain:automation。P0 实现走 RuntimeBridge.invoke 到 :main Router。
 */
/** 选择器条件（与 Kotlin UiSelector 语义对齐：全部条件 AND）。 */
export interface UiSelector {
    text?(v: string): this;
    desc?(v: string): this;
    id?(v: string): this;
    className?(v: string): this;
    packageName?(v: string): this;
    clickable?(v: boolean): this;
    time?(ms: number): this;
    findOne(opts?: FindOneOptions): Promise<UiObject>;
    findOneOrNull(opts?: FindOneOptions): Promise<UiObject | null>;
    findAll(opts?: FindAllOptions): Promise<UiObject[]>;
}
/** 每次查找的超时/频率；失败抛 NotFoundError（对齐 Pro v9）。 */
export interface FindOneOptions {
    timeout?: number;
    interval?: number;
    signal?: AbortSignal;
}
/** findAll 的完整选项（max 返回上限）。 */
export interface FindAllOptions {
    timeout?: number;
    max?: number;
    signal?: AbortSignal;
}
/** 滚动方向（与 :domain ScrollDirection 对齐；小写 wire 名，Kotlin 侧大小写不敏感）。 */
export type ScrollDirectionInput = 'forward' | 'backward' | 'up' | 'down' | 'left' | 'right';
/** 控件句柄代理（§7.4 gen/id）：JS 侧持 HandleRef，操作携带 generation 校验。 */
export interface UiObject {
    readonly ref: {
        refId: number;
        generation: number;
    };
    click(opts?: {
        timeout?: number;
        signal?: AbortSignal;
    }): Promise<boolean>;
    longClick(opts?: {
        timeout?: number;
        signal?: AbortSignal;
    }): Promise<boolean>;
    /** 滚动（§9.1 scroll 走无障碍 Action；缺省向前；不可滚动容器回 false）。 */
    scroll(direction?: ScrollDirectionInput, opts?: {
        timeout?: number;
        signal?: AbortSignal;
    }): Promise<boolean>;
    /**
     * 复制节点文本到剪贴板（§9.1 copy 走无障碍 Action；text ?? desc，皆空记空串）。
     * 粘贴剪贴板到可编辑节点（不可编辑/空剪贴板回 false，不抛错）。
     */
    copy(opts?: {
        timeout?: number;
        signal?: AbortSignal;
    }): Promise<boolean>;
    paste(opts?: {
        timeout?: number;
        signal?: AbortSignal;
    }): Promise<boolean>;
    setText(text: string, opts?: {
        timeout?: number;
        signal?: AbortSignal;
    }): Promise<boolean>;
    get bounds(): Promise<{
        left: number;
        top: number;
        right: number;
        bottom: number;
    }>;
    get text(): Promise<string | null>;
    get desc(): Promise<string | null>;
    children(opts?: {
        timeout?: number;
    }): Promise<UiObject[]>;
    parent(opts?: {
        timeout?: number;
    }): Promise<UiObject | null>;
    dispose(): void;
}
/** 无障碍命名空间（auto.a11y）。 */
export declare const a11y: {
    /** 创建选择器：链式条件，findOne 失败抛 NotFoundError。 */
    selector(): UiSelector;
    /** 等待某条件出现（一定次数内触发则成功；§12.3 waitFor）。 */
    waitFor(sel: UiSelector, opts?: {
        timeout?: number;
        interval?: number;
    }): Promise<boolean>;
    /**
     * 事件流拉取（§9.1 节流拉取式，seq 游标；对偶 Kotlin `a11y.events`）。
     * 空增量回 `{first:sinceSeq,last:sinceSeq,events:[]}`——调用方以前进游标为准。
     */
    events(opts?: {
        sinceSeq?: number;
        batch?: number;
        timeout?: number;
    }): Promise<UiEventBatch>;
    /** 手势能力门（§9.1 canPerformGestures；false 时走能力中心引导，不发手势）。 */
    canPerformGestures(opts?: {
        timeout?: number;
    }): Promise<boolean>;
    /**
     * 手势派发（§9.1 dispatchGesture；对偶 Kotlin `a11y.gesture`）。
     * 关门回 false（不抛错）；非法手势（空笔画/负坐标/非正 duration）抛 ERR_INVALID_PARAM。
     */
    gesture(input: GestureInput, opts?: {
        timeout?: number;
        signal?: AbortSignal;
    }): Promise<boolean>;
};
/** 手势点（逻辑像素；非负）。 */
export interface GesturePointInput {
    readonly x: number;
    readonly y: number;
}
/** 手势笔画（一起点 + 持续时长；与 :domain GestureStroke 对齐）。 */
export interface GestureStrokeInput {
    readonly points: readonly GesturePointInput[];
    readonly startDelayMillis?: number;
    readonly durationMillis?: number;
}
/** 手势输入（至少一个笔画；与 :domain GestureInput 对齐）。 */
export interface GestureInput {
    readonly strokes: readonly GestureStrokeInput[];
}
/** 无障碍事件（对偶 :domain UiEvent：seq/type/node/payload）。 */
export interface UiEvent {
    readonly seq: number;
    readonly type: string;
    readonly node: {
        refId: number;
        generation: number;
    } | null;
    readonly payload: string | null;
}
/** 事件批次（对偶 :domain UiEventBatch：游标 first/last + 事件数组）。 */
export interface UiEventBatch {
    readonly first: number;
    readonly last: number;
    readonly events: UiEvent[];
}
/** 条件提取（module 层 JSON 序列化的最小形态；运行时实现负责还原）。 */
export declare function conditionsOf(sel: UiSelector): Record<string, unknown>;
