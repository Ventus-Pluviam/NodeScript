#!/usr/bin/env bash
# :nodeN 宿主（main.cpp）+ :bridge:native addon 的本机 NDK 交叉编译验证。
# CLAUDE.md：本机只做 C++ 交叉编译验证（AArch64 ELF）；APK/AGP assemble 仍走 CI。
#
# 用法:  engine/node-process/scripts/build-native.sh
# env:   ANDROID_NDK_HOME（默认 /root/ndk/android-ndk-r28c）
#        NODE_SRC（默认 /tmp/node24/node-v24.21.0，取 node_api.h 等三头文件）
#        LIBNODE（默认 /tmp/nrb-out7/libnode.so，符号对表用）
#        OUT_DIR（默认 engine/node-process/build/native-local，build/ 已 gitignore）
#
# 断言（任一失败即 exit 1）：
#  1) addon 导出 napi_register_module_v1；2) 宿主导出/无缺符号可执行；
#  3) node::Start 声明的 mangled 名 == main.cpp dlsym 字面量 == libnode 导出（三方对表）；
#  4) 两个产物 LOAD 段 align >= 16KB（§16 硬门禁）；5) 不依赖 libc++_shared（静态 STL）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

NDK="${ANDROID_NDK_HOME:-/root/ndk/android-ndk-r28c}"
TRIPLE=aarch64-linux-android26
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CXX="$TOOLCHAIN/${TRIPLE}-clang++"
NM="$TOOLCHAIN/llvm-nm"
READELF="$TOOLCHAIN/llvm-readelf"

NODE_SRC="${NODE_SRC:-/tmp/node24/node-v24.21.0}"
LIBNODE="${LIBNODE:-/tmp/nrb-out7/libnode.so}"
OUT="${OUT_DIR:-$ROOT/engine/node-process/build/native-local}"

fail() { printf '\033[1;31m[NATIVE FAIL]\033[0m %s\n' "$*" >&2; exit 1; }
step() { printf '\033[1;36m[native]\033[0m %s\n' "$*"; }

[ -x "$CXX" ] || fail "缺 NDK 编译器 $CXX（export ANDROID_NDK_HOME=?）"
[ -f "$NODE_SRC/src/node_api.h" ] || fail "缺 node_api.h（NODE_SRC=$NODE_SRC）"
[ -f "$LIBNODE" ] || fail "缺 libnode.so（LIBNODE=$LIBNODE）"
mkdir -p "$OUT"

# §16 硬门禁：LOAD 段 >= 2**14（16KB）。llvm-readelf -l 的 LOAD 行末列即 align。
assert_16k() {
  local f="$1"
  "$READELF" -l "$f" | python3 -c '
import sys, re
seen = 0
bad = []
for line in sys.stdin:
    if not line.startswith("  LOAD"): continue
    seen += 1
    parts = line.split()
    align = int(parts[-1], 16) if parts[-1].startswith("0x") else int(parts[-1])
    if align < 0x4000: bad.append(line.rstrip())
if seen == 0: sys.exit("no LOAD segments: not ELF?")
if bad: sys.exit("LOAD align < 16KB:\n" + "\n".join(bad))
' || fail "$f 未过 16KB LOAD 对齐门禁"
}

CXXFLAGS=(-std=c++20 -fPIC -O2 -Wall -Wextra -DNAPI_VERSION=10)
# -static-libstdc++：产物不依赖 libc++_shared.so（libnode 自己 NEEDED 它，运行期归
# node-runtime-build 装载面；addon/宿主不新增这条依赖）。
LDFLAGS=(-Wl,-z,max-page-size=16384 -static-libstdc++)

step "编 addon → bridge_native.node"
"$CXX" "${CXXFLAGS[@]}" -shared -I "$NODE_SRC/src" "${LDFLAGS[@]}" \
  -o "$OUT/bridge_native.node" "$ROOT/bridge/native/src/main/cpp/bridge_addon.cc"

step "编宿主 → noden（dlopen 形，不链 -lnode）"
"$CXX" "${CXXFLAGS[@]}" -fPIE -pie "${LDFLAGS[@]}" \
  -o "$OUT/noden" "$ROOT/engine/node-process/src/main/cpp/main.cpp" -ldl

step "符号对表（声明 ↔ dlsym 字面量 ↔ libnode 导出）"
# 3a. 独立 TU 按 main.cpp 同款声明调用 node::Start → 未定义符即该声明的 mangled 名
cat > "$OUT/start_probe.cc" <<'PROBE'
namespace node { int Start(int argc, char** argv); }
int probe(int argc, char** argv) { return node::Start(argc, argv); }
PROBE
"$CXX" -std=c++20 -c -o "$OUT/start_probe.o" "$OUT/start_probe.cc"
DECLARED="$("$NM" "$OUT/start_probe.o" | awk '$1=="U"{print $2}')"
[ -n "$DECLARED" ] || fail "probe.o 无未定义符号，nm 解析异常"
LITERAL="$(grep -o '_ZN4node5StartEiPPc' "$ROOT/engine/node-process/src/main/cpp/main.cpp" | head -1)"
EXPORTED="$("$NM" -D "$LIBNODE" | awk '$2=="T"{print $3}' | grep -x '_ZN4node5StartEiPPc' || true)"
[ "$DECLARED" = "_ZN4node5StartEiPPc" ] || fail "声明 mangled 名漂移：$DECLARED"
[ "$LITERAL" = "$DECLARED" ] || fail "main.cpp dlsym 字面量与声明不符：'$LITERAL' vs '$DECLARED'"
[ -n "$EXPORTED" ] || fail "libnode.so 未导出 T $DECLARED（§7.8 符号表不符）"
step "node::Start 三方一致: $DECLARED"

step "addon 导出 napi_register_module_v1"
"$NM" -D "$OUT/bridge_native.node" | grep -q 'napi_register_module_v1' \
  || fail "bridge_native.node 缺 napi_register_module_v1"

step "NEEDED 只许系统库（静态 STL，不把 libc++_shared 推给装载面）"
for f in "$OUT/noden" "$OUT/bridge_native.node"; do
  "$READELF" -d "$f" | grep -q 'libc++_shared' && fail "$f 误链 libc++_shared"
  # dlopen 形宿主/懒解析 addon：NEEDED 应 ⊆ {libc,libdl,libm}（NDK 默认 + -ldl）。
  mapfile -t needed < <("$READELF" -d "$f" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p')
  for lib in "${needed[@]}"; do
    case "$lib" in
      libc.so|libdl.so|libm.so) ;;
      *) fail "$f NEEDED 出现预期外依赖: $lib" ;;
    esac
  done
done

step "16KB LOAD 对齐（§16）"
assert_16k "$OUT/noden"
assert_16k "$OUT/bridge_native.node"

step "产物:"
ls -l "$OUT/noden" "$OUT/bridge_native.node"
file "$OUT/noden" "$OUT/bridge_native.node" 2>/dev/null || true
printf '\033[1;32m[NATIVE PASS]\033[0m addon + 宿主编译/符号/对齐全过（交叉验证，未在本机执行 —— 真机链待 CI）\n'
