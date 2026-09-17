#!/usr/bin/env bash
# AutoScript 16KB/ELF/平台/ABI 门禁（docs/framework-design.md §16 硬门禁；Android 官方 page-sizes 指南）
# 用法: check-alignment.sh <llvm-objdump路径> <产物目录>
#  1) ELF 断言：每个 LOAD 段 align >= 2**14（0x4000，16KB），且至少解析到一个 LOAD 段
#  2) 平台契约断言：config.gypi（configure 写的 JSON）variables.OS=android 且 node_shared=true
#     —— configure 把 config.gypi（json.dumps）与 config.mk（BUILDTYPE/NODE_TARGET_TYPE）写在
#        工作目录即源码根；CLI 参数（--dest-os/--shared）不回显进 config.mk，故契约以 JSON 键为准
#  3) ABI 断言：存在 libnode.so.<EXPECTED_NODE_MODULE_VERSION>（预编译 .node addon 必须同号）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
set -a
# shellcheck source=../VERSIONS.env
source "$ROOT_DIR/VERSIONS.env"
set +a
# ABI 号 = NODE_MODULE_VERSION，单一事实来源派生，不设默认值防双处漂移
EXPECTED_ABI="${EXPECTED_NODE_MODULE_VERSION:?VERSIONS.env 未定义 EXPECTED_NODE_MODULE_VERSION}"

OBJDUMP="${1:?缺 llvm-objdump 路径}"
OUT="${2:?缺产物目录}"

fail() { printf '\033[1;31m[GATE FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

# ── 1) LOAD 段 16KB 对齐（零 LOAD 行 = 损坏/非 ELF，必须失败）──────────────
mapfile -t so_files < <(for f in "$OUT"/libnode.so*; do [ -L "$f" ] || echo "$f"; done)
[ "${#so_files[@]}" -gt 0 ] || fail "未找到 libnode.so 实体文件（非软链）"

for so in "${so_files[@]}"; do
    if ! "$OBJDUMP" -p "$so" | python3 -c '
import sys, re
seen = False
bad = []
for line in sys.stdin:
    if not line.lstrip().startswith("LOAD"):   # llvm-objdump 的 LOAD 行带两空格缩进
        continue
    seen = True
    m = re.search(r"align\s+2\*\*(\d+)", line)
    if not m:
        bad.append(("no-align", line.strip()))
    elif int(m.group(1)) < 14:
        bad.append((int(m.group(1)), line.strip()))
if not seen:
    print("   未解析到任何 LOAD 段（objdump 输出异常或非 ELF）", file=sys.stderr)
    sys.exit(1)
if bad:
    for v, l in bad:
        print("   ", v, repr(l), file=sys.stderr)
    sys.exit(1)
' && true; then
        fail "$so: 存在 align < 2**14 的 LOAD 段（非 16KB 对齐，dlopen 在 16KB 页设备会崩）"
    fi
    echo "[OK] $(basename "$so"): 全部 LOAD 段 align >= 2**14"
done

# ── 2) 平台契约（config.gypi：variables.OS=android / node_shared=true）─────
[ -f "$OUT/config.gypi" ] || fail "缺 config.gypi（configure 未成功产出）"
if ! python3 - "$OUT/config.gypi" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    cfg = json.load(f)
vars_ = cfg.get("variables", {})
if vars_.get("OS") != "android":
    print(f"   OS = {vars_.get('OS')!r}（期望 android）", file=sys.stderr)
    sys.exit(1)
if vars_.get("node_shared") is not True:
    print(f"   node_shared = {vars_.get('node_shared')!r}（期望 true）", file=sys.stderr)
    sys.exit(1)
PY
then
    fail "config.gypi 平台契约不符（process.platform 将不是 android / libnode.so 未以 --shared 启用）"
fi

# ── 3) ABI 断言（容忍 libnode.so.137 与带补丁号的 libnode.so.137.x.y）─────
found_abi=""
for f in "${so_files[@]}"; do
    name="$(basename "$f")"
    if [[ "$name" == "libnode.so.$EXPECTED_ABI" || "$name" == "libnode.so.$EXPECTED_ABI."* ]]; then
        found_abi="$f"
        break
    fi
done
[ -n "$found_abi" ] || fail "未见 libnode.so.$EXPECTED_ABI（期望 NODE_MODULE_VERSION=$EXPECTED_ABI）"

echo "[OK] 门禁通过：16KB LOAD 对齐 / OS=android+shared / libnode.so.$EXPECTED_ABI"