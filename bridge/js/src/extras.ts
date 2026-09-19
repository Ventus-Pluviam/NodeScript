/**
 * dialogs / shell / device / app / floatingWindow 命名空间（docs/framework-design.md §9.4/§12.3）。
 * - dialog：BAL 安全路径 —— overlay 可见时弹窗，否则通知回调；回调只提交请求，人工在 UI 确认。
 * - shell：独立 Shell 实现（root 或 adb），Promise 封装；分级 DENIED 时抛 ERR_PERMISSION_DENIED。
 * P2 全形态（overlay/通知降级、root_automator/Shizuku）；P0 面只给类型 + 桥调用骨架。
 */

import { runtimeBridge } from './runtime'

/** 对话框返回（对 §12.3 prompt 的桥回包形态）。 */
export interface DialogResult {
  /** 用户输入/选择；取消返回 null。 */
  value: string | null
  confirmed: boolean
}

/** 对话框模式（§9.4 BAL：overlay 可见时弹窗；否则通知降级回调）。 */
export type DialogMode = 'auto' | 'overlay' | 'notification'

export const dialogs = {
  /** 输入框（overlay 可见时弹窗，否则通知回调）；取消 → { value: null, confirmed: false }。 */
  async prompt(title: string, opts: { placeholder?: string; mode?: DialogMode; timeout?: number } = {}): Promise<DialogResult> {
    return (await runtimeBridge.invoke('dialogs', 'prompt', {
      title,
      placeholder: opts.placeholder ?? null,
      mode: opts.mode ?? 'auto',
    }, { ttl: opts.timeout ?? 30_000 })) as DialogResult
  },

  /** 选择框（同步选项列表；返回选中索引，取消 -1）。 */
  async choose(title: string, options: readonly string[], opts: { mode?: DialogMode; timeout?: number } = {}): Promise<number> {
    const r = await runtimeBridge.invoke('dialogs', 'choose', {
      title,
      options: [...options],
      mode: opts.mode ?? 'auto',
    }, { ttl: opts.timeout ?? 30_000 })
    return (r as number) ?? -1
  },
}

/** shell 执行结果（对齐 Pro v9 exec 形态）。 */
export interface ShellResult {
  readonly code: number
  readonly stdout: string | null
  readonly stderr: string | null
}

export const shell = {
  /** 执行 shell 命令（root/adb）；分级 DENIED → ERR_PERMISSION_DENIED。 */
  async exec(cmd: string, opts: { timeout?: number } = {}): Promise<ShellResult> {
    return (await runtimeBridge.invoke('shell', 'exec', { cmd }, { ttl: opts.timeout ?? 30_000 })) as ShellResult
  },

  async shell(cmd: string, opts: { timeout?: number } = {}): Promise<ShellResult> {
    return this.exec(cmd, opts)
  },
}

/** 设备信息（§12.3 auto.device）。 */
export const device = {
  /** 品牌/型号/系统（P0 最小集；其余 P2）。 */
  async model(opts: { timeout?: number } = {}): Promise<string> {
    return (await runtimeBridge.invoke('device', 'model', null, { ttl: opts.timeout ?? 5_000 })) as string
  },
  async sdkInt(opts: { timeout?: number } = {}): Promise<number> {
    return (await runtimeBridge.invoke('device', 'sdkInt', null, { ttl: opts.timeout ?? 5_000 })) as number
  },
}

/** 应用开关（§9.3 app）。 */
export const app = {
  async launch(packageName: string, opts: { timeout?: number } = {}): Promise<boolean> {
    return (await runtimeBridge.invoke('app', 'launch', { packageName }, { ttl: opts.timeout ?? 15_000 })) === true
  },
  async currentPackage(opts: { timeout?: number } = {}): Promise<string | null> {
    return (await runtimeBridge.invoke('app', 'currentPackage', null, { ttl: opts.timeout ?? 10_000 })) as string | null
  },
}

/** 悬浮窗（§9.4；P1 全形态，P0 类型面）。 */
export const floatingWindow = {
  /** 创建悬浮窗宿主（overlay 权限门禁；P1 实现）。 */
  async create(_opts: { title?: string; width?: number; height?: number; timeout?: number } = {}): Promise<unknown> {
    return runtimeBridge.invoke('floatingWindow', 'create', null, { ttl: 10_000 })
  },
}