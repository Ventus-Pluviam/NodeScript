// decode 归一化的通道/深度覆盖（对偶 host_color_test.cpp 的判据 1 与 4）。
//
// 钉住四档归一化结果：灰度 PNG（r=g=b=灰度、a=255）、16 位 PNG（压回 8 位）、
// 3 通道 PNG（补 alpha=255）、4 通道 PNG（alpha 原样，findColor 的 a 分量真参与）。
// 回归锚点：**IMREAD_COLOR 那条老路**会把任何来源压成 3 通道 BGR，alpha 丢掉，
// 于是 Vec4b 的第 4 字节静默读进下一行像素 —— 回给脚本的 a 是垃圾，
// tolerance 对 a 的判定也是垃圾。这一段就是把那条路焊死不让回来。
#include <cstdint>
#include <cstdio>
#include <string>
#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
extern "C" {
int imgnative_decode(const char*, int64_t*, int32_t*, int32_t*);
int imgnative_color(int64_t, const int32_t*, int32_t, const int32_t*,
                    int32_t*, int32_t*, int32_t*, int32_t*, int32_t*, int32_t*, int64_t*);
int imgnative_release(int64_t);
}
static int fails = 0, checks = 0;
static void chk(bool ok, const std::string& w) { ++checks; if (!ok) { ++fails; std::printf("  [FAIL] %s\n", w.c_str()); } }
static int findit(int64_t ref, int32_t r, int32_t g, int32_t b, int32_t a, int32_t tol, int32_t* out) {
    const int32_t c[4] = {r, g, b, a};
    int32_t x = 0, y = 0, orr = 0, og = 0, ob = 0, oa = 0; int64_t sc = -1;
    const int rc = imgnative_color(ref, c, tol, nullptr, &x, &y, &orr, &og, &ob, &oa, &sc);
    if (out) *out = 0;
    if (rc != 0) return rc;
    out[0] = x; out[1] = y; out[2] = orr; out[3] = og; out[4] = ob; out[5] = oa; out[6] = (int32_t)sc;
    return 0;
}
int main() {
    const std::string d = "/tmp/imgtest";

    // 1) 灰度 PNG：归一后 r=g=b=灰度值、a=255（不透明常识）
    {
        cv::Mat g(2, 2, CV_8UC1);
        for (int r = 0; r < 2; ++r) for (int c = 0; c < 2; ++c) g.at<unsigned char>(r, c) = (r == 0 && c == 0) ? 200 : 100;
        const std::string p = d + "/gray.png";
        chk(cv::imwrite(p, g), "灰度 PNG 写盘");
        int64_t ref = 0; int32_t w = 0, h = 0;
        chk(imgnative_decode(p.c_str(), &ref, &w, &h) == 0, "灰度 decode 成功");
        int32_t o[7] = {0};
        chk(findit(ref, 200, 200, 200, 255, 0, o) == 0, "灰度图按 r=g=b=灰度 命中");
        if (o[6] > 0) {
            chk(o[0] == 0 && o[1] == 0, "命中 (0,0)（实际 " + std::to_string(o[0]) + "," + std::to_string(o[1]) + "）");
            chk(o[2] == 200 && o[3] == 200 && o[4] == 200 && o[5] == 255,
                "灰度归一后分量 (200,200,200,255)（实际 " + std::to_string(o[2]) + "," +
                std::to_string(o[3]) + "," + std::to_string(o[4]) + "," + std::to_string(o[5]) + "）");
        }
        imgnative_release(ref);
    }

    // 2) 16 位 PNG：压回 8 位
    {
        cv::Mat m(2, 2, CV_16UC1);
        for (int r = 0; r < 2; ++r) for (int c = 0; c < 2; ++c)
            m.at<uint16_t>(r, c) = (r == 0 && c == 0) ? 60000 : 1000;   // >>8 = 234 / 3
        const std::string p = d + "/deep16.png";
        chk(cv::imwrite(p, m), "16 位 PNG 写盘");
        int64_t ref = 0; int32_t w = 0, h = 0;
        const int rc = imgnative_decode(p.c_str(), &ref, &w, &h);
        if (rc == 0) {
            int32_t o[7] = {0};
            findit(ref, 234, 234, 234, 255, 0, o);
            chk(o[0] == 0 && o[1] == 0, "16 位压 8 位后命中 (0,0)（实际 " + std::to_string(o[0]) + "）");
            imgnative_release(ref);
        } else {
            chk(false, "16 位 decode 也应成功（rc=" + std::to_string(rc) + "）");
        }
    }

    // 3) 3 通道 BGR PNG：alpha 补 255（不是 0、不是未定义）
    {
        cv::Mat m(2, 2, CV_8UC3, cv::Scalar(30, 60, 90));   // B30 G60 R90
        const std::string p = d + "/bgr3.png";
        chk(cv::imwrite(p, m), "3 通道 PNG 写盘");
        int64_t ref = 0; int32_t w = 0, h = 0;
        chk(imgnative_decode(p.c_str(), &ref, &w, &h) == 0, "3 通道 decode 成功");
        int32_t o[7] = {0};
        chk(findit(ref, 90, 60, 30, 255, 0, o) == 0, "3 通道帧找色命中（alpha 补 255）");
        chk(o[2] == 90 && o[3] == 60 && o[4] == 30 && o[5] == 255,
            "3 通道补 alpha=255 后分量 (90,60,30,255)（实际 " + std::to_string(o[2]) + "," +
            std::to_string(o[3]) + "," + std::to_string(o[4]) + "," + std::to_string(o[5]) + "）");
        imgnative_release(ref);
    }

    // 4) 4 通道 BGRA PNG 且 alpha 有区分：a 分量参与判定（这次是真参与）
    {
        cv::Mat m(2, 2, CV_8UC4, cv::Scalar(10, 20, 30, 0));
        // at(r, c) 是 (行,列)——下面注释里的 (x,y) 是列先行，别混
        m.at<cv::Vec4b>(0, 0) = cv::Vec4b(10, 20, 30, 40);    // (x=0,y=0) a=40
        m.at<cv::Vec4b>(0, 1) = cv::Vec4b(10, 20, 30, 200);   // (x=1,y=0) a=200
        m.at<cv::Vec4b>(1, 0) = cv::Vec4b(10, 20, 30, 250);   // (x=0,y=1) a=250
        m.at<cv::Vec4b>(1, 1) = cv::Vec4b(10, 20, 30, 255);   // (x=1,y=1) a=255
        const std::string p = d + "/bgrdistinct.png";
        chk(cv::imwrite(p, m), "4 通道 PNG 写盘");
        int64_t ref = 0; int32_t w = 0, h = 0;
        chk(imgnative_decode(p.c_str(), &ref, &w, &h) == 0, "4 通道 decode 成功");
        chk(w == 2 && h == 2, "宽高真值");

        int32_t o[7] = {0};
        // 只放 a=40 的像素过
        chk(findit(ref, 30, 20, 10, 40, 0, o) == 0, "a=40 精确命中");
        chk(o[0] == 0 && o[1] == 0 && o[5] == 40, "命中 (0,0) 且回包 a=40（实际 " + std::to_string(o[5]) + "）");
        int32_t o2[7] = {0};
        chk(findit(ref, 30, 20, 10, 255, 0, o2) == 0, "a=255 精确命中");
        chk(o2[0] == 1 && o2[1] == 1, "只命中右下 (x=1,y=1)（实际 " + std::to_string(o2[0]) + "," + std::to_string(o2[1]) + "）");
        int32_t o3[7] = {0};
        // a=250 附近 ±5：放 a=255 或 250 过
        chk(findit(ref, 30, 20, 10, 250, 5, o3) == 0, "a=250 ±5 命中");
        // findNonZero 点列是**列主序**（x 变化快）：带内 a=200 在 (1,0)、a=250 在 (0,1)，
        // 点列先吐 (0,1)。所以 points[0] 的"首个"= 列主序首个，不是行主序首个。
        chk(o3[0] == 0 && o3[1] == 1, "带内首个命中在 (0,1)（实际 " + std::to_string(o3[0]) + "," + std::to_string(o3[1]) + "）");
        chk(o3[5] == 250, "回包实际像素 a=250（实际 " + std::to_string(o3[5]) + "）");
        imgnative_release(ref);
    }

    std::printf("\nchecks=%d failures=%d\n", checks, fails);
    return fails == 0 ? 0 : 1;
}
