// bridge/image —— libimgnative.so 的 C++ 面（docs/framework-design.md §9.2）
//
// 职责边界（与 :domain 的 ImageAnalyzer SPI 逐条对齐）：
//   - 只做三件事：decode 一帧、管帧表、按阈值做模板匹配；
//   - 不做路径策略、不发桥请求、不碰 Kotlin 侧句柄号（发号归桥面 handler）；
//   - 跨语言接触面全部 extern "C"：无 C++ 名修饰，无异常穿越（cv::Exception
//     在本文件内就地折叠成状态码，绝不抛过 ABI 边界）。
//
// 为什么不是 JNI：这是**纯计算核**，唯一被允许 #include <jni.h> 的是装载侧的
// images_jni.cc。本文件零 JNI 依赖 → 编进 libimgnative.so 的那一侧与宿主侧
// 编排互不相干，后续要加 host 侧单测也不必捎带 NDK 的 C runtime。
//
// 帧表所有权（§9.2）：本 TU 自管 unordered_map<refId, Mat>，单调发号、
// 不放缓存——缓存会让两个 refId 指向同一份像素，释放一个另一个即成野指针
// （与 :domain ImageAnalyzer KDoc 同一理由）。guard：一把全局互斥量；帧表
// 操作是 O(1) 元数据改动，远低于匹配耗时，与 Kotlin 侧 ImagesNamespaceHandler
// 的 Mutex guard 同构（不是热点，不细分）。
//
// 状态码与桥面 ERR_* 一一对应（Kotlin 侧原码透传，不做二次折叠）：
//   0 = OK（含"未命中"，见 *match 出参）
//   1 = ERR_STALE_HANDLE（未知/跨代/已释放的句柄）
//   2 = ERR_FILE_NOT_FOUND（路径不存在）
//   3 = ERR_IO（文件在但不是合法图片 / 模板比画面大）
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>

#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

namespace {

std::mutex g_mu;
std::unordered_map<int64_t, cv::Mat> g_frames;
int64_t g_next_ref = 1;

constexpr int IMG_OK = 0;
constexpr int IMG_ERR_STALE_HANDLE = 1;
constexpr int IMG_ERR_FILE_NOT_FOUND = 2;
constexpr int IMG_ERR_IO = 3;

// 调用方必须已持 g_mu（三个入口的帧表段都在锁内）。
cv::Mat* find_locked(int64_t ref) {
    auto it = g_frames.find(ref);
    return it == g_frames.end() ? nullptr : &it->second;
}

}  // namespace

extern "C" {

// ── decode：文件 → 一帧（不缩放不裁剪，内容 opaque）────────────────────────
// width/height 出参是**帧真尺寸**（桥面随回包给脚本做坐标换算）。
// 空路径按文件缺失报（契约：空白路径已是 handler 层的 ERR_INVALID_PARAM，
// 走到这儿说明是原生调用方，仍按"没有这个文件"答，不编造）。
int imgnative_decode(const char* path, int64_t* out_ref, int32_t* out_w, int32_t* out_h) {
    if (path == nullptr || path[0] == '\0') return IMG_ERR_FILE_NOT_FOUND;
    const std::string p(path);

    // 存在性先判：把「路径不在」与「在但不是合法图片」分成两个码 —— 契约要求
    // 脚本能分辨这两件事（imread 对两者都回空 Mat，单看返回值分不开）。
    std::FILE* probe = std::fopen(p.c_str(), "rb");
    if (probe == nullptr) return IMG_ERR_FILE_NOT_FOUND;
    std::fclose(probe);

    // 解码失败就地折叠成状态码：cv::Exception 不过 ABI 边界（见文件头）。
    try {
        cv::Mat mat = cv::imread(p, cv::IMREAD_COLOR);
        if (mat.empty()) return IMG_ERR_IO;

        const std::lock_guard<std::mutex> lk(g_mu);
        const int64_t ref = g_next_ref++;
        auto [it, _] = g_frames.emplace(ref, std::move(mat));
        *out_ref = ref;
        *out_w = it->second.cols;
        *out_h = it->second.rows;
        return IMG_OK;
    } catch (const cv::Exception&) {
        return IMG_ERR_IO;
    }
}

// ── match：模板匹配。threshold ∈ [0,1]（域校验归桥面 handler，此处不再放宽）
// 返回 IMG_OK 时看 *out_match：0 = 未达阈值（**未匹配是答案不是异常**），
// 1 = 命中且 x/y/w/h/confidence 已填。句柄不在场 → IMG_ERR_STALE_HANDLE。
int imgnative_match(int64_t haystack, int64_t needle, double threshold,
                    int32_t* out_x, int32_t* out_y,
                    int32_t* out_w, int32_t* out_h,
                    double* out_conf, int32_t* out_match) {
    try {
        const std::lock_guard<std::mutex> lk(g_mu);
        cv::Mat* h = find_locked(haystack);
        cv::Mat* n = find_locked(needle);
        if (h == nullptr || n == nullptr) return IMG_ERR_STALE_HANDLE;

        // 模板比画面大：opencv matchTemplate 会直接断言失败，先一步按 IO 错答
        // （这是"参数关系不成立"，不是"图上没有"）。
        if (n->cols > h->cols || n->rows > h->rows) return IMG_ERR_IO;

        cv::Mat result;
        cv::matchTemplate(*h, *n, result, cv::TM_CCOEFF_NORMED);
        double minv = 0.0, maxv = 0.0;
        cv::Point minloc, maxloc;
        cv::minMaxLoc(result, &minv, &maxv, &minloc, &maxloc);

        if (maxv < threshold) {
            *out_match = 0;
            *out_x = *out_y = *out_w = *out_h = 0;
            *out_conf = 0.0;
            return IMG_OK;
        }
        *out_match = 1;
        *out_x = maxloc.x;
        *out_y = maxloc.y;
        *out_w = n->cols;   // 模板在画面里被匹配上的区域尺寸（= 模板尺寸）
        *out_h = n->rows;
        *out_conf = maxv;
        return IMG_OK;
    } catch (const cv::Exception&) {
        return IMG_ERR_IO;
    }
}

// ── release：放掉即从在场表移除。未知/已释放 → STALE（不给静默成功的第二次）
int imgnative_release(int64_t ref) {
    const std::lock_guard<std::mutex> lk(g_mu);
    return g_frames.erase(ref) == 1 ? IMG_OK : IMG_ERR_STALE_HANDLE;
}

}  // extern "C"
