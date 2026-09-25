#!/usr/bin/env bash
# AutoScript :node-runtime-build —— OpenCV 4.14.0 静态链接 → libopencv.so（aarch64-android）
# 用法：构建容器内 `bash scripts/build-opencv.sh`（与 fetch-and-build.sh 同源同门禁）。
# 职责：下载+校验 → cmake 交叉 configure（静态）→ strip → 16KB/ELF/NEEDED 门禁 → 基表。
#
# 与 Node 管线（fetch-and-build.sh）的分工：**不共用 out/** —— 产物名/门禁项都不同
# （本脚本产 libopencv.so，不含 config.gypi/libnode.so.<ABI> 契约），共目录会让
# check-alignment.sh 的 Node 专属断言误扫。本次交付刻意只做 OpenCV 一条轨，不合并。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# ROOT_DIR = **仓库**根（不是 node-runtime-build/）。布局判据取**桥面 C++** 的落点（它在仓库根下，Dockerfile 平铺时会落进 /build/，
# 而 VERSIONS.env 在两种布局里都挨着脚本）：找到 bridge/image/ 的那层即仓库根。
# 找不到就如实 die —— 不静默 fallback 到 SCRIPT_DIR/..（CI 布局下那是错的一层，
# 2026-09-24 连红三次：路径错层 / die 未定义 / 又是路径错层）。
if [ -f "$SCRIPT_DIR/../../bridge/image/build.gradle.kts" ]; then
    ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"      # 仓库内 / CI（node-runtime-build/scripts/）
    VERSIONS="$SCRIPT_DIR/../VERSIONS.env"
elif [ -f "$SCRIPT_DIR/../bridge/image/build.gradle.kts" ]; then
    ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"          # Dockerfile 平铺（/build/）
    VERSIONS="$SCRIPT_DIR/../VERSIONS.env"
else
    printf '[FATAL] 找不到 bridge/image（仓库根锚点）：既不在 %s 也不在 %s\n' \
        "$SCRIPT_DIR/../.." "$SCRIPT_DIR/.." >&2
    exit 1
fi
# shellcheck source=../VERSIONS.env
source "$VERSIONS"
say() { printf '\033[1;34m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '\033[1;31m[FATAL]\033[0m %s\n' "$*" >&2; exit 1; }

# 桥面 C++ 住**仓库**根的 bridge/image/（Gradle 模块，不在 node-runtime-build 里）。
# 两个文件各 assert 一次：缺装载面比缺计算核更难查（so 有、门禁绿、loadLibrary 成功，
# 首次调 native 方法才 UnsatisfiedLinkError → images.* 全 ERR_NOT_IMPLEMENTED，
# 症状像"so 没交付"而不像"链接行漏文件"，见 RISKS.md §13）。
IMG_CPP_DIR="$ROOT_DIR/bridge/image/src/main/cpp"
[ -f "$IMG_CPP_DIR/imgnative.cpp" ] || die "桥面计算核缺失: $IMG_CPP_DIR/imgnative.cpp"
[ -f "$IMG_CPP_DIR/images_jni.cc" ] || die "桥面装载面缺失: $IMG_CPP_DIR/images_jni.cc（JNI 符号名 Kotlin 侧与之对表，缺一即不装）"

WORK="${WORK_DIR:?WORK_DIR 未设置}"   # 本管线唯一的"从外面带进来的目录"约定：
                                      # Dockerfile 与 image-native.yml 都注入同一个值
OCV_SRC="$WORK/src/opencv"
OUT="$WORK/out-opencv"
NDK_DIR="$WORK/ndk/android-ndk-$NDK_VERSION"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"

: "${OPENCV_VERSION:?VERSIONS.env 缺 OPENCV_VERSION}"
: "${OPENCV_COMMIT:?VERSIONS.env 缺 OPENCV_COMMIT}"
: "${KLEIDICV_COMMIT:?VERSIONS.env 缺 KLEIDICV_COMMIT}"
: "${KLEIDICV_MD5:?VERSIONS.env 缺 KLEIDICV_MD5}"

mkdir -p "$WORK/src" "$OUT"

# ── 1) OpenCV 源码：按 commit SHA 固定拉取（不用浮动 tag/tarball，可复现）────
if [ ! -d "$OCV_SRC/.git" ]; then
    say "clone opencv @ $OPENCV_VERSION ($OPENCV_COMMIT)"
    # 全量 clone 而非 tarball：kleidicv 等子目录与 tag 元数据都在，且能 swappiness 校验。
    git clone --filter=blob:none --no-checkout https://github.com/opencv/opencv.git "$OCV_SRC"
fi
say "checkout $OPENCV_COMMIT（detached HEAD）"
git -C "$OCV_SRC" -c advice.detachedHead=false checkout "$OPENCV_COMMIT"
HEAD_SHA="$(git -C "$OCV_SRC" rev-parse HEAD)"
[ "$HEAD_SHA" = "$OPENCV_COMMIT" ] || die "OpenCV commit 漂移: $HEAD_SHA != $OPENCV_COMMIT"

# kleidicv pin 校验：上游 4.14 把下载参数写死在 hal/kleidicv/kleidicv.cmake，
# 一旦上游换 pin 而 VERSIONS.env 没跟上，产物里嵌的加速面就和我们记的审计行不符。
grep -qF "ocv_update(KLEIDICV_SRC_COMMIT \"$KLEIDICV_COMMIT\")" "$OCV_SRC/hal/kleidicv/kleidicv.cmake" \
    || die "kleidicv commit pin 漂移（期望 $KLEIDICV_COMMIT，见 hal/kleidicv/kleidicv.cmake）"
grep -qF "$KLEIDICV_MD5" "$OCV_SRC/hal/kleidicv/kleidicv.cmake" \
    || die "kleidicv md5 pin 漂移（期望 $KLEIDICV_MD5）"

# ── 2) NDK 工具链（r28c 与 Node 管线同一 zip，复用 $WORK/ndk）──────────────
[ -x "$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang" ] \
    || die "NDK clang 缺失: $TOOLCHAIN（先跑 fetch-and-build.sh 或其 Docker 层）"
[ -f "$NDK_DIR/build/cmake/android.toolchain.cmake" ] \
    || die "NDK cmake toolchain 缺失: $NDK_DIR/build/cmake/android.toolchain.cmake"

# ── 3) cmake 交叉 configure（静态 + 三条裁剪）───────────────────────────
# BUILD_LIST 见 VERSIONS.env（core/imgproc/imgcodecs + features2d/flann for ORB）：
# imgcodecs 只留 PNG/JPEG（截图与随包资源图就这两种），
# 其余格式源/壳全 OFF；WITH_* 逐个 OFF 掉我们不开的面（TIFF/WEBP/EXR/JASPER/OPENJPEG/
# AVIF/FFMPEG/V4L/1394/TBB/OPENCL/OPENVX/PROTOBUF/ITT…）。
# WITH_KLEIDICV 保持**默认 ON**（AArch64+Android 默认开，见 VERSIONS.env 选型注记）：
#   覆盖 add/sub/absdiff/multiply/cvtColor/GaussianBlur/Sobel/medianBlur/resize/… 不含
#   matchTemplate，开着不亏且给 P1 算子铺路；下载失败是软降级（不 fatal），ON/OFF 由
#   下方 3b 段从 configure 摘要判定并写进 SHASUMS256 审计行。
# CPU_BASELINE=DETECT / CPU_DISPATCH 交给 toolchain 默认（AArch64 → NEON_DOTPROD 等），
# 不手写 -march 把 armv8.2 指令烤进基线（minSdk 26 设备要能跑）。
BUILD_DIR="$WORK/build-opencv"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
say "cmake configure（BUILD_LIST=$OPENCV_BUILD_LIST, kleidicv=$KLEIDICV_COMMIT）"

# configure 输出落盘： kleidicv 的 ON/OFF 判据是摘要里的 `Custom HAL: … KleidiCV (ver …)`
# 一行，而那不是 CMakeCache 能回答的（见 3b 段注释）。同时也让"配置改了但摘要看着没变"
# 这类问题有据可查。**pipefail 闭环**：`cmake … 2>&1 | tee` 的退出码取管道最后一条
# 命令，而 set -o pipefail 让 cmake 自己的退出码也能传给 set -e —— 配置失败在这里就停，
# 不会走到 3b 段把"没配完的 configure.log"当成 kleidicv=OFF 的证据。
CONFIGURE_LOG="$BUILD_DIR/configure.log"
cmake -S "$OCV_SRC" -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM="${ANDROID_API}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_LIST="$OPENCV_BUILD_LIST" \
    -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_EXAMPLES=OFF \
    -DBUILD_DOCS=OFF -DBUILD_opencv_python2=OFF -DBUILD_opencv_python3=OFF -DBUILD_JAVA=OFF \
    -DBUILD_ANDROID_PROJECTS=OFF -DBUILD_ANDROID_EXAMPLES=OFF -DBUILD_ANDROID_SERVICE=OFF \
    -DBUILD_FAT_JAVA_LIB=OFF -DBUILD_PACKAGE=OFF -DBUILD_opencv_apps=OFF \
    -DWITH_OPENMP=OFF -DWITH_OPENCL=OFF -DWITH_TBB=OFF -DWITH_ITT=OFF \
    -DWITH_IPP=OFF -DWITH_LAPACK=OFF -DWITH_EIGEN=OFF -DWITH_PROTOBUF=OFF \
    -DWITH_FFMPEG=OFF -DWITH_GSTREAMER=OFF -DWITH_V4L=OFF -DWITH_1394=OFF \
    -DWITH_GTK=OFF -DWITH_WIN32UI=OFF -DWITH_QT=OFF -DWITH_VTK=OFF \
    -DWITH_WEBP=OFF -DWITH_AVIF=OFF -DWITH_TIFF=OFF -DWITH_OPENEXR=OFF \
    -DWITH_JASPER=OFF -DWITH_OPENJPEG=OFF -DWITH_JAVA=OFF -DWITH_QUIRC=OFF \
    -DWITH_ANDROID_MEDIANDK=OFF -DWITH_ANDROID_NATIVE_CAMERA=OFF \
    -DWITH_FASTCV=OFF -DWITH_OPENVX=OFF -DWITH_HPX=OFF -DWITH_TIMVX=OFF -DWITH_CANN=OFF \
    -DWITH_OPENVINO=OFF -DWITH_INF_ENGINE=OFF -DWITH_NGRAPH=OFF \
    -DWITH_ARMPL=OFF -DWITH_VULKAN=OFF -DWITH_ADE=OFF \
    -DBUILD_ZLIB=ON -DBUILD_JPEG=ON -DBUILD_PNG=ON \
    -DOPENCV_ENABLE_NONFREE=OFF \
    -DENABLE_CONFIG_VERIFICATION=OFF \
    -DOPENCV_WARNINGS_ARE_ERRORS=OFF 2>&1 | tee "$CONFIGURE_LOG"

# ── 3b) kleidicv 审计行：软降级不 fatal，但必须可查───────────────────────
# **判定源不是 CMakeCache**（2026-09-25 定位教训，此前两处错判都源于此）：
#   上游 cmake/OpenCVFindLibsPerf.cmake:218/224 只 `set(HAVE_KLEIDICV ON)`，
#   **没有 CACHE BOOL**，所以 CMakeCache.txt 里从来不存在 `HAVE_KLEIDICV:BOOL=`
#   这一项 —— grep 它永远 grep 不到，脚本会稳定地报 OFF（"采集点错"伪装成"没启用"）。
#   可用的证据按可靠度排列：
#     a. configure 摘要的 `Custom HAL: YES (… KleidiCV (ver …))`
#        —— 只有调过 add_subdirectory(hal/kleidicv) 才会出现，最贴近"启用了"。
#     b. configure 摘要的 `Custom HAL: …` 里没有 KleidiCV → 真关。
#     c. debug 信息 `Enable KleidiCV acceleration` 只在 -DOPENCV_CMAKE_DEBUG_MESSAGES=ON 才打。
# 另报一件**独立事实**：解包后的源码在场与否（下载/校验这一环的单独证据，
# 与"OpenCV 认不认它"是两回事，别混为一谈）。
KLEIDI_SRC_DIR="$BUILD_DIR/3rdparty/kleidicv/kleidicv-$KLEIDICV_COMMIT"
KLEIDI_SRC="无"
[ -f "$KLEIDI_SRC_DIR/adapters/opencv/CMakeLists.txt" ] && KLEIDI_SRC="有"
KLEIDI_STATE="OFF"
# `if grep … 2>/dev/null` 而非裸 grep：set -e + pipefail 下 configure.log 缺失时
# grep 的非零退出会带走整个脚本；configure 失败本就该由 cmake 那步的退出码报，
# 这里只做采集，不该抢先生死。
if grep -q 'KleidiCV (ver' "$CONFIGURE_LOG" 2>/dev/null; then
    KLEIDI_STATE="ON"
else
    printf '\033[1;33m[WARN]\033[0m kleidicv 未启用（configure 摘要的 Custom HAL 里没有 KleidiCV）\n' >&2
    if [ "$KLEIDI_SRC" = "有" ]; then
        printf '       但源码在位（下载成功）：判定失败点在上游\n' \
            '       cmake/OpenCVFindLibsPerf.cmake 的 WITH_KLEIDICV 分支，不在网络\n' >&2
    fi
fi
# 这一行是 image-native.yml 与人工核对共用的锚点，别改字面（grep 'kleidicv 状态:'）。
say "kleidicv 状态: $KLEIDI_STATE（源码=$KLEIDI_SRC）"

# ── 4) 编 imgcodecs（连带 core/imgproc）+ features2d（连带 flann）────────
# target 决定"编出来"：`opencv_imgcodecs` 只连带它的依赖闭包（core/imgproc），
# features2d/flann 虽在 BUILD_LIST 白名单里但没人编就不落盘 —— CI 实测红过一次
# （ld.lld: unable to find library -lopencv_features2d）。显式再编一轮。
say "make -j$(nproc) opencv_imgcodecs opencv_features2d（连带各自依赖闭包）"
cmake --build "$BUILD_DIR" --target opencv_imgcodecs -j"$(nproc)"
cmake --build "$BUILD_DIR" --target opencv_features2d -j"$(nproc)"

# ── 5) 我们的桥面 C++ + 静态链成 libopencv.so ───────────────────────────
# 静态 STL：产物不依赖 libc++_shared.so（libnode 那条已有的 NEEDED 归装载面，见
# engine/node-process/scripts/build-native.sh 同款决定）。
# 产物名 = 装载名：Kotlin 侧 System.loadLibrary("opencv") 找的就是 libopencv.so。
IMG_LIB="$OUT/libopencv.so"
say "链 libopencv.so（计算核 + 装载面 + 静态 opencv + 静态 STL）"
CXX="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang++"
"$CXX" -std=c++17 -fPIC -O2 -Wall -Wextra \
    -Wl,-z,max-page-size=16384 -static-libstdc++ \
    -shared \
    -I "$OCV_SRC/modules/core/include" \
    -I "$OCV_SRC/modules/imgproc/include" \
    -I "$OCV_SRC/modules/imgcodecs/include" \
    -I "$OCV_SRC/modules/features2d/include" \
    -I "$OCV_SRC/modules/flann/include" \
    -I "$BUILD_DIR" \
    -o "$IMG_LIB" \
    "$IMG_CPP_DIR/imgnative.cpp" \
    "$IMG_CPP_DIR/images_jni.cc" \
    -L"$BUILD_DIR/lib/arm64-v8a" -L"$BUILD_DIR/3rdparty/lib/arm64-v8a" \
    -lopencv_features2d -lopencv_flann -lopencv_imgcodecs -lopencv_imgproc -lopencv_core \
    -llibjpeg-turbo -llibpng -lzlib \
    -ldl -lm -llog

# 链接面 = BUILD_LIST 五个模块 + 它们自带的两个格式库（libjpeg-turbo/libpng/zlib，
# BUILD_JPEG/BUILD_PNG/BUILD_ZLIB=ON 强制走树内源码，不找宿主/交叉 sysroot ——
# 无外部下载、无系统依赖，产物可复现）。最终的 NEEDED 白名单由下方门禁来验。

# ── 6) strip（保留动态符号：extern "C" 四个入口是 JNI/dlsym 的靶子）──────
STRIP="$TOOLCHAIN/bin/llvm-strip"
"$STRIP" --strip-unneeded "$IMG_LIB" || true

# ── 7) 门禁 ────────────────────────────────────────────────────────────
say "16KB/ELF/NEEDED 门禁"
"$SCRIPT_DIR/check-opencv-alignment.sh" "$TOOLCHAIN/bin/llvm-readelf" "$IMG_LIB" "$CXX"

# ── 8) 基表 + 审计行 ────────────────────────────────────────────────────
# kleidicv 状态进审计：产物 sha256 之外还要能回答"这个 so 里到底有没有 kleidicv 加速"。
( cd "$OUT" && {
    sha256sum libopencv.so | tee SHASUMS256
    printf 'build: opencv=%s commit=%s kleidicv_commit=%s kleidicv_state=%s kleidicv_src=%s platform=aarch64-android%s\n' \
        "$OPENCV_VERSION" "$OPENCV_COMMIT" "$KLEIDICV_COMMIT" "$KLEIDI_STATE" "$KLEIDI_SRC" "$ANDROID_API" >> SHASUMS256
  } )
say "完成。产物: $OUT/libopencv.so（kleidicv=$KLEIDI_STATE）"
