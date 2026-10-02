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
    # 断点续传：tarball 约 30MB+，失败重试用 -C - 接着写（curl --retry 3 只重试
    # 连接，写了一半的文件默认截断重来；-C - 让服务端按已有字节续传，已完整即秒过）。
    curl -fsS -C - --retry 3 -o "$DL/$NODE_TARBALL" \
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

# ccache（CI 提速，可选开关）：USE_CCACHE 非空且 ccache 在 PATH 时，CC/CXX 包一层
# ccache 前缀（`CC="ccache <clang>"` 形态；gyp/make 经 env 取 CC，空格分隔天然可分）。
# 放在 `[ -x ]` 探针**之后**（探针验的是裸 wrapper 路径）；AR/RANLIB 不包（归档由缓存
# 对象重链，本就快）；host toolset（CC_host=gcc，下方）不包（mksnapshot 等宿主工具
# 量小，且换了它们的编译器指纹会污染命中口径）。Docker 发布轨不装 ccache → 条件恒假。
# 跨轮命中前提：$WORK 布局稳定（ccache 默认按预处理内容哈希；CI 的 WORK_DIR 固定，
# 见 node-slice.yml）—— 绝对路径一致即命中，无需 sloppiness。
if [ -n "${USE_CCACHE:-}" ] && command -v ccache >/dev/null 2>&1; then
    export CC="ccache $CC"
    export CXX="ccache $CXX"
    say "ccache 已接管 CC/CXX（dir=${CCACHE_DIR:-~/.ccache}）"
elif [ -n "${USE_CCACHE:-}" ]; then
    printf '\033[1;33m[WARN]\033[0m USE_CCACHE=1 但无 ccache，走无缓存构建\n' >&2
fi

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
# ICU：small-icu + zh,en（§18 第 4 项 2026-09-26 拍板）。数据源是**仓内 canned ICU**
# （deps/icu-small/ 带 README-FULL-ICU.txt → configure.py 走 canned_is_full 分支），
# **不需要联网下载 icu4c**；locales 由 tools/icu 在构建期裁剪（root 自动加，故 zh,en）。
./configure \
    --dest-cpu="$TARGET_ARCH" \
    --dest-os=android \
    --openssl-no-asm \
    --with-intl=small-icu \
    --with-icu-locales=zh,en \
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
say "16KB/ELF/平台/ABI 门禁（含 libc++_shared：libnode 的传递依赖，须与它同 16KB 口径）..."
# libc++_shared.so：libnode 的 DT_NEEDED（readelf 实证；bionic 的 RUNPATH 不作用于
# 被依赖库的传递依赖，2026-09-29 真机实测 —— 它必须由装载面显式携带）。产线用 NDK
# sysroot 的同 ABI 版本（与 app/build.gradle.kts 的 prepareEngineNativeLibs 同源），
# 同门禁断言，保证 APK 里三件套的 16KB 口径一致。
# NDK 的 sysroot ABI 目录不含 API 号（aarch64-linux-android/，不是 ${TARGET_TUPLE}/）：
# 顶层 libc++_shared.so 是 r28c 的无前缀版本，30/…/35 子目录是带版本号的（给 crt 变体用）。
# API 约束只影响链入的库集合（libc.a 等），libc++_shared.so 本体不分 API —— 取无前缀件。
LIBCXX_SHARED="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${TARGET_TUPLE%%$ANDROID_API}/libc++_shared.so"
[ -f "$LIBCXX_SHARED" ] || die "libc++_shared.so 不在 NDK sysroot: $LIBCXX_SHARED"
# 收敛进 $OUT：§8 基表与下游 artifact 都从 $OUT 取件（门禁只读不断言落位，
# 此处不拷则基表 `sha256sum` 缺件、node-slice.yml 上传空集 —— 2026-09-30 CI 实测红）。
cp -f "$LIBCXX_SHARED" "$OUT/libc++_shared.so"
"$STRIP" --strip-unneeded "$OUT/libc++_shared.so" || true
bash "$SCRIPT_DIR/check-alignment.sh" "$TOOLCHAIN/bin/llvm-objdump" "$OUT" "$OUT/libc++_shared.so"

# ── 8) 产物基表（对照 Node SHASUMS256 语义，供发布审计）──────────────────
# 三件套同基表（libc++_shared 与 libnode 同 16KB 口径、同为装载闭包成员 —— 真机实测
# 它是 libnode 的传递依赖，RUNPATH 不替它解析，2026-09-29）。基表件数从 4 → 5。
(cd "$OUT" && sha256sum node libnode.so* libc++_shared.so config.gypi config.mk | tee SHASUMS256)

# ── 9) vendored npm CLI 素材（§10.2 调用链首段：assets/npm/** → filesDir/npm/）──
# 素材 = **registry 发布态 tarball**（2026-10-02 A6 换源；原「Node 源码树 deps/npm」
# 口径作废 —— Node 24.21.0 携带 11.19.0 ≠ §10.1 脊梁的 12.x 系，等 Node 线原理上
# 不通）。原「不另下 registry tarball」的理由是版本纪律 —— 那条纪律**没丢**：版本与
# sha1 钉在 VERSIONS.env（改那里即命中本脚本的 paths 触发面 → 全链回归），此处再加
# 内容断言做双闸。素材与 libnode.so 同批产出、同一 artifact 出库；:app 的随包任务
# （build-logic 的 prepareNpmCliAssets）从这里取件，启动期由 NpmCliDeployer 原子
# 部署到 filesDir/npm/。
#
# 剪裁（动手前先想清楚代价）：
#   · docs/ man/ —— 只有 `npm help` 用得到，不参与 install/ci/ls/prune 任何一条链；
#     registry 发布态仍带这两样，照剪。**test/ 与 tap-snapshots/ 不用剪 —— 发布态
#     本来就没有**（backlog E4 的 2.7MB 表观目标随换源天然达成）；
#   · 以 . 开头的条目**一律不随包** —— AssetManager 对点条目的可见性在 ROM 间不一致
#     （历史上有的实现直接跳过 list 结果），留着就是「源里有、设备上没有」的静默差。
#     npm 树里的点条目只有 node_modules/.bin（npm 自己的 bin 链接；用户项目的
#     bin-links 由 npm 现建，不读这里）与 node_modules/.package-lock.json（npm 自身
#     node_modules 的隐藏 lock，只有"在 npm 自己的目录里跑 npm ci"才用得到），
#     外加发布态自带的杂项点文件（.release-please-manifest.json 之类）—— 同批清。
#     根上的 .npmrc 也不是 npm 的运行时配置源（它读的是 <npm 根>/npmrc，无点）。
#   · 符号链接一律解引用（cp -RL）：assets 与 APK 都装不了符号链接。
# 下载/校验与 Node、NDK 同一套路：落 $DL（工作流下载层缓存随 VERSIONS.env 失效重下），
# sha1 对 registry 发布物的 dist.shasum（发布即事实，改了 = registry 事故）。
NPM_TARBALL="npm-$NPM_CLI_VERSION.tgz"
if [ ! -f "$DL/$NPM_TARBALL" ]; then
    say "下载 npm $NPM_CLI_VERSION（registry tarball）..."
    curl -fsS --retry 3 -o "$DL/$NPM_TARBALL" \
        "https://registry.npmjs.org/npm/-/$NPM_TARBALL"
else
    say "复用已下载 $DL/$NPM_TARBALL"
fi
say "校验 npm tarball sha1（期望 ${NPM_CLI_SHA1:0:16}…）"
echo "$NPM_CLI_SHA1  $DL/$NPM_TARBALL" | sha1sum -c - >/dev/null \
    || die "npm tarball sha1 校验失败（registry 发布物 ≠ VERSIONS.env 钉的 $NPM_CLI_VERSION）"
rm -rf "$SRC/npm-$NPM_CLI_VERSION"
mkdir -p "$SRC/npm-$NPM_CLI_VERSION"
tar -xzf "$DL/$NPM_TARBALL" -C "$SRC/npm-$NPM_CLI_VERSION" --strip-components=1

say "收敛 vendored npm CLI 素材到 $OUT/npm（registry tarball，剪裁 docs/man/点条目）..."
NPM_SRC="$SRC/npm-$NPM_CLI_VERSION"
[ -f "$NPM_SRC/bin/npm-cli.js" ] || die "npm tarball 无 bin/npm-cli.js：$NPM_SRC（素材形态变了？见 VERSIONS.env 的 NPM_CLI_VERSION 段）"
# 版本断言（双闸的内容面）：sha1 挡发布物漂移，这里挡 VERSIONS.env 与解包内容脱节 ——
# 换版本 = 一件事一个提交 + 全链回归，断言红即是逼那次显式决策。
NPM_GOT_VERSION="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["version"])' "$NPM_SRC/package.json")" \
    || die "读不出 $NPM_SRC/package.json 的 version（素材形态变了）"
[ "$NPM_GOT_VERSION" = "$NPM_CLI_VERSION" ] \
    || die "vendored npm 版本漂移：解包内容 = $NPM_GOT_VERSION，VERSIONS.env 钉的是 $NPM_CLI_VERSION（换版本 = 一件事一个提交 + 全链回归）"
rm -rf "$OUT/npm"
cp -RL "$NPM_SRC" "$OUT/npm"
rm -rf "$OUT/npm/docs" "$OUT/npm/man"
find "$OUT/npm" -name '.*' -prune -exec rm -rf {} +
# 点条目清零的兜底断言（防未来 npm 版本又塞进新的点条目 —— 上面那条 find 是"删"，这条是"验"）
[ -z "$(find "$OUT/npm" -name '.*' -print -quit)" ] \
    || die "npm 素材仍有点条目（AssetManager 读不到，随包即静默差）"
# 锚文件 + 安装引擎在场断言：npm-cli.js 在但 node_modules 空 = 设备上一跑就缺模块的**半瘫 CLI**，
# 比没素材更糟（部署的锚校验只看 bin/，看不穿依赖树）。arborist 是 §10 反复点名的安装引擎本体。
for anchor in bin/npm-cli.js bin/npx-cli.js node_modules/@npmcli/arborist/package.json; do
    [ -f "$OUT/npm/$anchor" ] || die "npm 素材缺 $anchor（半瘫 CLI 不随包；registry 发布态的 node_modules 是否被 files 收敛裁掉？）"
done
NPM_FILES="$(find "$OUT/npm" -type f | wc -l)"
NPM_BYTES="$(du -sb "$OUT/npm" | cut -f1)"
say "npm 素材就位：$NPM_FILES 个文件 / $((NPM_BYTES / 1024 / 1024))MiB（npm $NPM_GOT_VERSION）"

# ── 10) npm 素材基表（§8 同语义，逐文件 —— 出库 artifact 一并上传，供下载方核验）──
(cd "$OUT/npm" && find . -type f | LC_ALL=C sort | sed 's|^\./||' | xargs -d '\n' sha256sum) > "$OUT/npm-manifest.sha256"
say "npm 素材基表：$(wc -l < "$OUT/npm-manifest.sha256") 行 → $OUT/npm-manifest.sha256"

say "完成。产物：$OUT/node + $OUT/libnode.so.* + $OUT/libc++_shared.so + $OUT/npm（vendored npm CLI，垂直切片见设计 §844）"