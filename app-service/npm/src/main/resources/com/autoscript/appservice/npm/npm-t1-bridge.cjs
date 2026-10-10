'use strict'
/**
 * T1 spawn 桥 —— **npm 会话侧**的 `child_process` shim（docs §10.3 T1）。
 *
 * 与同目录的 `npm-spawn-gate.cjs` 是**两件东西、两个用途**，别混：
 * - 门禁（T0，批 80）：安装会话里**一律拒** spawn —— 装包不需要起进程，起了一定是漂移；
 * - 本件（T1）：`npm run` / `npm exec` 的**批准后脚本**本来就要起进程，而设备上没有
 *   可用的 `child_process`（Node-on-Android 不带它）。故本件把 spawn **接到宿主**：
 *   经 unix socket 把 `{cmd,args,opts}` 报给 `:main`，由宿主决定跑不跑、并在宿主侧
 *   真起进程，stdio 经同一条 socket 以**假管道**回填。
 *
 * 为什么必须绕一圈而不是让 npm 自己 `sh -c`：本平台的承诺是「批准后脚本以**最小能力**
 * 运行、可被宿主按进程树回收、输出可审计」（§10.5-4）。让 npm 自己在 app 进程里起 `sh`
 * 等于把这三条全丢了 —— 那是"能跑"，不是"受控地跑"。
 *
 * **谁被替换**：七个入口里只放行**异步**的三个（`spawn`/`exec`/`execFile`）——
 * npm 自己的 `@npmcli/promise-spawn` 走的就是异步 `spawn`（实测：`npm run` 只发一条
 * `spawn("sh", ["-c", <body>], opts)`）。同步三兄弟（`spawnSync`/`execSync`/`execFileSync`）
 * 要**阻塞事件循环**等一次 socket 往返，本版做不到，如实 `ERR_NOT_IMPLEMENTED` 而不是
 * 假装支持（§1 诚实原则）；`fork` 是 §10.3「明确不可行」那条，照旧拒。
 *
 * **本件不是安全边界**（与门禁 shim 同一条自我定位）：已获批的脚本可以
 * `delete require.cache[require.resolve('child_process')]` 绕过。真判据在宿主侧 ——
 * 宿主只跑它认得的那条命令，shim 只是个送信的。
 */

const net = require('net')
const { EventEmitter } = require('events')
const { Readable, Writable } = require('stream')

/** 打在 stderr 上的可识别前缀（与 `NpmT1Bridge.MARKER` 逐字同值）。 */
const MARKER = '[npm-t1-bridge]'

/** 宿主经 env 告诉本 shim 的两件事：socket 路径与本次会话的凭据。 */
const ENV_SOCKET = 'AUTOSCRIPT_T1_SOCKET'
const ENV_TOKEN = 'AUTOSCRIPT_T1_TOKEN'

const VERSION = 1

const CODE_DENIED = 'ERR_PERMISSION_DENIED'
const CODE_NOT_IMPLEMENTED = 'ERR_NOT_IMPLEMENTED'
const CODE_BLOCKED = 'ERR_NPM_SPAWN_BLOCKED'

/** 与门禁 shim 同款：带 `.code` 的 Error（Node 的错误折叠面认这个字段，普通 Error 会被折成 ERR_INVALID_PARAM）。 */
function deny(code, detail) {
  try {
    process.stderr.write(MARKER + ' ' + code + ': ' + detail + '\n')
  } catch (_) {
    /* 管道已关：拒绝照旧生效，只是这条播报丢了 */
  }
  const err = new Error(detail)
  err.code = code
  return err
}

/**
 * `exec` 用的 shell。Node 自己的缺省是写死的 `/bin/sh`，但**Android 上没有 `/bin/sh`**
 * （shell 在 `/system/bin/sh`，且 app 进程的 PATH 里通常就有 `sh`）。
 * 故先看 `/bin/sh` 在不在，不在就交给 PATH 解析 —— 两台机器上语义相同（都是 POSIX sh），
 * 而写死那一条在设备上会让每一次 `exec` 都 ENOENT。
 *
 * 刻意**不读** `process.env.SHELL`：那是用户的交互 shell（bash/zsh），拿它跑脚本会让
 * 同一段脚本在不同机器上语法不同 —— 那正是 sh 存在的意义所在。
 */
function defaultShell() {
  try {
    return require('fs').existsSync('/bin/sh') ? '/bin/sh' : 'sh'
  } catch (_) {
    return 'sh'
  }
}

/** 选项对象是位置不定的第一个非数组/非 Buffer 对象实参（`spawn(cmd,args,opts)` 与 `spawn(cmd,opts)` 都合法）。 */
function findOptions(args) {
  for (const a of args) {
    if (a && typeof a === 'object' && !Array.isArray(a) && !Buffer.isBuffer(a) && !(a instanceof ArrayBuffer)) return a
  }
  return null
}

/** 尾随回调（`exec(cmd, opts, cb)` 的 cb 是最后一个函数实参）。 */
function findCallback(args) {
  for (let i = args.length - 1; i >= 0; i--) {
    if (typeof args[i] === 'function') return args[i]
  }
  return null
}

// ── 连接（进程内单例：一条 npm 会话对宿主一条连接）────────────────────────────

let conn = null
let buf = ''
let nextId = 1
/** 已发出 spawn 帧、等宿主回话的子进程：id → BridgedChild。 */
const waiting = new Map()
/** 连接就绪前挂起的发送（spawn 是同步返回的，连接是异步的）。 */
const pendingSends = []
let ready = false
let failure = null

function write(obj) {
  try {
    conn.write(JSON.stringify(obj) + '\n')
    return true
  } catch (_) {
    /* 写失败：这条子进程会在 close 时收到 error，不在这里抛（spawn 已经返回了） */
    return false
  }
}

/**
 * 发一帧给宿主。**握手帧不走这里**（见 [write] 与 'connect' 那处的注释）——
 * 这条路上的帧全都要等 `helloAck`：连接还没认证就发业务帧，宿主会当它是垃圾丢掉。
 */
function send(obj) {
  if (!conn || !ready) {
    pendingSends.push(obj)
    return
  }
  write(obj)
}

function flushPending() {
  const q = pendingSends.splice(0, pendingSends.length)
  for (const obj of q) send(obj)
}

function failAll(err) {
  failure = err
  const q = pendingSends.splice(0, pendingSends.length)
  for (const obj of q) {
    const child = waiting.get(obj.id)
    if (child) child._fail(err)
  }
  for (const child of waiting.values()) child._fail(err)
  waiting.clear()
}

function onData(chunk) {
  buf += chunk.toString('utf8')
  let nl
  while ((nl = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, nl)
    buf = buf.slice(nl + 1)
    if (!line.trim()) continue
    let msg
    try {
      msg = JSON.parse(line)
    } catch (_) {
      continue // 半截/坏帧：跳过，不让一条坏行打死整条会话
    }
    onFrame(msg)
  }
}

function onFrame(msg) {
  if (msg.t === 'helloAck') {
    ready = true
    flushPending()
    return
  }
  if (msg.t === 'helloErr') {
    failAll(deny(msg.code || CODE_DENIED, '宿主拒绝本次 T1 会话：' + (msg.detail || '')))
    return
  }
  const child = waiting.get(msg.id)
  if (!child) return
  if (msg.t === 'out') {
    child._push(msg.fd, msg.data)
  } else if (msg.t === 'exit') {
    waiting.delete(msg.id)
    refWhileBusy()
    child._exit(msg.code, msg.signal)
  } else if (msg.t === 'err') {
    waiting.delete(msg.id)
    refWhileBusy()
    child._fail(deny(msg.code || CODE_BLOCKED, msg.detail || ''))
  }
}

/**
 * 会话 socket 的 ref/unref —— **这条不是优化，是正确性**（2026-10-10 批 91 实测）。
 *
 * 一条常驻的 socket 是一个**活跃 handle**，它会把 Node 的事件循环吊住：npm 跑完脚本、
 * 打印完摘要、该退出了，却因为这条连接还开着而**永远不退**。实测症状是
 * `npm run hello` 挂满 60 秒 TTL 被宿主强杀 —— 而它其实早就干完了活。
 *
 * 但也不能一路 unref：子进程的退出码是**经这条 socket 回来**的，socket 若在子进程还活着时
 * 就不保活，事件循环会先一步排空、进程直接退出，脚本的输出与退出码全丢。
 *
 * 故判据是「**有没有在途的子进程**」：起了就 ref（这时桥是唯一能拿到结果的通道），
 * 全退完就 unref（这时桥已经没有未竟之事，不该拦着会话进程退出）。
 */
function refWhileBusy() {
  if (!conn) return
  if (waiting.size > 0) conn.ref()
  else conn.unref()
}

function ensureConn() {
  if (conn || failure) return
  const path = process.env[ENV_SOCKET]
  const token = process.env[ENV_TOKEN]
  if (!path || !token) {
    failAll(deny(CODE_NOT_IMPLEMENTED, 'T1 桥未接线：缺 ' + ENV_SOCKET + '/' + ENV_TOKEN + '（宿主未注入）'))
    return
  }
  conn = net.connect({ path: path })
  // 起步即 unref：连上但还没有子进程时，这条连接不该保活（见 refWhileBusy 的 KDoc）。
  conn.unref()
  conn.on('connect', () => {
    // **hello 直接 write，不经 `send`**：`send` 会把它塞进 pending 队列等 `ready`，
    // 而 `ready` 恰恰要等宿主回 helloAck —— 那条 Ack 永远不会来（实测：整条会话挂死，
    // 表现为 `npm run` 卡满 TTL 被强杀，报错里一个字都看不出是握手没发出去）。
    write({ t: 'hello', v: VERSION, token: token })
    refWhileBusy()
  })
  conn.on('data', onData)
  conn.on('error', (e) => {
    if (!failure) failAll(deny(CODE_BLOCKED, 'T1 桥连接失败（' + path + '）：' + e.message))
  })
  conn.on('close', () => {
    if (!failure && waiting.size > 0) failAll(deny(CODE_BLOCKED, 'T1 桥连接被宿主关闭'))
  })
}

// ── 假 ChildProcess ────────────────────────────────────────────────────────

/**
 * 宿主侧那个真进程的**本地投影**。
 *
 * 为什么要有它而不是直接回一个 EventEmitter：npm 的 `@npmcli/promise-spawn` 读
 * `child.stdout` / `child.stderr`（流）、`child.pid`、`child.kill()`，并在 `close` 上收尾。
 * 少一样就是「跑起来了但 npm 收不到输出」，而那种失败在界面上表现为"脚本静默没输出"，
 * 比报错难查得多。
 *
 * 背压**不做**（如实记账）：`push` 不看 `readableLength`，宿主来多少塞多少 —— 脚本输出
 * 以 MB 计时，这个缓冲区就是那点内存。真做背压要把 pause/resume 也经 socket 报回宿主，
 * 那是另一层协议，本版不做。
 */
class BridgedChild extends EventEmitter {
  constructor(id) {
    super()
    this.pid = -1
    this.exitCode = null
    this.signalCode = null
    this.killed = false
    this.spawnfile = ''
    this.spawnargs = []
    this.stdout = new Readable({ read() {} })
    this.stderr = new Readable({ read() {} })
    // stdin 也接上（哪怕多数脚本用不着）：npm 的 promise-spawn 在 `stdio: 'pipe'` 下会
    // 拿 `child.stdin` 写东西，`null` 会让它当场 TypeError —— 而那是"我们没接"，
    // 不是"脚本写错了"。转发成 `in` 帧，宿主那边写进真进程的 stdin。
    this.stdin = new Writable({
      write: (chunk, _enc, cb) => {
        send({ t: 'in', id: id, data: Buffer.from(chunk).toString('base64') })
        cb()
      },
    })
    this._id = id
    this._done = false
  }

  _push(fd, data) {
    if (this._done) return
    const text = Buffer.from(data, 'base64')
    const stream = fd === 2 ? this.stderr : this.stdout
    stream.push(text)
  }

  _exit(code, signal) {
    if (this._done) return
    this._done = true
    this.exitCode = code === null || code === undefined ? null : code
    this.signalCode = signal || null
    this.stdout.push(null)
    this.stderr.push(null)
    // 顺序与真 child_process 一致：exit 先、close 后（close 才是"流也读完了"）。
    this.emit('exit', this.exitCode, this.signalCode)
    this.emit('close', this.exitCode, this.signalCode)
  }

  _fail(err) {
    if (this._done) return
    this._done = true
    this.stdout.push(null)
    this.stderr.push(null)
    this.emit('error', err)
    this.emit('close', null, null)
  }

  kill() {
    this.killed = true
    // 宿主侧真杀由 socket 上的一条 kill 帧完成；本版**不发**（见 KDoc：取消是 T1 的后续件），
    // 如实把 killed 置位让调用方知道"杀过"，但不谎称进程已停。
    return true
  }

  ref() {}
  unref() {}
}

// ── 七个入口 ───────────────────────────────────────────────────────────────

/**
 * 起一个子进程（异步三兄弟的共同实现）。
 *
 * [file]/[args]/[opts] 一律**原样**送宿主：本 shim 不做 PATH 解析、不做 `sh -c` 拼装 ——
 * 那些都是判据，判据只能有一处（宿主侧）。shim 这里拼一遍、宿主那里再拼一遍，
 * 两份迟早会在"谁负责加引号"上分家，而分家的表现是"跑的东西与我以为的不是同一个"。
 */
function bridgedSpawn(file, args, opts) {
  ensureConn()
  const id = nextId++
  const child = new BridgedChild(id)
  child.spawnfile = String(file)
  child.spawnargs = Array.isArray(args) ? args.slice() : []
  if (failure) {
    // 连接已经明确失败：异步报错（spawn 的契约是同步返回、错误走 'error' 事件）。
    process.nextTick(() => child._fail(failure))
    return child
  }
  waiting.set(id, child)
  refWhileBusy()
  send({
    t: 'spawn',
    id: id,
    cmd: String(file),
    args: child.spawnargs,
    opts: {
      cwd: (opts && opts.cwd) || process.cwd(),
      env: (opts && opts.env) || null,
      detached: !!(opts && opts.detached),
      shell: !!(opts && opts.shell),
      stdio: (opts && opts.stdio) || null,
    },
  })
  return child
}

function spawnDenier(name, code, detail) {
  return function denied() {
    throw deny(code, detail)
  }
}

const cp = require('child_process')

cp.spawn = function spawn(file, args, opts) {
  if (typeof args === 'function') {
    opts = undefined
    args = []
  } else if (!Array.isArray(args) && args !== undefined && args !== null && typeof args === 'object') {
    opts = args
    args = []
  }
  if (opts && opts.detached === true) {
    throw deny(
      CODE_DENIED,
      'child_process.spawn 的 detached:true 被拒（§10.3 T1）：脱离进程组的子进程回收不到。' +
        '去掉 detached，或把要跑的东西做成项目里的一个 npm script（审批后经 npm run 走宿主）。',
    )
  }
  return bridgedSpawn(file, args || [], opts)
}

/** `exec` = `sh -c` 包一层再走同一条桥（与 npm 自己的实现同款：命令串交给 shell）。 */
cp.exec = function exec(command, opts, cb) {
  if (typeof opts === 'function') {
    cb = opts
    opts = undefined
  }
  const shell = (opts && opts.shell) || defaultShell()
  const child = bridgedSpawn(shell, ['-c', String(command)], opts)
  return collect(child, opts, cb)
}

/** `execFile` = 不包 shell 的 exec（Node 自己就是这么分的）。 */
cp.execFile = function execFile(file, args, opts, cb) {
  if (typeof args === 'function') {
    cb = args
    args = []
    opts = undefined
  } else if (typeof opts === 'function') {
    cb = opts
    opts = undefined
  }
  if (!Array.isArray(args)) {
    opts = args
    args = []
  }
  const child = bridgedSpawn(file, args || [], opts)
  return collect(child, opts, cb)
}

/** 缓冲整条输出再回调（`exec`/`execFile` 的契约：回调拿到的是**全部** stdout）。 */
function collect(child, opts, cb) {
  const enc = (opts && opts.encoding) || 'utf8'
  let out = []
  let err = []
  child.stdout.on('data', (d) => out.push(d))
  child.stderr.on('data', (d) => err.push(d))
  child.on('close', (code, signal) => {
    if (typeof cb !== 'function') return
    const stdout = enc === 'buffer' ? Buffer.concat(out) : Buffer.concat(out).toString(enc)
    const stderr = enc === 'buffer' ? Buffer.concat(err) : Buffer.concat(err).toString(enc)
    if (code === 0) {
      cb(null, stdout, stderr)
      return
    }
    const e = new Error('Command failed: ' + child.spawnfile + ' ' + child.spawnargs.join(' '))
    e.code = code
    e.killed = false
    e.signal = signal
    e.cmd = child.spawnfile + ' ' + child.spawnargs.join(' ')
    e.stdout = stdout
    e.stderr = stderr
    cb(e, stdout, stderr)
  })
  child.on('error', (e) => {
    if (typeof cb === 'function') cb(e, null, null)
  })
  return child
}

for (const name of ['spawnSync', 'execSync', 'execFileSync']) {
  cp[name] = spawnDenier(
    name,
    CODE_NOT_IMPLEMENTED,
    'child_process.' + name + ' 在 T1 桥下不可用：同步入口要阻塞事件循环等一次宿主往返，' +
      '本版桥只支持异步入口。请改用异步的 spawn/exec/execFile（npm run 走的就是它）。',
  )
}

cp.fork = spawnDenier(
  'fork',
  CODE_NOT_IMPLEMENTED,
  'child_process.fork 本平台不支持（§10.3「明确不可行」）：并发请用 auto.engines 引擎池。',
)
