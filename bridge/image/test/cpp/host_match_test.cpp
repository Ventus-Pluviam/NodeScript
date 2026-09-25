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
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

extern "C" {
int imgnative_decode(const char* path, int64_t* out_ref, int32_t* out_w, int32_t* out_h);
int imgnative_match(int64_t haystack, int64_t needle, double threshold,
                    int32_t* out_x, int32_t* out_y,
                    int32_t* out_w, int32_t* out_h,
                    double* out_conf, int32_t* out_match);
int imgnative_release(int64_t ref);
}

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
        int32_t x = -1, y = -1, w = -1, h = -1, m = -1; double c = -1;
        const int rc = imgnative_match(h_ref, n_ref, 0.5, &x, &y, &w, &h, &c, &m);
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
        int32_t x = -1, y = -1, w = -1, h = -1, m = -1; double c = -1;
        const int rc = imgnative_match(h_ref, o_ref, 0.9, &x, &y, &w, &h, &c, &m);
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
        int32_t x = -1, y = -1, w = -1, h = -1, m = -1; double c = -1;
        imgnative_match(h_ref, n_ref, 0.5, &x, &y, &w, &h, &c, &m);   // 拿真置信度
        const double exact = c;
        chk(m == 1, "第 1 条先确认命中，取其置信度 " + std::to_string(exact) + " 作边界");
        int32_t mx = -1, my = -1, mw = -1, mh = -1, mm = -1; double mc = -1;
        chk(imgnative_match(h_ref, n_ref, exact, &mx, &my, &mw, &mh, &mc, &mm) == 0, "等值阈值可用");
        chk(mm == 1, "maxv == threshold 判命中（≥ 不是 >）");
        chk(mx == 2 && my == 3, "等值阈值下坐标不变（实际 " + std::to_string(mx) + "," + std::to_string(my) + "）");
        const double just_above = exact + 1e-6;
        int32_t ax = -1, ay = -1, aw = -1, ah = -1, am = -1; double ac = -1;
        chk(imgnative_match(h_ref, n_ref, just_above, &ax, &ay, &aw, &ah, &ac, &am) == 0, "略高阈值可用");
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
        const int rc = imgnative_match(h_ref, big_ref, 0.5, &x, &y, &w, &h, &c, &m);
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
        chk(imgnative_match(h_ref, n_ref, 0.5, &x, &y, &w, &h, &c, &m) == 1,
            "needle 已释放 → ERR_STALE_HANDLE(1)");
        chk(imgnative_match(n_ref, h_ref, 0.5, &x, &y, &w, &h, &c, &m) == 1,
            "haystack 已释放同样 STALE（两个方向都钉）");
        imgnative_release(h_ref);
    }

    std::printf("\nchecks=%d failures=%d\n", checks, fails);
    return fails == 0 ? 0 : 1;
}
