// 宿主机侧 ingest 语义验证（bridge/image/src/main/cpp/imgnative.cpp 直链，零 JNI）。
//
// 为什么有这一个文件：`imgnative_ingest` 是 §18 第 8 项 (b)「截屏帧与 decode 帧共用
// 一个帧表」的落点（2026-09-25 拍板），它有两处**编得过不代表读对**的判读：
//   * **通道序**：入参是 Android `Bitmap` 的 R,G,B,A 在内存序，帧表不变式是 B,G,R,A
//     —— swizzle 漏了/译反了，`imgnative_color` 照样命中，只是回包的 r/b 互换，
//     而"截屏帧上找色"恰恰是最常走的那条链路，静默错答案的代价最高；
//   * **不持调用方的字节**：源是读视图、cvtColor 必须另开缓冲 —— 若哪天改成
//     "就地 swizzle"，调用方复用同一块 buffer 时帧内容会在背后变化。
//
// 另外钉住"共用帧表"这条本文件存在的理由：ingest 的号段与 decode 同源（g_next_ref），
// 且两者的帧都进同一张 g_frames —— 这是"截屏帧可以直接当 findImage haystack"的
// 结构前提，JVM 侧 mock 看不见（mock 只回一个自报的 ref）。
//
// 跑法见 test/cpp/run-host-tests.sh（同 commit OpenCV 静态库，与其余 host 测试同批）。
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include <unistd.h>

#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

extern "C" {
int imgnative_ingest(const uint8_t* source, int32_t width, int32_t height,
                     int64_t* out_ref, int32_t* out_w, int32_t* out_h);
int imgnative_decode(const char* path, int64_t* out_ref, int32_t* out_w, int32_t* out_h);
int imgnative_match(int64_t haystack, int64_t needle, double threshold,
                    int32_t* out_x, int32_t* out_y,
                    int32_t* out_w, int32_t* out_h,
                    double* out_conf, int32_t* out_match);
int imgnative_color(int64_t frame, const int32_t* color, int32_t tolerance,
                    const int32_t* region,
                    int32_t* out_x, int32_t* out_y,
                    int32_t* out_r, int32_t* out_g, int32_t* out_b, int32_t* out_a,
                    int64_t* out_scanned);
int imgnative_release(int64_t ref);
}

namespace {

int failures = 0;
int checks = 0;

void check(bool ok, const std::string& what) {
    ++checks;
    if (!ok) {
        ++failures;
        std::printf("  [FAIL] %s\n", what.c_str());
    }
}

// 一张 width*height 的 RGBA 紧排（Android ARGB_8888 的在内存序）。
// 像素 (x,y) 在偏移 (y*width + x)*4。
struct Frame {
    std::vector<uint8_t> rgba;
    int32_t w = 0, h = 0;
};

struct Hit {
    int rc = 0;
    int32_t x = 0, y = 0, r = 0, g = 0, b = 0, a = 0;
    int64_t scanned = -1;
};

Hit run_color(int64_t frame, const std::vector<int32_t>& color, int32_t tol) {
    Hit h;
    h.rc = imgnative_color(frame, color.data(), tol, nullptr,
                           &h.x, &h.y, &h.r, &h.g, &h.b, &h.a, &h.scanned);
    return h;
}

// 把 RGBA 紧排装进帧表；回 nativeRef（失败回 0，配合 rc 使用）。
int64_t ingest(const Frame& f, int* rc, int32_t* w = nullptr, int32_t* h = nullptr) {
    int64_t ref = 0;
    int32_t ow = 0, oh = 0;
    *rc = imgnative_ingest(f.rgba.data(), f.w, f.h, &ref, &ow, &oh);
    if (w) *w = ow;
    if (h) *h = oh;
    return ref;
}

}  // namespace

int main() {
    // ── 1) 通道序：入 RGBA、帧表存 BGRA，回包必须是**入参那份 RGBA** ──────────
    // 这条是本文件的核心：swizzle 译反只表现为回包 r/b 互换，静态门抓不到。
    {
        Frame f;
        f.w = 3; f.h = 2;
        f.rgba.assign(static_cast<size_t>(f.w) * f.h * 4, 0);
        // (0,0) = 纯红 (255,0,0,255)；(1,0) = 纯绿；(2,0) = 纯蓝；(0,1) = 灰
        auto px = [&](int x, int y, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
            size_t i = (static_cast<size_t>(y) * f.w + x) * 4;
            f.rgba[i] = r; f.rgba[i + 1] = g; f.rgba[i + 2] = b; f.rgba[i + 3] = a;
        };
        px(0, 0, 255, 0, 0, 255);
        px(1, 0, 0, 255, 0, 255);
        px(2, 0, 0, 0, 255, 255);
        px(0, 1, 18, 52, 86, 200);

        int rc = -1;
        int32_t ow = 0, oh = 0;
        const int64_t ref = ingest(f, &rc, &ow, &oh);
        check(rc == 0, "ingest 合法尺寸 rc=0（实际 " + std::to_string(rc) + "）");
        check(ow == 3 && oh == 2, "ingest 回报帧真尺寸 3x2");

        // 找纯红：目标色按 **R,G,B,A** 传（契约序），命中必须是 (0,0) 且回包 r=255。
        const Hit red = run_color(ref, {255, 0, 0, 255}, 0);
        check(red.rc == 0 && red.x == 0 && red.y == 0,
              "找纯红命中 (0,0)（实际 x=" + std::to_string(red.x) + " y=" + std::to_string(red.y) + "）");
        check(red.r == 255 && red.g == 0 && red.b == 0,
              "回包 r/g/b = 255/0/0（通道序译反会变成 0/0/255）：实际 "
                  + std::to_string(red.r) + "/" + std::to_string(red.g) + "/" + std::to_string(red.b));

        // 找纯蓝：若 RGBA→BGRA 漏做，这里的 b 会读成 r（=255）而 r 读成 b（=0）。
        const Hit blue = run_color(ref, {0, 0, 255, 255}, 0);
        check(blue.rc == 0 && blue.x == 2 && blue.y == 0, "找纯蓝命中 (2,0)");
        check(blue.r == 0 && blue.b == 255, "纯蓝回包 r=0/b=255（实际 "
                  + std::to_string(blue.r) + "/" + std::to_string(blue.b) + "）");

        // a 分量参与判定（Android 帧 alpha 不是恒 255）：(0,1) 是 a=200 的灰。
        const Hit a200 = run_color(ref, {18, 52, 86, 200}, 0);
        check(a200.rc == 0 && a200.x == 0 && a200.y == 1 && a200.a == 200,
              "a 分量参与判定：命中第 1 行且 a=200");
        const Hit a255 = run_color(ref, {18, 52, 86, 255}, 0);
        check(a255.rc == 0 && a255.x == -1, "目标 a=255 → 未命中（扫过了、没有）");

        check(imgnative_release(ref) == 0, "ingest 的帧可 release");
        check(imgnative_release(ref) == 1, "再 release → STALE（放掉即不在场）");
    }

    // ── 2) 共用帧表：ingest 与 decode 同号段，截屏帧可当 matchTemplate 的 haystack ──
    // 这条是 §18-8(b) 的结构前提：两张来源进同一张 g_frames，JVM 侧 mock 看不见。
    {
        // 先 ingest 一帧（占一个号），再 decode 一张真 PNG —— 后者的号必须 > 前者，
        // 说明两者共用 g_next_ref（各自计数器的话会撞成同一个号）。
        Frame f;
        f.w = 8; f.h = 8;
        f.rgba.assign(static_cast<size_t>(f.w) * f.h * 4, 0);
        // 铺一个 4x4 的红色方块在 (0,0)（作为"截屏里有个图标"）
        for (int y = 0; y < 4; ++y) {
            for (int x = 0; x < 4; ++x) {
                size_t i = (static_cast<size_t>(y) * f.w + x) * 4;
                f.rgba[i] = 255; f.rgba[i + 1] = 0; f.rgba[i + 2] = 0; f.rgba[i + 3] = 255;
            }
        }
        int rc1 = -1;
        const int64_t shot = ingest(f, &rc1);
        check(rc1 == 0, "截屏帧 ingest 成功");

        // 模板走 **decode**（文件来源）—— 真正的跨来源同表：一帧来自内存（截屏），
        // 一帧来自文件（images.decode），两者必须落在同一张 g_frames / 同一个号段。
        // 这正是 §18-8(b) 要取消的那条"帧不通用"纪律的结构证据。
        const char* dir = getenv("TMPDIR");
        if (dir == nullptr || dir[0] == '\0') dir = "/tmp";
        const std::string png = std::string(dir) + "/ingest_icon.png";
        cv::Mat icon_mat(4, 4, CV_8UC4, cv::Scalar(0, 0, 255, 255));  // BGRA = 纯红
        check(cv::imwrite(png, icon_mat), "写模板 PNG");
        int64_t icon = 0;
        int32_t iw = 0, ih = 0;
        const int drc = imgnative_decode(png.c_str(), &icon, &iw, &ih);
        check(drc == 0, "decode 模板 PNG 成功（rc=" + std::to_string(drc) + "）");
        check(icon > shot, "decode 与 ingest 共用 g_next_ref（文件帧号 > 截屏帧号："
              + std::to_string(icon) + " > " + std::to_string(shot) + "）");
        unlink(png.c_str());

        int32_t mx = 0, my = 0, mw = 0, mh = 0, hit = 0;
        double conf = 0.0;
        const int mrc = imgnative_match(shot, icon, 0.9, &mx, &my, &mw, &mh, &conf, &hit);
        check(mrc == 0 && hit == 1, "截屏帧当 haystack、模板当 needle：命中（跨来源同帧表）");
        check(mw == 4 && mh == 4, "命中区域 = 模板尺寸 4x4");

        // 放掉截屏帧后，模板帧仍在场（各自独立 release —— 与 decode 产出帧同一条纪律）。
        check(imgnative_release(shot) == 0, "release 截屏帧成功");
        const int32_t dummy_color[4] = {255, 0, 0, 255};
        int32_t cx = 0, cy = 0, cr = 0, cg = 0, cb = 0, ca = 0;
        int64_t scanned = 0;
        check(imgnative_color(icon, dummy_color, 0, nullptr,
                              &cx, &cy, &cr, &cg, &cb, &ca, &scanned) == 0,
              "放掉截屏帧后模板帧照常可用（两帧独立在场）");
        check(imgnative_release(icon) == 0, "release 模板帧成功");
    }

    // ── 3) 拒收：参数关系不成立一律 INVALID_PARAM，不写 out ─────────────────────
    {
        Frame f;
        f.w = 2; f.h = 2;
        f.rgba.assign(16, 0);
        int64_t ref = 0;
        int32_t ow = 0, oh = 0;
        check(imgnative_ingest(nullptr, 2, 2, &ref, &ow, &oh) == 4, "空指针 → INVALID_PARAM");
        check(imgnative_ingest(f.rgba.data(), 0, 2, &ref, &ow, &oh) == 4, "宽 0 → INVALID_PARAM");
        check(imgnative_ingest(f.rgba.data(), 2, -1, &ref, &ow, &oh) == 4, "高为负 → INVALID_PARAM");
        check(imgnative_ingest(f.rgba.data(), 2, 2, nullptr, &ow, &oh) == 4, "空 out_ref → INVALID_PARAM");
        check(imgnative_ingest(f.rgba.data(), 2, 2, &ref, nullptr, &oh) == 4, "空 out_w → INVALID_PARAM");
        check(imgnative_ingest(f.rgba.data(), 2, 2, &ref, &ow, nullptr) == 4, "空 out_h → INVALID_PARAM");
        // 拒收是早退：出参一个字节都不写（与 match/gray 同口径 —— 调用方不能拿
        // 残留值当"成功的结果"读）。
        ow = -777; oh = -777;
        imgnative_ingest(f.rgba.data(), 0, 2, &ref, &ow, &oh);
        check(ow == -777 && oh == -777, "拒收早退：out_w/out_h 未被改写");
    }

    std::printf("\nchecks=%d failures=%d\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
