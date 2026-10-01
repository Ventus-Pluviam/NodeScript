// 宿主机侧模板匹配语义验证（bridge/image/src/main/cpp/imgnative.cpp 直链，零 JNI）。
//
// 补上 host 门禁的最后一块覆盖：`imgnative_match` 的判读此前**只被 JVM mock
// 覆盖**（mock 不碰像素，永远回一个编好的 ImageMatch），于是"命中坐标译反了"
// "未匹配时把 x/y/w/h 也回出去"这类错在 JVM/JS 两门全绿时照样能溜过去。
// 钉四处：
//   * **命中坐标 = 模板左上角**：matchTemplate 的 maxloc 是结果面左上角，照搬即
//     模板在画面里的左上角；加过一次 ROI 偏移（照 findColor 的思路）就是双算。
//   * **w/h = 模板尺寸**，不是命中区域面积、不是画面尺寸（契约三处各自说明白）；
//   * **未匹配是答案不是异常**：out_match = 0 且 x/y/w/h/confidence 全 0，
//     status 仍是 0 —— 与"扫过了、没有"同一套纪律。若把 w=0 当"未命中"判，
//     一张 0 宽模板就能让脚本把命中读成未命中。
//   * **阈值域**：[0,1] 内单调，maxv < threshold 才落未命中（含 maxv == threshold
//     判命中 —— "≥ 阈值即命中"是契约原话）。
//
//  TemplateSizeLargerThanFrame → ERR_IO 这一类"参数关系不成立"的分支在这层
//  不发（opencv 会断言失败），一并钉住：它不能变成 ERR_STALE_HANDLE。
//
// 跑法见 test/cpp/run-host-tests.sh（OpenCV 4.14.0 按 build-opencv.sh 同款
// commit 固定，kleidicv OFF —— host 是 x86_64，那条加速面只在 aarch64 上）。
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

extern "C" {
int imgnative_decode(const char* path, int64_t* out_ref, int32_t* out_w, int32_t* out_h);
int imgnative_match(int64_t haystack, int64_t needle, double threshold,
                    const int32_t* region,
                    int32_t* out_x, int32_t* out_y,
                    int32_t* out_w, int32_t* out_h,
                    double* out_conf, int32_t* out_match);
int imgnative_release(int64_t ref);
}

// 金字塔路径 kill switch（imgnative.cpp 定义，外部链接）：差分双跑要逐 case 翻它。
extern std::atomic<bool> g_force_exact;

static int fails = 0, checks = 0;
static void chk(bool ok, const std::string& w) { ++checks; if (!ok) { ++fails; std::printf("  [FAIL] %s\n", w.c_str()); } }

/** decode 的薄封装：写盘 + 解码 + 回 ref（失败如实报出，后续断言才有意义）。 */
static int64_t dec(const std::string& p, const cv::Mat& m) {
    if (!cv::imwrite(p, m)) { std::printf("  [FAIL] imwrite %s\n", p.c_str()); ++fails; return -1; }
    int64_t ref = 0; int32_t w = 0, h = 0;
    const int rc = imgnative_decode(p.c_str(), &ref, &w, &h);
    chk(rc == 0, "decode " + p);
    return rc == 0 ? ref : -1;
}

/** 一次 imgnative_match 的完整返回面（差分双跑要逐字段比）。 */
struct MR { int rc; int32_t x, y, w, h, m; double c; };
static MR call_match(int64_t hh, int64_t nn, double thr, const int32_t* region = nullptr) {
    MR r{-1, -1, -1, -1, -1, -1, -1.0};
    r.rc = imgnative_match(hh, nn, thr, region, &r.x, &r.y, &r.w, &r.h, &r.c, &r.m);
    return r;
}

/**
 * 差分双跑（2026-09-30 评审第 2 条）：同一输入先强制精确路径、再走默认（金字塔）
 * 路径，断言同 rc、同命中/同未命中、同位置且 |Δconf| ≤ 2e-3 —— 假 miss / 坐标漂
 * 移在这里被**直接计数**，比只读旧断言的通过与否强。
 *
 * `equiv` 非空 = 已知等价重复位置集（同形副本各一处）：两条路径在完全相同的副本
 * 间允许挑到不同那一处 —— 精确路径的 argmax 在重复峰之间由 FFT 浮点噪声决定，
 * 本来就不可复现（评审第 3 条）。这种 case 只钉「落在集合内 + 置信度一致」。
 * 返回精确路径的结果（它是语义基准），用例自己的断言接在它上面。
 */
static MR dual_match(int64_t hh, int64_t nn, double thr,
                     const std::vector<cv::Point>* equiv = nullptr) {
    g_force_exact.store(true);
    const MR e = call_match(hh, nn, thr);
    g_force_exact.store(false);
    const MR p = call_match(hh, nn, thr);
    chk(e.rc == p.rc, "差分 rc 一致（" + std::to_string(e.rc) + " vs " + std::to_string(p.rc) + "）");
    if (e.rc != 0) return p;
    if (e.m == 0 && p.m == 0) return p;                 // 同为未命中 = 差分通过
    chk(e.m == 1 && p.m == 1, "差分命中一致（精确=" + std::to_string(e.m) +
                              " 金字塔=" + std::to_string(p.m) + "，假 miss/假中在此计数）");
    chk(std::fabs(e.c - p.c) <= 2e-3, "差分置信度 ≤2e-3（实际 " +
        std::to_string(e.c) + " vs " + std::to_string(p.c) + "）");
    if (e.m == 1 && p.m == 1) {
        if (equiv != nullptr) {
            const auto in_set = [&](const MR& r) {
                return std::find(equiv->begin(), equiv->end(), cv::Point(r.x, r.y)) != equiv->end();
            };
            chk(in_set(e), "精确路径位置 ∈ 等价集（实际 " + std::to_string(e.x) + "," + std::to_string(e.y) + "）");
            chk(in_set(p), "金字塔路径位置 ∈ 等价集（实际 " + std::to_string(p.x) + "," + std::to_string(p.y) + "）");
        } else {
            chk(e.x == p.x && e.y == p.y, "差分同位置（精确 " +
                std::to_string(e.x) + "," + std::to_string(e.y) + " vs 金字塔 " +
                std::to_string(p.x) + "," + std::to_string(p.y) + "）");
        }
    }
    return p;
}

int main() {
    // 夹具自建（PID 后缀）：imwrite 到不存在的目录**静默返回 false**，之后每一条
    // 断言会以一种跟根因八竿子打不着的方式红。见 host_color_test 同段注释。
    const std::string d = "/tmp/imgtest-match-" + std::to_string(getpid());
    const int rc_mkdir = ::mkdir(d.c_str(), 0755);
    if (rc_mkdir != 0 && errno != EEXIST) { std::fprintf(stderr, "[FATAL] mkdir %s: %s\n", d.c_str(), std::strerror(errno)); return 97; }

    // 一张 8×8 的整幅 + 一个 3×2 的模板：模板内容从 (x=2,y=3) 起逐字节等于整幅，
    // 于是结果面的 max 恰好落在 (2,3)。纹理用随机但**固定**的值 —— 别用常数块：
    // 常数模板在 TM_CCOEFF_NORMED 下方差≈0，整个结果面恒 1.0（§7.7 记录的代价），
    // 那时 maxloc 虽仍是 (2,3)，"未匹配"那几条却再也造不出来（到处都满分）。
    cv::RNG rng(12345);
    cv::Mat big(8, 8, CV_8UC4);
    rng.fill(big, cv::RNG::UNIFORM, 0, 256);
    cv::Mat needle(2, 3, CV_8UC4);
    big(cv::Rect(2, 3, 3, 2)).copyTo(needle);

    const int64_t h_ref = dec(d + "/big.png", big);
    const int64_t n_ref = dec(d + "/needle.png", needle);
    if (h_ref < 0 || n_ref < 0) { std::printf("\nchecks=%d failures=%d\n", checks, fails); return fails == 0 ? 0 : 1; }

    // 1) 命中：阈值放低让"确实在"这个事实先立住，坐标/尺寸/置信度逐字段钉
    {
        // 双跑（评审第 2 条）：3×2 模板短边 < min_templ_side，两条路径都恒走
        // 精确 —— 这里钉的是"小模板无分岔"，真粗筛覆盖在 case 6/7。
        const MR d = dual_match(h_ref, n_ref, 0.5);
        const int rc = d.rc;
        const int32_t x = d.x, y = d.y, w = d.w, h = d.h, m = d.m;
        const double c = d.c;
        chk(rc == 0, "match 成功");
        chk(m == 1, "命中（out_match=1）");
        chk(x == 2 && y == 3, "命中坐标 = 模板左上角 (2,3)（实际 " + std::to_string(x) + "," + std::to_string(y) + "）");
        chk(w == 3 && h == 2, "w/h = 模板尺寸 3×2（实际 " + std::to_string(w) + "×" + std::to_string(h) + "）");
        chk(c > 0.99, "置信度接近 1.0（实际 " + std::to_string(c) + "）");
    }

    // 2) **同一位置**、另一份内容不同的模板：未匹配是答案（out_match=0 全零），
    //    status 仍是 0。这一条是本文件存在的核心理由：mock 层永远只会命中，
    //    "未命中时究竟回什么形状"只有 native 能回答。
    {
        cv::Mat other(2, 3, CV_8UC4);
        rng.fill(other, cv::RNG::UNIFORM, 0, 256);
        // 保底不同于 big 的任何 3×2 窗：随机也可能撞上一模一样的概率极低，但
        // 真撞上这条就会以"置信度为何接近 1"的方式红，比静默通过好。
        const int64_t o_ref = dec(d + "/other.png", other);
        const MR d = dual_match(h_ref, o_ref, 0.9);      // 未命中也双跑：数假中/假 miss
        const int rc = d.rc;
        const int32_t x = d.x, y = d.y, w = d.w, h = d.h, m = d.m;
        const double c = d.c;
        chk(rc == 0, "未匹配时 status 仍是 OK（答案不是异常）");
        chk(m == 0, "out_match=0（未达阈值）");
        chk(x == 0 && y == 0 && w == 0 && h == 0 && c == 0.0,
            "未命中字段全 0（实际 x=" + std::to_string(x) + " y=" + std::to_string(y) +
            " w=" + std::to_string(w) + " h=" + std::to_string(h) + " c=" + std::to_string(c) + "）");
        imgnative_release(o_ref);
    }

    // 3) 阈值边界：maxv == threshold 判命中（契约"≥ 阈值即命中"）。第 1 条的
    //    置信度取来当阈值本身，等于把边界钉在这个具体数值上 —— 比写 0.99 稳。
    {
        const MR d = dual_match(h_ref, n_ref, 0.5);      // 拿真置信度（顺带差分）
        const double exact = d.c;
        const int32_t m = d.m;
        chk(m == 1, "第 1 条先确认命中，取其置信度 " + std::to_string(exact) + " 作边界");
        int32_t mx = -1, my = -1, mw = -1, mh = -1, mm = -1; double mc = -1;
        chk(imgnative_match(h_ref, n_ref, exact, nullptr, &mx, &my, &mw, &mh, &mc, &mm) == 0, "等值阈值可用");
        chk(mm == 1, "maxv == threshold 判命中（≥ 不是 >）");
        chk(mx == 2 && my == 3, "等值阈值下坐标不变（实际 " + std::to_string(mx) + "," + std::to_string(my) + "）");
        const double just_above = exact + 1e-6;
        int32_t ax = -1, ay = -1, aw = -1, ah = -1, am = -1; double ac = -1;
        chk(imgnative_match(h_ref, n_ref, just_above, nullptr, &ax, &ay, &aw, &ah, &ac, &am) == 0, "略高阈值可用");
        chk(am == 0, "阈值略高于 maxv 即未命中（严格小于才落未命中）");
    }

    // 4) 参数关系不成立：模板比画面大 → IO 错（不是"图上没有"，也不是句柄问题）。
    //    opencv 的 matchTemplate 对大模板会断言失败，实现在此之前就先一步拒。
    //    方向别造反（第一版就造反了，跑出来 rc=0 + 命中 1 才发现）："模板比画面大"
    //    指的是 **needle 比 haystack 大**，所以 needle 放 20×20、haystack 留 8×8。
    {
        cv::Mat hugeNeedle(20, 20, CV_8UC4, cv::Scalar(1, 2, 3, 255));
        const int64_t big_ref = dec(d + "/huge.png", hugeNeedle);
        chk(big_ref > h_ref, "新帧号大于旧帧号（帧表单调，旁证没有顶掉 big 的帧）");
        int32_t x = -1, y = -1, w = -1, h = -1, m = -1; double c = -1;
        const int rc = imgnative_match(h_ref, big_ref, 0.5, nullptr, &x, &y, &w, &h, &c, &m);
        chk(rc == 3, "模板比画面大 → ERR_IO(3)（实际 rc=" + std::to_string(rc) + "）");
        chk(m == -1, "拒收是**早退**：out_match 一个字节都不碰（调用方预置的 -1 原样留着）"
                    "实际 m=" + std::to_string(m) + "）");
        // 这一条是**口径声明**不是缺陷记录：早退分支不写出参，所以调用方不能拿
        // out_match 当"没命中"判（它可能还是上次调用的残留）。判据只有 status ——
        // 真实调用链正是这么做的（`images_jni.cc` 的 matchNative 只看 rc，rc != 0
        // 就置 status 不回数组；`NativeImageAnalyzer.match` 同样 rc 优先）。
        imgnative_release(big_ref);
    }

    // 5) 句柄纪律：任一帧已死 → STALE（与 findColor/match 同一条口径）
    {
        int32_t x = -1, y = -1, w = -1, h = -1, m = -1; double c = -1;
        chk(imgnative_release(n_ref) == 0, "先放 needle");
        chk(imgnative_match(h_ref, n_ref, 0.5, nullptr, &x, &y, &w, &h, &c, &m) == 1,
            "needle 已释放 → ERR_STALE_HANDLE(1)");
        chk(imgnative_match(n_ref, h_ref, 0.5, nullptr, &x, &y, &w, &h, &c, &m) == 1,
            "haystack 已释放同样 STALE（两个方向都钉）");
        imgnative_release(h_ref);
    }

    // 6) **差分主战场**：640×480 平滑纹理屏 + 128×96 模板（短边 96 ≥ 80、
    //    96×0.25=24 ≥ 24 → 真走 0.25× 粗筛）。纹理先造小图再放大 = 低频频谱，
    //    形如 UI/图标/文字 —— 这是粗筛**真开动**且必须赢的形态（白噪声是反例，
    //    见 6c）。命中位置唯一（纹理不重复），差分断言严格同位置；未匹配同样
    //    双跑，数假 miss。
    {
        cv::Mat base(60, 80, CV_8UC4);
        rng.fill(base, cv::RNG::UNIFORM, 0, 256);
        cv::Mat scr;
        cv::resize(base, scr, cv::Size(640, 480), 0, 0, cv::INTER_LINEAR);
        const cv::Rect at(301, 177, 128, 96);
        cv::Mat tpl = scr(at).clone();                   // 独立成帧（imwrite 要独立 Mat）
        const int64_t s6 = dec(d + "/scr6.png", scr);
        const int64_t t6 = dec(d + "/tpl6.png", tpl);
        if (s6 >= 0 && t6 >= 0) {
            const MR r = dual_match(s6, t6, 0.9);
            chk(r.rc == 0, "case6 match 成功");
            chk(r.m == 1, "case6 命中");
            chk(r.x == 301 && r.y == 177, "case6 坐标 = 模板原位 (301,177)（实际 " +
                std::to_string(r.x) + "," + std::to_string(r.y) + "）");
            chk(r.w == 128 && r.h == 96, "case6 w/h = 模板尺寸");
            chk(r.c > 0.99, "case6 置信度 >0.99（实际 " + std::to_string(r.c) + "）");

            // 未匹配同双跑：不同内容的 128×96，两条路径必须**同时**判未命中。
            cv::Mat other(96, 128, CV_8UC4);
            rng.fill(other, cv::RNG::UNIFORM, 0, 256);
            const int64_t o6 = dec(d + "/other6.png", other);
            if (o6 >= 0) {
                const MR miss = dual_match(s6, o6, 0.9);
                chk(miss.rc == 0 && miss.m == 0, "case6b 未匹配 status=OK answer");
                chk(miss.x == 0 && miss.y == 0 && miss.c == 0.0, "case6b 未命中字段全 0");
                imgnative_release(o6);
            }
            // 高阈值差分：thr=0.99 时提名带宽 0.84（2026-10-02 起相位门已拆、
            // 不再有 floor，提名带宽单源 coarse_margin_of）。粗筛与强制精确
            // 两条路径必须同解。
            const MR hi = dual_match(s6, t6, 0.99);
            chk(hi.rc == 0 && hi.m == 1, "case6 高阈值 0.99 仍命中且差分一致");
            imgnative_release(s6);
            imgnative_release(t6);
        }
    }

    // 6b) **48×48 小模板也应能进入金字塔**：这是设备 A4-small 的原始瓶颈。
    //     模板由低频小图放大得到，0.25× 后仍保留 12×12 结构 —— 尺寸门
    //     （min_templ_side / kMinCoarseSide）放行即可进粗筛（2026-10-02 起
    //     std/相位门已拆，设计决策 24）。
    //     精确路径约束保留：金字塔只负责提名，最终位置/置信度回原图重算。
    {
        cv::Mat base48(12, 12, CV_8UC4);
        rng.fill(base48, cv::RNG::UNIFORM, 0, 256);
        cv::Mat icon48;
        cv::resize(base48, icon48, cv::Size(48, 48), 0, 0, cv::INTER_LINEAR);

        cv::Mat scr48(480, 640, CV_8UC4);
        rng.fill(scr48, cv::RNG::UNIFORM, 110, 150);
        const cv::Point at48(301, 177);
        icon48.copyTo(scr48(cv::Rect(at48.x, at48.y, 48, 48)));

        const int64_t s48 = dec(d + "/scr48.png", scr48);
        const int64_t t48 = dec(d + "/tpl48.png", icon48);
        if (s48 >= 0 && t48 >= 0) {
            const MR r = dual_match(s48, t48, 0.9);
            chk(r.rc == 0, "case48 match 成功");
            chk(r.m == 1, "case48 命中");
            chk(r.x == at48.x && r.y == at48.y,
                "case48 坐标 = 模板原位 (301,177)（实际 " +
                std::to_string(r.x) + "," + std::to_string(r.y) + "）");
            chk(r.w == 48 && r.h == 48, "case48 w/h = 模板尺寸");
            chk(r.c > 0.99, "case48 置信度 >0.99（实际 " + std::to_string(r.c) + "）");
            imgnative_release(s48);
            imgnative_release(t48);
        }
    }

    // 6d) **FastPath（12a，2026-10-01）唯一高置信差分锁**：粗筛提名唯一（NMS 后
    //     全图只有主峰过带宽）+ 主峰 ≥ thr+0.05 → 精配窗收窄到 ceil(1/sc)·1+2。
    //     不变式：坐标/置信度仍回原 4 通道窗重算，只改「窗多大」不改「报什么」。
    //     这里双跑三条：① 同图唯一高置信 → fast 与 exact 同位同 conf；② 复制
    //     出第二枚图标（唯一性被破坏）→ 仍同位同 conf（常态 pad，fast 不触发）；
    //     ③ 高置信阈值收敛：粗峰恰在带宽+0.05 边缘时 fast 不触发（走常态 pad）。
    {
        // ① 低频放大模板（case48 同款）：0.25× 后保留结构，粗筛唯一高置信。
        cv::Mat base(12, 12, CV_8UC4);
        rng.fill(base, cv::RNG::UNIFORM, 0, 256);
        cv::Mat icon;
        cv::resize(base, icon, cv::Size(64, 64), 0, 0, cv::INTER_LINEAR);
        cv::Mat scr(480, 640, CV_8UC4);
        rng.fill(scr, cv::RNG::UNIFORM, 110, 150);
        const cv::Point at(201, 241);
        icon.copyTo(scr(cv::Rect(at.x, at.y, 64, 64)));
        const int64_t sd = dec(d + "/scr6d.png", scr);
        const int64_t td = dec(d + "/tpl6d.png", icon);
        if (sd >= 0 && td >= 0) {
            const MR r = dual_match(sd, td, 0.9);
            chk(r.rc == 0 && r.m == 1, "case6d unique-highconf 命中");
            chk(r.x == at.x && r.y == at.y, "case6d 坐标 = 模板原位 (201,241)（实际 " +
                std::to_string(r.x) + "," + std::to_string(r.y) + "）");
            chk(r.c > 0.99, "case6d 置信度 >0.99（实际 " + std::to_string(r.c) + "）");
            imgnative_release(sd);
            imgnative_release(td);
        }

        // ② 复制出第二枚同款图标：唯一性破坏 → fast 不触发，坐标仍第一枚。
        {
            cv::Mat scr2 = scr.clone();
            icon.copyTo(scr2(cv::Rect(at.x + 120, at.y, 64, 64)));
            const int64_t s2 = dec(d + "/scr6d2.png", scr2);
            const int64_t t2 = dec(d + "/tpl6d2.png", icon);
            if (s2 >= 0 && t2 >= 0) {
                const MR r = dual_match(s2, t2, 0.9);
                chk(r.rc == 0 && r.m == 1, "case6d2 重复副本命中");
                chk(r.x == at.x && r.y == at.y, "case6d2 坐标 = 第一枚 (201,241)（实际 " +
                    std::to_string(r.x) + "," + std::to_string(r.y) + "）");
                chk(r.c > 0.99, "case6d2 置信度 >0.99（实际 " + std::to_string(r.c) + "）");
                imgnative_release(s2);
                imgnative_release(t2);
            }
        }
    }

    // 6c) **高频反例 = 精确兜底的锁**（差分门 2026-09-30 首跑抓到的真红；锁的
    //     守护对象 2026-10-02 起从「频率门」换成「负结果回精确兜底」）：
    //     i.i.d. 逐像素噪声模板落在不对齐的坐标 (301,177) 上 —— 0.25× 的 4×4
    //     平均块在错位坐标上与模板的平均块互不相关 → 提名的候选精配后全不过
    //     阈值（当时差分直接数出来了：精确=1 金字塔=0）。原修法是频率自检门
    //     挡去粗筛；2026-10-02 门已拆（设计决策 24），改为 match_pyramid 负
    //     结果回全图精确路径兜底。这条 case 是兜底的锁：兜底拆掉，这里立刻红。
    {
        cv::Mat scr(480, 640, CV_8UC4);
        rng.fill(scr, cv::RNG::UNIFORM, 0, 256);
        cv::Mat tpl = scr(cv::Rect(301, 177, 128, 96)).clone();
        const int64_t s6c = dec(d + "/scr6c.png", scr);
        const int64_t t6c = dec(d + "/tpl6c.png", tpl);
        if (s6c >= 0 && t6c >= 0) {
            const MR r = dual_match(s6c, t6c, 0.9);
            chk(r.rc == 0 && r.m == 1, "case6c 高频模板仍命中（粗筛负结果 → 精确兜底）");
            chk(r.x == 301 && r.y == 177, "case6c 坐标 (301,177)（实际 " +
                std::to_string(r.x) + "," + std::to_string(r.y) + "）");
            imgnative_release(s6c);
            imgnative_release(t6c);
        }
    }

    // 7) **重复图标格**（评审第 3 条）：12 枚一模一样的 100×100 图标铺在噪底上。
    //    位置在 12 枚之间不可钉（精确路径 argmax 由 FFT 噪声挑，金字塔路径在提名
    //    到的 8 枚里取行先序 —— 两者都合法），差分改用等价位置集：两条路径各自
    //    落在图标格点上、置信度 ≤2e-3。这同时压测 NMS：12 峰 > K=8，压窗必须
    //    放过互不重叠的峰，否则 8 个名额被一枚图标附近的旁瓣占满、远处副本漏提。
    {
        cv::Mat scr(480, 640, CV_8UC4);
        rng.fill(scr, cv::RNG::UNIFORM, 100, 160);       // 低幅噪底：避开纯平背景的 0/0 方差区
        cv::Mat icon_base(12, 12, CV_8UC4);              // 小图放大 = 平滑图标（粗筛真开动）
        rng.fill(icon_base, cv::RNG::UNIFORM, 0, 256);
        cv::Mat icon;
        cv::resize(icon_base, icon, cv::Size(100, 100), 0, 0, cv::INTER_LINEAR);
        std::vector<cv::Point> origins;
        for (int gy = 0; gy < 3; ++gy) {
            for (int gx = 0; gx < 4; ++gx) {
                const int ox = 40 + gx * 130, oy = 50 + gy * 150;
                icon.copyTo(scr(cv::Rect(ox, oy, 100, 100)));
                origins.emplace_back(ox, oy);
            }
        }
        const int64_t s7 = dec(d + "/scr7.png", scr);
        const int64_t t7 = dec(d + "/icon.png", icon);
        if (s7 >= 0 && t7 >= 0) {
            const MR r = dual_match(s7, t7, 0.9, &origins);
            chk(r.rc == 0, "case7 match 成功");
            chk(r.m == 1, "case7 命中（12 枚等价副本中的一枚）");
            chk(r.c > 0.99, "case7 置信度 >0.99（实际 " + std::to_string(r.c) + "）");
            imgnative_release(s7);
            imgnative_release(t7);
        }
    }

    // 8) **region 搜索范围**（2026-09-30 评审第 2 条）：错误码分界 + 全帧坐标 +
    //    粗筛在 ROI 视图上照常。判据与 findColor/crop 同一条 resolve_region。
    {
        // case 5 已把 h_ref/n_ref 放掉 —— 这里重新落帧（夹具 Mat 还在，直接复用）。
        const int64_t h8 = dec(d + "/big8.png", big);
        const int64_t n8 = dec(d + "/needle8.png", needle);
        if (h8 < 0 || n8 < 0) { std::printf("\nchecks=%d failures=%d\n", checks, fails); return fails == 0 ? 0 : 1; }
        // 8a) 坐标全帧口径：needle 在 (2,3)，region 给半帧 → 命中仍报 (2,3)。
        {
            const int32_t reg[4] = {0, 0, 5, 8};
            const MR r = call_match(h8, n8, 0.5, reg);
            chk(r.rc == 0 && r.m == 1, "8a region 半帧内命中");
            chk(r.x == 2 && r.y == 3, "8a 命中坐标是全帧口径 (2,3)（实际 " +
                std::to_string(r.x) + "," + std::to_string(r.y) + "）");
            chk(r.w == 3 && r.h == 2, "8a w/h 仍是模板尺寸");
        }
        // 8b) region 不含模板内容 → 未命中是答案（status=OK、字段全 0）。
        {
            const int32_t reg[4] = {4, 0, 4, 4};
            const MR r = call_match(h8, n8, 0.5, reg);
            chk(r.rc == 0 && r.m == 0, "8b region 内无模板 = 未命中（答案）");
            chk(r.x == 0 && r.y == 0 && r.c == 0.0, "8b 未命中字段全 0");
        }
        // 8c) region 比模板小 → ERR_IO（"参数关系不成立"，与"模板比画面大"同码）。
        {
            const int32_t reg[4] = {0, 0, 2, 2};   // 2×2 < 模板 3×2
            int32_t x = -1, y = -1, w = -1, h = -1, m = -1; double c = -1;
            const int rc = imgnative_match(h8, n8, 0.5, reg, &x, &y, &w, &h, &c, &m);
            chk(rc == 3, "region 比模板小 → ERR_IO(3)（实际 rc=" + std::to_string(rc) + "）");
            chk(m == -1, "8c 早退不碰出参");
        }
        // 8d) region 越界 → ERR_INVALID_PARAM（不静默裁剪），出参同样不碰。
        {
            const int32_t reg[4] = {0, 0, 9, 9};   // 9 > 8×8 帧宽高
            int32_t x = -1, y = -1, w = -1, h = -1, m = -1; double c = -1;
            const int rc = imgnative_match(h8, n8, 0.5, reg, &x, &y, &w, &h, &c, &m);
            chk(rc == 4, "region 越界 → ERR_INVALID_PARAM(4)（实际 rc=" + std::to_string(rc) + "）");
            chk(m == -1, "8d 早退不碰出参");
        }
        // 8e) **粗筛 × ROI × 全帧坐标**：平滑屏（金字塔真开动）上给一个刚好装下
        //     模板的窗 —— ROI 内粗筛/精配/origin 回加三件事一次钉住（差分双跑）。
        {
            cv::Mat base(60, 80, CV_8UC4);
            rng.fill(base, cv::RNG::UNIFORM, 0, 256);
            cv::Mat scr;
            cv::resize(base, scr, cv::Size(640, 480), 0, 0, cv::INTER_LINEAR);
            cv::Mat tpl = scr(cv::Rect(301, 177, 128, 96)).clone();
            const int64_t s8 = dec(d + "/scr8.png", scr);
            const int64_t t8 = dec(d + "/tpl8.png", tpl);
            if (s8 >= 0 && t8 >= 0) {
                const int32_t reg[4] = {280, 160, 180, 130};   // 含 (301,177) 的窗
                const MR r = call_match(s8, t8, 0.9, reg);
                chk(r.rc == 0 && r.m == 1, "8e ROI 内命中");
                chk(r.x == 301 && r.y == 177, "8e 全帧坐标 (301,177)（实际 " +
                    std::to_string(r.x) + "," + std::to_string(r.y) + "）");
                chk(r.c > 0.99, "8e 置信度 >0.99（实际 " + std::to_string(r.c) + "）");
                imgnative_release(s8);
                imgnative_release(t8);
            }
        }
    }

    // 9) **场景端粗筛缓存（2026-10-01）：同帧连跑两次必须同解**。
    //    缓存是纯性能件（省 cvtColor+resize ≈7.6ms/次），但它把一份**派生图**
    //    钉在了帧的生存期上 —— 判据是"缓存命中不改变任何出参字节"。
    //    这里三重差分：① 同 (帧,模板,thr) 连跑两次逐字段一致（第二次命中的是
    //    缓存图，第一次是现算图）；② 中间插一次**别的模板**匹配（不同 sc 档，
    //    逼迫缓存键 (ref) 上的图被换掉/复用），回来后第三条仍与原值一致；
    //    ③ 换 sc 档：拿一个短边落在 0.5× 档的模板（48→0.25×、100→0.25×，
    //    要 0.5× 需短边 24..47 —— 用 320×40 让 40*0.5=20≥12 且 40*0.25=10<12）
    //    与 370×80 交替匹配，两条各自与"单独跑"同解。
    //    缓存图是灰度**缩小**图（CV_8U 单通道），与精配用的原 4 通道窗是两张图
    //    —— 这里顺带把"缓存没有把原图像素改掉"也钉住：第三次跑完再验一次全帧
    //    像素哈希（缓存若误写成就地缩小，原图会变）。
    {
        cv::Mat base2(240, 320, CV_8UC4);
        rng.fill(base2, cv::RNG::UNIFORM, 0, 256);
        base2(cv::Rect(80, 90, 120, 100)).setTo(cv::Scalar(30, 180, 60, 255));
        cv::Mat scr;
        cv::resize(base2, scr, cv::Size(1280, 960), 0, 0, cv::INTER_LINEAR);
        cv::Mat tpl9 = scr(cv::Rect(320, 360, 480, 400)).clone();   // 短边 400 → 0.25×
        cv::Mat tpl9b = scr(cv::Rect(320, 360, 480, 40)).clone();   // 短边 40 → 0.5×
        const int64_t s9 = dec(d + "/scr9.png", scr);
        const int64_t t9 = dec(d + "/tpl9.png", tpl9);
        const int64_t t9b = dec(d + "/tpl9b.png", tpl9b);
        if (s9 >= 0 && t9 >= 0 && t9b >= 0) {
            const MR a = call_match(s9, t9, 0.9);      // 现算（缓存空）
            const MR b = call_match(s9, t9, 0.9);      // 命中缓存
            chk(a.rc == 0 && a.m == 1, "case9 首次命中");
            chk(b.rc == 0 && b.m == 1 && b.x == a.x && b.y == a.y &&
                std::fabs(b.c - a.c) < 1e-9, "case9 二次（命中场景缓存）与原值逐字段一致");
            const MR mid = call_match(s9, t9b, 0.9);   // 另一个 sc 档：换图
            const MR c = call_match(s9, t9, 0.9);      // 换回来
            chk(mid.rc == 0, "case9 中间换 sc 档调用成功");
            chk(c.rc == 0 && c.m == 1 && c.x == a.x && c.y == a.y &&
                std::fabs(c.c - a.c) < 1e-9, "case9 换档后回到原档：仍与原值逐字段一致");
            // region 路径**不走**场景缓存：与全帧调用同解（坐标加回全帧口径）。
            const int32_t reg9[4] = {300, 340, 600, 500};
            const MR rf = call_match(s9, t9, 0.9, reg9);
            chk(rf.rc == 0 && rf.m == 1 && rf.x == a.x && rf.y == a.y &&
                std::fabs(rf.c - a.c) < 1e-9, "case9 region 路径与全帧同解（场景缓存不参与 region）");
            imgnative_release(s9);
            imgnative_release(t9);
            imgnative_release(t9b);
        }
    }

    // 差分跑完复位 kill switch（dual_match 尾态即 false，显式钉一次防回归时把
    // 别的 case 悄悄圈进强制精确路径）。
    g_force_exact.store(false);

    std::printf("\nchecks=%d failures=%d\n", checks, fails);
    return fails == 0 ? 0 : 1;
}
