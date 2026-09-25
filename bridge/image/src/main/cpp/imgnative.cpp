// bridge/image —— libopencv.so 的 C++ 面（docs/framework-design.md §9.2）
//
// 职责边界（与 :domain 的 ImageAnalyzer SPI 逐条对齐）：
//   - 只做八件事：decode 一帧（**顺带把帧归一成 4 通道 BGRA**）、管帧表、
//     按阈值做模板匹配、按容差找色、取灰度信息面（**产出新帧**）、
//     按区域取子图（**产出新帧**）、按尺寸缩放（**产出新帧**）；
//   - 不做路径策略、不发桥请求、不碰 Kotlin 侧句柄号（发号归桥面 handler）；
//   - 跨语言接触面全部 extern "C"：无 C++ 名修饰，无异常穿越（cv::Exception
//     在本文件内就地折叠成状态码，绝不抛过 ABI 边界）。
//
// 为什么不是 JNI：这是**纯计算核**，唯一被允许 #include <jni.h> 的是装载侧的
// images_jni.cc。本文件零 JNI 依赖 → 编进 libopencv.so 的那一侧与宿主侧
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
//   4 = ERR_INVALID_PARAM（参数关系不成立：颜色分量越界 / 容差越界 /
//     区域不在帧内 / 区域扫过 0 像素）—— handler 也已先做域校验，这里是
//     原生调用方（无桥面）或两处判据漂移时的兜底，绝不放一个默认色过去。
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
constexpr int IMG_ERR_INVALID_PARAM = 4;

/** `color` 未命中的 x 哨兵（0,0 是合法首像素坐标，不能拿它当"没有"）。 */
constexpr int32_t IMG_MISS = -1;

// 调用方必须已持 g_mu（七个入口的帧表段都在锁内）。
cv::Mat* find_locked(int64_t ref) {
    auto it = g_frames.find(ref);
    return it == g_frames.end() ? nullptr : &it->second;
}

/**
 * 帧表不变式：**在场帧恒 4 通道 8 位**（由 decode 归一保证；detail 见
 * imgnative_decode 的归一那段）。产出新帧的算子（`imgnative_gray`/`imgnative_crop`，
 * 后续 resize/rotate 同）必须自己满足它 —— 正因为这一条是可被违反的，
 * 才有这个具名谓词：`imgnative_color` 的 `Vec4b` 回读在 3 通道帧上会静默
 * 读进下一行首字节（2026-09-25 实测过那类越字节读），所以进像素前先问一遍。
 */
bool frame_is_normalized(const cv::Mat& m) { return m.channels() == 4 && m.depth() == CV_8U; }

/**
 * 把可选 region（x,y,w,h）解析成一个**保证落在帧内**的矩形。
 * 调用方必须已持 g_mu（要读帧尺寸）。
 *
 * 三条判据合一处：`region == nullptr` = 全帧；给了就必须是正宽高**且整体落在帧内**
 * —— 半截区域在帧外时"帧外的像素是什么"没有答案，不许静默裁剪成"只看得到的那半"
 * （那会让脚本以为扫过全区域）。越界一律 IMG_ERR_INVALID_PARAM，不是"没找到"。
 *
 * 为什么提出来：找色已经这么判了，而**下一个要区域的算子（裁剪）会需要同一个判据** ——
 * 抄一份就意味着两处判据能漂移（一处宽严不一，脚本按 A 算出来的坐标在 B 上越界）。
 * 2026-09-25 兑现：`imgnative_crop` 直接复用本函数，没有第二份判据（找色与裁剪对
 * "区域越界"的回答是同一个码、同一个边界口径 —— `rx + rw == cols` 合法）。
 * 返回 false 时 `out` 不动。
 */
bool resolve_region(const cv::Mat& frame, const int32_t* region, cv::Rect* out) {
    if (region == nullptr) {
        *out = cv::Rect(0, 0, frame.cols, frame.rows);
        return true;
    }
    const int32_t rx = region[0], ry = region[1], rw = region[2], rh = region[3];
    if (rw <= 0 || rh <= 0 || rx < 0 || ry < 0 ||
        rx + rw > frame.cols || ry + rh > frame.rows) {
        return false;
    }
    *out = cv::Rect(static_cast<int>(rx), static_cast<int>(ry),
                    static_cast<int>(rw), static_cast<int>(rh));
    return true;
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
        // IMREAD_UNCHANGED（不是 IMREAD_COLOR）：保留下游要判定的 A 通道。
        // 合同写的是四分量 [r,g,b,a]，而 IMREAD_COLOR 会把任何来源一律压成
        // **3 通道 BGR**（alpha 被丢掉）—— 那后面 findColor 的 a 分量就只能
        // 拿固定值糊过去，等于契约里有一个分量从来不参与判定。
        cv::Mat mat = cv::imread(p, cv::IMREAD_UNCHANGED);
        if (mat.empty()) return IMG_ERR_IO;

        // 归一成 4 通道 BGRA：3 通道补一个恒 255 的 alpha（不透明，符合
        // "没存 alpha 的图就是全不透明"的常识），1 通道铺成三份同值 + 255，
        // 16 位深压回 8 位（契约分量域是 [0,255]）。已经在 4 通道 8 位的那一档
        // **一个字节都不动**（截图/PNG 主路径不付转换代价）。
        //
        // 【2026-09-25 实测更正】此处原先写着"浅拷贝优先 —— cvtColor 需要连续内存，
        // 先归置再转"，两句都不对，host 侧实测过：
        //   - cvtColor **能**吃非连续视图（`big(Rect)` 出的 3×2 子图 step=24、
        //     isContinuous=0，BGRA2GRAY 照常出正确值），不存在"必须先归置"；
        //   - 真正要小心的是**就地形式**（`cvtColor(m, m, …)`）：同通道数的转换会
        //     **写穿到父矩阵的缓冲**（实测 `cvtColor(view, view, BGR2RGB)` 把
        //     big(1,1) 从 10,20,30 改成了 30,20,10），只有改通道数时才另开缓冲。
        // 本函数不受影响（imread 给的是自有连续块），但**后续按 ROI 产出帧的算子
        // （crop/rotate 那一类）别在视图上就地 cvtColor** —— 那会静默改掉源帧，
        // 与 imgnative_gray 注释里"产出新帧不改原帧"是同一条纪律。
        if (mat.depth() != CV_8U) {
            cv::Mat narrowed;
            mat.convertTo(narrowed, CV_8U, mat.depth() == CV_16U ? 1.0 / 256.0 : 1.0);
            mat = narrowed;
        }
        if (mat.channels() == 4) {
            // 已在目标形态：可能非连续（ROI/子矩阵），这里 decode 出来的是整帧，
            // imread 给的就是连续块，无需处理。
        } else if (mat.channels() == 3) {
            cv::cvtColor(mat, mat, cv::COLOR_BGR2BGRA);
        } else if (mat.channels() == 1) {
            cv::cvtColor(mat, mat, cv::COLOR_GRAY2BGRA);
        } else {
            return IMG_ERR_IO;   // 通道数不在已知集合内：不猜着补
        }

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

// ── gray：取灰度信息面到**新的一帧**（§9.2 管线里的"灰度"）。
//
// **桥面此刻不对脚本开这个方法**（`:domain ImageAnalyzer` 五方法里没有它，
// `ImagesNamespaceHandler` 也不认 `toGrayscale`）：显式转换在脚本侧是可选操作
// —— 模板匹配/findColor 都在 native 内部按需处理通道，独独灰度没有消费方。
// 先落计算核 + host 语义门（判读对不对先钉住），桥面等真出现消费方再开；
// 这与 §9.2「没有脚本消费方之前不开桥面」是同一条纪律，不是落了一半。
//
// 为什么在新帧上产出而不是就地改灰度：帧表里这一帧被谁引用过、脚本手上
// 还有没有它的句柄，本层不知道 —— 就地改会让先前读到的帧内容在背后变化。
// 脚本拿到的句柄本来就"不是快照"（§7.4 既有语义），但"哪个算子会不会动我
// 这帧"不该让脚本去猜：**产出新帧 + 自己 release 旧帧**是显式的，代价看得见。
// （同理，产出帧与原帧各自独立在场面表，任一 release 不影响另一个。）
//
// 产出帧仍是 **4 通道 BGRA**：灰度值铺在三份，**alpha 原样带过去**。
//   - 铺三份而不是单通道：帧表的不变式是"在场帧恒 4 通道 8 位"（见
//     frame_is_normalized），单通道产出会让后续 matchTemplate/findColor 的
//     Vec4b 回读越字节读 —— 这不是洁癖，是那条不变式的直接后果；顺带
//     findColor 的 r=g=b=v 判定在灰度帧上照常成立。
//   - alpha 保留而不是一律 255：灰度压掉的是**色彩信息**，透明与否不是色彩。
//     decode 特意用 IMREAD_UNCHANGED 保住 A 就是为了让 a 分量参与判定
//     （那是一次真事故：alpha 从来没参与过判定），在灰度这一步把它抹平等于
//     把事故重新引回来 —— 脚本 toGray 之后按 a 找色会静默换答案。
//
// 灰度权重用库的 COLOR_BGRA2GRAY（0.299R+0.587G+0.114B，从 BGRA 直取 BGR），
// **不自己写系数**：系数是契约外的事实，抄一份就多一个漂移面，将来两处不一致
// 时没人能说清哪个对。实测值已钉进 host 测试（纯红 76 / 纯绿 150 / 纯蓝 29，
// 平均法会给 85/85/85 —— 那组断言就是用来分开"用了权重"与"用了平均"的）。
//
// 宽高出参随帧一起回（与 imgnative_decode 同形）：灰度不改尺寸，但契约里
// `ImageFrame` 是三元组（句柄 + 宽 + 高），让下游从"源帧尺寸"自己推是**猜** ——
// 尺寸的真值只有产出这帧的地方知道，多传两个 int32 比多一处约定便宜。
//
// 拒收是**早退**：失败时三个出参一个字节都不写（与 imgnative_match 同口径）——
// 调用方只能凭 status 判，别拿 out_ref 的残留值当帧号用。
int imgnative_gray(int64_t frame, int64_t* out_ref, int32_t* out_w, int32_t* out_h) {
    if (out_ref == nullptr || out_w == nullptr || out_h == nullptr) return IMG_ERR_INVALID_PARAM;
    try {
        const std::lock_guard<std::mutex> lk(g_mu);
        const cv::Mat* f = find_locked(frame);
        if (f == nullptr) return IMG_ERR_STALE_HANDLE;
        if (!frame_is_normalized(*f)) return IMG_ERR_IO;

        cv::Mat g;
        cv::cvtColor(*f, g, cv::COLOR_BGRA2GRAY);

        cv::Mat out(g.rows, g.cols, CV_8UC4);
        for (int y = 0; y < g.rows; ++y) {
            const unsigned char* gs = g.ptr<unsigned char>(y);
            const cv::Vec4b* sa = f->ptr<cv::Vec4b>(y);
            cv::Vec4b* dst = out.ptr<cv::Vec4b>(y);
            for (int x = 0; x < g.cols; ++x) {
                dst[x] = cv::Vec4b(gs[x], gs[x], gs[x], sa[x][3]);
            }
        }

        const int64_t ref = g_next_ref++;
        auto [it, _] = g_frames.emplace(ref, std::move(out));
        *out_ref = ref;
        *out_w = it->second.cols;
        *out_h = it->second.rows;
        return IMG_OK;
    } catch (const cv::Exception&) {
        return IMG_ERR_IO;
    }
}

// ── crop：按区域取子图到**新的一帧**（§9.2 管线里的"裁剪"）。
//
// **桥面此刻同样不对脚本开这个方法**（`:domain ImageAnalyzer` 五方法里没有它，
// `ImagesNamespaceHandler` 也不认 `crop`）：与 imgnative_gray 同一条纪律 ——
// 先落计算核 + host 语义门，等真出现消费方再开桥面。裁剪在脚本侧本来也是
// 可选操作：找色已经能在 `region` 上直接限定范围（读路径不产出帧），
// 只有"要把子图当独立一帧反复用/当模板"时才需要它。
//
// 为什么必须是新帧、不能返回一个视图：帧表是**所有权表**（unordered_map 持
// `Mat` 自己的缓冲），不是视图表。返回 `(*f)(roi)` 这个浅视图的话，源帧一
// release，脚本手里的"子图"就成了悬垂引用 —— 而它看起来完全正常（尺寸对、
// 前几帧读得出值），只在源帧被放掉之后才出错。产出帧必须自己持有像素，
// 所以这里是**拷贝**（clone），不是视图：裁剪的代价就应该是看得见的。
// （这不与 §9.2 的"0~1 拷贝"冲突：那条说的是**读**路径上的 ROI 浅视图
// ——imgnative_color 的 `view` 就是浅的；裁剪是**产出**，产出要独立。§7.7
// 也没有为 crop 承诺 0 拷贝。）
//
// 区域判据**复用 resolve_region**（找色那同一个函数，不是抄一份）：于是"越界"
// 在两处是同一个码、同一个边界口径（`rx + rw == cols` 合法 —— 贴边不算越界）。
// 上文把它提出来的理由就是这条：抄一份 = 两处判据能漂移，脚本按找色算出来的
// 坐标在裁剪上越界（或反过来）。
//
// 但 `region == nullptr` 在**本算子**是拒收（IMG_ERR_INVALID_PARAM），而不是
// 按 resolve_region 的缺省解释成"整帧"：裁剪的语义就是"取一个子区域"，
// 缺区域时唯一的自洽解释是"整帧拷贝"—— 那不是裁剪，是另一个算子（想要整帧
// 副本就明写整帧区域）。这条判据在 resolve_region **之上**（算子的入参契约），
// 不改区域合法性那一条；与 imgnative_color 的 `color == nullptr` 拒收同层。
//
// 产出帧仍是 4 通道 BGRA：裁剪不动通道数，源帧是归一帧（下面先问 frame_is_normalized）
// 则产出自动归一 —— 这条不变式是**保持**的，不是重新建立的，但必须显式判一次
// 源帧：帧表被塞进非归一帧时 clone 出来的子图也非归一，而下游的 Vec4b 回读
// 会静默读进下一行首字节（那类越字节读 2026-09-25 实测过）。所以不进像素先问。
//
// 宽高出参随帧一起回（与 decode/gray 同形）：尺寸真值只有产出这帧的地方知道，
// 让下游从源帧尺寸和 region 自己推是**猜**。
//
// 拒收是**早退**：失败时三个出参一个字节都不写（与 gray/match 同口径）——
// 调用方只能凭 status 判，别拿 out_ref 的残留值当帧号用。
int imgnative_crop(int64_t frame, const int32_t* region,
                   int64_t* out_ref, int32_t* out_w, int32_t* out_h) {
    if (out_ref == nullptr || out_w == nullptr || out_h == nullptr) return IMG_ERR_INVALID_PARAM;
    if (region == nullptr) return IMG_ERR_INVALID_PARAM;   // 见上：裁剪必须给区域
    try {
        const std::lock_guard<std::mutex> lk(g_mu);
        const cv::Mat* f = find_locked(frame);
        if (f == nullptr) return IMG_ERR_STALE_HANDLE;
        if (!frame_is_normalized(*f)) return IMG_ERR_IO;

        cv::Rect roi;
        if (!resolve_region(*f, region, &roi)) return IMG_ERR_INVALID_PARAM;

        // clone 而不是 `cv::Mat out = (*f)(roi);`：后者是浅视图（共享源帧缓冲），
        // 源帧 release 后即悬垂 —— 见上"为什么必须是新帧"。
        cv::Mat out = (*f)(roi).clone();
        if (out.empty()) return IMG_ERR_IO;   // 正常路径不可达（resolve_region 已保证 w/h > 0）

        const int64_t ref = g_next_ref++;
        auto [it, _] = g_frames.emplace(ref, std::move(out));
        *out_ref = ref;
        *out_w = it->second.cols;
        *out_h = it->second.rows;
        return IMG_OK;
    } catch (const cv::Exception&) {
        return IMG_ERR_IO;
    }
}

// ── resize：按目标尺寸缩放到**新的一帧**（§9.2 管线里的"缩放"）。
//
// **桥面此刻同样不对脚本开这个方法**（`:domain ImageAnalyzer` 五方法里没有它，
// `ImagesNamespaceHandler` 也不认 `resize`）：与 imgnative_gray/crop 同一条纪律 ——
// 先落计算核 + host 语义门，等真出现消费方再开桥面。缩放在脚本侧也是可选操作：
// 匹配的模板本来就是按屏上尺寸准备的（matchTemplate 要求模板 ≤ 画面），
// 只有"同一套模板要跑在不同分辨率设备上"时才需要它。
//
// 入参契约：要的是**目标尺寸**（`dst_w` × `dst_h`），不是缩放倍数 —— 倍数是调用方
// 从"源帧尺寸→目标"自己算出来的浮点，而尺寸的真值只有产出这帧的地方知道
// （与 gray/crop 的"宽高出参随帧一起回"是同一条纪律：让下游自己推是**猜**）。
// `dst_w`/`dst_h` 必须都是正数：0 或负数没有对应的像素网格（与 crop 的 w/h <= 0
// 拒收同层），拒收码同样是 IMG_ERR_INVALID_PARAM。
//
// 上限 16384：这不是 OpenCV 的限制，是本层的**配额** —— 16384×16384×4 ≈ 1GB，
// 再往上就是调用方笔误（比如把字节数当成了宽高）。配额拒收与"尺寸不合法"是
// 同一个码（调用方能做的事一样：换个尺寸再调），但注释里分开写，免得下一个人
// 以为 20000 是 OpenCV 吃不下。
//
// 插值固定 `INTER_LINEAR`（双线性），不做入参：NEAREST 在放大时是块状马赛克，
// 在 UI 元素（细线条/文字边缘）上丢信息；CUBIC/LANCZOS 更贵且在本管线的输入
// （截图/PNG 这类非照片）上没有可证的增益 —— 多一个入参就多一个"选错了静默换
// 答案"的漂移面。host 断言用"纯色帧任意缩放值不变"和"2×2 四角帧放大四角守恒"
// 两条把 LINEAR 的可观测行为钉住（NEAREST 在第二条上同样全过，所以另有一条
// "中心像素是混合值"专杀 NEAREST —— 换插值枚举必红其一）。
//
// 产出帧仍是 4 通道 BGRA：resize 不动通道数（INTER_LINEAR 在 4 通道上逐通道做），
// 源帧归一（下面先问 frame_is_normalized）则产出自动归一 —— 与 crop 同一条
// "保持而非重建"，判一次源帧。
//
// 同尺寸（dst == src 尺寸）是合法调用：语义是"拷一份同尺寸帧"，不走早退 ——
// 调用方不用先判"要不要调"，尺寸算出来一样就直调，结果仍是独立新帧。
//
// 拒收是**早退**：失败时三个出参一个字节都不写（与 gray/crop/match 同口径）。
int imgnative_resize(int64_t frame, int32_t dst_w, int32_t dst_h,
                     int64_t* out_ref, int32_t* out_w, int32_t* out_h) {
    if (out_ref == nullptr || out_w == nullptr || out_h == nullptr) return IMG_ERR_INVALID_PARAM;
    if (dst_w <= 0 || dst_h <= 0) return IMG_ERR_INVALID_PARAM;
    if (dst_w > 16384 || dst_h > 16384) return IMG_ERR_INVALID_PARAM;
    try {
        const std::lock_guard<std::mutex> lk(g_mu);
        const cv::Mat* f = find_locked(frame);
        if (f == nullptr) return IMG_ERR_STALE_HANDLE;
        if (!frame_is_normalized(*f)) return IMG_ERR_IO;

        cv::Mat out;
        cv::resize(*f, out, cv::Size(dst_w, dst_h), 0, 0, cv::INTER_LINEAR);
        if (out.empty()) return IMG_ERR_IO;   // 正常路径不可达（尺寸已判正）
        // resize 不动通道数：归一帧进、归一帧出 —— 断言一次，免得将来换插值/后端
        // 时静默掉通道（Vec4b 回读越字节读的教训见 decode 那段）。
        if (!frame_is_normalized(out)) return IMG_ERR_IO;

        const int64_t ref = g_next_ref++;
        auto [it, _] = g_frames.emplace(ref, std::move(out));
        *out_ref = ref;
        *out_w = it->second.cols;
        *out_h = it->second.rows;
        return IMG_OK;
    } catch (const cv::Exception&) {
        return IMG_ERR_IO;
    }
}

// ── color：在某帧（或其区域）里找"与目标色相近"的像素，回第一个命中的坐标。
//
// 未命中**不是错误也不是异常**：status 仍回 IMG_OK，而 out_x 留 -1（调用方据此
// 区分"扫过了、没有"与"出错"）—— 与 matchTemplate 的 out_match=0 同一套纪律。
//
// 颜色表示法（与桥面 :domain ImageAnalyzer.findColor 的 KDoc 逐字对齐）：
// 四个分量各 0..255，**顺序是加载方按字节序解释的 R,G,B,A**（Android Bitmap
// 的 int 像素常写作 0xAARRGGBB，脚本侧经 Bitmap 得到的也是这个序）——
// 这里不引入第二套坐标系，分量域由调用方（handler）先校验，本层兜底再拒。
//
// 容差 tolerance 是**每个分量各自**的允许偏差（非欧氏距离，也不是"整体平均"）：
// OpenCV 的 cv::inRange 本身就是逐分量上下界包含，我们只是把 [c-t, c+t]
// 交给它 —— 用现成语义而不是自己遍历像素，一是免得重写一遍（容易错），
// 二是kleidicv/carotene 对 inRange/cvtColor 有加速面，自己遍历就白拿了。
//
// 为什么不转换到 HSV 再比：hue 在 0/180 附近回绕、饱和度/明度区分"深红/浅红"
// 的语义都在这里容易出歧义，而 §7.7 的 10ms 预算要的是"子图里有没有这个色"。
// RGB 逐分量容差在 UI 定色这类场景（按钮/图标底色）才是脚本作者的心智模型。
//
// 区域 region：{x,y,w,h} 缺 null = 全帧；给了就必须整体落在帧内（越界即
// IMG_ERR_INVALID_PARAM —— 半截区域在帧外时"帧外的像素是什么"没有答案，
// 不能静默裁剪成"只看得到的那半"，那会让脚本以为扫过全区域）。
int imgnative_color(int64_t frame, const int32_t* color, int32_t tolerance,
                    const int32_t* region,
                    int32_t* out_x, int32_t* out_y,
                    int32_t* out_r, int32_t* out_g, int32_t* out_b, int32_t* out_a,
                    int64_t* out_scanned) {
    if (color == nullptr) return IMG_ERR_INVALID_PARAM;
    if (tolerance < 0 || tolerance > 255) return IMG_ERR_INVALID_PARAM;
    for (int i = 0; i < 4; ++i) {
        if (color[i] < 0 || color[i] > 255) return IMG_ERR_INVALID_PARAM;
    }

    try {
        const std::lock_guard<std::mutex> lk(g_mu);
        cv::Mat* f = find_locked(frame);
        if (f == nullptr) return IMG_ERR_STALE_HANDLE;

        // 帧恒 4 通道 8 位（decode 归一）；不成立 = 帧表被塞进了非归一帧
        // （帧表是本 TU 私有的，正常路径只经 decode/gray 进）—— 如实报 IO，
        // 不让下面 Vec4b 回读越字节读下一行的字节糊过去。
        //
        // 【2026-09-25 实测更正】这句原先问的是 **ROI 视图**（`frame_is_normalized(view)`），
        // 那是个形式主语：**视图的 channels()/depth() 与父矩阵同解**（父 3 通道的子图
        // 也是 3 通道 —— host 侧量过），所以视图级判定与帧级判定判的是同一个事实，
        // 并不存在"漏判"。真问题只在于**主语写法**：谓词的名字与注释都在谈"帧"，
        // 拿视图去问会让下一个人以为"视图会继承某种归一化"（它不会，也没这回事）。
        // 判据放在**源帧**上 —— 帧表不变式是关于帧表里的帧的，且它必须在 resolve_region
        // **之前**：非归一帧上连"某个 region 合不合法"都是另一套尺寸语义，
        // 先问帧、再量区域，顺序本身就是判据的一部分。
        if (!frame_is_normalized(*f)) return IMG_ERR_IO;

        cv::Rect roi;
        if (!resolve_region(*f, region, &roi)) return IMG_ERR_INVALID_PARAM;

        // ROI 是浅视图（共享 f 的数据，不拷贝像素 —— §9.2 的 0~1 拷贝）；
        // 索引域是**视图内** 0..rw/0..rh，命中坐标要加回 roi 左上角才是全帧坐标。
        const cv::Mat view = (*f)(roi);
        if (view.empty()) return IMG_ERR_INVALID_PARAM;

        // 下界/上界夹在 0..255：目标色贴边时（0 或 255）容差仍成立，不溢出成负数。
        // cv::Scalar 四个分量的顺序是 B,G,R,A（OpenCV 的通道序），所以 R/G/B 要
        // 按位对回去 —— 分量序只在 JNI 装载面解释一次，这里也要解释一次，两处
        // 注释互指，别只改一处。
        const unsigned char b = static_cast<unsigned char>(color[2]);
        const unsigned char g = static_cast<unsigned char>(color[1]);
        const unsigned char r = static_cast<unsigned char>(color[0]);
        const unsigned char a = static_cast<unsigned char>(color[3]);
        const int lo_b = b > tolerance ? b - tolerance : 0;
        const int hi_b = b + tolerance < 255 ? b + tolerance : 255;
        const int lo_g = g > tolerance ? g - tolerance : 0;
        const int hi_g = g + tolerance < 255 ? g + tolerance : 255;
        const int lo_r = r > tolerance ? r - tolerance : 0;
        const int hi_r = r + tolerance < 255 ? r + tolerance : 255;
        const int lo_a = a > tolerance ? a - tolerance : 0;
        const int hi_a = a + tolerance < 255 ? a + tolerance : 255;

        cv::Mat mask;
        cv::inRange(view, cv::Scalar(lo_b, lo_g, lo_r, lo_a),
                    cv::Scalar(hi_b, hi_g, hi_r, hi_a), mask);

        *out_scanned = static_cast<int64_t>(mask.total());
        if (cv::countNonZero(mask) == 0) {
            *out_x = IMG_MISS;   // 哨兵 = -1：扫过了、没有（答案，不是异常）
            return IMG_OK;
        }

        // findNonZero 给 N×1 的 (x,y) 点列（单通道 CV_32SC2）；只取第一个 ——
        // 没有命中（上面已判）与命中多个取哪个，是两个问题：多个命中时**回第一个
        // 不排序**（顺序 = OpenCV 点列的**列主序**：y 不变、x 从 0 扫到 w，
        // 再进下一行。这不是"离左上角最近"，严格说 (y=0,x=w-1) 会排在
        // (y=1,x=0) 前面 —— 稳定可复现就够了；按距离/面积排序会是另一套没在
        // 契约里出现的策略，脚本要自己再筛）。
        cv::Mat points;
        cv::findNonZero(mask, points);
        if (points.empty() || points.total() == 0) return IMG_ERR_IO;
        const cv::Point p = points.at<cv::Point>(0);
        // ROI 内的坐标加回 roi 左上角，让脚本拿到的是**全帧坐标**（与 matchTemplate
        // 的命中坐标同口径，不发明第二套坐标系）。
        // Vec4b 的通道序是 **B,G,R,A**（OpenCV 的三通道基序是 BGR），所以回包的
        // R/G/B 要从 2/1/0 号位取 —— 分量序只在这两处（Scalar 下界、Vec4b 回读）
        // 解释，各写一次且互指，别改成"顺手按 0,1,2 命名"。
        // 帧在 decode 时已归一成 4 通道（见上），所以这里的 Vec4b 取像素永不会
        // 读到第 4 通道以外的字节 —— 换成 3 通道帧它会静默读进下一行的首字节。
        const cv::Vec4b px = view.at<cv::Vec4b>(p);
        *out_x = roi.x + p.x;
        *out_y = roi.y + p.y;
        *out_r = static_cast<int32_t>(px[2]);
        *out_g = static_cast<int32_t>(px[1]);
        *out_b = static_cast<int32_t>(px[0]);
        *out_a = static_cast<int32_t>(px[3]);
        return IMG_OK;
    } catch (const cv::Exception&) {
        return IMG_ERR_IO;
    }
}

}  // extern "C"
