// :nodeN 宿主进程 —— docs/framework-design.md §7.8「宿主进程（:nodeN）最小启动序」的 C++ 实现。
//
// 用法:  noden [--] <script> [scriptArgs...]
// env:
//  AUTOSCRIPT_LIBNODE        libnode.so 绝对路径（必填）—— dlopen + RTLD_GLOBAL
//                            （addon 的 napi_* 符号从 libnode 动态表可见，不链时依赖）。
//  AUTOSCRIPT_BRIDGE_ADDON   bridge_native.node 路径（选填）—— 给则经 -e 引导预载，
//                            并把 AUTOSCRIPT_SOCK_FD 注入 addon.setSocketFd。
//  AUTOSCRIPT_HOST_SOCKET    :main 的 unix socket 路径（选填）—— 给则必须连上
//                            （连不上 = exit 3，不静默降级：生产由 :main spawn 并带上，
//                            缺失只应出现在离线调试）；不给 = 离线跑，桥调用如实抛
//                            ERR_ENGINE_STOPPED（addon 语义），stderr 打一行提示。
//
// 启动序（§7.8，对应四步）：
//  1) 连 :main socket（宿主建连 —— addon 契约是「宿主注入已连 fd」，不自连 §7.5），
//     fd 经 AUTOSCRIPT_SOCK_FD 传给引导脚本；
//  2) dlopen libnode.so（16KB 门禁产物，见 node-runtime-build）；
//  3) dlsym node::Start（§7.8 符号表实证 mangled 名 _ZN4node5StartEiPPc，即契约）
//     → node::Start 单 isolate，argv = node -e BOOTSTRAP -- <script> [args...]
//     （BOOTSTRAP 预载 addon + setSocketFd + require 真脚本；无 addon 时直接跑 script）；
//  4) Start 返回即收宿主退出码（四步 quiesce 由脚本侧/看门狗驱动，禁直接 kill §5 推论 A）；
//     ppid 探活/心跳/pid 上送属 Kotlin 侧 spawn 契约（§8.4），本文件不越界。
//
// 本机只做 C++ 交叉编译验证（CLAUDE.md NDK 节）：
//   engine/node-process/scripts/build-native.sh —— NDK r28c 编译 + 符号对表 + 16KB 对齐断言。
// 真机执行链（spawn、libc++_shared 装载、JNI 注册）仍待 CI。

#include <dlfcn.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace {

constexpr int kExitUsage = 2;    // argv/env 缺失
constexpr int kExitSocket = 3;   // :main socket 连不上（env 给了就必须连上）
constexpr int kExitDlopen = 4;   // libnode 装载/符号解析失败

// §7.8 符号表：llvm-nm -D /tmp/nrb-out7/libnode.so 实证（mangled 名即契约；
// build-native.sh 里有声明↔库的对表断言，改声明改库任一侧漂移即红）。
constexpr const char kNodeStartSymbol[] = "_ZN4node5StartEiPPc";

// -e 引导（无换行，单引号安全地嵌进 argv）：预载 addon → 注入 fd → 跑真脚本。
// -e 形态下 process.argv = [execPath, <--后第一个位置参数>, ...]，故 argv[1] 即脚本路径；
// "--" 隔开防止脚本路径以 - 开头被 node 当选项。
constexpr const char kBootstrap[] =
    "const a=require(process.env.AUTOSCRIPT_BRIDGE_ADDON);"
    "if(process.env.AUTOSCRIPT_SOCK_FD)a.setSocketFd(+process.env.AUTOSCRIPT_SOCK_FD);"
    "require(process.argv[1]);";

// 连 :main 的 unix socket；成功返回 fd，失败 -1（errno 保留给调用方打印）。
int ConnectHostSocket(const char* path) {
  sockaddr_un addr;
  std::memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  if (std::strlen(path) >= sizeof(addr.sun_path)) {
    errno = ENAMETOOLONG;
    return -1;
  }
  std::memcpy(addr.sun_path, path, std::strlen(path) + 1);
  int fd = socket(AF_UNIX, SOCK_STREAM, 0);
  if (fd < 0) return -1;
  if (connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
    int saved = errno;
    close(fd);
    errno = saved;
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
    sock_fd = ConnectHostSocket(sock_path);
    if (sock_fd < 0) {
      std::fprintf(stderr, ":nodeN 连桥 socket 失败 path=%s err=%s\n", sock_path,
                   std::strerror(errno));
      return kExitSocket;
    }
    char fd_text[16];
    std::snprintf(fd_text, sizeof(fd_text), "%d", sock_fd);
    setenv("AUTOSCRIPT_SOCK_FD", fd_text, 1);
  } else {
    unsetenv("AUTOSCRIPT_SOCK_FD");
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
  node_argv.push_back(dd);
  for (int i = 1; i < argc; ++i) node_argv.push_back(argv[i]);

  int rc = node_start(static_cast<int>(node_argv.size()), node_argv.data());
  if (sock_fd >= 0) close(sock_fd);
  return rc;
}
