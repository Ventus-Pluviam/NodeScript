// 宿主机侧裁剪算子语义验证（bridge/image/src/main/cpp/imgnative.cpp 直链，零 JNI）。
//
// `imgnative_crop` 是**第二个产出新帧**的算子（继 imgnative_gray 之后），且是第一个
// **尺寸会变**的算子 —— JVM/JS 两门结构上看不见的东西更多了：
//   * mock 层只回一个自报的 {ref,width,height}，帧表里到底有没有这一帧、
//     像素是不是真搬过去了、源帧有没有被牵连，只有真跑 native 才知道；
//   * 裁剪的尺寸是**算出来的**（region 的 w/h），不是从源帧抄的 ——
//     "宽高回的是源帧尺寸"这类错在 mock 上是完美的绿。
//
// 钉七处：
//   * **拷像素不是拷视图**：这是本算子最容易写错的地方（`(*f)(roi)` 是浅视图，
//     少一个 .clone() 就成悬垂）。**像素读法分不开这两种实现**（2026-09-25 实测：
//     放掉源帧后再读产出帧，视图实现照常读出正确值 —— 缓冲没被覆写就还是原值，
//     64 轮同尺寸重分配也没能把它分出来），所以判据看的是**内存归属**而非像素：
//     大源帧裁 1×1 后放掉源帧，拷贝实现把大缓冲还给分配器（RSS 回落），
//     视图实现被 1×1 的子图钉住整块缓冲（ROI 共享同一个 UMatData，引用计数不为零
//     就释放不掉）—— 0.3MB 的产出帧却让 64MB 常驻，这是别名唯一可观测的后果，
//     也正是它在脚本侧的真代价（放掉源帧 ≠ 内存回来了）。
//   * **区域坐标是源帧坐标系**：子图第一像素 == 源帧 (rx,ry) 处的像素（值逐点比）。
//   * **宽高 = region 的 w/h**（不是源帧尺寸、也不是 x2-x1 减出来的）。
//   * **非零起点**：region 从 (1,1) 起时产出帧的 (0,0) 是源帧的 (1,1)
//     —— 偏移没加/加错方向在这里必红。
//   * **区域判据与 findColor 同一份**（resolve_region）：`rx+rw == cols` 贴边合法、
//     越界一律 INVALID_PARAM(4)、w/h <= 0 也拒 —— 且**拒收是早退**（不写出参）。
//   * **产出帧仍是 4 通道 BGRA（含 alpha）**：findColor 能在子图上按 a 精确命中。
//   * **两帧独立**：产出帧与原帧各自 release，互不牵连；region == nullptr 拒收
//     （裁剪必须给区域 —— 缺区域时唯一自洽解释是"整帧拷贝"，那是另一个算子）。
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
int imgnative_crop(int64_t frame, const int32_t* region,
                   int64_t* out_ref, int32_t* out_w, int32_t* out_h);
int imgnative_color(int64_t frame, const int32_t* color, int32_t tolerance,
                    const int32_t* region,
                    int32_t* out_x, int32_t* out_y,
                    int32_t* out_r, int32_t* out_g, int32_t* out_b, int32_t* out_a,
                    int64_t* out_scanned);
int imgnative_release(int64_t ref);
}

/** 进程 RSS（KB）：别名判据要读的是内存归属，不是像素值（见文件头）。 */
static long rss_kb() {
    std::FILE* f = std::fopen("/proc/self/statm", "r");
    long sz = 0, res = 0;
    if (f != nullptr && std::fscanf(f, "%ld %ld", &sz, &res) == 2) { std::fclose(f); return res * 4; }
    if (f != nullptr) std::fclose(f);
    return -1;
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

int main() {
    const std::string d = "/tmp/imgtest-crop-" + std::to_string(getpid());
    const int rc_mkdir = ::mkdir(d.c_str(), 0755);
    if (rc_mkdir != 0 && errno != EEXIST) { std::fprintf(stderr, "[FATAL] mkdir %s: %s\n", d.c_str(), std::strerror(errno)); return 97; }

    // 4×3 的源帧，每格一个可辨认的像素（B,G,R,A 序），便于逐点比对搬对了没有。
    // 值取 (b = 10*x, g = 20*y, r = 100 + x, a = 200 + y)：三通道各自随坐标变化，
    // 通道译反（r/b 互换）或行列互换都会在下面的逐点断言里露出来。
    const int W = 4, H = 3;
    const int64_t src = [&] {
        cv::Mat m(H, W, CV_8UC4);
        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W; ++x) {
                m.at<cv::Vec4b>(y, x) = cv::Vec4b(
                    static_cast<unsigned char>(10 * x),          // B
                    static_cast<unsigned char>(20 * y),          // G
                    static_cast<unsigned char>(100 + x),         // R
                    static_cast<unsigned char>(200 + y));        // A
            }
        }
        return dec(d + "/src.png", m);
    }();
    if (src < 0) { std::printf("\nchecks=%d failures=%d\n", checks, fails); return fails == 0 ? 0 : 1; }

    // 1) 基本形状：region = (1,1,2,1) → 2×1 的子图，第一像素 == 源帧 (1,1)
    int64_t sub = -1;
    {
        const int32_t r[4] = {1, 1, 2, 1};   // region = x,y,w,h
        int32_t sw = -1, sh = -1;
        const int rc = imgnative_crop(src, r, &sub, &sw, &sh);
        chk(rc == 0, "crop 成功（rc=" + std::to_string(rc) + "）");
        chk(sub > src, "产出新帧号大于源帧号（帧表单调，没有顶掉源帧）");
        chk(sw == 2 && sh == 1,
            "宽高 = region 的 w/h（2×1；实际 " + std::to_string(sw) + "×" + std::to_string(sh) + "）");
        chk(!(sw == W && sh == H), "宽高不是源帧尺寸（4×3）");
    }
    {
        // 逐点比对：产出帧 (0,0) == 源帧 (1,1)；(1,0) == 源帧 (2,1)
        const Px a = pixel(sub, 0, 0), b0 = pixel(src, 1, 1);
        chk(a.rc == 0 && b0.rc == 0, "两帧都能取像素");
        chk(a.r == b0.r && a.g == b0.g && a.b == b0.b && a.a == b0.a,
            "产出帧 (0,0) 逐分量 == 源帧 (1,1)（实际 " + std::to_string(a.r) + "," + std::to_string(a.g) + "," +
            std::to_string(a.b) + "," + std::to_string(a.a) + " vs " + std::to_string(b0.r) + "," +
            std::to_string(b0.g) + "," + std::to_string(b0.b) + "," + std::to_string(b0.a) + "）");
        // 源帧 (1,1) 的期望值：b=10, g=20, r=101, a=201 —— 通道序也在这里钉住
        chk(a.r == 101 && a.g == 20 && a.b == 10 && a.a == 201,
            "源帧 (1,1) 的像素是 r=101,g=20,b=10,a=201（实际 " + std::to_string(a.r) + "," +
            std::to_string(a.g) + "," + std::to_string(a.b) + "," + std::to_string(a.a) + "）");
        const Px a1 = pixel(sub, 1, 0), b1 = pixel(src, 2, 1);
        chk(a1.r == b1.r && a1.g == b1.g && a1.b == b1.b && a1.a == b1.a,
            "产出帧 (1,0) 逐分量 == 源帧 (2,1)");
        const Px out_of_range = pixel(sub, 2, 0);   // 子图只有 2 列：越界区域 → INVALID_PARAM
        chk(out_of_range.rc == 4, "子图宽度确实是 2（第 3 列越界 → INVALID_PARAM(4)，实际 rc=" +
            std::to_string(out_of_range.rc) + "）");
    }

    // 2) 区域判据与 findColor 共用一份（resolve_region）：贴边合法、越界一律 4、w/h<=0 也拒
    {
        const int32_t edge[4] = {2, 1, 2, 2};    // rx+rw == 4 == cols（贴边，合法）
        int64_t e = -1; int32_t ew = -1, eh = -1;
        chk(imgnative_crop(src, edge, &e, &ew, &eh) == 0 && ew == 2 && eh == 2,
            "贴边区域 rx+rw == cols 合法（与 findColor 同口径：贴边不算越界）");
        imgnative_release(e);

        const int32_t over[4] = {2, 1, 3, 2};    // rx+rw == 5 > 4
        int64_t o = -1; int32_t ow = -1, oh = -1;
        chk(imgnative_crop(src, over, &o, &ow, &oh) == 4, "越界区域 → INVALID_PARAM(4)");
        chk(o == -1 && ow == -1 && oh == -1, "越界是早退，不写出参");

        const int32_t neg[4] = {-1, 0, 2, 2};
        int64_t n = -1; int32_t nw = -1, nh = -1;
        chk(imgnative_crop(src, neg, &n, &nw, &nh) == 4, "负起点 → INVALID_PARAM(4)");

        const int32_t zw[4] = {1, 1, 0, 2};
        int64_t z = -1; int32_t zw2 = -1, zh = -1;
        chk(imgnative_crop(src, zw, &z, &zw2, &zh) == 4, "零宽 → INVALID_PARAM(4)");
        const int32_t zh_neg[4] = {1, 1, 2, -1};
        int64_t zh3 = -1; int32_t zhw = -1, zhh = -1;
        chk(imgnative_crop(src, zh_neg, &zh3, &zhw, &zhh) == 4, "负高 → INVALID_PARAM(4)");

        // region == nullptr：裁剪**拒收**（缺区域时唯一自洽解释是"整帧拷贝"，那是另一个算子）
        int64_t no = -1; int32_t now = -1, noh = -1;
        chk(imgnative_crop(src, nullptr, &no, &now, &noh) == 4,
            "region == nullptr → INVALID_PARAM(4)（裁剪必须给区域，不默认整帧）");
        chk(no == -1 && now == -1 && noh == -1, "缺区域也是早退，不写出参");
    }

    // 3) 整帧区域**是合法裁剪**（与缺区域区分开）：w/h 全给 → 尺寸等于源帧，内容相同
    {
        const int32_t whole[4] = {0, 0, W, H};
        int64_t wf = -1; int32_t ww = -1, wh = -1;
        chk(imgnative_crop(src, whole, &wf, &ww, &wh) == 0 && ww == W && wh == H,
            "整帧区域合法（尺寸 " + std::to_string(ww) + "×" + std::to_string(wh) + "）");
        const Px p = pixel(wf, 3, 2), q = pixel(src, 3, 2);
        chk(p.r == q.r && p.g == q.g && p.b == q.b && p.a == q.a, "整帧裁剪与大图逐分量一致");
        // 右下角 (3,2)：b=30, g=40, r=103, a=202
        chk(p.b == 30 && p.g == 40 && p.r == 103 && p.a == 202,
            "右下角像素 b=30,g=40,r=103,a=202（实际 " + std::to_string(p.r) + "," + std::to_string(p.g) + "," +
            std::to_string(p.b) + "," + std::to_string(p.a) + "）");
        imgnative_release(wf);
    }

    // 4) 源帧释放后产出帧照常可读、照常能找色、两帧各自独立。
    //    **注意这一段分不开拷贝与视图**（实测过：视图实现同样读得出正确值）——
    //    别名判据在第 8 段（读 RSS）。这一段钉的是生命周期口径：产出帧不是
    //    "源帧的附属物"，源帧一走它照常活着。
    {
        const int32_t r[4] = {0, 0, 2, 2};
        int64_t survivor = -1; int32_t sw = -1, sh = -1;
        chk(imgnative_crop(src, r, &survivor, &sw, &sh) == 0, "再裁一块");
        chk(imgnative_release(src) == 0, "释放源帧");
        // 源帧已死：拿它再裁 → STALE（帧表已无此号）
        int64_t dead = -1; int32_t dw = -1, dh = -1;
        chk(imgnative_crop(src, r, &dead, &dw, &dh) == 1, "已释放的源帧再裁 → STALE_HANDLE(1)");
        chk(dead == -1 && dw == -1 && dh == -1, "STALE 也是早退，不写出参");
        // 产出帧仍在场且内容正确：源帧 (1,1) 的像素还在它的 (1,1) 上
        const Px p = pixel(survivor, 1, 1);
        chk(p.r == 101 && p.g == 20 && p.b == 10 && p.a == 201,
            "源帧释放后产出帧照常可读且内容正确（实际 " + std::to_string(p.r) + "," + std::to_string(p.g) + "," +
            std::to_string(p.b) + "," + std::to_string(p.a) + "）");
        const int32_t one[4] = {1, 1, 1, 1};
        const Hit hit = probe(survivor, 101, 20, 10, 201, 0, one);
        chk(hit.rc == 0 && hit.x == 1 && hit.y == 1,
            "在产出帧上按全分量精确找色命中 (1,1)（实际 rc=" + std::to_string(hit.rc) + " x=" +
            std::to_string(hit.x) + "," + std::to_string(hit.y) + "）");
        imgnative_release(survivor);
        chk(imgnative_release(survivor) == 1, "产出帧再放 → STALE（与 gray/match 同口径）");
    }

    // 5) alpha 原样带过去（不抹 255）：裁一块 a 各不相同的区域，产出帧的 a 仍在。
    {
        cv::Mat m(1, 2, CV_8UC4);
        m.at<cv::Vec4b>(0, 0) = cv::Vec4b(9, 9, 9, 210);
        m.at<cv::Vec4b>(0, 1) = cv::Vec4b(9, 9, 9, 40);
        const int64_t ar = dec(d + "/alpha.png", m);
        const int32_t r0[4] = {0, 0, 1, 1};    // 只裁第一个像素
        int64_t a0 = -1; int32_t aw = -1, ah = -1;
        chk(imgnative_crop(ar, r0, &a0, &aw, &ah) == 0 && aw == 1 && ah == 1, "裁 1×1 单像素");
        const Px p = pixel(a0, 0, 0);
        chk(p.a == 210, "裁出的单像素 alpha 原样是 210（没被抹成 255，实际 " + std::to_string(p.a) + "）");
        const Hit wrong = probe(a0, 9, 9, 9, 255, 0);
        chk(wrong.rc == 0 && wrong.x == -1, "a=255 在产出帧上未命中（a 确实不是 255）");
        imgnative_release(ar);
        imgnative_release(a0);
    }

    // 6) 产出帧是 4 通道：能再裁（单通道帧会让 findColor 的 Vec4b 回读越字节读，
    //    而"再裁一次"要求源帧满足 frame_is_normalized —— 3 通道会在这里回 IO(3)）
    {
        const int64_t s2 = dec(d + "/src2.png", cv::imread(d + "/src.png", cv::IMREAD_UNCHANGED));
        const int32_t r[4] = {1, 0, 3, 3};
        int64_t c1 = -1; int32_t w1 = -1, h1 = -1;
        chk(imgnative_crop(s2, r, &c1, &w1, &h1) == 0, "第一层裁剪");
        const int32_t r2[4] = {0, 0, 2, 2};
        int64_t c2 = -1; int32_t w2 = -1, h2 = -1;
        const int rc2 = imgnative_crop(c1, r2, &c2, &w2, &h2);
        chk(rc2 == 0 && w2 == 2 && h2 == 2,
            "产出帧可再裁（4 通道不变式保持；rc=" + std::to_string(rc2) + " " + std::to_string(w2) + "×" + std::to_string(h2) + "）");
        // 二层裁剪的 (0,0) == 源帧 (1,0)：b=10,g=0,r=101,a=200
        const Px p = pixel(c2, 0, 0);
        chk(p.r == 101 && p.g == 0 && p.b == 10 && p.a == 200,
            "二层裁剪坐标累积正确（实际 " + std::to_string(p.r) + "," + std::to_string(p.g) + "," +
            std::to_string(p.b) + "," + std::to_string(p.a) + "）");
        imgnative_release(s2);
        imgnative_release(c1);
        imgnative_release(c2);
    }

    // 7) 出参校验：出参指针为 null → INVALID_PARAM（与 gray 同口径），且不崩
    {
        const int32_t r[4] = {0, 0, 1, 1};
        int64_t ok_ref = -1; int32_t ok_w = -1, ok_h = -1;
        chk(imgnative_crop(src, r, nullptr, &ok_w, &ok_h) == 4, "out_ref 为 null → INVALID_PARAM(4)");
        chk(imgnative_crop(src, r, &ok_ref, nullptr, &ok_h) == 4, "out_w 为 null → INVALID_PARAM(4)");
        chk(imgnative_crop(src, r, &ok_ref, &ok_w, nullptr) == 4, "out_h 为 null → INVALID_PARAM(4)");
        chk(ok_ref == -1 && ok_w == -1 && ok_h == -1, "出参校验失败也不写出参");
    }

    // 8) **别名判据：裁的是拷贝，不是共享视图。**
    //    C API 的像素读法看不见这件事（视图与拷贝读出同一串字节 —— 见第 4 段），
    //    能看见的是内存归属：4000×4000×4 ≈ 64MB 的大源帧裁 1×1，然后放掉源帧。
    //    .clone() 实现 → 大缓冲归还分配器（RSS 回落）；
    //    `(*f)(roi)` 视图实现 → 1×1 的产出帧共享源帧的 UMatData，引用计数不为零
    //    → 整块 64MB 释放不掉，0.3MB 的产出帧钉住 64MB 常驻。
    //    阈值 16MB 取在两种实现的中间（拷贝残留≈0，视图残留≈64MB），留 4 倍余量。
    {
        const long base = rss_kb();
        const int64_t big = dec(d + "/big.png", cv::Mat::zeros(4000, 4000, CV_8UC4));
        const int32_t one[4] = {10, 10, 1, 1};
        int64_t tiny = -1; int32_t tw = -1, th = -1;
        chk(imgnative_crop(big, one, &tiny, &tw, &th) == 0 && tw == 1 && th == 1,
            "从 4000×4000 源帧裁出 1×1");
        const long both = rss_kb();
        chk(both - base > 32 * 1024, "裁的时候大缓冲确实在（+64MB 量级，实际 +" +
            std::to_string((both - base) / 1024) + "MB）—— 否则这条判据本身是空转");
        chk(imgnative_release(big) == 0, "放掉大源帧");
        const long after = rss_kb();
        const long residue_mb = (after - base) / 1024;
        chk(after - base < 16 * 1024,
            "产出 1×1 帧没有钉住源帧的大缓冲（放源帧后残留 " + std::to_string(residue_mb) +
            "MB；视图实现会留 ~64MB）");
        // 产出帧仍可用：别名判据不能靠"把它也放掉"来通过
        const Px p = pixel(tiny, 0, 0);
        chk(p.rc == 0 && p.r == 0 && p.g == 0 && p.b == 0 && p.a == 0,
            "大源帧放掉之后 1×1 产出帧照常可读（全 0 像素）");
        imgnative_release(tiny);
    }

    std::printf("\nchecks=%d failures=%d\n", checks, fails);
    return fails == 0 ? 0 : 1;
}
