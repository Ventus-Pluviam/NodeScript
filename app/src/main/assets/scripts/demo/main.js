/**
 * 内置示例脚本（§9.6 `assets/scripts/<projectId>/`）。
 *
 * 它随 APK 分发，装配期由 ScriptDeployRecovery **只补缺不覆盖**地落到
 * `files/scripts/demo/` —— 在任务中心登记一条任务（`projectId=demo`、
 * `scriptPath=main.js`）即可在真机上把整条链跑一遍。
 *
 * 为什么留这个示例：APK 不带任何脚本时，登记出来的任务只会以「脚本文件不存在」
 * 告终，而引擎、facade、桥、平台面四层到底通没通，从界面上看不出来。
 * 本文件只演示**读**（不写文件、不改系统状态），跑坏了也不会留下痕迹。
 *
 * **输出为什么写 `await auto.console.*`**：数据面（桥上 `console` namespace，§7.3 →
 * ConsoleCollector）是 fire-and-forget，**不 await 就可能"脚本已退出、行还在队列里"**
 * —— 而本脚本结尾立刻 `process.exit(0)`。`await` 只是"投递完成"，不是"用户看完了"。
 * 代价是每条一个来回，示例脚本不在乎。
 *
 * 裸 `console.log` **同样能出到控制台**（2026-10-15 起 kBootstrap 装了全局 console 包装，
 * 见 `installConsoleBridge`）—— 它走的是同一条数据面，只是**返回 undefined、await 不到**，
 * 在"打完就 exit"的脚本里可能丢尾行。本示例因此保留 `auto.console.*`：它给得出那个
 * Promise。（`console.dir`/`table` 之类**也接上了同一条桥**，一样看得见；仍看不见的只有
 * 流对象直写 `process.stdout.write` —— 那条通道引擎**读即弃**，且它的入口不在 console
 * 对象上，收编要给流 API 造一份假的背压契约，本仓不做。）
 *
 * 约定（§12.1）：CJS 具名解构 `const { auto } = require('auto')`；脚本自行
 * `process.exit` 结束进程（引擎按退出码结算，别让进程挂着）。
 */
const { auto } = require('auto');
const { greet } = require('./lib/greet');   // 子目录一起随包，验证 assets 递归部署

async function main() {
  await auto.console.log('[demo] ' + greet('AutoScript'));
  await auto.console.log('[demo] Node ' + process.version + ' 已启动（引擎进程活着）');
  await auto.console.log('[demo] 跨进程桥 installed=' + auto.bridge.installed);

  // 下面两行走完整条链：脚本 → nodeN 进程 → 桥 → Kotlin Router → 平台 handler → 回包。
  // 任一层断了，这里就会抛（错误码原文见 catch），而不是"打印个假值糊过去"。
  const model = await auto.device.model();
  const sdk = await auto.device.sdkInt();
  await auto.console.log('[demo] 设备 ' + model + ' / API ' + sdk);
  await auto.console.log('[demo] 四层都通了（引擎 → facade → 桥 → 平台面）');
}

main().then(
  () => process.exit(0),
  (e) => {
    const msg = '[demo] 失败：' + (e && e.message ? e.message : String(e));
    // 失败路径两条都试，因为**桥断了 `auto.console` 也发不出去**（fire-and-forget 吞掉，
    // 这正是失败原因之一）。裸 stderr 写用同步 API：`process.exit` 不 flush 异步管道写，
    // 本例在开发机上实测被截断过（成功路径那 5 行只出来 3 行）。
    // 真机上的可见信号仍是退出码（≠0 → 任务中心记 CRASHED）；stderr 另被引擎收进
    // 有界尾摘要（`RunRecord.crashSummary`，§8.3），落盘可查、但控制台屏不显示。
    try {
      auto.console.error(msg);
    } catch (_) {
      /* 桥不在，本就发不出去 */
    }
    require('fs').writeSync(2, msg + '\n');
    process.exit(1);
  },
);
