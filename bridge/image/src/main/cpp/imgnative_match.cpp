// bridge/image —— match 族（模板匹配：金字塔粗筛 + 原像素精配）
//
// 2026-10-01 D7 自 `imgnative.cpp` 拆出（同批拆出 feature 族；共享面的口子见
// `imgnative_internal.h`）。**语义逐字未改**：机制说明留在各函数自己的 KDoc 里，
// 拆分只搬位置 —— 三段大注释（金字塔路径 / 相位平均粗模板 / 计算出锁）跟着代码走。
//
// 本 TU 自持两张派生缓存（模板端 `NeedlePrep` / 场景端 `ScenePrep`）与那把缓存锁：
// 它们是**帧不可变**这一条不变式的派生物，帧没了就必须同批清掉 —— 失效钩子
// `imgnative::drop_prep_caches` 由帧表侧（`imgnative.cpp` 的 `imgnative_release`）
// 在擦掉条目后调用，锁序仍是「先 frame_mutex 再 cache_mu」，与拆分前一字不差。
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <mutex>
#include <unordered_map>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#include "imgnative_internal.h"

// 本 TU 用到的共享面（定义见 imgnative_internal.h / imgnative.cpp）。
using imgnative::contains_locked;
using imgnative::find_locked;
using imgnative::frame_mutex;
using imgnative::resolve_region;

namespace {

// 仅缓存“模板端”的粗筛准备结果。模板帧也是不可变的；A2/重复匹配场景里，
// 每次重做 cvtColor + resize + 相位平均没有信息增量。独立 cache 锁避免
// 为了这几个毫秒把 g_mu 的匹配段重新变成长锁。release 时同步清掉对应 ref。
// （原 phase_worst 相位探针分数字段 2026-10-02 随相位门一并移除：探针只服务
// 那道门，门没了留着是死代码 —— 见设计决策 24。）
struct NeedlePrep {
    double sc = 1.0;
    cv::Mat small;
    // 相位平均粗模板（2026-10-01）：粗筛**用它**互打，`small` 留作尺寸基准与
    // 构造失败时的回退（见 build_needle_prep 的「相位鲁棒粗模板」段）。
    cv::Mat small_avg;
};
std::mutex g_match_cache_mu;
std::unordered_map<int64_t, NeedlePrep> g_needle_prep;

// 场景端粗筛准备缓存（2026-10-01）：同一帧的 `cvtColor(BGRA2GRAY)` + `resize(0.25×)`
// 每次 match 都重做一遍，而**帧入表后不可变**（与上面 needle 那条不变式逐字同源：
// 「帧表纪律本身：帧入表后不可变」，见 imgnative_match 的「计算出锁」段）。
// 真机实测（1080×2400）：cvtColor ≈5.9ms + resize ≈1.6ms，A4 单次 24ms 里占 ~7.6ms；
// A2「一次截图两次匹配」正是这条缓存的消费方 —— 同一 haystack 跑两遍，第二遍白付。
//
// 只缓存**全帧**（region == nullptr）：region 是浅视图，其灰度化/缩小结果与"先全帧
// 再裁"在小尺度边界上有舍入差，缓存键要带着 region 走才等价 —— 那套账不值当，
// region 调用原样走现算路径（region 本来就是低延迟出路，见 imgnative_match 注）。
//
// sc 存在条目里：同一帧配不同模板可能落到 0.25×/0.5× 两档，档不对就重建覆盖
// （混用会抖动但不影响正确性 —— 结果只由 (帧, sc) 决定，不由缓存命中与否决定）。
struct ScenePrep {
    double sc = 1.0;
    cv::Mat hs;   // 灰度缩小图（全帧口径）
};
std::unordered_map<int64_t, ScenePrep> g_scene_prep;

// ── match 的两条路径与调参口（金字塔粗筛 + 原像素精配，2026-09-30 评审采纳）──

/** 一次匹配的判读：found = 命中，pos = 结果面左上角（= 模板在画面里的左上角）。 */
struct MatchHit {
    bool found;
    cv::Point pos;
    double conf;
};

// 可调常数：进程起始读环境变量，缺省与原 constexpr 逐字相同 —— **imgbench 调参口**：
// 云手机上扫这几个数不该重编 so（评审 2026-09-30 第 4 条：原常数是在合成屏上
// 每档 6 个样本调出来的，真机 sweep 比那组数字更有信息量）。
//   AUTOSCRIPT_MATCH_MIN_TEMPL_SIDE=48    模板短边低于此值 → 精确路径
//   AUTOSCRIPT_MATCH_MARGIN=0.10          粗筛候选带宽：提出 conf ≥ thr−margin 的峰
//   AUTOSCRIPT_MATCH_MAX_CANDIDATES=8     精配候选数下限（自适应 K 的地板，见 kKMax）
// 【2026-10-01 移除 `AUTOSCRIPT_MATCH_HEADROOM`】原 `floor = 带宽 + headroom`（0.05）
// 的意图是"保守一点"，但实测它把「够得着带宽、只是最差相位余量薄」的模板也挡回
// 精确路径 —— 300×150 全帧：带宽 0.75、相位 0.8086，floor 0.80 差 0.0086 被挡，
// 单次 473~1467ms。而**粗筛只负责提名**（候选还要过带宽、坐标/置信度回原图重算），
// 带宽之上再留余量是重复上保险，收益为 0、代价是一次全图精确匹配。现 floor == 带宽。
// 【2026-10-02】相位门（floor 落地的那道）整体移除，floor 不复存在；提名带宽仍
// 单源在 coarse_margin_of，负结果改由 match_pyramid 兜底回精确（设计决策 24）。
struct MatchTune {
    int min_templ_side;
    double margin;
    int max_candidates;
};

int env_int(const char* key, int dflt) {
    const char* v = std::getenv(key);
    if (v == nullptr || *v == '\0') return dflt;
    char* end = nullptr;
    const long x = std::strtol(v, &end, 10);
    return (end == v || *end != '\0') ? dflt : static_cast<int>(x);
}

double env_double(const char* key, double dflt) {
    const char* v = std::getenv(key);
    if (v == nullptr || *v == '\0') return dflt;
    char* end = nullptr;
    const double x = std::strtod(v, &end);
    return (end == v || *end != '\0') ? dflt : x;
}

const MatchTune& match_tune() {
    static const MatchTune t{
        env_int("AUTOSCRIPT_MATCH_MIN_TEMPL_SIDE", 48),
        env_double("AUTOSCRIPT_MATCH_MARGIN", 0.10),
        env_int("AUTOSCRIPT_MATCH_MAX_CANDIDATES", 8),
    };
    return t;
}

/** 某粗筛尺度下的候选带宽 —— 提名线单源（原兼作相位 floor 基准，2026-10-02
 *  相位门已拆，见设计决策 24）。 */
double coarse_margin_of(double sc) {
    return match_tune().margin + (sc <= 0.25 ? 0.05 : 0.0);
}

// 粗筛层模板短边最低对应 12px（再小的模板继续走精确路径）。与上面三个不同：
// 它不给调参口（合成屏调参时改过一次就够，扫它对真机决策无增量）。0.25× 对
// 48px 模板意味着 12px 短边 —— 48×48 这档正好进粗筛。
// 【2026-10-02 E2 拆两门】原 std 门（kMinTemplStd=12，挡纯色/平噪）与相位门
// （phase_worst ≥ floor，挡「粗尺度够不着提名线」的模板）一并移除：两门的职责
// （防假漏检）由 match_pyramid 负结果**回全图精确路径的兜底**结构性接管 ——
// 门挡对了省一次全图 matchTemplate，挡错了赔一次假漏检；兜底把「挡错」的代价
// 封顶为「多付一次粗筛」。判据/实测见 docs/design/07-bridge.md §7.7 的
// 2026-10-02 段与 docs/design-decisions.md 第 24 项。
constexpr double kMinCoarseSide = 12.0;
// 相位平均模板的重采样参数：B = 反射填充边宽（毫像素模板的一圈“假邻域”，
// 让粗图带上下文）。原相位探针（门的度量，扫 {1,2,3}² 各相位取最差分）
// 2026-10-02 已随门移除。
constexpr int kPhaseBorder = 12;
// 自适应 K（评审二轮）：精配总像素预算固定，窗面积小 → 放更多候选（≤kKMax）。
// 对症重复峰：K=8 时 12 枚等价图标的真峰排第 9 就出局；窗小的时候多提名几乎免费。
constexpr double kRefineBudget = 8.0 * 96 * 398;
constexpr int kKMax = 32;
constexpr int kPadk = 3;   // 精配窗 pad = ceil(1/sc)·3+2（比 2 多留一档量化余量）

/** 精确路径 = 原行为原语义（全图 TM_CCOEFF_NORMED + 取最大）。h 可以是 ROI 视图。 */
MatchHit match_exact(const cv::Mat& h, const cv::Mat& n, double thr) {
    cv::Mat r;
    cv::matchTemplate(h, n, r, cv::TM_CCOEFF_NORMED);
    double mx = 0.0;
    cv::Point loc;
    cv::minMaxLoc(r, nullptr, &mx, nullptr, &loc);
    // 「≥ 阈值即命中」与旧实现的 `maxv < threshold → 未命中` 同一判据。
    return {mx >= thr, loc, mx};
}

/**
 * 相位重采样（相位平均模板的取材原语，2026-10-01 起）：模板按粗筛栅格的各相位
 * 做反射填充 → 缩小，供 build_phase_avg 对齐取平均。0.25× 采 {0..3}² 十六个
 * 相位（覆盖 0.25/0.5/0.75/1 四种错位），0.5× 只有半格相位。
 * （原相位探针 phase_probe —— 各相位自打取最差分，服务相位门 —— 2026-10-02
 * 随门一并移除，见设计决策 24。）
 */
cv::Mat phase_resample(const cv::Mat& gray, double sc, int dx, int dy) {
    cv::Mat c;
    cv::copyMakeBorder(gray, c, kPhaseBorder + dy, kPhaseBorder - dy + 4,
                       kPhaseBorder + dx, kPhaseBorder - dx + 4, cv::BORDER_REFLECT);
    cv::Mat cs;
    cv::resize(c, cs, cv::Size(), sc, sc, cv::INTER_AREA);
    return cs;
}

/** 相位 (dx,dy) 下「模板内容原点」在重采样图中的整数坐标（= 相位平均的对齐基准）。 */
inline int phase_origin(double sc, int d) {
    return static_cast<int>(std::lround((kPhaseBorder + d) * sc));
}

/**
 * 相位平均粗模板（2026-10-01，治「大模板全帧恒精确 1.4s」）—— 相位探针的同源推广。
 *
 * **测出来的病灶与一般直觉相反，先记下来**：粗筛的假 miss 不是粗模板"太好"，而是
 * 粗模板**只对住了一个相位**。现状粗模板 = `resize(gray, sc)` 相位 0，等于赌"场景里
 * 模板也落在相位 0"。真机实测（1080×2400，同一份 OpenCV）真位置各相位粗分：
 *
 *   300×150 @0.25   1.0000 / 0.9007 / **0.6926** / 0.8943   （相位 0/1/2/3）
 *   300×150 @0.50   1.0000 / 0.8269 / ……                     ← 两个被采样的相位
 *
 * 相位 0 是 1.0（自匹配），可场景里的模板落在哪个相位是**未知的 nuisance 参数**：
 * thr=0.9 时带宽 0.75，0.6926 够不着 → 提名层直接空手 → 回精确路径 473~1467ms。
 * 相位探针之所以"准"正是因为它诚实地报出了这个最差相位；问题不在门，在**模板**。
 *
 * 修法 = 匹配滤波的标准解法：对 nuisance 参数做**平均**，而不是压成一个点。
 * 把 16 个相位的粗图按内容原点对齐后取平均当粗模板 —— 场景无论落在哪个相位，
 * 与这个"平均模板"的归一化互相关都不低于各相位分的中位附近（实测最差相位
 * 0.6926 → 0.8086，抬 0.12；0.5× 档 0.8269 → 0.9514）。
 *
 * **风险与界的诚实交代**（探针 /tmp/ffix/pavg.cpp 给出）：
 *   * 平均对**对齐/结构起支配**的模板（UI/文字/图标）抬相位地板；对 i.i.d. 高频
 *     噪声模板（case6c 那种）会把结构抹平 —— 但那类模板粗筛本来就给不出可信
 *     提名（相位 0 自匹配虚高 1.0，任何相位错位都塌），平均只是把"虚高 1.0"
 *     换成"诚实的低分"。2026-10-02 起门已拆、负结果一律回精确兜底（设计决策
 *     24），低分的后果从「假漏检」变成「多付一次粗筛」，不会制造假中。
 *   * 粗分整体下移（best 1.0 → 0.97）：带宽是**绝对**阈值，所以提名会略保守；
 *     精配仍按原 4 通道窗重算，报出的数字与精确路径一字不差（差分门逐字段钉）。
 */
cv::Mat build_phase_avg(const cv::Mat& gray, double sc, const cv::Size& out_size) {
    // 尺寸取 `small`（相位 0 的 resize 产物）而不是自己 round(gray.cols*sc)：
    // cv::resize 的目标尺寸用 cvRound（.5 向偶），lround（.5 远离零）会差 1 像素
    // —— 粗模板与粗场景的尺寸口径必须是同一个（370×80 @0.25：92 vs 93）。
    const int tw = out_size.width, th = out_size.height;
    const int np = (sc <= 0.25) ? 4 : 2;   // 0.25× 采 {0,1,2,3}²；0.5× 只有半格相位 {0,1}²
    cv::Mat acc = cv::Mat::zeros(th, tw, CV_32F);
    int n = 0;
    for (int dx = 0; dx < np; ++dx) {
        for (int dy = 0; dy < np; ++dy) {
            const cv::Mat cs = phase_resample(gray, sc, dx, dy);
            const int ox = phase_origin(sc, dx), oy = phase_origin(sc, dy);
            const cv::Rect r(ox, oy, tw, th);
            if (r.x < 0 || r.y < 0 || r.br().x > cs.cols || r.br().y > cs.rows) continue;
            acc += cs(r);
            ++n;
        }
    }
    if (n == 0) return cv::Mat();
    cv::Mat out;
    acc.convertTo(out, CV_8U, 1.0 / n);
    return out;
}

/** 金字塔缩放：0.25×（首选，省 16× 像素）/ 0.5×（模板短边撑不起 0.25 时）/ 1.0 = 不走粗筛。 */
NeedlePrep build_needle_prep(const cv::Mat& n) {
    const MatchTune& t = match_tune();
    NeedlePrep out;
    const int m = std::min(n.cols, n.rows);
    // 48px 是新的粗筛下限：
    //   * 48×48 -> 0.25× = 12×12；
    //   * <48px 仍走精确路径，避免继续把模板压成个位数像素。
    // 0.25× 的候选只负责“提名”，位置/置信度最终仍回原图精配。
    if (m < t.min_templ_side) return out;

    cv::Mat gray;
    cv::cvtColor(n, gray, cv::COLOR_BGRA2GRAY);

    double sc = 1.0;
    if (m * 0.25 >= kMinCoarseSide) sc = 0.25;
    else if (m * 0.5 >= kMinCoarseSide) sc = 0.5;
    if (sc >= 1.0) {
        return out;
    }

    cv::resize(gray, out.small, cv::Size(), sc, sc, cv::INTER_AREA);
    // 粗筛用相位平均模板（见 build_phase_avg）；构造失败（尺寸不齐）退回相位 0
    // 的 small —— 粗筛仍能跑，只是回到「赌相位 0」的旧行为。
    out.small_avg = build_phase_avg(gray, sc, out.small.size());
    if (out.small_avg.empty()) out.small_avg = out.small;
    out.sc = sc;
    // 只保留粗筛真正需要的缩小模板；灰度原图不需要跨调用保存。
    return out;
}

NeedlePrep get_needle_prep(int64_t needle, const cv::Mat& n) {
    {
        const std::lock_guard<std::mutex> lk(g_match_cache_mu);
        auto it = g_needle_prep.find(needle);
        if (it != g_needle_prep.end()) return it->second;
    }
    NeedlePrep built = build_needle_prep(n);
    // release() 与这里使用同样的锁顺序：先 g_mu，再 cache_mu。这样不会把一个已经
    // 释放的 ref 重新插回缓存，也不会和 release 形成锁顺序反转。
    {
        const std::lock_guard<std::mutex> lk(frame_mutex());
        if (!contains_locked(needle)) return built;
        const std::lock_guard<std::mutex> ck(g_match_cache_mu);
        auto [it, inserted] = g_needle_prep.emplace(needle, built);
        return it->second;
    }
}

/**
 * 全帧场景端粗筛图（灰度 0.25×/0.5×）—— 命中即复用，未命中现算并按 (ref) 缓存。
 *
 * 与 `get_needle_prep` 同一套纪律（同两把锁、同顺序、同 release 清理）：帧不可变
 * 是这条缓存的**全部依据**，所以它和模板端缓存写在同一个地方、用同一个失效钩子。
 * 缓存的量只由 (帧, sc) 决定 —— 「命中与否」不改变结果，只改变谁付这笔钱。
 */
cv::Mat get_scene_prep(int64_t haystack, const cv::Mat& h, double sc) {
    {
        const std::lock_guard<std::mutex> lk(g_match_cache_mu);
        auto it = g_scene_prep.find(haystack);
        if (it != g_scene_prep.end() && it->second.sc == sc && !it->second.hs.empty()) {
            return it->second.hs;
        }
    }
    cv::Mat hg, hs;
    cv::cvtColor(h, hg, cv::COLOR_BGRA2GRAY);
    cv::resize(hg, hs, cv::Size(), sc, sc, cv::INTER_AREA);
    // 与 get_needle_prep 同锁序（先 g_mu 再 cache_mu）：不相识的 ref 不入表。
    {
        const std::lock_guard<std::mutex> lk(frame_mutex());
        if (!contains_locked(haystack)) return hs;
        const std::lock_guard<std::mutex> ck(g_match_cache_mu);
        g_scene_prep[haystack] = ScenePrep{sc, hs};   // 换 sc 即覆盖（大小只由 (帧,sc) 定）
        return hs;
    }
}

/**
 * 金字塔路径：灰度缩小图上只**提名**候选，报出去的坐标/置信度全部回到**原图
 * 4 通道**小窗里重算 —— 阈值与置信度语义因此与精确路径一字不差（这是与「灰度
 * 上直接匹配」的本质区别：那条会漂移答案，评审已否）。
 *
 * 候选提名：conf ≥ thr−margin 的前 K 个峰，NMS 半径 = 粗模板边长一半（重复列表
 * 行会产生近等高的一排峰，不压会把 K 个名额全占了）。提名不到峰 / 精配后仍无
 * 过阈值候选 → **回全图精确路径兜底**（2026-10-02 E2）：假 miss 从「语料统计
 * 能不能抓住」变成**结构上不可能**，代价是「图上没有」这类负结果多付一次粗筛
 * （约 0.1× 全图成本）。原「快速 miss 不回退」是拿假漏检风险换负结果延迟，已
 * 随相位/std 门一并退役（设计决策 24）。host 差分双跑门仍逐字段对拍：同一输入
 * 强制精确 vs 本路径，必须同命中/同位置/置信度 ≤2e-3。
 */
// `use_scene_cache` = 全帧调用（region == nullptr）才允许走场景缓存：region 是浅视图，
// 它的灰度/缩小与"先全帧再裁"在小尺度边界上有舍入差，缓存键得带 region 才等价。
MatchHit match_pyramid(const cv::Mat& h, const cv::Mat& n, double thr, const NeedlePrep& prep,
                       int64_t haystack, bool use_scene_cache) {
    const MatchTune& t = match_tune();
    const double sc = prep.sc;
    cv::Mat hs = use_scene_cache ? get_scene_prep(haystack, h, sc) : cv::Mat();
    if (hs.empty()) {
        cv::Mat hg;
        cv::cvtColor(h, hg, cv::COLOR_BGRA2GRAY);
        cv::resize(hg, hs, cv::Size(), sc, sc, cv::INTER_AREA);
    }
    // 粗筛模板 = 相位平均版（见 build_phase_avg）：提名层对「场景落在哪个子像素
    // 相位」不再赌 0。尺寸与 small 同解。
    const cv::Mat& ns = prep.small_avg.empty() ? prep.small : prep.small_avg;
    if (ns.cols > hs.cols || ns.rows > hs.rows) return match_exact(h, n, thr);

    cv::Mat r;
    cv::matchTemplate(hs, ns, r, cv::TM_CCOEFF_NORMED);

    // 精配窗：粗坐标除以缩放比映回原图（截断误差 < 1/sc 原图像素 + resize 舍入），
    // pad 取 ceil(1/sc)·kPadk+2 盖住这两项（kPadk=3 比 2 多留一档量化余量）。
    // 窗是视图不拷像素；裁边后宽度恒 ≥ 模板边（左右对称收缩），不会触发断言。
    // 非常量：FastPath（见下）在「唯一高置信」时把 pad 收窄一档；win_area/K 仍按
    // 常态 pad 算（精配预算按保守窗估，与收窄无关）。
    int pad = static_cast<int>(std::ceil(1.0 / sc)) * kPadk + 2;

    // Top-K + NMS：迭代取全局峰 → 局部窗置 −1 → 取下一个（重复 UI 行/图标格
    // 会给出一排近等高峰，NMS 不压则名额被同一个小区域占满）。K 按精配预算自适应
    // （评审二轮）：窗小（小模板）→ 放到 kKMax；总精配像素量恒 ≤ kRefineBudget。
    // 0.25× 时量化/插值会压低 coarse conf；多放 0.05 的候选带宽只会增加少量精配
    // 候选，不改变最终原图判定。
    const double coarse_margin = coarse_margin_of(sc);
    const double win_area = static_cast<double>(n.cols + 2 * pad) * (n.rows + 2 * pad);
    const int K = std::min(kKMax,
                           std::max(t.max_candidates,
                                    static_cast<int>(std::lround(kRefineBudget / win_area))));
    std::vector<cv::Point> cands;
    double first_conf = 0.0;
    const int supp = std::max(ns.cols, ns.rows) / 2 + 1;
    const cv::Rect bounds(0, 0, r.cols, r.rows);
    for (int k = 0; k < K; ++k) {
        double mx = 0.0;
        cv::Point loc;
        cv::minMaxLoc(r, nullptr, &mx, nullptr, &loc);
        if (mx < thr - coarse_margin) break;
        if (cands.empty()) first_conf = mx;
        cands.push_back(loc);
        r(cv::Rect(loc.x - supp, loc.y - supp, 2 * supp + 1, 2 * supp + 1) & bounds)
            .setTo(-1.0f);
    }

    // FastPath（12a，2026-10-01）：粗筛提名**唯一** + **高置信** → 精配窗收窄一档。
    //   唯一性 = NMS 后全图只有一个过带宽候选（cands.size()==1 已含「第二个峰过
    //   不了带宽」，探针 370×80：peak1=0.979、NMS 后 peak2=0.728 < 带宽 0.75）。
    //   高置信 = 主峰 ≥ thr + 0.05（0.25× 量化余量；370×80 实测粗峰 0.979 vs
    //   带宽 0.75，余量 0.23 远足）。常态 pad 给次峰余量，唯一候选不需要：
    //   收窄后的 pad = ceil(1/sc)·1+2 仍盖住粗坐标回映误差（< 1/sc 原图像素）+
    //   resize 舍入。**不变式**：精配仍在原 4 通道窗内重算，只改「窗多大」不改
    //   「报什么」—— 差分门对 fast 路径逐字段比对（位置 + |Δconf| ≤ 2e-3）。
    //   收窄 pad 的 CCOEFF 归一化分母随窗缩略有变化，conf 微移；真机 370×80 实测
    //   两条路径 conf 均 1.0000 同位、坐标逐像素相同（饱和区，漂移 < 1e-4）。
    //   与 kPadk=3 的关系：常态 pad = ceil(1/sc)·kPadk+2 = 14（0.25× 档，上面已
    //   声明；本函数 rebase 到相位门基线之前，常态是 ceil(1/sc)·2+2 = 10）；
    //   FastPath 的收窄 pad = ceil(1/sc)·1+2 = 6 是「唯一高置信候选」专用的一档，
    //   其余情形不变。收窄的绝对量因此比首版更大（14→6，而非 10→6）。
    if (cands.size() == 1 && first_conf >= thr + 0.05) {
        pad = static_cast<int>(std::ceil(1.0 / sc)) * 1 + 2;
    }
    const cv::Rect frame(0, 0, h.cols, h.rows);
    MatchHit best{false, {0, 0}, -1.0};
    for (const cv::Point& c : cands) {
        const int x = std::min(static_cast<int>(c.x / sc), h.cols - n.cols);
        const int y = std::min(static_cast<int>(c.y / sc), h.rows - n.rows);
        cv::Rect win(x - pad, y - pad, n.cols + 2 * pad, n.rows + 2 * pad);
        win &= frame;
        const MatchHit hit = match_exact(h(win), n, thr);
        const cv::Point p(hit.pos.x + win.x, hit.pos.y + win.y);
        // 并列取行先序在前者 —— 对齐全图 minMaxLoc 的「结果面行先序」口径。
        const bool better = hit.conf > best.conf + 1e-6 ||
            (std::abs(hit.conf - best.conf) <= 1e-6 && best.conf >= 0 &&
             (p.y < best.pos.y || (p.y == best.pos.y && p.x < best.pos.x)));
        if (better) best = {hit.conf >= thr, p, hit.conf};
    }
    // 负结果兜底（2026-10-02 E2）：粗筛提名没够着阈值 ≠ 图上没有 —— 回全图
    // 精确路径拿权威答案。命中 → 报出（与强制精确同解，差分门逐字段钉）；仍
    // 不中 → 同一个未命中，只是多付了一次粗筛。
    return best.conf >= thr ? best : match_exact(h, n, thr);
}

}  // namespace

namespace imgnative {

/** 见头文件：release 擦掉帧条目后调它，把两张派生缓存按 ref 清掉。 */
void drop_prep_caches(int64_t ref) {
    const std::lock_guard<std::mutex> ck(g_match_cache_mu);
    g_needle_prep.erase(ref);
    g_scene_prep.erase(ref);   // 场景端缓存与模板端缓存同一个失效钩子（帧没了图也没了）
}

}  // namespace imgnative

extern "C" {

// ── match：模板匹配。threshold ∈ [0,1]（域校验归桥面 handler，此处不再放宽）
// 返回 IMG_OK 时看 *out_match：0 = 未达阈值（**未匹配是答案不是异常**），
// 1 = 命中且 x/y/w/h/confidence 已填。句柄不在场 → IMG_ERR_STALE_HANDLE。
//
// **计算出锁（2026-09-30）**：互斥量只盖帧表查找那一小段 —— 浅拷贝两个 cv::Mat
// （引用计数 +1，缓冲区不随 map 变动释放）就放锁，matchTemplate 的几百毫秒在
// 锁外跑。依据是帧表纪律本身：帧**入表后不可变**（十个算子全部"产出新帧不改
// 原帧"，见 imgnative_gray 段），锁外读到的像素与锁内指向同一份不变的数据；
// 并发 release 只动 map，浅拷贝照活。收益：一次 900ms 级 match 不再把
// release/findColor 全关在锁后。STALE 判据一字未动：仍在锁内、先于任何计算。
//
// **金字塔粗筛 + 原像素精配**（助手在匿名命名空间，机制见 match_pyramid 注）：
// 直接全图 TM_CCOEFF_NORMED 是 933ms（§7.7 实测），粗筛把像素量降 16×；报出
// 去的坐标/置信度全部回原图 4 通道小窗重算，阈值与置信度语义一字不动。
// kill switch = AUTOSCRIPT_MATCH_FORCE_EXACT=1（现场回退阀）/ g_force_exact。
//
// **region 可选搜索范围（2026-09-30 评审第 2 条）**：nullptr = 全帧；给了 =
// [x,y,w,h] 复用 findColor/crop 的 resolve_region 判据（越界 → IMG_ERR_INVALID_PARAM，
// 不静默裁剪）。两条错误码分界：region 形状/越界 = 参数错（4）；**region 比模板小
// = IMG_ERR_IO(3)** —— 与"模板比画面大"同属"参数关系不成立"（§7.7 已记）。
// 命中坐标恒**全帧口径**（区域只是搜索范围不是坐标系，与 findColor 的
// roi.x + p.x 同一条）。小模板（48×48 这类原先进不了粗筛）的提速出路就是它：
// 搜索窗缩到 300×150 后，全图 2.6M 像素的频谱开销按窗面积缩水。
int imgnative_match(int64_t haystack, int64_t needle, double threshold,
                    const int32_t* region,
                    int32_t* out_x, int32_t* out_y,
                    int32_t* out_w, int32_t* out_h,
                    double* out_conf, int32_t* out_match) {
    try {
        cv::Mat h, n;
        {
            const std::lock_guard<std::mutex> lk(frame_mutex());
            cv::Mat* hp = find_locked(haystack);
            cv::Mat* np = find_locked(needle);
            if (hp == nullptr || np == nullptr) return IMG_ERR_STALE_HANDLE;
            // 浅拷贝 = 只搬 Mat 头（引用计数 +1），像素缓冲不复制。
            h = *hp;
            n = *np;
        }

        cv::Point origin(0, 0);
        bool full_frame = true;
        if (region != nullptr) {
            cv::Rect roi;
            if (!resolve_region(h, region, &roi)) return IMG_ERR_INVALID_PARAM;
            h = h(roi);          // 浅视图，不拷像素
            origin = roi.tl();   // 命中坐标加回全帧口径
            full_frame = false;
        }

        // 模板比画面（或所选 region）大：opencv matchTemplate 会直接断言失败，
        // 先一步按 IO 错答（这是"参数关系不成立"，不是"图上没有"）。
        if (n.cols > h.cols || n.rows > h.rows) return IMG_ERR_IO;

        const bool force_exact = g_force_exact.load(std::memory_order_relaxed);
        const NeedlePrep prep = force_exact ? NeedlePrep{} : get_needle_prep(needle, n);
        // 【2026-10-02 E2】原相位门（phase_worst ≥ floor）与 std 门已拆 —— 职责
        // 移交 match_pyramid 负结果回精确路径的兜底：走粗筛最坏 = 多付一次粗筛
        // 拿同一答案；挡回 = 白付一次全图 matchTemplate（实测 473~1500ms）还没
        // 解决问题。粗筛仍只**提名**，坐标/置信度回原 4 通道窗重算，阈值语义与
        // 精确路径一字不差（差分门 host_match_test 逐字段对拍）。尺寸门还在：
        // prep.sc < 1.0 才走粗筛（见 build_needle_prep），小于门限的模板直落
        // 精确路径。
        const bool use_pyramid = !force_exact && prep.sc < 1.0;
        const MatchHit hit = use_pyramid ? match_pyramid(h, n, threshold, prep, haystack, full_frame)
                                         : match_exact(h, n, threshold);
        if (!hit.found) {
            *out_match = 0;
            *out_x = *out_y = *out_w = *out_h = 0;
            *out_conf = 0.0;
            return IMG_OK;
        }
        *out_match = 1;
        *out_x = hit.pos.x + origin.x;   // 全帧坐标（region 只是搜索范围）
        *out_y = hit.pos.y + origin.y;
        *out_w = n.cols;   // 模板在画面里被匹配上的区域尺寸（= 模板尺寸）
        *out_h = n.rows;
        *out_conf = hit.conf;
        return IMG_OK;
    } catch (const cv::Exception&) {
        return IMG_ERR_IO;
    }
}

}  // extern "C"
