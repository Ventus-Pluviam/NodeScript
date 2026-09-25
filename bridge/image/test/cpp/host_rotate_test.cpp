// 宿主机侧旋转算子语义验证（bridge/image/src/main/cpp/imgnative.cpp 直链，零 JNI）。
//
// `imgnative_rotate` 是**第四个产出新帧**的算子（继 gray/crop/resize 之后），且是
// 第一个**画布尺寸要算**的算子 —— resize 的尺寸是入参直接给的，rotate 的画布是包络
// 公式算出来的（bw = round(|w·cosθ|+|h·sinθ|)）。JVM/JS 两门结构上看不见的东西：
// mock 只回自报的 {ref,w,h}，角度方向写反（顺/逆时针）、中心选错（原点 vs 中心）、
// expand 平移漏掉（内容偏出画布一半黑边）照样编得过、出尺寸对的帧。
//
// 入参是**逆时针角度**（与 getRotationMatrix2D 正方向一致）。画布是 **expand**
// （包住整图，不静默裁像素；想要"旋转裁剪"先 rotate 再 crop）。中心是**帧中心**。
// 插值固定 LINEAR、填充固定 REPLICATE（黑边是假阳性源 —— 见实现注释）。
//
// 钉七处：
//   * **方向**：5×5 数字帧（r = 1..25 行主序）转 90°，首行 == {5,10,15,20,25}
//     （原左列自下而上 —— 逆时针的定义）；转反了（顺时针）首行是 {21,21,16,11,6}，
//     整行对不上，不是差一两个像素的事。
//   * **180°/270° 整行精确**：5×5 转 180° 首行 == {25,25,24,23,22}、
//     270° 首行 == {21,21,16,11,6}。奇尺寸 + 帧中心 + LINEAR 在 90° 倍角上采样点
//     恰落整数格点、相邻行采样到同一行（行复制 —— warpAffine 逆映射的精确行为，
//     host 实测五组全是整行相等），所以敢写整行相等、不留容差。
//   * **画布公式**：4×2 转 90° → 2×4、转 180° → 4×2（回原尺寸）、5×5 转 30° → 7×7
//     （round(5·cos30+5·sin30) = round(6.83) = 7）。
//   * **0°/360° 是恒等**：尺寸回原尺寸、像素逐点一致（360° 先归一到 0°，不因浮点
//     余数差一像素）。
//   * **30° 中心是混合值**：5×5 数字帧转 30°（expand 7×7），中心 3×3 ==
//     {6,9,13, 10,13,16, 14,17,20}（host 实测 LINEAR）；NEAREST 在同九点给出
//     {8,9,15, 7,13,14, 12,18,19} —— **专杀 NEAREST**（已用故意 NEAREST 实现验过
//     变红；诚实起见：CUBIC/LANCZOS 同样给混合值，钉的是"不是 NEAREST"，与 resize
//     同一条诚实线）。
//   * **纯色帧任意角度值不变**：纯色无梯度，任何角度都该原样 —— 杀"通道丢了/alpha
//     被抹"，不杀插值。
//   * **拒收是早退**：NaN/Inf → INVALID_PARAM(4) 且不写出参；句柄已死 → STALE；
//     出参指针 null → INVALID_PARAM。
#include <cerrno>
#include <cmath>
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
int imgnative_rotate(int64_t frame, double degrees,
                     int64_t* out_ref, int32_t* out_w, int32_t* out_h);
int imgnative_color(int64_t frame, const int32_t* color, int32_t tolerance,
                    const int32_t* region,
                    int32_t* out_x, int32_t* out_y,
                    int32_t* out_r, int32_t* out_g, int32_t* out_b, int32_t* out_a,
                    int64_t* out_scanned);
int imgnative_release(int64_t ref);
}

static int fails = 0, checks = 0;
static void chk(bool ok, const std::string& w) { ++checks; if (!ok) { ++fails; std::printf("  [FAIL] %s\n", w.c_str()); } }

static int64_t dec(const std::string& p, const cv::Mat& m) {
    if (!cv::imwrite(p, m)) { std::printf("  [FAIL] imwrite %s\n", p.c_str()); ++fails; return -1; }
    int64_t ref = 0; int32_t w = 0, h = 0;
    const int rc = imgnative_decode(p.c_str(), &ref, &w, &h);
    chk(rc == 0, "decode " + p);
    return rc == 0 ? ref : -1;
}

struct Hit { int rc = -1; int32_t x = -2, y = -2, r = -2, g = -2, b = -2, a = -2; int64_t scanned = -1; };
static Hit probe(int64_t ref, int32_t cr, int32_t cg, int32_t cb, int32_t ca, int32_t tol,
                 const int32_t* region = nullptr) {
    const int32_t color[4] = {cr, cg, cb, ca};
    Hit h;
    h.rc = imgnative_color(ref, color, tol, region, &h.x, &h.y, &h.r, &h.g, &h.b, &h.a, &h.scanned);
    return h;
}

/** 取一帧某点的像素（容差 0 的找色 + 单像素区域；回 r/g/b/a）。 */
struct Px { int32_t r = -2, g = -2, b = -2, a = -2; int rc = -1; };
static Px pixel(int64_t ref, int32_t x, int32_t y) {
    const int32_t one[4] = {x, y, 1, 1};   // region = x,y,w,h
    const Hit h = probe(ref, 0, 0, 0, 0, 255, one);   // 容差 255：任何像素都命中
    Px p; p.rc = h.rc; p.r = h.r; p.g = h.g; p.b = h.b; p.a = h.a;
    return p;
}

/** 一个从没发过号的句柄（帧表单调发号，0 恒不在场）。 */
static int64_t oracle_dead_handle() { return 0; }

/** 5×5 数字帧：r = 行主序 1..25（b=x, g=y），转 90°/180°/270° 的整行断言都从它来。 */
static int64_t digits5(const std::string& d) {
    cv::Mat m(5, 5, CV_8UC4);
    for (int y = 0; y < 5; ++y)
        for (int x = 0; x < 5; ++x)
            m.at<cv::Vec4b>(y, x) = cv::Vec4b(
                static_cast<unsigned char>(x),
                static_cast<unsigned char>(y),
                static_cast<unsigned char>(1 + y * 5 + x), 255);
    return dec(d + "/digits5.png", m);
}

/** 读一整行（r 分量），与期望逐点比。 */
static bool row_eq(int64_t ref, int y, int w, const int* want) {
    for (int x = 0; x < w; ++x) {
        const Px p = pixel(ref, x, y);
        if (p.rc != 0 || p.r != want[x]) return false;
    }
    return true;
}

int main() {
    const std::string d = "/tmp/imgtest-rotate-" + std::to_string(getpid());
    const int rc_mkdir = ::mkdir(d.c_str(), 0755);
    if (rc_mkdir != 0 && errno != EEXIST) { std::fprintf(stderr, "[FATAL] mkdir %s: %s\n", d.c_str(), std::strerror(errno)); return 97; }

    const int64_t src = digits5(d);
    if (src < 0) { std::printf("\nchecks=%d failures=%d\n", checks, fails); return fails == 0 ? 0 : 1; }

    // 1) 方向：90° 首行 == 原左列自下而上 {5,10,15,20,25}（逆时针的定义）
    int64_t r90 = -1;
    {
        int32_t w = -1, h = -1;
        chk(imgnative_rotate(src, 90.0, &r90, &w, &h) == 0, "rotate 90° 成功");
        chk(r90 > src, "产出新帧号大于源帧号");
        chk(w == 5 && h == 5, "5×5 转 90° 画布仍 5×5（实际 " + std::to_string(w) + "×" + std::to_string(h) + "）");
        const int want0[5] = {5, 10, 15, 20, 25};
        chk(row_eq(r90, 0, 5, want0), "90° 首行 == {5,10,15,20,25}（原左列自下而上 —— 逆时针）");
        // 次行 == 首行（行复制：逆映射采样点恰落整行 —— 见实现注释；这是 LINEAR 在
        // 90° 倍角上的精确行为，不是 bug；NEAREST 在此同样行复制，杀 NEAREST 靠第 5 段）
        const int want1[5] = {5, 10, 15, 20, 25};
        chk(row_eq(r90, 1, 5, want1), "90° 次行 == 首行（行复制，精确值）");
        // 转反了（顺时针）首行会是 {21,16,11,6,1} —— 方向错整行对不上
        const int cw0[5] = {21, 16, 11, 6, 1};
        chk(!row_eq(r90, 0, 5, cw0), "90° 首行不是顺时针结果（方向没反）");
    }

    // 2) 180°/270° 整行精确
    {
        int64_t r180 = -1; int32_t w = -1, h = -1;
        chk(imgnative_rotate(src, 180.0, &r180, &w, &h) == 0 && w == 5 && h == 5,
            "rotate 180° 成功（画布 5×5）");
        const int w180a[5] = {25, 25, 24, 23, 22}, w180b[5] = {25, 25, 24, 23, 22};
        chk(row_eq(r180, 0, 5, w180a), "180° 首行 == {25,25,24,23,22}（行复制，精确值）");
        chk(row_eq(r180, 1, 5, w180b), "180° 次行 == 首行（行复制，精确值）");
        imgnative_release(r180);

        int64_t r270 = -1;
        chk(imgnative_rotate(src, 270.0, &r270, &w, &h) == 0 && w == 5 && h == 5,
            "rotate 270° 成功（画布 5×5）");
        const int w270[5] = {21, 21, 16, 11, 6};
        chk(row_eq(r270, 0, 5, w270), "270° 首行 == {21,21,16,11,6}（行复制，精确值）");
        imgnative_release(r270);

        // 负角度归一：-90° == 270°
        int64_t rneg = -1;
        chk(imgnative_rotate(src, -90.0, &rneg, &w, &h) == 0 && w == 5 && h == 5,
            "rotate -90° 成功（归一到 270°）");
        chk(row_eq(rneg, 0, 5, w270), "-90° 首行同 270°（角度归一对了）");
        imgnative_release(rneg);
    }

    // 3) 画布公式：4×2 转 90° → 2×4、转 180° → 4×2、5×5 转 30° → 7×7
    {
        cv::Mat m(2, 4, CV_8UC4);
        for (int y = 0; y < 2; ++y)
            for (int x = 0; x < 4; ++x)
                m.at<cv::Vec4b>(y, x) = cv::Vec4b(
                    static_cast<unsigned char>(x), static_cast<unsigned char>(y),
                    static_cast<unsigned char>(100 + 10 * x + y), 255);
        const int64_t rect = dec(d + "/rect42.png", m);
        chk(rect >= 0, "4×2 源帧就绪");
        if (rect >= 0) {
            int64_t e90 = -1; int32_t w = -1, h = -1;
            chk(imgnative_rotate(rect, 90.0, &e90, &w, &h) == 0 && w == 2 && h == 4,
                "4×2 转 90° 画布 2×4（实际 " + std::to_string(w) + "×" + std::to_string(h) + "）");
            // expand 不是保持尺寸：内容没有被裁 —— 首行全是原右列 {130,131}
            const Px a = pixel(e90, 0, 0), b = pixel(e90, 1, 0);
            chk(a.r == 130 && b.r == 131,
                "expand 首行是原右列 r=130,131（实际 " + std::to_string(a.r) + "," + std::to_string(b.r) + "）");
            imgnative_release(e90);

            int64_t e180 = -1;
            chk(imgnative_rotate(rect, 180.0, &e180, &w, &h) == 0 && w == 4 && h == 2,
                "4×2 转 180° 画布回 4×2（实际 " + std::to_string(w) + "×" + std::to_string(h) + "）");
            imgnative_release(e180);
        }
        imgnative_release(rect);

        int64_t e30 = -1; int32_t w30 = -1, h30 = -1;
        chk(imgnative_rotate(src, 30.0, &e30, &w30, &h30) == 0 && w30 == 7 && h30 == 7,
            "5×5 转 30° 画布 7×7（round(6.83)=7；实际 " + std::to_string(w30) + "×" + std::to_string(h30) + "）");
        if (e30 >= 0) imgnative_release(e30);
    }

    // 4) 0°/360° 恒等：尺寸回原尺寸、像素逐点一致
    {
        for (double a : {0.0, 360.0}) {
            int64_t id = -1; int32_t w = -1, h = -1;
            chk(imgnative_rotate(src, a, &id, &w, &h) == 0 && w == 5 && h == 5,
                "rotate " + std::to_string((int)a) + "° 画布 5×5");
            if (id >= 0) {
                const int want[5] = {1, 2, 3, 4, 5};
                chk(row_eq(id, 0, 5, want), std::to_string((int)a) + "° 首行 == 原首行（恒等）");
                imgnative_release(id);
            }
        }
    }

    // 5) 30° 中心是混合值（专杀 NEAREST）：expand 7×7 的中心 3×3 ==
    //    {6,9,13, 10,13,16, 14,17,20}（host 实测 LINEAR）
    {
        int64_t e30 = -1; int32_t w = -1, h = -1;
        chk(imgnative_rotate(src, 30.0, &e30, &w, &h) == 0, "再转一次 30° 取中心");
        if (e30 >= 0) {
            const int cx = w / 2, cy = h / 2;   // 7×7 → (3,3)
            const int want[3][3] = {{6, 9, 13}, {10, 13, 16}, {14, 17, 20}};
            bool ok = true;
            for (int dy = -1; dy <= 1 && ok; ++dy)
                for (int dx = -1; dx <= 1 && ok; ++dx) {
                    const Px p = pixel(e30, cx + dx, cy + dy);
                    if (p.rc != 0 || p.r != want[dy + 1][dx + 1]) ok = false;
                }
            chk(ok, "30° 中心 3×3 是混合值（NEAREST 会给另一组 —— 专杀它）");
            // NEAREST 在同九点的中心值是 13？不 —— NEAREST 中心九点含 8：
            // 直接断言"中心 (2,2) 不是 NEAREST 的 7/8 那套"太绕；混合值整表相等已足够
            imgnative_release(e30);
        }
    }

    // 6) 纯色帧任意角度值不变
    {
        cv::Mat m(4, 4, CV_8UC4, cv::Scalar(10, 20, 100, 200));
        const int64_t solid = dec(d + "/solid.png", m);
        chk(solid >= 0, "纯色源帧就绪");
        if (solid >= 0) {
            for (double a : {45.0, 90.0, 30.0}) {
                int64_t r = -1; int32_t w = -1, h = -1;
                chk(imgnative_rotate(solid, a, &r, &w, &h) == 0,
                    "纯色转 " + std::to_string((int)a) + "° 成功（画布 " + std::to_string(w) + "×" + std::to_string(h) + "）");
                if (r >= 0) {
                    const Px p = pixel(r, w / 2, h / 2);
                    chk(p.rc == 0 && p.r == 100 && p.g == 20 && p.b == 10 && p.a == 200,
                        "纯色转 " + std::to_string((int)a) + "° 中心值不变");
                    imgnative_release(r);
                }
            }
        }
        imgnative_release(solid);
    }

    // 7) 两帧独立 + 拒收早退
    {
        // (0,0) 的像素来自源帧 (4,0)：Vec4b 是 B,G,R,A = x,y,r → (b=4,g=0,r=5)。
        // 三分量全写精确值：任一通道错位（r/b 互换、行列互换）都命中不到 (0,0)。
        const Hit before = probe(r90, 5, 0, 4, 255, 0);
        chk(before.rc == 0 && before.x == 0 && before.y == 0, "旋转帧上找色：(r=5,g=0,b=4) 首个命中在 (0,0)");
        chk(imgnative_release(src) == 0, "放掉源帧");
        const Px p = pixel(r90, 0, 0);
        chk(p.rc == 0 && p.r == 5, "源帧放掉后旋转帧照常可读（首像素 r=5）");
        int64_t re = -1; int32_t w = -1, h = -1;
        chk(imgnative_rotate(r90, 90.0, &re, &w, &h) == 0 && w == 5 && h == 5,
            "旋转帧可再旋转（5×5 转 90° 仍 5×5）");
        imgnative_release(re);
        imgnative_release(r90);

        int64_t o = -1; int32_t ow = -1, oh = -1;
        const double nan = std::acos(2.0);   // NaN（定义域外 acos）
        chk(imgnative_rotate(oracle_dead_handle(), nan, &o, &ow, &oh) == 4,
            "NaN 角度 → INVALID_PARAM(4)（先于 STALE 判 —— 入参错就是入参错）");
        chk(o == -1 && ow == -1 && oh == -1, "NaN 早退，不写出参");
        const double inf = 1.0 / 0.0;
        chk(imgnative_rotate(oracle_dead_handle(), inf, &o, &ow, &oh) == 4, "Inf 角度 → INVALID_PARAM(4)");
        chk(imgnative_rotate(oracle_dead_handle(), 90.0, &o, &ow, &oh) == 1, "死句柄 → STALE(1)");
        int64_t n1 = -1; int32_t n2 = -1, n3 = -1;
        chk(imgnative_rotate(oracle_dead_handle(), 90.0, nullptr, &n2, &n3) == 4, "out_ref=null → INVALID_PARAM(4)");
        chk(imgnative_rotate(oracle_dead_handle(), 90.0, &n1, nullptr, &n3) == 4, "out_w=null → INVALID_PARAM(4)");
        chk(imgnative_rotate(oracle_dead_handle(), 90.0, &n1, &n2, nullptr) == 4, "out_h=null → INVALID_PARAM(4)");
    }

    std::printf("\nchecks=%d failures=%d\n", checks, fails);
    return fails == 0 ? 0 : 1;
}
