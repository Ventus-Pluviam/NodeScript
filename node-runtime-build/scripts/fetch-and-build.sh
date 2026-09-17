#!/usr/bin/env bash
# AutoScript :node-runtime-build —— Node 24 自建 libnode.so（aarch64-android）管线
# 用法：构建容器内 `bash scripts/fetch-and-build.sh`（Dockerfile 即此流程）。
# 职责：下载+校验 → 解包 → 交叉 configure/make → 产物收敛 strip → 触发门禁。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=../VERSIONS.env
source "$ROOT_DIR/VERSIONS.env"

WORK="${WORK_DIR:?WORK_DIR 未设置}"
DL="$WORK/dl"
SRC="$WORK/src"
NDK_DIR="$WORK/ndk/android-ndk-$NDK_VERSION"
OUT="$WORK/out"

TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
TARGET_TUPLE="aarch64-linux-android${ANDROID_API}"

say() { printf '\033[1;34m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '\033[1;31m[FATAL]\033[0m %s\n' "$*" >&2; exit 1; }

mkdir -p "$DL" "$SRC" "$OUT"

# ── 1) Node 源码：TLS 下载 + 官方 SHASUMS256 校验 ─────────────────────────
NODE_TARBALL="node-$NODE_VERSION.tar.xz"
if [ ! -f "$DL/$NODE_TARBALL" ]; then
    say "下载 Node $NODE_VERSION ..."
    curl -fsS --retry 3 -o "$DL/$NODE_TARBALL" \
        "https://nodejs.org/dist/$NODE_VERSION/$NODE_TARBALL"
else
    say "复用已下载 $DL/$NODE_TARBALL"
fi
say "校验 Node sha256（期望 ${NODE_SHA256:0:16}…）"
echo "$NODE_SHA256  $DL/$NODE_TARBALL" | sha256sum -c - >/dev/null \
    || die "Node 源码校验失败"

# ── 2) NDK：下载 + size/sha1 校验（repository2-3.xml 同一校验源）──────────
NDK_ZIP="android-ndk-$NDK_VERSION-linux.zip"
if [ ! -f "$DL/$NDK_ZIP" ]; then
    say "下载 NDK $NDK_VERSION（$NDK_VERSION_CODE）约 $((NDK_ZIP_SIZE / 1024 / 1024))MB ..."
    curl -fsS --retry 3 -o "$DL/$NDK_ZIP" \
        "https://dl.google.com/android/repository/$NDK_ZIP"
fi
say "校验 NDK sha1/size（期望 size=$NDK_ZIP_SIZE sha1=${NDK_SHA1:0:16}…）"
actual_size=$(stat -c%s "$DL/$NDK_ZIP")
[ "$actual_size" = "$NDK_ZIP_SIZE" ] || die "NDK zip 大小不符: $actual_size != $NDK_ZIP_SIZE"
actual_sha1=$(sha1sum "$DL/$NDK_ZIP" | awk '{print $1}')
[ "$actual_sha1" = "$NDK_SHA1" ] || die "NDK sha1 不符: $actual_sha1"

# ── 3) 解包（已存在即复用；DL 层随 VERSIONS.env 缓存）────────────────────
if [ ! -d "$SRC/node-$NODE_VERSION" ]; then
    say "解包 Node 源码"
    tar -xJf "$DL/$NODE_TARBALL" -C "$SRC"
fi
if [ ! -d "$NDK_DIR" ]; then
    say "解包 NDK"
    unzip -q "$DL/$NDK_ZIP" -d "$(dirname "$NDK_DIR")"
fi

# ── 4) 交叉工具链 env（NDK r28 时代：直连 clang wrapper，无 make-standalone-toolchain）──
export ANDROID_NDK_HOME="$NDK_DIR"
export PATH="$TOOLCHAIN/bin:$PATH"

# 目标 toolset：NDK 交叉 clang（configure/gyp 从 env CC/CXX/AR 读取，见 configure.py 的
# GetEnvironFallback 语义与 android_configure.py:66-74 —— 本管线 configure 命令与官方同源）
export CC="$TOOLCHAIN/bin/${TARGET_TUPLE}-clang"
export CXX="$TOOLCHAIN/bin/${TARGET_TUPLE}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
[ -x "$CC" ] && [ -x "$CXX" ] || die "NDK clang wrapper 缺失: $NDK_DIR"

# host toolset：gyp 对 toolsets:['host']（mksnapshot 等构建期 x86_64 宿主工具）从
# CC_host→CC、CXX_host→CXX 回退解析（tools/gyp/.../make.py:2482-2485）。若缺 CC_host，
# 会回退到上方 CC=NDK 交叉 clang → mksnapshot 编成 arm64-android ELF → 宿主执行 ENOEXEC
# → make 在 v8_snapshot 的 run_mksnapshot 处确定性断链（评审在 v24.21.0 实证）。
# --cross-compiling 强制 GYP want_separate_host_toolset=1，host 与 target 永远分离。
# build-essential 提供宿主 gcc/g++/ar（Dockerfile builder 阶段已装）。
export CC_host=gcc
export CXX_host=g++
export AR_host=ar

# GYP_DEFINES：目标 arch/宿主 OS/NDK 路径在 configure.py 之外、只能从 GYP_DEFINES 注入
# （缺则 gyp-load 阶段即报 "Undefined variable android_ndk_path / host_os is not defined"，
# 评审实证）。取值与 Node 官方 android-configure 完全同源（android_configure.py:69-74）。
export GYP_DEFINES="target_arch=${TARGET_ARCH} v8_target_arch=${TARGET_ARCH} android_target_arch=${TARGET_ARCH} host_os=linux OS=android android_ndk_path=${NDK_DIR}"

# ── 5) configure + make（configure 仅读 env CC/CXX/AR，见 configure.py:24）──
cd "$SRC/node-$NODE_VERSION"
say "configure --dest-os=android --dest-cpu=$TARGET_ARCH --shared"
./configure \
    --dest-cpu="$TARGET_ARCH" \
    --dest-os=android \
    --openssl-no-asm \
    --with-intl=none \
    --cross-compiling \
    --shared

say "make -j$(nproc) ..."
make -j"$(nproc)"

# ── 6) 产物收敛 + strip ──────────────────────────────────────────────────
say "收敛产物到 $OUT"
cp -f out/Release/node "$OUT/node"
# --shared 产出 libnode.so.<ABI>(137) 与未版本化软链；实体文件 strip，软链跳过
for f in out/Release/libnode.so*; do cp -df "$f" "$OUT/"; done
# 契约审计文件：configure 把 config.gypi（JSON）/ config.mk 写在源码根（工作目录），非 out/Release/
cp -f config.gypi config.mk "$OUT/"  # --shared 的 config.gypi 必含 node_shared=true，缺则 configure 异常，立即失败

"$STRIP" --strip-unneeded "$OUT/node" || true
for so in "$OUT"/libnode.so*; do
    [ -L "$so" ] || "$STRIP" --strip-unneeded "$so" || true
done

# ── 7) 门禁 ──────────────────────────────────────────────────────────────
say "16KB/ELF/平台/ABI 门禁 ..."
"$SCRIPT_DIR/check-alignment.sh" "$TOOLCHAIN/bin/llvm-objdump" "$OUT"

# ── 8) 产物基表（对照 Node SHASUMS256 语义，供发布审计）──────────────────
(cd "$OUT" && sha256sum node libnode.so* | tee SHASUMS256)
say "完成。产物：$OUT/node + $OUT/libnode.so.*（垂直切片见设计 §844）"