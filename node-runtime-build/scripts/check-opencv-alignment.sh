#!/usr/bin/env bash
# AutoScript libopencv.so 门禁（docs/framework-design.md §16 硬门禁；§9.2 图像管线）
# 用法: check-opencv-alignment.sh <llvm-readelf路径> <so路径> <clang++路径> [llvm-nm路径]
# 与 Node 的 check-alignment.sh **不共用**（那边断言 libnode.so.<ABI>/config.gypi/node_shared，
# 这些是 Node 产物契约，放这儿会让纯图像产物误扫）。四条断言全是 §16/§9.2 通用面：
#   1) 16KB LOAD 对齐：每个 LOAD 段 align >= 2**14（0x4000），且至少一个 LOAD 段；
#   2) ABI/平台：ELF 机器为 AArch64、类型 ET_DYN（PIE/.so）；
#   3) NEEDED 白名单：只许 libc/libdl/libm/liblog/libc++_shared（装载面已知）——
#      **不许 libopencv_*.so**（静态链接的全部意义：不把 opencv 共享库推给装载面）；
#   4) JNI 符号面：五个 `Java_com_autoscript_platform_system_NativeImageAnalyzer_*`
#      （decode/match/release/color）逐个在场。
set -euo pipefail

READELF="${1:?缺 llvm-readelf 路径}"
SO="${2:?缺 so 路径}"
CXX="${3:?缺 clang++ 路径（未用，占位防误调）}"
NM="${4:-$(dirname "$READELF")/llvm-nm}"

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

# ── 4) JNI 符号面（2026-09-25 补）──────────────────────────────────────────
# 为什么这条必须**在 CI 里自动**跑：JNI 符号名是**字符串约定**——Kotlin 侧的
# `external fun` 与 C++ 的函数名之间没有任何编译器看护，改包名/类名/方法名只要
# 一处漏改，产物照样编得出来、照样通过上面三条，要到真机 dlopen 那一刻才以
# `UnsatisfiedLinkError` 现形。本机已吃过一次近失：手边那份产物早于 findColor
# 提交 87 分钟、`colorNative` **0 个**，而它照样是合法 AArch64 ELF、LOAD 对齐、
# NEEDED 干净 —— 那三条门禁一条都不会红。"手边的产物是新的"这种判断不能靠
# 目录列表，得靠符号逐个在场。
#
# 五处名字的来源（改必须同批）：`bridge/image/src/main/cpp/images_jni.cc` 的五个
# JNIEXPORT ↔ `:platform:system` 的 `NativeImageAnalyzer` 五个 external fun
# （包名 com.autoscript.platform.system + 类名 NativeImageAnalyzer）。
[ -x "$NM" ] || fail "缺 llvm-nm: $NM（第 4 条断言需要它；第三个参数之外再传一个）"
# 符号表**一次读完落进变量**，再做子串判定 —— 不写成 `nm | grep -q`：
# 本脚本是 `set -euo pipefail`，而 `grep -q` 命中即退出，`nm` 若还在写就被 SIGPIPE
# 掉，管道整体退出码 = 141（**找到了也判失败**）。这个坑的隐蔽处在于它取决于
# 输出量：符号表小到装进管道缓冲时 nm 已写完、不复现；换个更大的产物或换一次
# 调度就红，且红得没有道理。用 bash 的 `case` 做子串匹配，不引管道也不挑 grep。
nm_syms="$("$NM" -D --defined-only "$SO")"
missing=()
for sym in decodeNative ingestNative matchNative releaseNative colorNative; do
    full="Java_com_autoscript_platform_system_NativeImageAnalyzer_${sym}"
    case "$nm_syms" in
        *"$full"*) ;;
        *) missing+=("$full") ;;
    esac
done
if [ "${#missing[@]}" -gt 0 ]; then
    printf '%s\n' "${missing[@]}" | sed 's/^/  缺符号: /' >&2
    fail "$SO 的 JNI 符号面不全（Kotlin external fun 与 images_jni.cc 对不上：dlopen 后 UnsatisfiedLinkError）"
fi
echo "[OK] $(basename "$SO"): JNI 五个符号逐个在场（decode/ingest/match/release/color）"
echo "[OK] 门禁通过：16KB LOAD 对齐 / AArch64+DYN / NEEDED 白名单（OpenCV 已静态链入）/ JNI 符号面"
