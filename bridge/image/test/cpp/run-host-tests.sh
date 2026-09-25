#!/usr/bin/env bash
# 宿主机语义测试：把 bridge/image/src/main/cpp/imgnative.cpp（纯计算核、零 JNI）
# 与**同 commit** 的 OpenCV 4.14.0 静态库链成一个 x86_64 可执行文件，跑断言。
#
# 为什么需要它：NDK `-fsyntax-only` 只证明编得过，不证明判读对。计算核里有几处
# "译反了照样出结论"的判读（Vec4b 通道序、ROI 偏移回加、扫过 vs 扫过 0 像素）,
# 只有真跑像素才能证伪。真机红测仍是最后一关，但"等上设备才发现"太贵。
#
# 用法：bash bridge/image/test/cpp/run-host-tests.sh [opencv 源码目录]
#   不给目录 = $OCV_SRC 环境变量，再没有则 /tmp/ocvpin（本机惯位）
# OpenCV 的 commit pin 见 node-runtime-build/VERSIONS.env 的 OPENCV_COMMIT ——
# 与 build-opencv.sh 拉的是同一个 SHA，不是"本机随便哪个版本"。
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../../../.. && pwd)"; cd "$REPO"

OCV_SRC="${1:-${OCV_SRC:-/tmp/ocvpin}}"
BUILD="${OCV_HOST_BUILD:-/tmp/ocvhostbuild}"
HERE=bridge/image/test/cpp

[ -f "$OCV_SRC/CMakeLists.txt" ] || {
  printf '[FATAL] 找不到 OpenCV 源码：%s（先按 build-opencv.sh 的 commit 拉一份）\n' "$OCV_SRC" >&2
  exit 1
}
# commit 对表：VERSIONS.env 是 build-opencv.sh 与本脚本共用的 pin 源。host 侧跑到
# 另一个 commit 上，"同 commit 的语义门禁"这句话就不成立 —— 抓不到 SOUP 的漂移，
# 只会安静地拿另一版 OpenCV 的判读下结论。缺 .git（tarball 解包）时跳过，如实告知。
if [ -d "$OCV_SRC/.git" ]; then
  WANT="$(sed -n 's/^OPENCV_COMMIT=//p' node-runtime-build/VERSIONS.env)"
  GOT="$(git -C "$OCV_SRC" rev-parse HEAD 2>/dev/null || echo '<detached-or-unknown>')"
  [ "$GOT" = "$WANT" ] || {
    printf '[FATAL] OpenCV commit 漂移：%s != %s（host 门槛的判据必须与 libopencv.so 同源）\n' \
      "$GOT" "$WANT" >&2
    printf '       切过去：git -C %s checkout %s\n' "$OCV_SRC" "$WANT" >&2
    exit 1
  }
  printf '[pin] OpenCV @ %s（与 VERSIONS.env 一致）\n' "${GOT:0:12}"
else
  printf '[WARN] %s 无 .git，跳过 commit 对表（tarball 解包无从校验）\n' "$OCV_SRC" >&2
fi
command -v cmake >/dev/null || { printf '[FATAL] 缺 cmake\n' >&2; exit 1; }
command -v g++ >/dev/null || { printf '[FATAL] 缺 g++\n' >&2; exit 1; }

if [ ! -f "$BUILD/lib/libopencv_core.a" ]; then
  printf '[build] 配置 + 编译 host OpenCV（一次性，产物落 %s）\n' "$BUILD"
  # kleidicv OFF：host 是 x86_64，那条加速面只在 aarch64 上（开着会让 configure
  # 尝试交叉/下载）。CPU_BASELINE 写 SSE3 而不是 DETECT —— 要的是能在任意 x86_64
  # 上跑，不把本机指令集烤进基线（与 build-opencv.sh 不手写 -march 同一考虑）。
  cmake -S "$OCV_SRC" -B "$BUILD" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_LIST=core,imgproc,imgcodecs,features2d,flann \
    -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_DOCS=OFF \
    -DBUILD_opencv_python2=OFF -DBUILD_opencv_python3=OFF -DBUILD_JAVA=OFF \
    -DBUILD_ANDROID_PROJECTS=OFF -DBUILD_ANDROID_EXAMPLES=OFF \
    -DBUILD_FAT_JAVA_LIB=OFF -DBUILD_PACKAGE=OFF -DBUILD_opencv_apps=OFF \
    -DWITH_OPENMP=OFF -DWITH_OPENCL=OFF -DWITH_TBB=OFF -DWITH_ITT=OFF -DWITH_IPP=OFF \
    -DWITH_LAPACK=OFF -DWITH_EIGEN=OFF -DWITH_PROTOBUF=OFF -DWITH_FFMPEG=OFF \
    -DWITH_GTK=OFF -DWITH_QT=OFF -DWITH_WEBP=OFF -DWITH_TIFF=OFF -DWITH_OPENEXR=OFF \
    -DWITH_OPENJPEG=OFF -DWITH_KLEIDICV=OFF -DCPU_BASELINE=SSE3 -DCPU_DISPATCH="" \
    -DBUILD_ZLIB=ON -DBUILD_JPEG=ON -DBUILD_PNG=ON >/tmp/ocvhost-build.log 2>&1
  cmake --build "$BUILD" --target opencv_imgcodecs -j"$(nproc)" >>/tmp/ocvhost-build.log 2>&1
fi
# 增量目标：`opencv_imgcodecs` 只连带 core/imgproc（imgcodecs 的依赖闭包），
# features2d/flann 虽在 BUILD_LIST 白名单里、configure 配出来了，但没人编它就不落盘
# —— host 侧与 device 侧各实测红过一次（ld.lld: unable to find library
# -lopencv_features2d）。所以这里显式再编一轮（已编过即 no-op，不重编）。
cmake --build "$BUILD" --target opencv_features2d -j"$(nproc)" >>/tmp/ocvhost-build.log 2>&1

INC=(-I"$OCV_SRC/modules/core/include" -I"$OCV_SRC/modules/imgproc/include"
     -I"$OCV_SRC/modules/imgcodecs/include" -I"$BUILD")
# features2d/flann 与三模块同缓存（$BUILD 按 VERSIONS.env 的 BUILD_LIST 一次配出，
# 见上增量目标注记）：include 多两行，链接把此二模块排在 core 之前（静态库链接顺序
# 是语义 —— features2d 的 Algorithm 符号住 core 里，后列先解，顺序反了即
# undefined reference，host 侧实测过）。
INC=(-I"$OCV_SRC/modules/core/include" -I"$OCV_SRC/modules/imgproc/include"
     -I"$OCV_SRC/modules/imgcodecs/include"
     -I"$OCV_SRC/modules/features2d/include" -I"$OCV_SRC/modules/flann/include"
     -I"$BUILD")
LIBS=(-L"$BUILD/lib" -L"$BUILD/3rdparty/lib"
      -lopencv_features2d -lopencv_flann -lopencv_imgcodecs -lopencv_imgproc -lopencv_core
      -llibjpeg-turbo -llibpng -llibjasper -lzlib)

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
failed=0
for t in host_ingest_test host_color_test host_decode_norm_test host_match_test host_gray_test host_crop_test host_resize_test host_rotate_test host_feature_test; do
  printf '[cc] %s\n' "$t"
  g++ -std=c++17 -O2 -Wall -Wextra "${INC[@]}" -o "$OUT/$t" \
    "$HERE/$t.cpp" bridge/image/src/main/cpp/imgnative.cpp "${LIBS[@]}"
  printf '[run] %s\n' "$t"
  if ! "$OUT/$t"; then
    printf '[FAIL] %s\n' "$t" >&2
    failed=1
  fi
done
[ "$failed" = 0 ] && printf '[OK] 全部宿主机语义测试通过\n'
exit "$failed"
