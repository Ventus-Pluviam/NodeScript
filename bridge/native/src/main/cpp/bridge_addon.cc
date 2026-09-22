// :bridge:native —— N-API addon 控制面（docs/design/03-bridge.md §7.8）。
//
// 职责（只做三件事）：
//  1. `invoke(ns, method, payloadJson, reqId, ttl)`：JS → socket（newline frame，
//     与 SocketBootstrap/JsonTransport 同信封），返回 undefined = 等响应经 TSF 回来；
//  2. socket 读线程收 ok/err → 经 TSF 双队列回投 JS（control 永不丢 / data 可丢包计数）；
//  3. `setSocketFd(fd)`：宿主注入已连 socket（建连/重试/熔断归宿主，addon 只管帧读写，
//     介质可换 binder 不影响本文件 —— §7.5）。
//
// 铁律（§5.3）：Java→JS 一律 nonblocking；socket 读线程不碰 JS 只 call_tsf；
// 持 Java lock 禁回調 JS；不缓存 JNIEnv*；TSF 与 context 同生共死、跨代丢弃（§7.4 对偶）。
// addon 不解释 payload（只透传 §7）。

#include <node_api.h>

#include <sys/socket.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
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
  napi_threadsafe_function control = nullptr;
  napi_threadsafe_function data = nullptr;
  std::atomic<uint64_t> data_dropped{0};
  std::atomic<uint64_t> generation{1};
};

TsfPair g_tsf;             // 每 context 一对（P0 单 context 即全局；多 context 时宿主另建）。
std::atomic<int> g_sock{-1};  // 宿主注入的已连 socket；-1 = 未就绪。

struct JsDelivery {
  TsfPair* pair;
  uint64_t generation;
  char* json;      // ok/err 信封行（\0 结尾，不含 \n）；堆分配，TSF 消费后释放。
  bool is_control;  // 预留：control TSF 接上后按此分拣（P0 只 data 有 TSF，恒 false）。
};

void CallJs(napi_env env, napi_value js_cb, void* /*context*/, void* data) {
  JsDelivery* d = static_cast<JsDelivery*>(data);
  if (d->generation != d->pair->generation.load(std::memory_order_acquire)) {
    delete[] d->json;  // 跨代丢弃（§7.4 tombstone 对偶）
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

// 读线程（socket → TSF）：逐字节到 \n 成帧；超 64MB 帧即关（与 Kotlin
// FrameTooLargeException / SocketBootstrap 同纪律，防内存吞噬）。
// data 面帧按 is_control=false 投递；满队列（napi_queue_full）→ 丢包计数（data）
// 或阻塞报错（control 永不丢：本 P0 直接 abort，宿主不应让 control 满）。
void* SocketReadLoop(void* arg) {
  (void)arg;
  std::vector<char> buf;
  buf.reserve(256);
  char c;
  while (true) {
    int fd = g_sock.load(std::memory_order_acquire);
    if (fd < 0) break;
    ssize_t n = recv(fd, &c, 1, 0);
    if (n <= 0) break;  // EOF/错：宿主看门狗收单（ERR_ENGINE_CRASHED），线程退出。
    if (c == '\n') {
      buf.push_back('\0');
      // 最小分拣：含 "\"t\":\"ok\"" 或 "\"t\":\"err\"" 即响应帧 → data TSF。
      // 事件帧（P1）忽略（本层不处理，与 SocketBootstrap 同）。
      bool is_resp = (strstr(buf.data(), "\"t\":\"ok\"") != nullptr ||
                      strstr(buf.data(), "\"t\":\"err\"") != nullptr);
      if (is_resp && g_tsf.data != nullptr) {
        JsDelivery* d = new JsDelivery{&g_tsf, g_tsf.generation.load(std::memory_order_acquire),
                                       new char[buf.size()], false};
        memcpy(d->json, buf.data(), buf.size());
        napi_status st =
            napi_call_threadsafe_function(g_tsf.data, d, napi_tsfn_nonblocking);
        if (st != napi_ok) {  // 队列满/已关：data 面丢包计数，不炸线程。
          g_tsf.data_dropped.fetch_add(1, std::memory_order_relaxed);
          delete[] d->json;
          delete d;
        }
      }
      buf.clear();
      if (buf.capacity() > (64u * 1024u * 1024u)) break;  // 巨帧熔断（同双侧 64MB）
    } else {
      buf.push_back(c);
      if (buf.size() > (64u * 1024u * 1024u)) break;
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
  if (has_payload) napi_get_value_string_utf8(env, argv[2], payload, sizeof(payload), &n);
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
  // 帧组装（payload 已是 JSON 文本，直接嵌入；零二次解析 §7.7）。
  char frame[64 * 1024 + 256];
  int len;
  if (has_payload) {
    len = snprintf(frame, sizeof(frame),
                   "{\"t\":\"req\",\"id\":%lld,\"ns\":\"%s\",\"m\":\"%s\",\"ttl\":%lld,"
                   "\"payload\":%s,\"side\":null}\n",
                   (long long)req_id, ns, method, (long long)ttl, payload);
  } else {
    len = snprintf(frame, sizeof(frame),
                   "{\"t\":\"req\",\"id\":%lld,\"ns\":\"%s\",\"m\":\"%s\",\"ttl\":%lld,"
                   "\"payload\":null,\"side\":null}\n",
                   (long long)req_id, ns, method, (long long)ttl);
  }
  if (len <= 0 || (size_t)len >= sizeof(frame)) {
    napi_throw_error(env, "ERR_INVALID_PARAM", "帧超长");
    return nullptr;
  }
  if (!WriteAll(fd, frame, (size_t)len)) {
    napi_throw_error(env, "ERR_ENGINE_CRASHED", "socket 写失败（宿主看门狗收单）");
    return nullptr;
  }
  napi_value undef;
  napi_get_undefined(env, &undef);
  return undef;
}

// setSocketFd(fd:int) → 宿主注入已连 socket（double-set 覆盖，旧 fd 宿主自关）。
napi_value SetSocketFd(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  int32_t fd = -1;
  if (argc >= 1) napi_get_value_int32(env, argv[0], &fd);
  g_sock.store(fd, std::memory_order_release);
  napi_value undef;
  napi_get_undefined(env, &undef);
  return undef;
}

// droppedData() → data 面丢包计数（JS queueError 对偶）。
napi_value DroppedData(napi_env env, napi_callback_info /*info*/) {
  napi_value v;
  napi_create_bigint_uint64(env, g_tsf.data_dropped.load(std::memory_order_relaxed), &v);
  return v;
}

napi_value Init(napi_env env, napi_value exports) {
  napi_value fn;
  napi_create_function(env, "invoke", NAPI_AUTO_LENGTH, Invoke, nullptr, &fn);
  napi_set_named_property(env, exports, "invoke", fn);
  napi_create_function(env, "setSocketFd", NAPI_AUTO_LENGTH, SetSocketFd, nullptr, &fn);
  napi_set_named_property(env, exports, "setSocketFd", fn);
  napi_create_function(env, "droppedData", NAPI_AUTO_LENGTH, DroppedData, nullptr, &fn);
  napi_set_named_property(env, exports, "droppedData", fn);
  return exports;
}

NAPI_MODULE(bridge_native, Init)

}  // namespace
}  // namespace bridge
}  // namespace autoscript
