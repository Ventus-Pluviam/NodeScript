// 宿主机侧灰度算子语义验证（bridge/image/src/main/cpp/imgnative.cpp 直链，零 JNI）。
//
// `imgnative_gray` 是第一个**产出新帧**的算子（decode 之外头一回），所以这道门
// 除了钉灰度值本身，还要钉"产出新帧"这件事的边界 —— 后者是 JVM/JS 两门**结构上
// 看不见**的：mock 层只回一个自报的 ref，帧表是不是真多了一帧、原帧有没有被就地
// 改掉、两帧的释放是否互不牵连，只有真跑 native 才知道。
//
// 钉五处：
//   * **权重**：0.299R+0.587G+0.114B（库的 COLOR_BGRA2GRAY），不是平均法。
//     纯红 76 / 纯绿 150 / 纯蓝 29 —— 平均法是 85/85/85，那组断言就是用来把
//     这两种实现分开的（写错系数在这里必红）。
//   * **产出帧仍是 4 通道 BGRA**：帧表不变式（frame_is_normalized）。产出单通道
//     会让后续 matchTemplate/findColor 的 Vec4b 回读越字节读下一行。
//   * **alpha 原样带过去**：灰度压掉的是色彩信息，透明与否不是色彩。decode 用
//     IMREAD_UNCHANGED 保住 A 就是让 a 分量参与判定（真事故过），这步抹平等于
//     把事故引回来。
//   * **产出新帧不改原帧**：原帧 gray 前后内容一致、在原帧上匹配照常、两帧各自
//     独立 release（放一个不影响另一个 —— 帧表不是引用计数，是各自一条记录）。
//   * **拒收是早退**：句柄已死 → STALE 且 *out_ref 一个字节都不写（与 match 同口径）。
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

extern "C" {
int imgnative_decode(const char* path, int64_t* out_ref, int32_t* out_w, int32_t* out_h);
int imgnative_gray(int64_t frame, int64_t* out_ref, int32_t* out_w, int32_t* out_h);
int imgnative_color(int64_t frame, const int32_t* color, int32_t tolerance,
                    const int32_t* region,
                    int32_t* out_x, int32_t* out_y,
                    int32_t* out_r, int32_t* out_g, int32_t* out_b, int32_t* out_a,
                    int64_t* out_scanned);
int imgnative_release(int64_t ref);
}

static int fails = 0, checks = 0;
static void chk(bool ok, const std::string& w) { ++checks; if (!ok) { ++fails; std::printf("  [FAIL] %s\n", w.c_str()); } }

/** decode 的薄封装：写盘 + 解码 + 回 ref。 */
static int64_t dec(const std::string& p, const cv::Mat& m) {
    if (!cv::imwrite(p, m)) { std::printf("  [FAIL] imwrite %s\n", p.c_str()); ++fails; return -1; }
    int64_t ref = 0; int32_t w = 0, h = 0;
    const int rc = imgnative_decode(p.c_str(), &ref, &w, &h);
    chk(rc == 0, "decode " + p);
    return rc == 0 ? ref : -1;
}

/** 找色的一次调用打包（region 传 nullptr = 全帧）。 */
struct Hit { int rc = -1; int32_t x = -2, y = -2, r = -2, g = -2, b = -2, a = -2; int64_t scanned = -1; };
static Hit probe(int64_t ref, int32_t cr, int32_t cg, int32_t cb, int32_t ca, int32_t tol,
                 const int32_t* region = nullptr) {
    const int32_t color[4] = {cr, cg, cb, ca};
    Hit h;
    h.rc = imgnative_color(ref, color, tol, region, &h.x, &h.y, &h.r, &h.g, &h.b, &h.a, &h.scanned);
    return h;
}

int main() {
    const std::string d = "/tmp/imgtest-gray-" + std::to_string(getpid());
    const int rc_mkdir = ::mkdir(d.c_str(), 0755);
    if (rc_mkdir != 0 && errno != EEXIST) { std::fprintf(stderr, "[FATAL] mkdir %s: %s\n", d.c_str(), std::strerror(errno)); return 97; }

    // 1) 权重：纯红/纯绿/纯蓝三像素横排（帧里是 BGRA 序）。平均法会给 85/85/85，
    //    所以下面三条"找到 76/150/29"就是权重与平均的分界（外加一条找 85 必须
    //    未命中，正面否认平均法）。
    const int64_t rgb_ref = [&] {
        cv::Mat m(1, 3, CV_8UC4);
        m.at<cv::Vec4b>(0, 0) = cv::Vec4b(0, 0, 255, 255);     // 纯红
        m.at<cv::Vec4b>(0, 1) = cv::Vec4b(0, 255, 0, 255);     // 纯绿
        m.at<cv::Vec4b>(0, 2) = cv::Vec4b(255, 0, 0, 255);     // 纯蓝
        return dec(d + "/rgb.png", m);
    }();
    if (rgb_ref < 0) { std::printf("\nchecks=%d failures=%d\n", checks, fails); return fails == 0 ? 0 : 1; }

    int64_t gray_ref = -1;
    {
        int32_t gw = -1, gh = -1;
        const int rc = imgnative_gray(rgb_ref, &gray_ref, &gw, &gh);
        chk(rc == 0, "gray 成功（rc=" + std::to_string(rc) + "）");
        chk(gray_ref > rgb_ref, "产出新帧号大于原帧号（帧表单调，没有顶掉原帧）");
        chk(gw == 3 && gh == 1, "宽高随帧回（3×1，实际 " + std::to_string(gw) + "×" + std::to_string(gh) + "）");
    }
    {
        const Hit red = probe(gray_ref, 76, 76, 76, 255, 0);
        chk(red.rc == 0 && red.x == 0 && red.y == 0,
            "纯红 → 灰度 76 命中 (0,0)（实际 rc=" + std::to_string(red.rc) + " x=" + std::to_string(red.x) + "）");
        const Hit green = probe(gray_ref, 150, 150, 150, 255, 0);
        chk(green.rc == 0 && green.x == 1,
            "纯绿 → 灰度 150 命中 (1,0)（实际 " + std::to_string(green.x) + "," + std::to_string(green.y) + "）");
        const Hit blue = probe(gray_ref, 29, 29, 29, 255, 0);
        chk(blue.rc == 0 && blue.x == 2,
            "纯蓝 → 灰度 29 命中 (2,0)（实际 " + std::to_string(blue.x) + "," + std::to_string(blue.y) + "）");
        const Hit avg = probe(gray_ref, 85, 85, 85, 255, 0);
        chk(avg.rc == 0 && avg.x == -1,
            "平均法的 85 **未命中**（实际 x=" + std::to_string(avg.x) + "）—— 正面否认用了平均");
        // r=g=b 三份同值：找 (76,76,76) 命中的像素回包三分量也该相等
        chk(red.r == 76 && red.g == 76 && red.b == 76,
            "灰度帧 r=g=b=76（实际 " + std::to_string(red.r) + "," + std::to_string(red.g) + "," + std::to_string(red.b) + "）");
    }

    // 2) alpha 原样带过去（不是一律 255）。用两边不同的 a 造帧，灰度后各自还在。
    //    先探出灰度值再拿它做精确断言 —— 不硬编码系数，硬编码系数那件事已由第 1 段管。
    {
        cv::Mat m(1, 2, CV_8UC4);
        m.at<cv::Vec4b>(0, 0) = cv::Vec4b(30, 20, 10, 200);
        m.at<cv::Vec4b>(0, 1) = cv::Vec4b(30, 20, 10, 100);
        const int64_t a_ref = dec(d + "/alpha.png", m);
        int64_t ag_ref = -1;
        int32_t aw = -1, ah = -1;
        chk(imgnative_gray(a_ref, &ag_ref, &aw, &ah) == 0, "带 alpha 的帧 gray 成功");
        // 探：容差 255 拿第一个像素的实际分量（灰度值 v）
        const Hit any = probe(ag_ref, 0, 0, 0, 255, 255);
        chk(any.rc == 0 && any.x == 0, "探测命中首像素");
        const int32_t v = any.r;
        chk(any.r == any.g && any.g == any.b, "探测回的 r=g=b（灰度帧三份同值）");
        chk(any.a == 200, "首像素 alpha 原样保留 200（实际 " + std::to_string(any.a) + "）");
        const int32_t r0[4] = {1, 0, 1, 1};   // 只搜第二个像素（region = x,y,w,h）
        const Hit second = probe(ag_ref, v, v, v, 100, 0, r0);
        chk(second.rc == 0 && second.x == 1,
            "第二像素按 a=100 精确命中 (1,0)（实际 x=" + std::to_string(second.x) + " a=" + std::to_string(second.a) + "）");
        const Hit wrong = probe(ag_ref, v, v, v, 255, 0);
        chk(wrong.rc == 0 && wrong.x == -1,
            "a 未被抹成 255：目标 a=255 未命中（实际 x=" + std::to_string(wrong.x) + "）");
        imgnative_release(a_ref);
        imgnative_release(ag_ref);
    }

    // 3) 产出新帧不改原帧：原帧内容 gray 前后一致（纯红那格仍是 (255,0,0,255)）
    {
        const Hit still = probe(rgb_ref, 255, 0, 0, 255, 0);
        chk(still.rc == 0 && still.x == 0,
            "原帧未被就地改灰：纯红那格仍命中 (0,0)（实际 x=" + std::to_string(still.x) + "）");
    }

    // 4) 两帧独立在场：放掉灰度帧后原帧照常可用（帧表不是引用计数，各自一条记录）
    {
        chk(imgnative_release(gray_ref) == 0, "释放灰度帧");
        const Hit after = probe(rgb_ref, 255, 0, 0, 255, 0);
        chk(after.rc == 0 && after.x == 0, "释放产出帧后原帧仍可用");
        chk(imgnative_release(gray_ref) == 1, "再放灰度帧 → STALE（与 color/match 同口径）");
    }

    // 5) 灰度帧可以再灰度：r=g=b 的帧过一遍权重仍是同一个值（系数和为 1）。
    //    这条同时是"灰度帧确实是 4 通道"的旁证 —— 3 通道会让 cvtColor 的
    //    BGRA2GRAY 直接抛（被折叠成 IO 错），跑不到这里。
    {
        int64_t g2 = -1; int32_t sw2 = -1, sh2 = -1;
        const int rc = imgnative_gray(rgb_ref, &g2, &sw2, &sh2);
        chk(rc == 0, "对原帧再 gray 成功");
        int64_t g3 = -1; int32_t sw3 = -1, sh3 = -1;
        const int rc2 = imgnative_gray(g2, &g3, &sw3, &sh3);
        chk(rc2 == 0, "对灰度帧再 gray 成功（4 通道不变式成立）");
        const Hit h2 = probe(g2, 76, 76, 76, 255, 0);
        const Hit h3 = probe(g3, 76, 76, 76, 255, 0);
        chk(h2.rc == 0 && h3.rc == 0 && h3.x == h2.x,
            "二次灰度幂等：76 的位置不变（实际 " + std::to_string(h2.x) + " → " + std::to_string(h3.x) + "）");
        imgnative_release(g2);
        imgnative_release(g3);
    }

    // 6) 拒收是早退：句柄已死 → STALE 且 *out_ref 一个字节都不写
    {
        const int64_t dead = 99999;
        int64_t out = -1; int32_t ow = -1, oh = -1;
        chk(imgnative_gray(dead, &out, &ow, &oh) == 1, "从不存在的帧 gray → STALE_HANDLE(1)");
        chk(out == -1 && ow == -1 && oh == -1,
            "早退不写出参：三个出参都保持调用方预置的 -1（实际 " + std::to_string(out) + "）");
        chk(imgnative_release(rgb_ref) == 0, "收尾释放原帧");
        int64_t out2 = -1; int32_t ow2 = -1, oh2 = -1;
        chk(imgnative_gray(rgb_ref, &out2, &ow2, &oh2) == 1, "已释放的原帧 gray → STALE");
        chk(out2 == -1, "同样不写出参");
    }

    std::printf("\nchecks=%d failures=%d\n", checks, fails);
    return fails == 0 ? 0 : 1;
}
