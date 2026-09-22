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

# ── 3b) zlib.gyp：把 NDK 的 cpu-features.c 编进 zlib 主 target ──────────────
# 背景：Node 的 deps/zlib 走 Chromium 的 BUILD.gn 源集（含 cpu_features.c，ARMV8_OS_ANDROID
# 分支调 android_getCpuFeatures()），而 BUILD.gn 要求 //third_party/cpu_features:ndk_compat
# 提供该符号实现 —— Node 源码树里没有这个目录（Chromium 才有）。gyp 路径下无人编译它，
# 于是 libzlib.a 带着未决符号进 openssl-cli/node/libnode.so，在 ld.lld 终链处确定性断链
# （v24.21.0 + NDK r28c 实证：undefined symbol: android_getCpuFeatures）。
# 修法：NDK 自带的 sources/android/cpufeatures/cpu-features.c 就是 ndk_compat 的本体
# （纯 C，只依赖 sys/* 头），随 zlib 主 target 同编同链。幂等：已打过则跳过。
ZLIB_GYP="$SRC/node-$NODE_VERSION/deps/zlib/zlib.gyp"
ZLIB_COMPAT_DIR="$SRC/node-$NODE_VERSION/deps/zlib/android-ndk-compat"
# 拷贝（每次都做：NDK 路径随构建容器变化，拷贝代价可忽略；幂等）。
mkdir -p "$ZLIB_COMPAT_DIR"
cp -f "$NDK_DIR/sources/android/cpufeatures/cpu-features.c" \
      "$NDK_DIR/sources/android/cpufeatures/cpu-features.h" \
      "$ZLIB_COMPAT_DIR/" \
    || die "NDK 缺 cpu-features.c/.h: $NDK_DIR/sources/android/cpufeatures/"
if ! grep -q "android-ndk-compat/cpu-features\.c" "$ZLIB_GYP"; then
    # 兼容上一版补丁（绝对路径写法，gyp/make 不认）：先清掉再打新补丁。
    python3 - "$ZLIB_GYP" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
s = s.replace("'sources': [ '<(android_ndk_path)/sources/android/cpufeatures/cpu-features.c' ],", "")
# 锚点：zlib 主 target 的 USE_FILE32API 条件块（mac/ios/freebsd/android 共用，v24.21.0 实测行）。
anchor = "              'defines': [\n                'USE_FILE32API'\n              ],\n            }],"
assert anchor in s, "zlib.gyp 锚点漂移（USE_FILE32API 条件块），补丁需人工跟进"
block = anchor + """
            ['OS=="android"', {
              # NDK 的 android_getCpuFeatures 实现（BUILD.gn 要求 //third_party/cpu_features:ndk_compat，
              # Node 源码树无该目录 —— 见上 3b 注释）。gyp 的 sources 必须相对 .gyp 文件，
              # 故上面的拷贝步骤先把 NDK 的 cpu-features.c/.h 落进本目录的子目录再引用。
              'sources': [ 'android-ndk-compat/cpu-features.c' ],
              'include_dirs': [ 'android-ndk-compat' ],
            }],"""
s = s.replace(anchor, block, 1)
open(p, "w").write(s)
print("patched")
PY
    say "补 zlib.gyp：编入 NDK cpu-features.c（android_getCpuFeatures 实现）"
fi

# ── 3c) v8.gyp：arm64 段 trap-handler 条件 += android ────────────────────
# 背景：V8 的 trap-handler 在 gyp 下按 OS 分文件：arm64-native 用
# handler-outside-posix.cc，host-x64 上的 arm64 simulator（mksnapshot）用
# handler-outside-simulator.cc。但两个条件的 OS 列表都只写
# "linux mac ... openharmony"，没有 android —— OS=android 时两个文件都不参编。
# 而 host=x64 + target=arm64 + V8_OS_LINUX（含 Android，见 v8config.h:84）时
# V8_TRAP_HANDLER_SUPPORTED=true，handler-outside.cc 的桩（#if !SUPPORTED）被裁，
# 于是 mksnapshot 终链悬空两个符号（v24.21.0 + NDK r28c 实证）：
#   undefined reference to v8_internal_simulator_ProbeMemory
#   undefined reference to RegisterDefaultTrapHandler()
# 上游 GN 路径（OS=="android" 分支自带该文件）无此问题，是 gyp 独有缺口。
# 修法：给 arm64 段两个条件行（native-posix / x64-simulator）各加 android，
# 行号写法（1187+1199，v24.21.0 实测；assert 双行双命中防漂移），不动 x64 段。
V8_GYP="$SRC/node-$NODE_VERSION/tools/v8_gypfiles/v8.gyp"
if ! grep -q 'openharmony android' "$V8_GYP"; then
    python3 - "$V8_GYP" <<'PY'
import sys
p = sys.argv[1]
lines = open(p).readlines()
# 1-based 1187 行 = arm64-native posix 条件；1199 行 = x64 simulator 条件
assert "linux mac ios openharmony" in lines[1186], "v8.gyp:1187 锚点漂移，补丁需人工跟进"
assert "(OS in \"linux mac win openharmony\")" in lines[1198], "v8.gyp:1199 锚点漂移，补丁需人工跟进"
lines[1186] = lines[1186].replace("linux mac ios openharmony", "linux mac ios openharmony android", 1)
lines[1186] = lines[1186].replace("linux mac openharmony", "linux mac openharmony android", 1)
lines[1198] = lines[1198].replace("linux mac win openharmony", "linux mac win openharmony android", 1)
open(p, "w").writelines(lines)
print("patched")
PY
    say "补 v8.gyp：arm64 trap-handler 条件 += android（mksnapshot 双符号实现）"
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

# 点名 libnode + node：顶层默认依赖图含 cctest，其 test_crypto_clienthello.cc 用
# aligned_alloc（bionic API 28+，ANDROID_API=26 不暴露）确定性断链 —— 见 RISKS §13。
# node/libnode.so 均不依赖 cctest，故直调 out 内 target，不走顶层 all/node 伪目标。
say "make -j$(nproc) libnode node ..."
make -C out BUILDTYPE=Release -j"$(nproc)" libnode node

# ── 6) 产物收敛 + strip ──────────────────────────────────────────────────
say "收敛产物到 $OUT"
cp -f out/Release/node "$OUT/node"
# gyp --shared 在 Linux/Android 下产物就是裸 libnode.so（soname 亦然，无版本后缀；
# 版本化命名是下游打包步骤）。实体文件 strip，软链（如有）跳过
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
(cd "$OUT" && sha256sum node libnode.so* config.gypi config.mk | tee SHASUMS256)
say "完成。产物：$OUT/node + $OUT/libnode.so.*（垂直切片见设计 §844）"