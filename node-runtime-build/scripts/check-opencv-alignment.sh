#!/usr/bin/env bash
# AutoScript libopencv.so 门禁（docs/framework-design.md §16 硬门禁；§9.2 图像管线）
# 用法: check-opencv-alignment.sh <llvm-readelf路径> <so路径> <clang++路径>
# 与 Node 的 check-alignment.sh **不共用**（那边断言 libnode.so.<ABI>/config.gypi/node_shared，
# 这些是 Node 产物契约，放这儿会让纯图像产物误扫）。三条断言全是 §16/§9.2 通用面：
#   1) 16KB LOAD 对齐：每个 LOAD 段 align >= 2**14（0x4000），且至少一个 LOAD 段；
#   2) ABI/平台：ELF 机器为 AArch64、类型 ET_DYN（PIE/.so）；
#   3) NEEDED 白名单：只许 libc/libdl/libm/liblog/libc++_shared（装载面已知）——
#      **不许 libopencv_*.so**（静态链接的全部意义：不把 opencv 共享库推给装载面）。
set -euo pipefail

READELF="${1:?缺 llvm-readelf 路径}"
SO="${2:?缺 so 路径}"
CXX="${3:?缺 clang++ 路径（未用，占位防误调）}"

fail() { printf '\033[1;31m[GATE FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

[ -f "$SO" ] || fail "产物不存在: $SO"

# ── 1) LOAD 段 16KB 对齐（llvm-readelf -l 的 LOAD 行末列即 align 十六进制）──
"$READELF" -l "$SO" | python3 -c '
import sys, re
seen = 0
bad = []
for line in sys.stdin:
    if not line.startswith("  LOAD"): continue
    seen += 1
    parts = line.split()
    raw = parts[-1]
    align = int(raw, 16) if raw.startswith("0x") else int(raw)
    if align < 0x4000: bad.append(line.rstrip())
if seen == 0: sys.exit("no LOAD segments: not ELF?")
if bad: sys.exit("LOAD align < 16KB:\n" + "\n".join(bad))
' || fail "$so: LOAD 段 16KB 对齐不符（dlopen 在 16KB 页设备会崩）"
echo "[OK] $(basename "$SO"): 全部 LOAD 段 align >= 2**14"

# ── 2) ABI/平台契约 ───────────────────────────────────────────────────────
HEADER="$("$READELF" -h "$SO")"
echo "$HEADER" | grep -qE "Machine:.*AArch64" || fail "$SO 非 AArch64 ELF"
echo "$HEADER" | grep -qE "Type:.*DYN" || fail "$SO 非 ET_DYN（共享对象）"
echo "[OK] $(basename "$SO"): AArch64 + ET_DYN"

# ── 3) NEEDED 白名单（静态 opencv 的验收点）──────────────────────────────
mapfile -t needed < <("$READELF" -d "$SO" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p')
[ "${#needed[@]}" -gt 0 ] || fail "$SO 无 NEEDED 段（readelf 解析异常？）"
for lib in "${needed[@]}"; do
    case "$lib" in
        libc.so|libdl.so|libm.so|liblog.so|libc++_shared.so) ;;
        libopencv_*.so) fail "$SO NEEDED 出现 libopencv_*.so（$lib）—— OpenCV 未静态链接" ;;
        *) fail "$SO NEEDED 出现预期外依赖: $lib" ;;
    esac
done
echo "[OK] $(basename "$SO"): NEEDED = ${needed[*]}"
echo "[OK] 门禁通过：16KB LOAD 对齐 / AArch64+DYN / NEEDED 白名单（OpenCV 已静态链入）"
