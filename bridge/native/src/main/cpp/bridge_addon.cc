// :bridge:native —— N-API addon 控制面（docs/framework-design.md §7.8）。
//
// 职责（①③为原契约，②④为 §7.8「起线程/TSF 接线」补全）：
//  1. `invoke(ns, method, payloadJson, reqId, ttl)`：JS → socket（newline frame，
//     与 SocketBootstrap/JsonTransport 同信封 —— payload 在信封里是 **JSON 字符串**，
//     本函数负责转义字符串化，金样见 JsonTransportTest），返回 undefined = 等响应经 TSF 回来；
//  2. socket 读线程收 ok/err → 经 TSF 双队列回投 JS（control 永不丢 / data 可丢包计数）；
//     读线程由 `setSocketFd(fd>=0)` 首次注入时拉起（§7.8「宿主起线程」的 addon 侧半边：
//     线程体住本文件，宿主只能经注入点拉起），poll 50ms 唤醒重读 g_sock 支持换 fd 重连；
//  3. `setSocketFd(fd)`：宿主注入已连 socket（建连/重试/熔断归宿主，addon 只管帧读写，
//     介质可换 binder 不影响本文件 —— §7.5）；
//  4. `setup(onFrame)`：JS 首帧接线 —— 建 data 面 TSF（一次性，闲置 unref §7.3）。
//     未 setup 就到达的响应帧计入 `droppedData()`（诚实可查，不静默吞）。
//
// 铁律（§5.3）：Java→JS 一律 nonblocking；socket 读线程不碰 JS 只 call_tsf；
// 持 Java lock 禁回調 JS；不缓存 JNIEnv*；TSF 与 context 同生共死、跨代丢弃（§7.4 对偶）。
// addon 不解释 payload（只透传 §7）。

#include <node_api.h>

#include <poll.h>
#include <sys/socket.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <pthread.h>
#include <string>
#include <unistd.h>
#include <vector>

#ifndef NAPI_VERSION
#define NAPI_VERSION 10
#endif

namespace autoscript {
namespace bridge {
namespace {

// ns/m 是 facade 写死的 ASCII 名，不含引号/反斜杠/控制字符（防帧注入）。
bool IsSafeToken(const char* s) {
  for (const char* p = s; *p; ++p) {
    if (*p == '"' || *p == '\\' || (unsigned char)*p < 0x20) return false;
  }
  return true;
}

// 全写（短写循环；EINTR 重试；其余错即返错 —— 断连由宿主看门狗收单，不在此重试）。
bool WriteAll(int fd, const char* buf, size_t len) {
  while (len > 0) {
    ssize_t n = send(fd, buf, len, MSG_NOSIGNAL);
    if (n < 0) {
      if (errno == EINTR) continue;
      return false;
    }
    buf += n;
    len -= (size_t)n;
  }
  return true;
}

struct TsfPair {
  napi_threadsafe_function control = nullptr;  // P0 未接（is_control 恒 false，见 JsDelivery）
  std::atomic<napi_threadsafe_function> data{nullptr};  // setup() 创建；读线程只 load
  std::atomic<uint64_t> data_dropped{0};
  std::atomic<uint64_t> generation{1};
};

TsfPair g_tsf;              // 每 context 一对（P0 单 context 即全局；多 context 时宿主另建）。
std::atomic<int> g_sock{-1};  // 宿主注入的已连 socket；-1 = 未就绪/已停机。

// 读线程存活标记 + 拉起互斥（reader 退出自清；setSocketFd 据此决定是否 pthread_create）。
std::atomic<bool> g_reader_running{false};

// DeliverFrame（call_tsf）与环境收尾（release TSF）互斥：收尾后读线程绝不触碰已释放 TSF。
std::mutex g_deliver_mu;

struct JsDelivery {
  TsfPair* pair;
  uint64_t generation;
  char* json;      // ok/err 信封行（\0 结尾，不含 \n）；堆分配，TSF 消费后释放。
  bool is_control;  // 预留：control TSF 接上后按此分拣（P0 只 data 有 TSF，恒 false）。
};

void CallJs(napi_env env, napi_value js_cb, void* /*context*/, void* data) {
  JsDelivery* d = static_cast<JsDelivery*>(data);
  // env==null（环境收尾派发）或跨代（§7.4 tombstone 对偶）：只释放不回 JS。
  if (env == nullptr || js_cb == nullptr ||
      d->generation != d->pair->generation.load(std::memory_order_acquire)) {
    delete[] d->json;
    delete d;
    return;
  }
  napi_value js_str, global;
  napi_create_string_utf8(env, d->json, NAPI_AUTO_LENGTH, &js_str);
  napi_get_global(env, &global);
  napi_call_function(env, global, js_cb, 1, &js_str, nullptr);
  delete[] d->json;
  delete d;
}

// 单帧投递（读线程唯一触碰 N-API 的点，仅 call_tsf —— 线程安全函数，非直接回 JS）。
// 含 "\"t\":\"ok\"" / "\"t\":\"err\"" 即响应帧 → data TSF；事件帧（P1）忽略。
// TSF 未接（setup 未调/环境已收尾）或队列满 → data 面丢包计数（诚实可查不静默）。
void DeliverFrame(const char* json) {
  if (strstr(json, "\"t\":\"ok\"") == nullptr && strstr(json, "\"t\":\"err\"") == nullptr) {
    return;
  }
  std::lock_guard<std::mutex> lk(g_deliver_mu);
  napi_threadsafe_function tsf = g_tsf.data.load(std::memory_order_acquire);
  if (tsf == nullptr) {
    g_tsf.data_dropped.fetch_add(1, std::memory_order_relaxed);
    return;
  }
  JsDelivery* d = new JsDelivery{&g_tsf, g_tsf.generation.load(std::memory_order_acquire),
                                 new char[strlen(json) + 1], false};
  memcpy(d->json, json, strlen(json) + 1);
  if (napi_call_threadsafe_function(tsf, d, napi_tsfn_nonblocking) != napi_ok) {
    g_tsf.data_dropped.fetch_add(1, std::memory_order_relaxed);
    delete[] d->json;
    delete d;
  }
}

// 读线程（socket → TSF）：poll 等可读（50ms 超时唤醒重读 g_sock，支持宿主换 fd 重连）；
// 8KB 块读 + 按 \n 成帧（部分行跨读保留）；超 64MB 行即熔断退出（与 Kotlin
// FrameTooLargeException / SocketBootstrap 同纪律，防内存吞噬）。
// EOF/读错且 g_sock 仍是本 fd = 真断连 → 线程退出（宿主看门狗收单 ERR_ENGINE_CRASHED）；
// g_sock 已换代则继续绑新 fd。线程退出自清 g_reader_running。
void* SocketReadLoop(void* /*arg*/) {
  char chunk[8192];
  std::vector<char> line;
  line.reserve(256);
  bool stop = false;
  int exited_fd = -1;  // 退出时绑着的 fd（fd<0 停机路径保持 -1）
  while (!stop) {
    int fd = g_sock.load(std::memory_order_acquire);
    if (fd < 0) break;  // 宿主清空 = 停机
    struct pollfd pfd = {fd, POLLIN, 0};
    int pr = poll(&pfd, 1, 50);
    if (pr < 0) {
      if (errno == EINTR) continue;
      exited_fd = fd;
      break;
    }
    if (pr == 0) continue;  // 超时：重读 g_sock（fd 可能已被宿主换代）
    ssize_t n = recv(fd, chunk, sizeof(chunk), 0);
    if (n <= 0) {
      if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue;
      // EOF/读错：g_sock 仍是本 fd → 真断；已换代 → 绑新 fd 继续。
      if (g_sock.load(std::memory_order_acquire) == fd) {
        exited_fd = fd;
        break;
      }
      continue;
    }
    for (ssize_t i = 0; i < n && !stop; ++i) {
      char c = chunk[i];
      if (c == '\n') {
        line.push_back('\0');
        DeliverFrame(line.data());
        line.clear();
      } else {
        line.push_back(c);
        if (line.size() > (64u * 1024u * 1024u)) {  // 巨帧熔断
          exited_fd = fd;
          stop = true;
        }
      }
    }
  }
  // 退出竞态自愈：宿主若在「本线程已决定退、标记还没清」的间隙换了 fd，它的
  // exchange 会看到 running=true 而不拉线程 —— 这里清标后比对 g_sock，已换代
  // （或清后又注新 fd）就补拉一个。g_sock==exited_fd（同 fd 真断/熔断）不补，
  // 否则 EOF 立即重入会空转。清标与补拉之间宿主若自己拉了，exchange 撞 true 让位。
  g_reader_running.store(false, std::memory_order_release);
  int now = g_sock.load(std::memory_order_acquire);
  if (now >= 0 && now != exited_fd && !g_reader_running.exchange(true, std::memory_order_acq_rel)) {
    pthread_t tid;
    if (pthread_create(&tid, nullptr, SocketReadLoop, nullptr) == 0) {
      pthread_detach(tid);
    } else {
      g_reader_running.store(false, std::memory_order_release);
    }
  }
  return nullptr;
}

// invoke(ns, method, payloadJson|null, reqId, ttl) → undefined（已投递，等 TSF 响应）。
// fd 未注入 → 抛 ERR_ENGINE_STOPPED（对齐 SocketBootstrap.checkConnected，不悬挂）。
napi_value Invoke(napi_env env, napi_callback_info info) {
  size_t argc = 5;
  napi_value argv[5];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 5) {
    napi_throw_error(env, "ERR_INVALID_PARAM", "invoke(ns,method,payload,reqId,ttl)");
    return nullptr;
  }
  char ns[64], method[64];
  char payload[64 * 1024];
  size_t n = 0;
  napi_get_value_string_utf8(env, argv[0], ns, sizeof(ns), &n);
  napi_get_value_string_utf8(env, argv[1], method, sizeof(method), &n);
  napi_valuetype t = napi_undefined;
  napi_typeof(env, argv[2], &t);
  bool has_payload = (t == napi_string);
  if (has_payload) {
    // 先问真实长度再拷：静默截断的 JSON 必然畸形 → 宿主整帧丢弃（同一类"坏帧"事故）。
    size_t need = 0;
    napi_get_value_string_utf8(env, argv[2], nullptr, 0, &need);
    if (need >= sizeof(payload)) {
      napi_throw_error(env, "ERR_INVALID_PARAM", "payload 超过 64KB 上限");
      return nullptr;
    }
    napi_get_value_string_utf8(env, argv[2], payload, sizeof(payload), &n);
  }
  int64_t req_id = 0, ttl = 0;
  napi_get_value_int64(env, argv[3], &req_id);
  napi_get_value_int64(env, argv[4], &ttl);
  if (!IsSafeToken(ns) || !IsSafeToken(method)) {
    napi_throw_error(env, "ERR_INVALID_PARAM", "ns/m 非法字符");
    return nullptr;
  }
  int fd = g_sock.load(std::memory_order_acquire);
  if (fd < 0) {
    napi_throw_error(env, "ERR_ENGINE_STOPPED", "桥 socket 未连接");
    return nullptr;
  }
  // payload 是 JSON 文本（如 {"runId":1,"seq":1}）—— 信封里必须成 JSON **字符串**
  // （"payload":"{\"runId\":1,...}"），与 JS 侧 BridgeEnvelope.encodeRequest 经
  // JSON.stringify 的形状一致：宿主 TinyJson 是扁平解码，payloadOrNull 只收字符串/null，
  // 裸嵌对象会在 readValue 的 '{' 分支被判"非法值"、整帧丢弃（§7.7「零二次解析」指
  // 宿主不解析 payload 内容，不是可以跳过信封层的字符串化 —— 金样钉在
  // JsonTransportTest「addon 心跳帧金样」，改本转义必须同批改金样）。
  std::string quoted;
  quoted.reserve(n * 2 + 2);
  quoted += '"';
  for (size_t i = 0; i < n; ++i) {
    const unsigned char c = static_cast<unsigned char>(payload[i]);
    if (c == '"' || c == '\\') {
      quoted += '\\';
      quoted += static_cast<char>(c);
    } else if (c == '\n') {
      quoted += "\\n";
    } else if (c == '\r') {
      quoted += "\\r";
    } else if (c == '\t') {
      quoted += "\\t";
    } else if (c < 0x20) {
      char u[8];
      snprintf(u, sizeof(u), "\\u%04x", c);
      quoted += u;
    } else {
      quoted += static_cast<char>(c);
    }
  }
  quoted += '"';

  char head[512];
  int hlen;
  if (has_payload) {
    hlen = snprintf(head, sizeof(head),
                    "{\"t\":\"req\",\"id\":%lld,\"ns\":\"%s\",\"m\":\"%s\",\"ttl\":%lld,\"payload\":",
                    (long long)req_id, ns, method, (long long)ttl);
  } else {
    hlen = snprintf(head, sizeof(head),
                    "{\"t\":\"req\",\"id\":%lld,\"ns\":\"%s\",\"m\":\"%s\",\"ttl\":%lld,\"payload\":null",
                    (long long)req_id, ns, method, (long long)ttl);
  }
  if (hlen <= 0 || (size_t)hlen >= sizeof(head)) {
    napi_throw_error(env, "ERR_INVALID_PARAM", "帧头超长");
    return nullptr;
  }
  std::string frame(head, (size_t)hlen);
  if (has_payload) frame += quoted;
  frame += ",\"side\":null}\n";
  if (!WriteAll(fd, frame.data(), frame.size())) {
    napi_throw_error(env, "ERR_ENGINE_CRASHED", "socket 写失败（宿主看门狗收单）");
    return nullptr;
  }
  napi_value undef;
  napi_get_undefined(env, &undef);
  return undef;
}

// setSocketFd(fd:int) → 宿主注入已连 socket（double-set 覆盖，旧 fd 宿主自关）。
// fd>=0 且读线程未跑 → 首次注入拉起读线程（detach；退出自清标记，换 fd 由 poll 重读接管）。
// pthread_create 失败即抛错 —— 静默不拉线程会让所有响应永远悬着。
napi_value SetSocketFd(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  int32_t fd = -1;
  if (argc >= 1) napi_get_value_int32(env, argv[0], &fd);
  g_sock.store(fd, std::memory_order_release);
  if (fd >= 0 && !g_reader_running.exchange(true, std::memory_order_acq_rel)) {
    pthread_t tid;
    if (pthread_create(&tid, nullptr, SocketReadLoop, nullptr) != 0) {
      g_reader_running.store(false, std::memory_order_release);
      napi_throw_error(env, "ERR_ENGINE_CRASHED", "桥读线程创建失败");
      return nullptr;
    }
    pthread_detach(tid);
  }
  napi_value undef;
  napi_get_undefined(env, &undef);
  return undef;
}

// setup(onFrame) → 建 data 面 TSF（一次性；二次调用抛 ERR_INVALID_PARAM）。
// onFrame 收到的是 ok/err 信封行文本（与 JsonTransport 同形状），由 facade 按 id 结算。
// 创建后立即 unref：脚本跑完即退出，不靠 TSF 吊命（§7.3 数据面闲置自动 unref）。
napi_value Setup(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 1) {
    napi_throw_error(env, "ERR_INVALID_PARAM", "setup(onFrame)");
    return nullptr;
  }
  napi_valuetype t = napi_undefined;
  napi_typeof(env, argv[0], &t);
  if (t != napi_function) {
    napi_throw_error(env, "ERR_INVALID_PARAM", "onFrame 必须是函数");
    return nullptr;
  }
  if (g_tsf.data.load(std::memory_order_acquire) != nullptr) {
    napi_throw_error(env, "ERR_INVALID_PARAM", "setup 只能调一次");
    return nullptr;
  }
  napi_value name;
  napi_create_string_utf8(env, "autoscript.data", NAPI_AUTO_LENGTH, &name);
  napi_threadsafe_function tsf = nullptr;
  napi_status st = napi_create_threadsafe_function(
      env, argv[0], /*async_resource=*/nullptr, /*async_resource_name=*/name,
      /*max_queue_size=*/1024, /*initial_thread_count=*/1,
      /*thread_finalize_data=*/nullptr, /*thread_finalize_cb=*/nullptr,
      /*context=*/nullptr, CallJs, &tsf);
  if (st != napi_ok || tsf == nullptr) {
    napi_throw_error(env, "ERR_ENGINE_CRASHED", "TSF 创建失败");
    return nullptr;
  }
  g_tsf.data.store(tsf, std::memory_order_release);
  napi_unref_threadsafe_function(env, tsf);  // §7.3：闲置不保活事件循环
  napi_value undef;
  napi_get_undefined(env, &undef);
  return undef;
}

// droppedData() → data 面丢包计数（JS queueError 对偶；含 setup 前到达的响应帧）。
napi_value DroppedData(napi_env env, napi_callback_info /*info*/) {
  napi_value v;
  napi_create_bigint_uint64(env, g_tsf.data_dropped.load(std::memory_order_relaxed), &v);
  return v;
}

// 环境收尾（§7.8 quiesce 第③步的 native 侧）：先断源（g_sock=-1 停读线程），
// 再锁交付域内换代 + 释 TSF —— 读线程此后只见 null，绝不触已释放句柄。
void TsfCleanup(void* /*arg*/) {
  g_sock.store(-1, std::memory_order_release);
  std::lock_guard<std::mutex> lk(g_deliver_mu);
  g_tsf.generation.fetch_add(1, std::memory_order_acq_rel);
  napi_threadsafe_function tsf = g_tsf.data.exchange(nullptr, std::memory_order_acq_rel);
  if (tsf != nullptr) napi_release_threadsafe_function(tsf, napi_tsfn_release);
}

napi_value Init(napi_env env, napi_value exports) {
  napi_add_env_cleanup_hook(env, TsfCleanup, nullptr);
  napi_value fn;
  napi_create_function(env, "invoke", NAPI_AUTO_LENGTH, Invoke, nullptr, &fn);
  napi_set_named_property(env, exports, "invoke", fn);
  napi_create_function(env, "setSocketFd", NAPI_AUTO_LENGTH, SetSocketFd, nullptr, &fn);
  napi_set_named_property(env, exports, "setSocketFd", fn);
  napi_create_function(env, "setup", NAPI_AUTO_LENGTH, Setup, nullptr, &fn);
  napi_set_named_property(env, exports, "setup", fn);
  napi_create_function(env, "droppedData", NAPI_AUTO_LENGTH, DroppedData, nullptr, &fn);
  napi_set_named_property(env, exports, "droppedData", fn);
  return exports;
}

NAPI_MODULE(bridge_native, Init)

}  // namespace
}  // namespace bridge
}  // namespace autoscript
