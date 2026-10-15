// :nodeN 宿主进程 —— docs §7.8「宿主进程（:nodeN）最小启动序」的 C++ 实现。
//
// 用法:  noden [--] <script> [scriptArgs...]
// env:
//  AUTOSCRIPT_LIBNODE        libnode.so 绝对路径（必填）—— dlopen + RTLD_GLOBAL
//                            （addon 的 napi_* 符号从 libnode 动态表可见，不链时依赖）。
//  AUTOSCRIPT_BRIDGE_ADDON   bridge_native.node 路径（选填）—— 给则经 -e 引导预载，
//                            并把 AUTOSCRIPT_SOCK_FD 注入 addon.setSocketFd。
//  AUTOSCRIPT_RUN_ID         本次执行的 runId（spawn 侧注入，NodeProcessEngine 同名 env）——
//                            本文件**透传不消费**：JS 侧读 process.env 打心跳（§8.4）。
//  AUTOSCRIPT_BRIDGE_TOKEN   宿主签发的一次性凭据（在线必填）；hello/ack 后清除，不打印。
//  AUTOSCRIPT_RUN_NONCE      §8.5 执行体幂等键（同上：透传，JS 读 process.env）。
//  AUTOSCRIPT_BRIDGE_DIST    facade dist 落位根（§12.4 资产交付轨）—— **与 addon 绑定**：
//                            给 addon 未给 dist / dist 顶层不存在 = exit 5（§7.8 契约，
//                            launch 期裁决，不推到运行期 ERR_ENGINE_STOPPED）。
//  AUTOSCRIPT_HOST_SOCKET    :main 的 unix socket 地址（选填）—— 给则必须连上
//                            （连不上 = exit 3，不静默降级：生产由 :main spawn 并带上，
//                            缺失只应出现在离线调试）；不给 = 离线跑，桥调用如实抛
//                            ERR_ENGINE_STOPPED（addon 语义），stderr 打一行提示。
//                            地址两形态（前导 '/' 判别）：以 '/' 开头 = 文件系统路径
//                            （桌面/CI/回归）；否则 = Linux abstract 名（真机，
//                            :main 侧 LocalServerSocket 绑抽象命名空间 —— minSdk 26 无
//                            ServerSocketChannel unix API）。连接后双向 SO_PEERCRED/uid
//                            校验：abstract 名无文件权限，同设备他 app 可抢绑/连入 ——
//                            uid 不等于本进程 uid 即拒（exit 3）。
//
// 启动序（§7.8，对应四步）：
//  1) 连 :main socket 并完成一次性 hello/ACK（addon 只接已认证 fd，不自连 §7.5），
//     ACK 成功才发布 AUTOSCRIPT_SOCK_FD，清 token 后进入 Node，避免 addon reader 抢 ACK；
//  2) dlopen libnode.so（16KB 门禁产物，见 node-runtime-build）；
//  3) dlsym node::Start（§7.8 符号表实证 mangled 名 _ZN4node5StartEiPPc，即契约）
//     → node::Start 单 isolate，argv = node -e BOOTSTRAP -- <script> [args...]
//     （BOOTSTRAP 预载 addon + setSocketFd + require 真脚本；无 addon 时直接跑 script）；
//     桥装上之后、require 真脚本之前顺带 `installConsoleBridge()`（§7.3/§12.1，
//     2026-10-15 用户令「js 的语法都要和原来的调用方式一样」）：把全局 console 的
//     log/info/warn/error/debug 五档**加上直写 stdout 的 dir/dirxml** 接到桥上，
//     脚本写裸 `console.log(...)` 与 `auto.console.log(...)` 落到同一条数据面 ——
//     否则裸 console.log 写的是子进程 stdout，而排水线程对它读即弃（§8.3，防反压），
//     输出在悬浮窗/控制台一处都看不见。**唯一仍看不见的是流对象直写**
//     （`process.stdout.write`）：入口不在 console 对象上，本包装碰不到（范围见 §12.1）。
//  4) Start 返回即收宿主退出码（四步 quiesce 由脚本侧/看门狗驱动，禁直接 kill §5 推论 A）；
//     ppid 探活/心跳/pid 上送属 Kotlin 侧 spawn 契约（§8.4），本文件不越界。
//
// 本机只做 C++ 交叉编译验证（CLAUDE.md NDK 节）：
//   engine/node-process/scripts/build-native.sh —— NDK r28c 编译 + 符号对表 + 16KB 对齐断言。
// 真机执行链（spawn、libc++_shared 装载、JNI 注册）仍待 CI。

#include <dlfcn.h>
#include <fcntl.h>
#include <poll.h>
#include <chrono>
#include <string>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <cstddef>

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace {

constexpr int kExitUsage = 2;    // argv/env 缺失
constexpr int kExitSocket = 3;   // :main socket 连不上（env 给了就必须连上）
constexpr int kExitDlopen = 4;   // libnode 装载/符号解析失败
constexpr int kExitDist = 5;     // bridge 资产（dist 顶层）缺失/畸形

// §7.8 符号表：llvm-nm -D /tmp/nrb-out7/libnode.so 实证（mangled 名即契约；
// build-native.sh 里有声明↔库的对表断言，改声明改库任一侧漂移即红）。
constexpr const char kNodeStartSymbol[] = "_ZN4node5StartEiPPc";

// -e 引导（无换行，单引号安全地嵌进 argv）：预载 addon → 注入 fd → 跑真脚本。
// -e 形态下 process.argv = [execPath, <--后第一个位置参数>, ...]，故 argv[1] 即脚本路径；
// "--" 隔开防止脚本路径以 - 开头被 node 当选项。
// 心跳（§8.4 原生宿主半边）：读 spawn 注入的 AUTOSCRIPT_RUN_ID，500ms 打点（与
// WatchdogPolicy.heartbeatIntervalMillis 同源）。unref 定时器不吊住事件循环（脚本跑完即退，
// §5.3 同款纪律，对齐 bridge/js startHeartbeat）。reqId 用 **-seq 负数命名空间**：心跳响应
// 由 addon 直接回包，JS 消费面（facade `attachNative` → runtimeBridge.handleResponse，
// §12.4 接入面 1）装上后，迟到的心跳响应撞不上任何在途正数 id（handleResponse 查不到
// 即丢），不会错结算别的请求。
// 无 RUN_ID（非 spawn 起的裸 noden）→ 不打点；离线无 fd 时 invoke抛错被 beat 吞掉
// （心跳失败不炸脚本 —— JS 侧 startHeartbeat 同款纪律）。
// 打包入口接线（§12.4 资产交付轨，2026-09-24）：AUTOSCRIPT_BRIDGE_DIST 在位 →
// require($DIST/bootstrap.js).attachNative({addon: a}) —— setup（TSF 结算面）先就位、
// install 把 addon.invoke 注入 runtimeBridge，脚本随后 require('auto') 即有桥。
// 传 {addon: a} 复用本引导已 require 的同一实例（模块缓存本就同份，显式传免得
// NativeBootstrap 再走一遍 env require）。
// **两层裁决分工**（§7.8 契约）：
//   · exe preflight（kExitDist=5）：addon 在位但 dist env 未给 / dist 顶层路径不存在 =
//     launch 期硬错（资源不齐不该推到运行期 ERR_ENGINE_STOPPED）。这层只查"路径在不在"。
//   · bootstrap try/catch（下方）：dist 路径在、但 bootstrap.js 内容损坏/attachNative 抛错 =
//     运行期失败，stderr 点名（"attachNative 未接上"）、脚本照跑（选填件不杀执行，与
//     addon 缺位同纪律）。这层查"内容能不能 attach"。
// 故 kBootstrap 仍保留 require($DIST/bootstrap.js).attachNative({addon}) 的 try/catch ——
// preflight 拦不住"路径在但内容坏"，那是这一层的事。
//
// **全局 console → 桥**（§7.3 数据面；2026-10-15 用户令「js 的语法都要和原来的调用方式
// 一样」）：桥装上之后、require 真脚本之前调 `installConsoleBridge()`，脚本写裸
// `console.log(...)` 与 `auto.console.log(...)` 落到同一条数据面 —— 否则裸 console.log
// 写的是子进程 stdout，而排水线程对它读即弃（§8.3，防反压），输出在悬浮窗/控制台
// 一处都看不见（设备实测 2026-10-15：裸 console.log 版脚本窗口显示「没有输出」、
// 日志文件 0 字节）。**三道守卫，缺一不装** —— 装了而桥没活着，脚本的 log/info/debug
// 会从"进 stdout（今天被排空丢弃）"变成"进一条投不出去的数据面"，而且**静默**：
//   · dist env 在位（没落位时整条 facade 都不在，包装体 require 不到）；
//   · `runtimeBridge.installed` 为真（attachNative 抛过错的半接线形态 —— 上面那个
//     catch 已经点名过，这里不再装作桥活着）；
//   · 包装体自带 `Symbol.for` 幂等标记（重复装才是真会翻车的形态）。
// 这一条失败只打一行 stderr、**脚本照跑**：最坏退化成"裸 console.log 看不见"的今天
// 形态，与 addon/dist 缺位同一条选择纪律（选填件不杀执行）。
constexpr const char kBootstrap[] =
    "const a=require(process.env.AUTOSCRIPT_BRIDGE_ADDON);"
    "if(process.env.AUTOSCRIPT_SOCK_FD)a.setSocketFd(+process.env.AUTOSCRIPT_SOCK_FD);"
    "const rid=+process.env.AUTOSCRIPT_RUN_ID;"
    "if(rid>0){let seq=0;setInterval(()=>{seq++;"
    "try{a.invoke('engines','heartbeat',JSON.stringify({runId:rid,seq}),-seq,2000)}catch(e){}},500).unref();}"
    "try{const d=process.env.AUTOSCRIPT_BRIDGE_DIST;"
    "if(d)require(d+\"/bootstrap.js\").attachNative({addon:a})"
    "}catch(e){console.error('bridge-dist attachNative 未接上: '+(e&&e.message||e))}"
    "try{const d=process.env.AUTOSCRIPT_BRIDGE_DIST;"
    "if(d&&require(d+\"/runtime.js\").runtimeBridge.installed)"
    "require(d+\"/console-bridge.js\").installConsoleBridge()"
    "}catch(e){console.error('console 未接到桥上（裸 console.log 不出现在悬浮窗）: '+(e&&e.message||e))}"
    "require(process.argv[1]);";

// 连 :main 的 unix socket；成功返回 fd，失败 -1（errno 保留给调用方打印）。
// 地址两形态（见文件头）：'/' 前导 = 文件系统路径；否则 = abstract 名。
// 连上后 SO_PEERCRED 校验对端 uid == 自身 uid —— 抢绑 abstract 名的他 app 在此被拒。
using Deadline = std::chrono::steady_clock::time_point;

// connect/hello/ack 共用一个期限；EINTR 不能重置计时，写端关闭不能以 SIGPIPE 杀宿主。
bool WaitSocket(int fd, short events, Deadline deadline) {
  for (;;) {
    const auto left = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - std::chrono::steady_clock::now()).count();
    if (left <= 0) { errno = ETIMEDOUT; return false; }
    pollfd pfd{fd, events, 0};
    int result = poll(&pfd, 1, static_cast<int>(left));
    if (result < 0 && errno == EINTR) continue;
    if (result == 0) errno = ETIMEDOUT;
    return result > 0;  // POLLHUP/POLLERR 交后续 send/recv/SO_ERROR 如实处理。
  }
}

bool AuthenticateHost(int fd, const char* token, Deadline deadline) {
  if (token == nullptr || std::strlen(token) != 64) { errno = EACCES; return false; }
  for (size_t i = 0; i < 64; ++i) {
    if (!((token[i] >= '0' && token[i] <= '9') || (token[i] >= 'a' && token[i] <= 'f'))) {
      errno = EACCES; return false;
    }
  }
  const std::string hello = std::string("{\"t\":\"hello\",\"v\":1,\"token\":\"") + token + "\"}\n";
  size_t offset = 0;
  while (offset < hello.size()) {
    if (!WaitSocket(fd, POLLOUT, deadline)) return false;
    const ssize_t n = send(fd, hello.data() + offset, hello.size() - offset, MSG_NOSIGNAL | MSG_DONTWAIT);
    if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
    if (n <= 0) return false;
    offset += static_cast<size_t>(n);
  }
  // 此时尚未进入 Node/addon，唯一读者就是这里。只读到换行，不多吞业务字节。
  std::string ack;
  while (ack.size() < 1024) {
    if (!WaitSocket(fd, POLLIN, deadline)) return false;
    char c;
    const ssize_t n = recv(fd, &c, 1, MSG_DONTWAIT);
    if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
    if (n <= 0) { errno = ECONNRESET; return false; }
    if (c == '\n') {
      if (ack == "{\"t\":\"helloAck\",\"v\":1}") return true;
      errno = EACCES; return false;
    }
    ack.push_back(c);
  }
  errno = EMSGSIZE;
  return false;
}

int ConnectHostSocket(const char* path, Deadline deadline) {
  sockaddr_un addr;
  std::memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  const size_t len = std::strlen(path);
  socklen_t addrlen;
  if (len > 0 && path[0] == '/') {
    if (len >= sizeof(addr.sun_path)) {
      errno = ENAMETOOLONG;
      return -1;
    }
    std::memcpy(addr.sun_path, path, len + 1);
    addrlen = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + len + 1);
  } else {
    // abstract：sun_path[0] 留 0，名字从 +1 起；地址长度含那个 0 字节（不含结尾 NUL）。
    if (len == 0 || len >= sizeof(addr.sun_path) - 1) {
      errno = ENAMETOOLONG;
      return -1;
    }
    addr.sun_path[0] = '\0';
    std::memcpy(addr.sun_path + 1, path, len);
    addrlen = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + len);
  }
  int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
  if (fd < 0) return -1;
  if (connect(fd, reinterpret_cast<sockaddr*>(&addr), addrlen) != 0) {
    if (errno != EINPROGRESS || !WaitSocket(fd, POLLOUT, deadline)) {
      int saved = errno; close(fd); errno = saved; return -1;
    }
    int error = 0;
    socklen_t error_len = sizeof(error);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &error_len) != 0 || error != 0) {
      int saved = error != 0 ? error : errno; close(fd); errno = saved; return -1;
    }
  }
  struct ucred peer;
  socklen_t peerlen = sizeof(peer);
  if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &peerlen) != 0) {
    int saved = errno;
    close(fd);
    errno = saved;
    return -1;
  }
  if (peer.uid != getuid()) {
    std::fprintf(stderr,
                 ":nodeN 拒绝桥对端：uid %u != 本进程 uid %u（%s 被同设备其他 app 抢绑？）\n",
                 peer.uid, getuid(), path);
    close(fd);
    errno = ECONNREFUSED;
    return -1;
  }
  return fd;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 2) {
    std::fprintf(stderr, "用法: noden [--] <script> [scriptArgs...]（并设 AUTOSCRIPT_LIBNODE）\n");
    return kExitUsage;
  }
  const char* libnode_path = std::getenv("AUTOSCRIPT_LIBNODE");
  if (libnode_path == nullptr || libnode_path[0] == '\0') {
    std::fprintf(stderr, ":nodeN 缺 AUTOSCRIPT_LIBNODE（libnode.so 绝对路径）\n");
    return kExitUsage;
  }

  // ── ① socket（env 给了就必须连上；离线则清掉可能残留的 fd env，防喂旧值）────────
  int sock_fd = -1;
  const char* sock_path = std::getenv("AUTOSCRIPT_HOST_SOCKET");
  if (sock_path != nullptr && sock_path[0] != '\0') {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    sock_fd = ConnectHostSocket(sock_path, deadline);
    if (sock_fd < 0) {
      std::fprintf(stderr, ":nodeN 连桥 socket 失败 path=%s err=%s\n", sock_path,
                   std::strerror(errno));
      return kExitSocket;
    }
    const bool authenticated = AuthenticateHost(sock_fd, std::getenv("AUTOSCRIPT_BRIDGE_TOKEN"), deadline);
    unsetenv("AUTOSCRIPT_BRIDGE_TOKEN");
    if (!authenticated) {
      std::fprintf(stderr, ":nodeN 桥身份认证失败 err=%s\n", std::strerror(errno));
      close(sock_fd);
      return kExitSocket;
    }
    // addon 既有写路径接阻塞 fd；握手完成后再发布，不能让其读线程先抢 ACK。
    const int flags = fcntl(sock_fd, F_GETFL);
    if (flags < 0 || fcntl(sock_fd, F_SETFL, flags & ~O_NONBLOCK) < 0) {
      close(sock_fd); return kExitSocket;
    }
    char fd_text[16];
    std::snprintf(fd_text, sizeof(fd_text), "%d", sock_fd);
    setenv("AUTOSCRIPT_SOCK_FD", fd_text, 1);
  } else {
    unsetenv("AUTOSCRIPT_SOCK_FD");
    unsetenv("AUTOSCRIPT_BRIDGE_TOKEN");
    std::fprintf(stderr, ":nodeN 离线启动（无 AUTOSCRIPT_HOST_SOCKET，桥不可用）\n");
  }

  // ── ② dlopen libnode（RTLD_GLOBAL：addon 后续 require 时 napi_* 从这里解析）────
  dlerror();
  void* libnode = dlopen(libnode_path, RTLD_NOW | RTLD_GLOBAL);
  if (libnode == nullptr) {
    std::fprintf(stderr, ":nodeN dlopen 失败 path=%s err=%s\n", libnode_path, dlerror());
    if (sock_fd >= 0) close(sock_fd);
    return kExitDlopen;
  }

  // ── ③ node::Start（符号名即 §7.8 契约，不自己拼变体）──────────────────────────
  dlerror();
  void* sym = dlsym(libnode, kNodeStartSymbol);
  if (sym == nullptr) {
    std::fprintf(stderr, ":nodeN dlsym %s 失败 err=%s（libnode 与 §7.8 符号表不符？）\n",
                 kNodeStartSymbol, dlerror());
    if (sock_fd >= 0) close(sock_fd);
    return kExitDlopen;
  }
  using NodeStart = int (*)(int, char**);
  NodeStart node_start = reinterpret_cast<NodeStart>(sym);

  // ── node argv：{node, [-e BOOTSTRAP --], script, args...}；不 dlclose —— 撕掉
  //    运行中的 isolate 等于 UAF，进程退出由内核回收映射。──────────────────────────
  const char* addon_path = std::getenv("AUTOSCRIPT_BRIDGE_ADDON");
  bool preload_addon = (addon_path != nullptr && addon_path[0] != '\0');
  // dist 资产缺/畸形 = 资源不齐，硬错（kExitDist=5）——见 kBootstrap 注：这个裁决
  // 不能落到 bootstrap 的 require 选择分支（node -e 那里只有 stderr + rc=1，Watchdog
  // 会当脚本错误），也不能落到运行期（脚本 require('auto') 只撞 ERR_ENGINE_STOPPED）。
  // 传 path 给 addon 之前就验 dist 存在（access 不判路径可执行位，只看可达性）。
  if (preload_addon) {
    const char* dist = std::getenv("AUTOSCRIPT_BRIDGE_DIST");
    if (dist == nullptr || dist[0] == '\0') {
      std::fprintf(stderr, ":nodeN 缺 AUTOSCRIPT_BRIDGE_DIST（bridge 资产 dist 顶层，§12.4 交付轨）\n");
      return kExitDist;
    }
    if (access(dist, F_OK) != 0) {
      std::fprintf(stderr, ":nodeN dist 资产不存在: %s（§12.4 交付轨未落位）\n", dist);
      return kExitDist;
    }
  }
  // 参数序契约：node 选项（堆上限等）必须落在 **`-e BOOTSTRAP --` 之前**。
  // node 的 argv 解析先吃选项、遇 `--` 收手，`--` 之后全是位置参数（脚本路径 +
  // 脚本自己的参数）；脚本经 kBootstrap 的 `require(process.argv[1])` 取脚本路径，
  // 拼错 = 选项被当成脚本名（设备实测 `MODULE_NOT_FOUND: Cannot find module
  // '--max-old-space-size=192'`，退出码 1，引擎判 CRASHED）。位置参数自 `--` 起
  // 原样透传（不含选项），顺序与个数都不变。
  std::vector<char*> node_argv;
  node_argv.push_back(argv[0]);  // argv[0] = 可执行名（node 惯例）
  static char flag_e[] = "-e";
  static char bootstrap[sizeof(kBootstrap)];
  std::memcpy(bootstrap, kBootstrap, sizeof(kBootstrap));
  static char dd[] = "--";
  if (preload_addon) {
    node_argv.push_back(flag_e);
    node_argv.push_back(bootstrap);
  }
  // 两段过：先收选项（`-` 开头，落在 `--` 之前 = node 认它是选项），再原样透传
  // 位置参数（脚本 + 脚本自己的参数，`--` 之后 = node 不碰）。脚本路径是绝对路径，
  // 不会以 `-` 开头；脚本自己的参数即便以 `-` 开头也在 `--` 之后，照旧安全。
  int first_positional = argc;   // 第一个位置参数的下标；== argc = 一个都没有
  for (int i = 1; i < argc; ++i) {
    if (argv[i][0] == '-') {
      node_argv.push_back(argv[i]);
    } else {
      first_positional = i;
      break;
    }
  }
  node_argv.push_back(dd);
  for (int i = first_positional; i < argc; ++i) node_argv.push_back(argv[i]);

  int rc = node_start(static_cast<int>(node_argv.size()), node_argv.data());
  if (sock_fd >= 0) close(sock_fd);
  return rc;
}
