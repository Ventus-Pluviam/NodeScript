// 宿主机侧缩放算子语义验证（bridge/image/src/main/cpp/imgnative.cpp 直链，零 JNI）。
//
// `imgnative_resize` 是**第三个产出新帧**的算子（继 gray/crop 之后），且是第一个
// **像素值要重算**的算子 —— crop 搬像素（逐点相等），resize 按插值重算（逐点不相等
// 是正常的）。JVM/JS 两门结构上看不见的东西：mock 只回自报的 {ref,w,h}，插值枚举
// 写错（NEAREST/CUBIC/LANCZOS 照样编得过、出尺寸对的帧），只有真跑像素才知道。
//
// 入参是**目标尺寸**（dst_w × dst_h），不是倍数：倍数是调用方算的浮点，尺寸真值
// 只有产出帧的地方知道（与 gray/crop 的"宽高随帧回"同一条纪律）。
//
// 钉七处：
//   * **尺寸**：产出 w/h == 入参 dst_w/dst_h（不是源帧尺寸、不是按倍数四舍五入的）。
//   * **纯色帧任意缩放值不变**：纯色没有梯度，任何插值都该原样 —— 这条杀"通道丢了
//     /alpha 被抹 / 缩放动了颜色"的错，不杀插值。
//   * **2×2 四角帧放大到 4×4，四角守恒**：四个角像素 == 源帧四角（LINEAR 实测成立，
//     角点是精确映射；NEAREST 同样全过 —— 所以这条不杀插值，杀"几何对错了/行列
//     互换/出参尺寸与真实 Mat 不一致"的错）。
//   * **中心像素是混合值**：2×2 四角 r={100,101,102,103} 放大到 4×4，中心 (1,1)
//     的 r=101（实测）—— NEAREST 在这里给出 100（左上块的复制），所以这条**专杀
//     NEAREST**。CUBIC/LANCZOS 在这里同样给出混合值（101 附近），杀不掉 ——
//     诚实起见：这条钉的是"不是 NEAREST"，不是"只能是 LINEAR"（文件头已写明）。
//   * **缩小是均值行为**：4×4 象限帧（r={100,110,120,130} 各占 2×2）缩到 2×2，
//     四像素 == 四象限均值（实测 LINEAR 精确成立 —— 缩小一半时权重恰好是均值）。
//   * **同尺寸是合法拷贝**：dst == src 尺寸时不早退，产出独立新帧、像素逐点一致。
//   * **拒收是早退**：dst_w/dst_h 为 0 或负 → INVALID_PARAM(4) 且不写出参；
//     句柄已死 → STALE；出参指针 null → INVALID_PARAM。另有 **16384 配额**：
//     单边超限即 INVALID_PARAM（1GB 配额线，防笔误把字节数当宽高）。
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
int imgnative_resize(int64_t frame, int32_t dst_w, int32_t dst_h,
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

int main() {
    const std::string d = "/tmp/imgtest-resize-" + std::to_string(getpid());
    const int rc_mkdir = ::mkdir(d.c_str(), 0755);
    if (rc_mkdir != 0 && errno != EEXIST) { std::fprintf(stderr, "[FATAL] mkdir %s: %s\n", d.c_str(), std::strerror(errno)); return 97; }

    // 四角各异的 2×2 源帧（B,G,R,A 序）：r 随 x/y 变化（100+x+2y 的变形），
    // b/g/a 也各自不同 —— 行列互换、通道译反在这里都会露出来。
    //   (0,0): b=10 g=20 r=100 a=200 | (1,0): b=11 g=21 r=101 a=201
    //   (0,1): b=12 g=22 r=102 a=202 | (1,1): b=13 g=23 r=103 a=203
    const int64_t q = [&] {
        cv::Mat m(2, 2, CV_8UC4);
        m.at<cv::Vec4b>(0, 0) = cv::Vec4b(10, 20, 100, 200);
        m.at<cv::Vec4b>(0, 1) = cv::Vec4b(11, 21, 101, 201);
        m.at<cv::Vec4b>(1, 0) = cv::Vec4b(12, 22, 102, 202);
        m.at<cv::Vec4b>(1, 1) = cv::Vec4b(13, 23, 103, 203);
        return dec(d + "/quad22.png", m);
    }();
    if (q < 0) { std::printf("\nchecks=%d failures=%d\n", checks, fails); return fails == 0 ? 0 : 1; }

    // 1) 放大 2×2 → 4×4：尺寸 + 四角守恒 + 中心混合（专杀 NEAREST）
    int64_t big = -1;
    {
        int32_t bw = -1, bh = -1;
        chk(imgnative_resize(q, 4, 4, &big, &bw, &bh) == 0, "resize 2×2→4×4 成功");
        chk(big > q, "产出新帧号大于源帧号（没有顶掉源帧）");
        chk(bw == 4 && bh == 4,
            "宽高 == 入参 dst（4×4；实际 " + std::to_string(bw) + "×" + std::to_string(bh) + "）");
        // 四角守恒（LINEAR 实测：角点精确映射）
        const Px c00 = pixel(big, 0, 0), c30 = pixel(big, 3, 0);
        const Px c03 = pixel(big, 0, 3), c33 = pixel(big, 3, 3);
        chk(c00.r == 100 && c00.g == 20 && c00.b == 10 && c00.a == 200,
            "放大后 (0,0) == 源帧左上（实际 r=" + std::to_string(c00.r) + "）");
        chk(c30.r == 101 && c30.a == 201, "放大后 (3,0) == 源帧右上（实际 r=" + std::to_string(c30.r) + "）");
        chk(c03.r == 102 && c03.a == 202, "放大后 (0,3) == 源帧左下（实际 r=" + std::to_string(c03.r) + "）");
        chk(c33.r == 103 && c33.g == 23 && c33.b == 13 && c33.a == 203,
            "放大后 (3,3) == 源帧右下（实际 r=" + std::to_string(c33.r) + "）");
        // 中心是混合值：(1,1) 的 r=101（实测 LINEAR）；NEAREST 给 100 —— 专杀它
        const Px mid = pixel(big, 1, 1);
        chk(mid.r == 101,
            "放大后中心 (1,1) r=101（混合值；NEAREST 会给 100。实际 r=" + std::to_string(mid.r) + "）");
        chk(!(mid.r == 100 && mid.g == 20 && mid.b == 10),
            "中心不是左上角的复制块（排除 NEAREST 的块状复制）");
    }

    // 2) 纯色帧任意缩放值不变（放大 + 缩小）：杀"通道丢了/alpha 被抹/颜色被动"
    {
        cv::Mat m(4, 4, CV_8UC4, cv::Scalar(10, 20, 100, 200));   // b,g,r,a
        const int64_t solid = dec(d + "/solid.png", m);
        chk(solid >= 0, "纯色源帧就绪");
        if (solid >= 0) {
            int64_t up = -1, dn = -1; int32_t uw = -1, uh = -1, dw = -1, dh = -1;
            chk(imgnative_resize(solid, 8, 8, &up, &uw, &uh) == 0 && uw == 8 && uh == 8,
                "纯色 4×4→8×8 成功");
            chk(imgnative_resize(solid, 2, 2, &dn, &dw, &dh) == 0 && dw == 2 && dh == 2,
                "纯色 4×4→2×2 成功");
            const Px a = pixel(up, 5, 5), b = pixel(dn, 1, 1);
            chk(a.r == 100 && a.g == 20 && a.b == 10 && a.a == 200,
                "放大后纯色值不变（实际 " + std::to_string(a.r) + "," + std::to_string(a.g) + "," +
                std::to_string(a.b) + "," + std::to_string(a.a) + "）");
            chk(b.r == 100 && b.g == 20 && b.b == 10 && b.a == 200,
                "缩小后纯色值不变（实际 " + std::to_string(b.r) + "," + std::to_string(b.g) + "," +
                std::to_string(b.b) + "," + std::to_string(b.a) + "）");
            imgnative_release(up);
            imgnative_release(dn);
        }
        imgnative_release(solid);
    }

    // 3) 缩小是均值行为：4×4 象限帧 → 2×2，四像素 == 四象限均值
    //    象限 r={100,110,120,130}（左上/右上/左下/右下各占 2×2），b={10,30}/g={20,40} 同理
    {
        cv::Mat m(4, 4, CV_8UC4);
        for (int y = 0; y < 4; ++y)
            for (int x = 0; x < 4; ++x)
                m.at<cv::Vec4b>(y, x) = cv::Vec4b(
                    static_cast<unsigned char>(x < 2 ? 10 : 30),
                    static_cast<unsigned char>(y < 2 ? 20 : 40),
                    static_cast<unsigned char>(100 + (x < 2 ? 0 : 10) + (y < 2 ? 0 : 20)),
                    255);
        const int64_t quad = dec(d + "/quad44.png", m);
        chk(quad >= 0, "象限源帧就绪");
        if (quad >= 0) {
            int64_t small = -1; int32_t sw = -1, sh = -1;
            chk(imgnative_resize(quad, 2, 2, &small, &sw, &sh) == 0 && sw == 2 && sh == 2,
                "象限 4×4→2×2 成功");
            if (small >= 0) {
                const Px p00 = pixel(small, 0, 0), p10 = pixel(small, 1, 0);
                const Px p01 = pixel(small, 0, 1), p11 = pixel(small, 1, 1);
                chk(p00.r == 100 && p00.g == 20 && p00.b == 10,
                    "缩小 (0,0) == 左上象限均值 r=100（实际 r=" + std::to_string(p00.r) + "）");
                chk(p10.r == 110 && p10.g == 20 && p10.b == 30,
                    "缩小 (1,0) == 右上象限均值 r=110（实际 r=" + std::to_string(p10.r) + "）");
                chk(p01.r == 120 && p01.g == 40 && p01.b == 10,
                    "缩小 (0,1) == 左下象限均值 r=120（实际 r=" + std::to_string(p01.r) + "）");
                chk(p11.r == 130 && p11.g == 40 && p11.b == 30,
                    "缩小 (1,1) == 右下象限均值 r=130（实际 r=" + std::to_string(p11.r) + "）");
                imgnative_release(small);
            }
        }
        imgnative_release(quad);
    }

    // 4) 同尺寸是合法拷贝：2×2 → 2×2，独立新帧、像素逐点一致
    {
        int64_t same = -1; int32_t sw = -1, sh = -1;
        chk(imgnative_resize(q, 2, 2, &same, &sw, &sh) == 0 && sw == 2 && sh == 2,
            "同尺寸 resize 合法（2×2→2×2）");
        if (same >= 0) {
            chk(same != q, "同尺寸产出仍是新帧（不是源帧号）");
            const Px a = pixel(same, 0, 0), b = pixel(q, 0, 0);
            const Px c = pixel(same, 1, 1), e = pixel(q, 1, 1);
            chk(a.r == b.r && a.g == b.g && a.b == b.b && a.a == b.a &&
                    c.r == e.r && c.g == e.g && c.b == e.b && c.a == e.a,
                "同尺寸像素逐点一致");
            imgnative_release(same);
        }
    }

    // 5) 非对称尺寸：4×2（只在 x 放大）—— 宽高各自独立，行方向是复制
    {
        cv::Mat m(1, 2, CV_8UC4);
        m.at<cv::Vec4b>(0, 0) = cv::Vec4b(10, 20, 100, 200);
        m.at<cv::Vec4b>(0, 1) = cv::Vec4b(11, 21, 101, 201);
        const int64_t row = dec(d + "/row.png", m);
        chk(row >= 0, "单行源帧就绪");
        if (row >= 0) {
            int64_t asym = -1; int32_t aw = -1, ah = -1;
            chk(imgnative_resize(row, 4, 2, &asym, &aw, &ah) == 0 && aw == 4 && ah == 2,
                "非对称 2×1→4×2 成功（实际 " + std::to_string(aw) + "×" + std::to_string(ah) + "）");
            if (asym >= 0) {
                // x 方向前两列是左像素的延续（实测 LINEAR：列 0,1 == r=100）
                const Px c0 = pixel(asym, 0, 0), c1 = pixel(asym, 1, 0);
                chk(c0.r == 100 && c1.r == 100,
                    "x 放大后前两列 == 左像素 r=100（实际 " + std::to_string(c0.r) + "," +
                    std::to_string(c1.r) + "）");
                // y 方向是复制：两行一致
                const Px r0 = pixel(asym, 0, 0), r1 = pixel(asym, 0, 1);
                chk(r0.r == r1.r && r0.g == r1.g && r0.b == r1.b && r0.a == r1.a,
                    "y 方向两行一致（单行源的复制）");
                imgnative_release(asym);
            }
        }
        imgnative_release(row);
    }

    // 6) 拒收是早退：0/负尺寸、超配额、死句柄、空出参
    {
        int64_t o = -1; int32_t ow = -1, oh = -1;
        chk(imgnative_resize(q, 0, 4, &o, &ow, &oh) == 4, "dst_w=0 → INVALID_PARAM(4)");
        chk(o == -1 && ow == -1 && oh == -1, "零宽早退，不写出参");
        chk(imgnative_resize(q, 4, -1, &o, &ow, &oh) == 4, "dst_h<0 → INVALID_PARAM(4)");
        chk(imgnative_resize(q, 20000, 4, &o, &ow, &oh) == 4, "dst_w=20000 超配额 → INVALID_PARAM(4)");
        chk(imgnative_resize(q, 4, 20000, &o, &ow, &oh) == 4, "dst_h=20000 超配额 → INVALID_PARAM(4)");
        chk(o == -1 && ow == -1 && oh == -1, "超配额也是早退，不写出参");
        int64_t dead = -1; int32_t dw2 = -1, dh2 = -1;
        chk(imgnative_resize(oracle_dead_handle(), 4, 4, &dead, &dw2, &dh2) == 1,
            "死句柄 → STALE(1)");
        int64_t n1 = -1; int32_t n2 = -1, n3 = -1;
        chk(imgnative_resize(q, 4, 4, nullptr, &n2, &n3) == 4, "out_ref=null → INVALID_PARAM(4)");
        chk(imgnative_resize(q, 4, 4, &n1, nullptr, &n3) == 4, "out_w=null → INVALID_PARAM(4)");
        chk(imgnative_resize(q, 4, 4, &n1, &n2, nullptr) == 4, "out_h=null → INVALID_PARAM(4)");
        (void)dw2; (void)dh2;
    }

    // 7) 两帧独立：放掉源帧后产出帧照常可读；产出帧可再缩放；findColor 可在缩放帧上跑
    {
        const Hit before = probe(big, 101, 21, 11, 201, 0);
        chk(before.rc == 0 && before.x >= 0, "缩放帧上找色：r=101 精确命中一处");
        chk(imgnative_release(q) == 0, "放掉源帧");
        const Px p = pixel(big, 3, 3);
        chk(p.rc == 0 && p.r == 103, "源帧放掉后产出帧照常可读（右下 r=103）");
        int64_t re = -1; int32_t rw = -1, rh = -1;
        chk(imgnative_resize(big, 2, 2, &re, &rw, &rh) == 0 && rw == 2 && rh == 2,
            "产出帧可再缩放（4×4→2×2）");
        imgnative_release(re);
        imgnative_release(big);
    }

    std::printf("\nchecks=%d failures=%d\n", checks, fails);
    return fails == 0 ? 0 : 1;
}

