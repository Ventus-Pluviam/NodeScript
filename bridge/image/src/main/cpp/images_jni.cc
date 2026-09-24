// bridge/image —— libopencv.so 的装载面（docs/framework-design.md §9.2）
//
// 分工（与 imgnative.cpp 的两层切法）：
//   - imgnative.cpp 是**纯计算核**：extern "C" 三入口（decode/match/release），
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

// ── 装载面只引计算核的三个入口（imgnative.cpp 的 extern "C"，声明在此）──
extern "C" {
int imgnative_decode(const char* path, int64_t* out_ref, int32_t* out_w, int32_t* out_h);
int imgnative_match(int64_t haystack, int64_t needle, double threshold,
                    int32_t* out_x, int32_t* out_y,
                    int32_t* out_w, int32_t* out_h,
                    double* out_conf, int32_t* out_match);
int imgnative_release(int64_t ref);
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

}  // extern "C"
