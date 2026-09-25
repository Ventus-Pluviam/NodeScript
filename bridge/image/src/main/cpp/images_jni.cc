// bridge/image —— libopencv.so 的装载面（docs/framework-design.md §9.2）
//
// 分工（与 imgnative.cpp 的两层切法）：
//   - imgnative.cpp 是**纯计算核**：extern "C" 四入口（decode/match/release/color），
//     零 JNI、可单独进 host 侧单测 —— 帧表（unordered_map<refId, Mat>）与
//     状态码折折叠都在那一侧；
//   - 本文件是**装载面**：全仓图像侧唯一 #include <jni.h> 的文件，只做
//     字符串编解码 + 数组装箱 + 状态码原样上抛（**不解释**语义 —— 解释权在
//     Kotlin 伴生对象，与 imgnative.cpp「不发明第二套坐标系」同一条纪律）。
//
// 帧号：直接透传 imgnative.cpp 的进程级递增号，**不在本层再套翻译表** ——
// 多一层映射只会多一个漂移面（Kotlin 侧 HandleRef.refId ← native 号一一
// 对应，翻译表两边查不到就是 bug，而这正是 ERR_STALE_HANDLE 要报的事）。
// 每进程各自一份帧表：帧不进 IPC（§7.8 引擎进程与本进程各有自己的 native
// 帧空间），跨进程持帧语义上就是野句柄。
//
// 异常纪律：任何 C++ 异常都**不许穿过 JNI 边界**（越过 = 未定义行为 +
// 崩溃栈不可读）。cv::Exception 已在计算核内折叠成状态码；本层把
// GetStringUTFChars 等 JNI 自身的失败如实回 null（Java 侧 OOME 即拿到）。
#include <jni.h>

#include <cstdint>

// ── 装载面只引计算核的四个入口（imgnative.cpp 的 extern "C"，声明在此）──
// 装载面与计算核**共持同一份状态码表**（文件头 0/1/2/3/4，Kotlin 侧再对一次）；
// 加/改状态码必须三处同批，别只改一处。
extern "C" {
int imgnative_decode(const char* path, int64_t* out_ref, int32_t* out_w, int32_t* out_h);
int imgnative_ingest(const uint8_t* source, int32_t width, int32_t height,
                     int64_t* out_ref, int32_t* out_w, int32_t* out_h);
int imgnative_match(int64_t haystack, int64_t needle, double threshold,
                    int32_t* out_x, int32_t* out_y,
                    int32_t* out_w, int32_t* out_h,
                    double* out_conf, int32_t* out_match);
int imgnative_release(int64_t ref);
int imgnative_color(int64_t frame, const int32_t* color, int32_t tolerance,
                    const int32_t* region,
                    int32_t* out_x, int32_t* out_y,
                    int32_t* out_r, int32_t* out_g, int32_t* out_b, int32_t* out_a,
                    int64_t* out_scanned);
}

extern "C" {

// ── decode：文件 → 一帧。回 jlong[3]{nativeRef, width, height}；
// 失败回 null + *outStatus 状态码（Kotlin 侧折 ErrorCode，见伴生对象）。
JNIEXPORT jlongArray JNICALL
Java_com_autoscript_platform_system_NativeImageAnalyzer_decodeNative(
    JNIEnv* env, jobject /*thiz*/, jstring path, jobject out_status) {
    jintArray status_arr = static_cast<jintArray>(out_status);
    jint status = 0;
    jlongArray result = nullptr;

    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath != nullptr) {
        int64_t native_ref = 0;
        int32_t w = 0, h = 0;
        const int rc = imgnative_decode(cpath, &native_ref, &w, &h);
        env->ReleaseStringUTFChars(path, cpath);
        if (rc == 0) {
            jlong triple[3] = {static_cast<jlong>(native_ref), static_cast<jlong>(w),
                               static_cast<jlong>(h)};
            result = env->NewLongArray(3);
            if (result != nullptr) env->SetLongArrayRegion(result, 0, 3, triple);
        } else {
            status = rc;
        }
    }
    // GetStringUTFChars 失败（OOME）：status 留 0 但 result 仍 null ——
    // Kotlin 侧"null + status 0"判为 JNI 自身失败（不猜成某个 ERR_*）。
    if (status_arr != nullptr) env->SetIntArrayRegion(status_arr, 0, 1, &status);
    return result;
}

// ── ingest：已在内存的 RGBA 紧排像素 → 一帧（§18-8(b) 截屏帧进 images 帧表）。
// 回 jlong[3]{nativeRef, width, height}（与 decodeNative 同形）；失败回 null + *outStatus。
// 字节数在这一层核（Kotlin 侧也核过一次 —— 两处判据必须一致，否则"谁在撒谎"分不清）：
// 长度 < width*height*4 = 参数错（不越读调用方的数组）。
JNIEXPORT jlongArray JNICALL
Java_com_autoscript_platform_system_NativeImageAnalyzer_ingestNative(
    JNIEnv* env, jobject /*thiz*/, jbyteArray rgba, jint width, jint height,
    jobject out_status) {
    jintArray status_arr = static_cast<jintArray>(out_status);
    jint status = 0;
    jlongArray result = nullptr;

    if (rgba == nullptr || width <= 0 || height <= 0) {
        status = 4;   // ERR_INVALID_PARAM
    } else {
        const jsize len = env->GetArrayLength(rgba);
        const int64_t need = static_cast<int64_t>(width) * static_cast<int64_t>(height) * 4;
        if (len < need) {
            status = 4;   // 字节数与尺寸不符：不越读
        } else {
            // GetByteArrayRegion 拷进 JNI 侧临时缓冲（不 pin 调用方数组）：cvtColor
            // 会再拷一次进帧表，多这一跳换"不持锁读 Java 堆"，非热点路径可接受。
            jbyte* buf = env->GetByteArrayElements(rgba, nullptr);
            if (buf == nullptr) {
                status = 3;   // OOME：如实 IO，不猜
            } else {
                int64_t native_ref = 0;
                int32_t w = 0, h = 0;
                const int rc = imgnative_ingest(
                    reinterpret_cast<const uint8_t*>(buf),
                    static_cast<int32_t>(width), static_cast<int32_t>(height),
                    &native_ref, &w, &h);
                env->ReleaseByteArrayElements(rgba, buf, JNI_ABORT);
                if (rc == 0) {
                    jlong triple[3] = {static_cast<jlong>(native_ref), static_cast<jlong>(w),
                                       static_cast<jlong>(h)};
                    result = env->NewLongArray(3);
                    if (result != nullptr) env->SetLongArrayRegion(result, 0, 3, triple);
                } else {
                    status = rc;
                }
            }
        }
    }
    if (status_arr != nullptr) env->SetIntArrayRegion(status_arr, 0, 1, &status);
    return result;
}

// ── match：模板匹配。命中回 jdouble[5]{x,y,w,h,confidence}；**未命中回
// 长度 0 的数组**（比 conf=0 更难误读 —— confidence 恒 ≥ 0，0 会被当成
// "真的匹上了但很差"）；失败回 null + *outStatus。
JNIEXPORT jdoubleArray JNICALL
Java_com_autoscript_platform_system_NativeImageAnalyzer_matchNative(
    JNIEnv* env, jobject /*thiz*/, jlong haystack, jlong needle, jdouble threshold,
    jobject out_status) {
    jintArray status_arr = static_cast<jintArray>(out_status);
    jint status = 0;
    jdoubleArray result = nullptr;

    int32_t x = 0, y = 0, w = 0, h = 0, hit = 0;
    double conf = 0.0;
    const int rc = imgnative_match(
        static_cast<int64_t>(haystack), static_cast<int64_t>(needle),
        static_cast<double>(threshold), &x, &y, &w, &h, &conf, &hit);
    if (rc != 0) {
        status = rc;
    } else if (hit == 0) {
        result = env->NewDoubleArray(0);       // 未匹配是答案，不是异常
    } else {
        jdouble quad[5] = {static_cast<jdouble>(x), static_cast<jdouble>(y),
                           static_cast<jdouble>(w), static_cast<jdouble>(h), conf};
        result = env->NewDoubleArray(5);
        if (result != nullptr) env->SetDoubleArrayRegion(result, 0, 5, quad);
    }
    if (status_arr != nullptr) env->SetIntArrayRegion(status_arr, 0, 1, &status);
    return result;
}

// ── release：放掉一帧。回状态码直出（0 = OK，1 = STALE）—— 语义足够简单，
// 不值得为它再开一个 out 数组；Kotlin 侧同样按对表折 ErrorCode。
JNIEXPORT jint JNICALL
Java_com_autoscript_platform_system_NativeImageAnalyzer_releaseNative(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong native_ref) {
    return static_cast<jint>(imgnative_release(static_cast<int64_t>(native_ref)));
}


// ── color：在某帧（或帧内区域）里找一个"与目标色相近"的像素。
// 回 jlong[6]{x, y, r, g, b, a}；**未命中回长度 6 而 x = -1**（扫过了、没有 ——
// 与未匹配同一条纪律；不是 conf=0 那种会被误读成"匹上了但很差"的形状）；
// 失败回 null + *outStatus。入参的 int[]（color 四分量 / region 四元组）走 JNI
// 数组直达，不经过 JSON：分量是原生侧的域（0..255），在装载面多绕一层字符串
// 往返只会多一个漂移面。
JNIEXPORT jlongArray JNICALL
Java_com_autoscript_platform_system_NativeImageAnalyzer_colorNative(
    JNIEnv* env, jobject /*thiz*/, jlong frame,
    jintArray color, jint tolerance, jintArray region, jobject out_status) {
    jintArray status_arr = static_cast<jintArray>(out_status);
    jint status = 0;
    jlongArray result = nullptr;

    if (color == nullptr) {
        status = 4;   // ERR_INVALID_PARAM：分量数组都不在，无从谈起色
    } else {
        jint c[4] = {0, 0, 0, 0};
        env->GetIntArrayRegion(color, 0, 4, c);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();   // 长度 <4 的数组：如实按参数错，不让异常穿边界
            status = 4;
        } else {
            jint r[4] = {0, 0, 0, 0};
            jboolean has_region = JNI_FALSE;
            if (region != nullptr) {
                env->GetIntArrayRegion(region, 0, 4, r);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    status = 4;
                } else {
                    has_region = JNI_TRUE;
                }
            }
            if (status == 0) {
                int32_t ox = -1, oy = 0, orr = 0, og = 0, ob = 0, oa = 0;  // x 哨兵 -1 = 未命中
                int64_t scanned = 0;
                const int rc = imgnative_color(
                    static_cast<int64_t>(frame), c, static_cast<int32_t>(tolerance),
                    has_region == JNI_TRUE ? r : nullptr,
                    &ox, &oy, &orr, &og, &ob, &oa, &scanned);
                if (rc != 0) {
                    status = rc;
                } else if (scanned == 0) {
                    // 扫过 0 像素（空区域）：不是"没有这个色"，是"没有可扫的面" ——
                    // 与"扫过了但没有"必须分开，否则脚本会把空区域当成找过色。
                    status = 4;
                } else {
                    // 命中与否由 x 分（-1 = 未命中）：扫描序稳定，0,0 是合法的第一
                    // 个像素坐标，不能拿它当"没有"的哨兵。
                    const jlong hit[6] = {static_cast<jlong>(ox), static_cast<jlong>(oy),
                                          static_cast<jlong>(orr), static_cast<jlong>(og),
                                          static_cast<jlong>(ob), static_cast<jlong>(oa)};
                    result = env->NewLongArray(6);
                    if (result != nullptr) env->SetLongArrayRegion(result, 0, 6, hit);
                }
            }
        }
    }
    if (status_arr != nullptr) env->SetIntArrayRegion(status_arr, 0, 1, &status);
    return result;
}

}  // extern "C"
