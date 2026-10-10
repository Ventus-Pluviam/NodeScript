'use strict'
/**
 * child_process 拦截 shim（docs §10.3 T0 末行 / §10.12 末行「零 spawn 不变量漂移」）。
 *
 * 装法：宿主把它落盘后经 `NODE_OPTIONS=--require=<本文件>` 注入 npm 安装会话进程
 * （见 `NpmSpawnGate` / `HostNodeExecutor`）。**装在这里而不是随 npm 素材树**：
 * 素材树受 `npm-manifest.json` 逐件摘要对账，多一个文件就让「部署校验失败」每启动必红
 * —— 它是宿主自己的件，住 `files/.autojs/`（宿主内务目录，与 journal/lock.sig 同处）。
 *
 * 拦的是什么：`--ignore-scripts` 只挡 lifecycle 脚本（版本无关，见 §10.12），挡不住
 * **非脚本** spawn —— npm 自己新增一条 `execFile` 路径、或某包在 `bin/` 里直接
 * `require('child_process')`。这类漂移在本平台上的表现是**静默失败**（无 child_process
 * → 装到一半行为诡异），本 shim 把它变成**响亮错误**（`ERR_NPM_SPAWN_BLOCKED`）。
 *
 * **边界（如实写死，别当它是沙箱）**：本文件是**不变量守卫**，不是安全边界 ——
 * 已获批的脚本仍可绕过（`delete require.cache[require.resolve('child_process')]`
 * 后重新 require 会拿到未打补丁的原模块）。真正的对抗面是 §10.5-2 人机分离
 * （脚本自发起路径；控制台直接跑、不过这道门，2026-10-11）
 * 与 T1 会话的最小 CapabilityMask（§10.5-4），本 shim 只负责「npm 与依赖没在背地里
 * spawn 而无人知晓」这一条可证伪的不变量。
 *
 * 为什么连 `execSync` 也拦：拦一半比不拦更糟 —— npm 的 reify 走的是异步路径，
 * 真出问题时人看到的是「装完了但东西不对」，而同步路径上的拦截会当场炸；两条一起拦
 * 才让「有没有 spawn」成为可判定的问题。§10.12 的 CI 金标准（child_process 全替换为
 * throw 后跑全命令矩阵）要的就是这个形态。
 */

/** 打在 stderr 上的可识别前缀 —— 宿主把它写进失败详情（redirectErrorStream 后一路带回）。 */
const MARKER = '[npm-spawn-gate]'

/** 与 :domain `ErrorCode.ERR_NPM_SPAWN_BLOCKED` / JS `ErrCode.NPM_SPAWN_BLOCKED` 逐字同码。 */
const CODE_BLOCKED = 'ERR_NPM_SPAWN_BLOCKED'
const CODE_DETACHED = 'ERR_PERMISSION_DENIED'
const CODE_FORK = 'ERR_NOT_IMPLEMENTED'

/**
 * 造一个带 `.code` 的错误。**必须带 `.code`**：Node 的错误折叠面（`errors.ts` 的
 * `errFromPayload` 同款判据）认的就是这个字段，普通 Error 会被折成 `ERR_INVALID_PARAM`
 * —— 那等于把「被门禁拦了」说成「参数错了」（§1 诚实原则）。
 */
function deny(code, detail) {
  // stderr 是**唯一**能到宿主的通道：npm 的报错面不走 stdout，而宿主 redirectErrorStream
  // 之后两条合一。写失败（管道已关）不改变拒绝语义，故吞掉。
  try {
    process.stderr.write(MARKER + ' ' + code + ': ' + detail + '\n')
  } catch (_) {
    /* 管道已关：拒绝照旧生效，只是这条播报丢了 */
  }
  const err = new Error(detail)
  err.code = code
  return err
}

/** 选项对象是**位置不定**的第一个非数组/非 Buffer 对象实参（`spawn(cmd, args, opts)` 与 `spawn(cmd, opts)` 都合法）。 */
function findOptions(args) {
  for (const a of args) {
    if (a && typeof a === 'object' && !Array.isArray(a) && !Buffer.isBuffer(a)) return a
  }
  return null
}

const DENIED = ['spawn', 'spawnSync', 'exec', 'execSync', 'execFile', 'execFileSync', 'fork']

function makeDenier(name) {
  return function deniedChildProcess() {
    const opts = findOptions(arguments)
    // §10.3 T1 明写「shim 直接拒绝 detached:true/setsid」：脱离进程组的子进程回收不到
    // （TERM→SIGKILL 的杀树与 /proc 二次收割都以同 pgrp 为前提），所以它不是「暂不支持」
    // 而是**权限面拒绝**，话术要指向可操作路径。
    if (opts && opts.detached === true) {
      throw deny(
        CODE_DETACHED,
        'child_process.' + name + ' 的 detached:true 被拒（§10.3 T1）：脱离进程组的子进程回收不到。' +
          '去掉 detached，或把要跑的东西做成项目里的一个 npm script（经控制台 npm run 走宿主）。',
      )
    }
    if (name === 'fork') {
      throw deny(
        CODE_FORK,
        'child_process.fork 本平台不支持（§10.3「明确不可行」）：并发请用 auto.engines 引擎池。',
      )
    }
    throw deny(
      CODE_BLOCKED,
      '非批准 spawn 被拦截：child_process.' + name + '（§10.3 T0 零 spawn 主路径）。' +
        '安装会话内不允许起子进程；要跑项目脚本请去控制台敲 npm run。',
    )
  }
}

const cp = require('child_process')
for (const name of DENIED) {
  cp[name] = makeDenier(name)
}
