// 宿主机侧特征匹配算子语义验证（bridge/image/src/main/cpp/imgnative.cpp 直链，零 JNI）。
//
// `imgnative_feature` 是**最后一个 P1 算子**（灰度/裁剪/缩放/旋转之后），且是第一个
// **不产出帧、只回坐标**的算子 —— 回包是模板中心在场景中的 (x,y,confidence)，
// 不是新帧号。JVM/JS 两门结构上看不见的东西：mock 只回编好的坐标，ORB 参数
// （nfeatures/距离口径/ratio 阈值）写错照样绿。
//
// 链（全固定，不做入参）：ORB(nfeatures=1000) → BFMatcher(HAMMING) knn k=2 →
// Lowe ratio 0.75 → 中位数偏移 ±3px 几何一致性计数。每个固定点都有 host 实测依据
// （见实现注释）：nfeatures 500/1000 描述子逐字节一致；BGRA 直喂与手转灰一致；
// ratio 0.7~0.8 不换答案。
//
// 钉七处：
//   * **命中**：圆点场景（400×300）里找左上子块（200×150），found=1，
//     坐标落在子块中心 (100,75) 附近（±10px —— 内点质心，不是逐像素精确），
//     confidence == 内点/good（[0,1]，与 matchTemplate 同域）。
//   * **未匹配是答案**：棋盘格模板 vs 圆点场景 → found=0 且 x/y/conf 全 0，
//     status 仍 0（与 matchTemplate 的 out_match=0 同一条纪律；host 实测误报
//     top 距离 60+，ratio 后 good 寥寥，几何验证过不了）。
//   * **空描述子是未匹配**：纯色模板（ORB 零关键点）→ found=0 不是 IO 错
//     （图合法，只是没有特征 —— 与"文件在但不是合法图片"的 IO 区分开）。
//   * **旋转的边界**：子块转 30° 后**未匹配**（found=0）—— 描述子层面仍对得上
//     （host 实测 rot30 crosscheck top 距离 0/0/1/1/1，ORB 的旋转不变性成立），
//     但几何阶段的中位数偏移假设（"模板是场景子块"→ 偏移常数）不再成立，内点只剩
//     1 个 → 按几何不一致判未匹配。这是设计不是 bug：真要转着找得接单应估计
//     （findHomography，calib3d 模块），那是另一个算子。这条断言钉的是这个边界
//     （把"旋转容忍"自动脑补成"转着也找到"的人会在这里红 —— 包括写测试的我自己）。
//   * **拒收是早退**：死句柄 → STALE；出参指针 null → INVALID_PARAM，
//     且四个出参一个字节都不写（与 match 同口径）。
//   * **两帧都不消耗**：匹配后场景/模板照常可读（只读，不产出，不改原帧）。
//   * **置信度语义**：命中时 0 < conf <= 1（内点/good）；未命中时 conf == 0。
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
int imgnative_feature(int64_t scene, int64_t templ, int32_t* out_found,
                      double* out_x, double* out_y, double* out_conf);
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

/** 一个从没发过号的句柄（帧表单调发号，0 恒不在场）。 */
static int64_t oracle_dead_handle() { return 0; }

/** 圆点场景（400×300，单通道）：圆/矩形/斜线 —— ORB 能抓到数百个关键点。 */
static cv::Mat dots(int W, int H) {
    cv::Mat m(H, W, CV_8UC1, cv::Scalar(0));
    cv::circle(m, cv::Point(W / 4, H / 4), 12, 255, -1);
    cv::circle(m, cv::Point(3 * W / 4, H / 3), 9, 255, -1);
    cv::circle(m, cv::Point(W / 3, 3 * H / 4), 14, 255, -1);
    cv::circle(m, cv::Point(3 * W / 4, 3 * H / 4), 7, 255, -1);
    cv::rectangle(m, cv::Point(W / 2 - 30, H / 2 - 8), cv::Point(W / 2 + 30, H / 2 + 8), 255, -1);
    cv::line(m, cv::Point(0, H - 1), cv::Point(W - 1, 0), 255, 3);
    return m;
}

struct Found { int rc = -1; int32_t f = -1; double x = -1, y = -1, c = -1; };
static Found match(int64_t scene, int64_t templ) {
    Found r;
    r.rc = imgnative_feature(scene, templ, &r.f, &r.x, &r.y, &r.c);
    return r;
}

int main() {
    const std::string d = "/tmp/imgtest-feature-" + std::to_string(getpid());
    const int rc_mkdir = ::mkdir(d.c_str(), 0755);
    if (rc_mkdir != 0 && errno != EEXIST) { std::fprintf(stderr, "[FATAL] mkdir %s: %s\n", d.c_str(), std::strerror(errno)); return 97; }

    // 场景 400×300（左上子块 200×150 含 1 个圆 + 部分直线/矩形边）
    const int64_t scene = dec(d + "/scene.png", dots(400, 300));
    if (scene < 0) { std::printf("\nchecks=%d failures=%d\n", checks, fails); return fails == 0 ? 0 : 1; }

    // 1) 命中：左上子块 → 中心 (100,75) 附近 ±10px，conf (0,1]
    int64_t sub = -1;
    {
        cv::Mat m = dots(400, 300)(cv::Rect(0, 0, 200, 150)).clone();
        sub = dec(d + "/sub.png", m);
        chk(sub >= 0, "子块模板就绪");
    }
    if (sub >= 0) {
        const Found r = match(scene, sub);
        chk(r.rc == 0, "feature 成功（rc=" + std::to_string(r.rc) + "）");
        chk(r.f == 1, "子块在场景中找到（found=" + std::to_string(r.f) + "）");
        chk(std::fabs(r.x - 100) <= 10 && std::fabs(r.y - 75) <= 10,
            "命中在子块中心 (100,75)±10（实际 " + std::to_string(r.x) + "," + std::to_string(r.y) + "）");
        chk(r.c > 0 && r.c <= 1,
            "置信度 (0,1]（内点/good；实际 " + std::to_string(r.c) + "）");
        // ratio 在 0.7~0.8 间不换答案（host 实测 good=29/30/33、几何正确都是 19）——
        // 但 ratio=0.99 会把 conf 从 0.63 拉到 0.47（多进来的全是几何外点）。
        // 这条把 conf 钉在 ±0.15 窗内：ratio 阈值被改动时这里变红。
        chk(std::fabs(r.c - 0.633) < 0.15,
            "置信度 ≈0.63±0.15（ratio 链的指纹；实际 " + std::to_string(r.c) + "）");
    }

    // 2) 未匹配是答案：棋盘格模板 vs 圆点场景
    {
        cv::Mat m(150, 200, CV_8UC1, cv::Scalar(0));
        for (int y = 0; y < 150; ++y)
            for (int x = 0; x < 200; ++x)
                if (((x / 20) + (y / 20)) % 2) m.at<unsigned char>(y, x) = 255;
        const int64_t chess = dec(d + "/chess.png", m);
        chk(chess >= 0, "棋盘格模板就绪");
        if (chess >= 0) {
            const Found r = match(scene, chess);
            chk(r.rc == 0, "误报调用成功（rc=0，未匹配是答案）");
            chk(r.f == 0, "棋盘格在圆点场景中未找到（found=" + std::to_string(r.f) + "）");
            chk(r.x == 0 && r.y == 0 && r.c == 0, "未匹配时 x/y/conf 全 0");
            imgnative_release(chess);
        }
    }

    // 3) 空描述子是未匹配：纯色模板
    {
        const int64_t blank = dec(d + "/blank.png", cv::Mat(100, 100, CV_8UC1, cv::Scalar(128)));
        chk(blank >= 0, "纯色模板就绪");
        if (blank >= 0) {
            const Found r = match(scene, blank);
            chk(r.rc == 0 && r.f == 0, "纯色模板 → 未匹配（rc=0 found=0，不是 IO 错）");
            chk(r.x == 0 && r.y == 0 && r.c == 0, "空描述子时 x/y/conf 全 0");
            imgnative_release(blank);
        }
    }

    // 4) 旋转的边界：子块转 30°（expand 画布）后**未匹配**（几何不一致 —— 见文件头）
    {
        cv::Mat m = dots(400, 300)(cv::Rect(0, 0, 200, 150)).clone();
        const cv::Point2f c(m.cols / 2.0f, m.rows / 2.0f);
        cv::Mat M = cv::getRotationMatrix2D(c, 30.0, 1.0);
        double xs[4] = {0, (double)m.cols, 0, (double)m.cols}, ys[4] = {0, 0, (double)m.rows, (double)m.rows};
        double mnx = 1e9, mxx = -1e9, mny = 1e9, mxy = -1e9;
        for (int i = 0; i < 4; ++i) {
            const double nx = M.at<double>(0, 0) * xs[i] + M.at<double>(0, 1) * ys[i] + M.at<double>(0, 2);
            const double ny = M.at<double>(1, 0) * xs[i] + M.at<double>(1, 1) * ys[i] + M.at<double>(1, 2);
            mnx = std::min(mnx, nx); mxx = std::max(mxx, nx); mny = std::min(mny, ny); mxy = std::max(mxy, ny);
        }
        const int bw = (int)std::round(mxx - mnx), bh = (int)std::round(mxy - mny);
        M.at<double>(0, 2) -= mnx; M.at<double>(1, 2) -= mny;
        cv::Mat rot; cv::warpAffine(m, rot, M, cv::Size(bw, bh), cv::INTER_LINEAR, cv::BORDER_REPLICATE);
        const int64_t rotref = dec(d + "/rot30.png", rot);
        chk(rotref >= 0, "旋转 30° 模板就绪（画布 " + std::to_string(bw) + "×" + std::to_string(bh) + "）");
        if (rotref >= 0) {
            const Found r = match(scene, rotref);
            chk(r.rc == 0 && r.f == 0,
                "转 30° 后未匹配（rc=" + std::to_string(r.rc) + " found=" + std::to_string(r.f) +
                " —— 描述子对得上，几何验证过不了，见文件头）");
            chk(r.x == 0 && r.y == 0 && r.c == 0, "几何不一致时 x/y/conf 全 0");
            imgnative_release(rotref);
        }
    }

    // 5) 拒收是早退：死句柄、空出参
    {
        int32_t f = -1; double x = -1, y = -1, c = -1;
        chk(imgnative_feature(oracle_dead_handle(), sub, &f, &x, &y, &c) == 1, "死场景句柄 → STALE(1)");
        chk(imgnative_feature(scene, oracle_dead_handle(), &f, &x, &y, &c) == 1, "死模板句柄 → STALE(1)");
        chk(f == -1, "STALE 早退，不写出参");
        int32_t f2 = -1; double x2 = -1, y2 = -1, c2 = -1;
        chk(imgnative_feature(scene, sub, nullptr, &x2, &y2, &c2) == 4, "out_found=null → INVALID_PARAM(4)");
        chk(imgnative_feature(scene, sub, &f2, nullptr, &y2, &c2) == 4, "out_x=null → INVALID_PARAM(4)");
        chk(imgnative_feature(scene, sub, &f2, &x2, nullptr, &c2) == 4, "out_y=null → INVALID_PARAM(4)");
        chk(imgnative_feature(scene, sub, &f2, &x2, &y2, nullptr) == 4, "out_conf=null → INVALID_PARAM(4)");
        chk(f2 == -1 && x2 == -1 && y2 == -1 && c2 == -1, "空出参早退，一个字节都不写");
    }

    // 6) 两帧都不消耗：匹配后场景/模板照常可解、模板可再裁剪式使用（release 顺序无关）
    {
        chk(imgnative_release(sub) == 0, "放掉模板");
        chk(imgnative_release(scene) == 0, "放掉场景（顺序无关，各自一条记录）");
        int32_t f = -1; double x = -1, y = -1, c = -1;
        chk(imgnative_feature(scene, sub, &f, &x, &y, &c) == 1, "两帧都放掉后匹配 → STALE(1)");
    }

    std::printf("\nchecks=%d failures=%d\n", checks, fails);
    return fails == 0 ? 0 : 1;
}
