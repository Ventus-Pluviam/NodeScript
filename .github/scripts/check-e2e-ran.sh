#!/usr/bin/env bash
# 「E2E 真跑了」门（nightly 专用，B1 的收口）：
#
# 为什么需要它：这些 E2E 的契约是「环境不齐就 assumeTrue **诚实跳过**」——那在本机是对的，
# 但在 CI 上会退化成 **一片绿的假通过**（B1 记的原始病灶：`-PskipNpmE2E` 恒开 + 写死的
# 宿主 npm 路径，等于最危险的路径一次都没走过）。所以 nightly 跑完必须回头**验尸**：
# 每个 E2E 类都要有测试结果 XML、tests > 0、skipped == 0。缺 XML = 整类没跑（最坏情形）；
# skipped > 0 = 又静默跳了（多半是环境发现/前置数据没铺好）。
#
# 本机同一条命令可跑（零依赖）：bash .github/scripts/check-e2e-ran.sh
set -euo pipefail

cd "$(dirname "$0")/../.."

E2E_CLASSES=(
  com.autoscript.appservice.npm.HostNodeNpmE2ETest        # 真 npm install（registry 网络）
  com.autoscript.appservice.npm.NpmCacheSeedDeployerTest  # 仅凭种子 cache 的离线 npm ci
  com.autoscript.appservice.npm.NpmSpawnGateMatrixTest    # 零 spawn 金标准：门禁下跑 P0 命令矩阵
  com.autoscript.shell.P0LoopbackTest                     # :app 全链路：桥 → 真 npm → 树 → 点击
)

fail=0
for cls in "${E2E_CLASSES[@]}"; do
  # Gradle 的结果位：JVM 模块 test-results/test/、Android 模块 test-results/testDebugUnitTest/
  xml="$(find . -path "*/build/test-results/*/TEST-${cls}.xml" -print -quit 2>/dev/null || true)"
  if [ -z "$xml" ]; then
    echo "✗ ${cls}：找不到测试结果 XML —— 整类没跑（最危险路径未被覆盖）"
    fail=1
    continue
  fi
  tests="$(grep -o 'tests="[0-9]*"' "$xml" | head -1 | grep -o '[0-9]*' || echo 0)"
  skipped="$(grep -o 'skipped="[0-9]*"' "$xml" | head -1 | grep -o '[0-9]*' || echo 0)"
  failures="$(grep -o 'failures="[0-9]*"' "$xml" | head -1 | grep -o '[0-9]*' || echo 0)"
  errors="$(grep -o 'errors="[0-9]*"' "$xml" | head -1 | grep -o '[0-9]*' || echo 0)"
  if [ "$tests" -eq 0 ]; then
    echo "✗ ${cls}：tests=0（XML 在但没有用例 —— 与没跑同义）[${xml}]"
    fail=1
  elif [ "$skipped" -gt 0 ]; then
    echo "✗ ${cls}：skipped=${skipped}（nightly 不许静默跳过：环境发现/前置数据没铺好）[${xml}]"
    fail=1
  elif [ "$failures" -gt 0 ] || [ "$errors" -gt 0 ]; then
    echo "✗ ${cls}：failures=${failures} errors=${errors}（Gradle 已经红了，这里再点名一次）[${xml}]"
    fail=1
  else
    echo "✓ ${cls}：tests=${tests} 全跑全过"
  fi
done

if [ "$fail" -ne 0 ]; then
  echo ""
  echo "nightly E2E 未达标：真 npm 路径没被证明跑过。先看上面 ✓/✗，"
  echo "多半是宿主 npm 发现（HostNpm：npm root -g → node prefix → 静态位）或 ~/.npm/_cacache 没预热。"
  exit 1
fi
echo "E2E 真跑了：四条真 npm 路径均有结果且零跳过。"
