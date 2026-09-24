// 宿主机侧 findColor 语义验证（bridge/image/src/main/cpp/imgnative.cpp 直链，零 JNI）。
//
// 为什么有这一个文件：`imgnative_color` 里有几处判读**编得过不代表读对** ——
//   * Vec4b 回读的通道序（px[2]→r / px[1]→g / px[0]→b）：译反了照样命中、
//     只是回包的 r/b 互换，静态门与桥面 mock 都抓不到（mock 不碰像素）；
//   * ROI 内坐标 + roi 左上角 = 全帧坐标：少了这一步脚本拿到的就是角落坐标系；
//   * "扫过了、没有"（x=-1, scanned=N）与"扫过 0 像素"（ERR_INVALID_PARAM）
//     的区分：混成一边，脚本会把空区域当成搜过一遍；
//   * 逐分量容差（不是欧氏距离）：差一个量级的判据在同样输入下也能出结论。
// 真机是另一个验证途径，但"等真机才发现通道序译反"太贵 —— 于是本机用 host
// OpenCV 静态库跑同一份计算核（x86_64，无 JNI，`imgnative.cpp` 零 android.*）。
//
// 跑法见 test/cpp/run-host-tests.sh（OpenCV 4.14.0 按 build-opencv.sh 同款
// commit 固定，kleidicv OFF —— host 是 x86_64，那条加速面只在 aarch64 上）。
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

extern "C" {
int imgnative_decode(const char* path, int64_t* out_ref, int32_t* out_w, int32_t* out_h);
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

// 一张 4×3、4 通道的 PNG —— 只有 4 通道帧能让 A 通道参与判定（decode 归一后
// 帧恒 4 通道，见 imgnative.cpp 的 IMREAD_UNCHANGED + cvtColor 段）。
// at(r,c) 是 (行,列)，注释里的 (x,y) 是列先行，别混。
struct Hit {
    int rc = 0;
    int32_t x = 0, y = 0, r = 0, g = 0, b = 0, a = 0;
    int64_t scanned = -1;
};

Hit run(int64_t frame, std::vector<int32_t> color, int32_t tol, const int32_t* region) {
    Hit h;
    h.rc = imgnative_color(frame, color.data(), tol, region, &h.x, &h.y,
                           &h.r, &h.g, &h.b, &h.a, &h.scanned);
    return h;
}

}  // namespace

int main() {
    const std::string dir = "/tmp/imgtest";

    // ── 一张 4×3、4 通道的 PNG：A 恒 255，BGR 三个色块 ────────────────────
    //   (0,0)=PureBlue  (1,0)=PureGreen (2,0)=PureRed  (3,0)=Gray
    //   第 1/2 行整行 = Gray，用来验 region 的 y 偏移与"扫过但没命中"。
    cv::Mat img(3, 4, CV_8UC4);
    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 4; ++c) {
            cv::Vec4b px(128, 128, 128, 255);   // 默认灰
            if (r == 0) {
                if (c == 0) px = cv::Vec4b(255, 0, 0, 255);      // 文件里是"蓝"
                if (c == 1) px = cv::Vec4b(0, 255, 0, 255);      // "绿"
                if (c == 2) px = cv::Vec4b(0, 0, 255, 255);      // "红"
                if (c == 3) px = cv::Vec4b(255, 255, 255, 255);  // 白
            }
            img.at<cv::Vec4b>(r, c) = px;
        }
    }
    const std::string png = dir + "/colorframe.png";
    check(cv::imwrite(png, img), "imwrite 出 4 通道 PNG");

    int64_t ref = 0;
    int32_t w = 0, h = 0;
    check(imgnative_decode(png.c_str(), &ref, &w, &h) == 0, "decode 成功");
    check(w == 4 && h == 3, "回包宽高是文件真值（实际 " + std::to_string(w) + "x" + std::to_string(h) + "）");

    // ── 1) 通道序：文件里 (0,0) 是"蓝"，即 R=0,G=0,B=255 ─────────────────
    // 这是本次测试的头号目的：Scalar 下界与 Vec4b 回读用的是**同一套分量序**。
    // 请求序是 [r,g,b,a]，所以请求 (0,0,255,255) 该命中 (0,0)，而回包的
    // r/g/b 必须是 (0,0,255) —— 若回成 (255,0,0)，说明 Vec4b 的 px[0]/px[2]
    // 读反了（这一行从没被执行过）。
    {
        const Hit h = run(ref, {0, 0, 255, 255}, 0, nullptr);
        check(h.rc == 0, "命中 rc=0");
        check(h.x == 0 && h.y == 0, "命中 (0,0)（实际 " + std::to_string(h.x) + "," + std::to_string(h.y) + "）");
        check(h.r == 0 && h.g == 0 && h.b == 255 && h.a == 255,
              "回包分量序 R,G,B,A（实际 r=" + std::to_string(h.r) + " g=" + std::to_string(h.g) +
                  " b=" + std::to_string(h.b) + " a=" + std::to_string(h.a) + "）");
        check(h.scanned == 12, "扫过全帧 12 像素（实际 " + std::to_string(h.scanned) + "）");
    }

    // ── 2) 同名请求换成"绿"：命中 (1,0)，分量序同理 ──────────────────────
    {
        const Hit h = run(ref, {0, 255, 0, 255}, 0, nullptr);
        check(h.x == 1 && h.y == 0, "命中 (1,0)");
        check(h.r == 0 && h.g == 255 && h.b == 0, "R,G,B,A = 0,255,0,255");
    }

    // ── 3) 逐分量容差：目标色各偏一点、用 tolerance 拉回来才命中 ──────────
    // inRange 是**逐分量包含**（不是欧氏距离）：目标 (300 不行) → 用
    // (200,0,0) 配容差让 (208,2,3) 这类像素进带。
    {
        // 灰 (128,128,128) 与目标 (130,120,128) 逐分量差 2/8/0：容差 8 全进，
        // 容差 2 则 G 分量（差 8）把这一行全挡住 → 扫过没命中。
        const Hit inside = run(ref, {130, 120, 128, 255}, 8, nullptr);
        check(inside.rc == 0 && inside.x >= 0, "容差 8 命中灰像素");
        check(inside.x == 0 && inside.y == 1,
              "扫描序首个：第 0 行的四色块都不是带内的，首个命中在 (0,1)（实际 " +
                  std::to_string(inside.x) + "," + std::to_string(inside.y) + "）");
        const Hit outside = run(ref, {130, 120, 128, 255}, 2, nullptr);
        check(outside.rc == 0 && outside.x == -1 && outside.scanned == 12,
              "容差 2 逐分量挡住 → 扫过 12 像素未命中（x=-1, scanned=12）");
    }

    // ── 4) 容差贴边夹取：目标 0/255 时 [c-t, c+t] 不许溢出成负数 ──────────
    {
        // 目标 (0,0,255,255) 容差 8：白像素 (255,255,255,255) 的 R/G 在带内
        // （0..8 不含 255）→ 不该命中白；而纯蓝 (0,0,255) 该命中。
        const Hit blue = run(ref, {0, 0, 255, 255}, 8, nullptr);
        check(blue.x == 0 && blue.y == 0, "容差 8 仍命中 (0,0) 的纯蓝");
        const Hit white = run(ref, {250, 250, 250, 255}, 8, nullptr);
        check(white.rc == 0 && white.x == 3 && white.y == 0,
              "目标近白 + 容差 8 → 命中 (3,0) 的白（实际 " + std::to_string(white.x) + "）");
    }

    // ── 5) region：ROI 浅视图 + 命中坐标加回左上角 = 全帧坐标 ─────────────
    {
        // 只扫第 0 行：首个命中仍是 (0,0)（region 从 0 开始时无偏移可验）。
        const int32_t row0[4] = {0, 0, 4, 1};
        const Hit h = run(ref, {0, 0, 255, 255}, 0, row0);
        check(h.scanned == 4, "region=整行 → 只扫 4 像素（实际 " + std::to_string(h.scanned) + "）");
        check(h.x == 0 && h.y == 0, "region 起点 (0,0) 命中无偏移");

        // 关键：region 从 (1,1) 起、2×2 —— 视图内首行是整灰行。
        // 命中坐标必须是**全帧**坐标 (1,1)，而不是视图内坐标 (0,0)。
        const int32_t sub[4] = {1, 1, 2, 2};
        const Hit g = run(ref, {128, 128, 128, 255}, 0, sub);
        check(g.scanned == 4, "2×2 region 扫 4 像素");
        check(g.x == 1 && g.y == 1,
              "命中坐标加回 roi 左上角 → 全帧 (1,1)（实际 " + std::to_string(g.x) + "," +
                  std::to_string(g.y) + "）—— 少了这步脚本拿到的就是角落坐标系");

        // region 里没有目标色 → 扫过、没有（不是没扫）
        const Hit none = run(ref, {0, 0, 255, 255}, 0, sub);
        check(none.rc == 0 && none.x == -1 && none.scanned == 4,
              "region 内无目标色 → x=-1 且 scanned=4（扫过了、没有）");
    }

    // ── 6) region 缺键/全帧与半越界的区分 ──────────────────────────────
    {
        const int32_t w0[4] = {0, 0, 0, 3};   // 宽 0 → 扫过 0 像素
        const Hit h = run(ref, {1, 2, 3, 255}, 0, w0);
        check(h.rc == 4, "region 宽 0 → INVALID_PARAM（扫过 0 像素，不是'没有'）");

        const int32_t out[4] = {2, 0, 4, 3};  // 右边界越帧
        check(run(ref, {1, 2, 3, 255}, 0, out).rc == 4, "region 右越界 → INVALID_PARAM");
        const int32_t neg[4] = {-1, 0, 2, 3};
        check(run(ref, {1, 2, 3, 255}, 0, neg).rc == 4, "region 负起点 → INVALID_PARAM");
    }

    // ── 7) 分量域与容差域在本层兜底（handler 已拒，这里是第二道） ────────
    {
        check(run(ref, {256, 0, 0, 255}, 0, nullptr).rc == 4, "分量 256 → INVALID_PARAM");
        check(run(ref, {0, 0, 0, -1}, 0, nullptr).rc == 4, "分量 -1 → INVALID_PARAM");
        check(run(ref, {0, 0, 0, 255}, 256, nullptr).rc == 4, "容差 256 → INVALID_PARAM");
        check(run(ref, {0, 0, 0, 255}, -1, nullptr).rc == 4, "容差 -1 → INVALID_PARAM");
        check(run(ref, {0, 0, 0, 255}, 0, nullptr).rc == 0, "全 0 分量的合法请求（命中与否不限）");
    }

    // ── 8) 帧句柄纪律：release 后同帧 → STALE（与 match 同口径） ─────────
    {
        check(imgnative_release(ref) == 0, "release 成功");
        check(run(ref, {1, 2, 3, 255}, 0, nullptr).rc == 1, "已释放的帧找色 → STALE_HANDLE");
        check(imgnative_release(ref) == 1, "再放同帧 → STALE（不放静默成功的第二次）");
        check(run(4242, {1, 2, 3, 255}, 0, nullptr).rc == 1, "从不存在的帧 → STALE");
    }

    // ── 9) A 通道参与判定：目标 a=200 配容差 100 时，a=255 与 a=100 都在带内，
    //      但目标 a=255 容差 0 只放 alpha=255 的像素过（灰块 a=255 恒真，
    //      所以这条只能证明"没把 a 固定成 255 忽略掉"：造一张 a=0 的帧再试）。
    {
        cv::Mat alpha(2, 2, CV_8UC4);
        for (int r = 0; r < 2; ++r) {
            for (int c = 0; c < 2; ++c) alpha.at<cv::Vec4b>(r, c) = cv::Vec4b(10, 20, 30, r == 0 ? 200 : 100);
        }
        const std::string p2 = dir + "/alphaframe.png";
        check(cv::imwrite(p2, alpha), "imwrite 第二张");
        int64_t r2 = 0;
        int32_t w2 = 0, h2 = 0;
        check(imgnative_decode(p2.c_str(), &r2, &w2, &h2) == 0, "第二张 decode");
        const Hit h = run(r2, {30, 20, 10, 100}, 0, nullptr);
        check(h.rc == 0 && h.y == 1,
              "a 分量参与判定：a=100 的像素在第 1 行（实际 y=" + std::to_string(h.y) + "）");
        check(h.a == 100, "回包 a=100（实际 " + std::to_string(h.a) + "）");
        const Hit none = run(r2, {30, 20, 10, 255}, 0, nullptr);
        check(none.rc == 0 && none.x == -1, "目标 a=255 两行都不在带内 → 未命中");
        imgnative_release(r2);
    }

    std::printf("\nchecks=%d failures=%d\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
