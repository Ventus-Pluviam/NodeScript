#!/usr/bin/env bash
# AutoScript :node-runtime-build —— OpenCV 4.14.0 静态链接 → libimgnative.so（aarch64-android）
# 用法：构建容器内 `bash scripts/build-opencv.sh`（与 fetch-and-build.sh 同源同门禁）。
# 职责：下载+校验 → cmake 交叉 configure（静态）→ strip → 16KB/ELF/NEEDED 门禁 → 基表。
#
# 与 Node 管线（fetch-and-build.sh）的分工：**不共用 out/** —— 产物名/门禁项都不同
# （本脚本产 libimgnative.so，不含 config.gypi/libnode.so.<ABI> 契约），共目录会让
# check-alignment.sh 的 Node 专属断言误扫。本次交付刻意只做 OpenCV 一条轨，不合并。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=../VERSIONS.env
source "$ROOT_DIR/VERSIONS.env"
say() { printf '\033[1;34m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '\033[1;31m[FATAL]\033[0m %s\n' "$*" >&2; exit 1; }

# 桥面 C++ 住**仓库**根的 bridge/image/（Gradle 模块，不在 node-runtime-build 里）。
# assert 而非默默跳过：CI 的 checkout 布局偶发/镜像平铺会把它指到 node-runtime-build/
# 下面（2026-09-24 实测 [FATAL] 之前先撞上 "no such file or directory: .../node-runtime-build/
# bridge/image/src/main/cpp/imgnative.cpp"，症状是 clang 报错而非本 die 的人话）。
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
# BUILD_LIST=core,imgproc,imgcodecs：imgcodecs 只留 PNG/JPEG（截图与随包资源图就这两种），
# 其余格式源/壳全 OFF；WITH_* 逐个 OFF 掉我们不开的面（TIFF/WEBP/EXR/JASPER/OPENJPEG/
# AVIF/FFMPEG/V4L/1394/TBB/OPENCL/OPENVX/PROTOBUF/ITT…）。
# WITH_KLEIDICV 保持**默认 ON**（AArch64+Android 默认开，见 VERSIONS.env 选型注记）：
#   覆盖 add/sub/absdiff/multiply/cvtColor/GaussianBlur/Sobel/medianBlur/resize/… 不含
#   matchTemplate，开着不亏且给 P1 算子铺路；下载失败是软降级（不 fatal），首次跑
#   须核对下方 CMakeCache 的 HAVE_KLEIDICV 并把结论写进 SHASUMS256 审计行。
# CPU_BASELINE=DETECT / CPU_DISPATCH 交给 toolchain 默认（AArch64 → NEON_DOTPROD 等），
# 不手写 -march 把 armv8.2 指令烤进基线（minSdk 26 设备要能跑）。
BUILD_DIR="$WORK/build-opencv"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
say "cmake configure（BUILD_LIST=$OPENCV_BUILD_LIST, kleidicv=$KLEIDICV_COMMIT）"

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
    -DOPENCV_WARNINGS_ARE_ERRORS=OFF

# ── 3b) kleidicv 审计行：ON/OFF 都记下来（软降级不 fatal，但必须可查）────
KLEIDI_STATE="OFF"
if grep -q '^HAVE_KLEIDICV:BOOL=ON' "$BUILD_DIR/CMakeCache.txt"; then
    KLEIDI_STATE="ON"
else
    printf '\033[1;33m[WARN]\033[0m HAVE_KLEIDICV 非 ON（软降级：gitlab.arm.com 不可达？）—— 已记入审计行\n' >&2
fi
say "kleidicv 状态: $KLEIDI_STATE"

# ── 4) 只编 imgcodecs 连带 core/imgproc（BUILD_LIST 已裁，不会捎带别的模块）
say "make -j$(nproc) opencv_imgcodecs（连带 core/imgproc 静态库）"
cmake --build "$BUILD_DIR" --target opencv_imgcodecs -j"$(nproc)"

# ── 5) 我们的桥面 C++ + 静态链成 libimgnative.so ────────────────────────
# 静态 STL：产物不依赖 libc++_shared.so（libnode 那条已有的 NEEDED 归装载面，见
# engine/node-process/scripts/build-native.sh 同款决定）。
IMG_LIB="$OUT/libimgnative.so"
say "链 libimgnative.so（计算核 + 装载面 + 静态 opencv + 静态 STL）"
CXX="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang++"
"$CXX" -std=c++17 -fPIC -O2 -Wall -Wextra \
    -Wl,-z,max-page-size=16384 -static-libstdc++ \
    -shared \
    -I "$OCV_SRC/modules/core/include" \
    -I "$OCV_SRC/modules/imgproc/include" \
    -I "$OCV_SRC/modules/imgcodecs/include" \
    -I "$BUILD_DIR" \
    -o "$IMG_LIB" \
    "$IMG_CPP_DIR/imgnative.cpp" \
    "$IMG_CPP_DIR/images_jni.cc" \
    -L"$BUILD_DIR/lib/arm64-v8a" -L"$BUILD_DIR/3rdparty/lib/arm64-v8a" \
    -lopencv_imgcodecs -lopencv_imgproc -lopencv_core \
    -llibjpeg-turbo -llibpng -lzlib \
    -ldl -lm -llog

# 链接面 = BUILD_LIST 三个模块 + 它们自带的两个格式库（libjpeg-turbo/libpng/zlib，
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
    sha256sum libimgnative.so | tee SHASUMS256
    printf 'build: opencv=%s commit=%s kleidicv_commit=%s kleidicv_state=%s platform=aarch64-android%s\n' \
        "$OPENCV_VERSION" "$OPENCV_COMMIT" "$KLEIDICV_COMMIT" "$KLEIDI_STATE" "$ANDROID_API" >> SHASUMS256
  } )
say "完成。产物: $OUT/libimgnative.so（kleidicv=$KLEIDI_STATE）"
