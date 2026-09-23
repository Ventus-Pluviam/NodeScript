#!/usr/bin/env bash
# 本机全模块 JVM 回归（无 Android SDK 环境用；CI 仍以 ./gradlew 为准）。
# 逐模块最小依赖编译：只带该模块真正依赖的源根，避免「一个模块的编译错误被别的模块掩盖」。
#
# 用法: tools/jvm-test-all.sh [模块名...]      # 不给参数 = 全跑；给了只跑指定的几个
#   tools/jvm-test-all.sh domain capabilities
#
# 纯 JVM 模块走 tools/jvm-test.sh（**不给** android.jar：这样 :domain 里误加
# `import android.*` 会在本机直接编译失败，不被掩盖）；含 Android 源码的模块走
# `--android-jar`。每个模块 1200s 超时，任一模块失败则整体退出码非 0。
#
# :ui **故意不在**下面的清单里：compose/@Composable 没有裸 kotlinc 配方
# （kotlinc 直跑连 `androidx.compose` 坐标都拿不到）——15 个 Gradle 模块中
# 它是唯一只走 `./gradlew :ui:testDebugUnitTest`（CI 任务表已列）的。
set -uo pipefail
cd "$(dirname "$0")/.."

T=tools/jvm-test.sh
D=domain/src/main/kotlin
FAILED=()

# grep 过滤只留结论行 + 报错行：全量 kotlinc 输出太长，看不完也没人看。
run() {   # run <模块名> <是否要 android.jar: 0|1> <main-src-roots> <test-src-root>
  local name="$1" android="$2" main_roots="$3" test_root="$4"
  if [ -n "$ONLY" ] && ! echo " $ONLY " | grep -q " $name "; then return 0; fi
  echo "##### $name"
  local flag=""; [ "$android" = 1 ] && flag="--android-jar"
  local out
  out=$(timeout 1200 "$T" $flag "$main_roots" "$test_root" 2>&1)
  echo "$out" | grep -E "tests (successful|failed|aborted)|containers failed|error:|FAILED|Exception in" | head -20
  # aborted（Assumptions.assume* 前置未满足 = 这条压根没跑）**不算过**：只认
  # "0 tests failed" 会让环境性静默跳过冒充绿 —— SocketE2EHostTest 曾把仓库根锚死在
  # worktree 名上，除该名外全部 abort，门却一路放行。计数用 grep -o 抽数字，
  # 不写死结论行的列宽（那列宽随位数变，写死就是换个地方假绿）。
  if ! echo "$out" | grep -q "0 tests failed"; then
    FAILED+=("$name")
  elif [ "$(echo "$out" | grep -oE '^[^0-9]*[0-9]+ tests aborted' | grep -oE '[0-9]+' | head -1)" != "0" ]; then
    FAILED+=("$name:aborted")
  fi
}

ONLY="$*"

run domain            0 "$D"                                                                     domain/src/test/kotlin
run bridge-java       0 "bridge/java/src/main/kotlin $D"                                          bridge/java/src/test/kotlin
run runtime           0 "app-service/runtime/src/main/kotlin $D"                                  app-service/runtime/src/test/kotlin
run scheduler         0 "app-service/scheduler/src/main/kotlin $D"                                app-service/scheduler/src/test/kotlin
run script-repo       1 "app-service/script-repo/src/main/kotlin $D"                              app-service/script-repo/src/test/kotlin
run permission-center 0 "app-service/permission-center/src/main/kotlin $D"                        app-service/permission-center/src/test/kotlin
run packager          0 "app-service/packager/src/main/kotlin $D"                                 app-service/packager/src/test/kotlin
run capabilities      1 "platform/capabilities/src/main/kotlin $D"                                platform/capabilities/src/test/kotlin
run platform-system   1 "platform/system/src/main/kotlin $D"                                      platform/system/src/test/kotlin
# engine：纯 JVM（flag 0 = 连 android.jar 都不给 —— 任何 android.* import 直接编译失败，
# 与 engine ArchitectureTest「本模块保持纯 JVM」同一道闸）。真起 node 的集成测试也在这一行。
run engine            0 "engine/node-process/src/main/kotlin $D"                                  engine/node-process/src/test/kotlin
run app               1 "app/src/main/kotlin app-service/runtime/src/main/kotlin app-service/scheduler/src/main/kotlin app-service/script-repo/src/main/kotlin app-service/permission-center/src/main/kotlin app-service/packager/src/main/kotlin bridge/java/src/main/kotlin platform/capabilities/src/main/kotlin platform/system/src/main/kotlin engine/node-process/src/main/kotlin $D" app/src/test/kotlin

if [ ${#FAILED[@]} -gt 0 ]; then
  echo "##### 失败模块: ${FAILED[*]}"
  exit 1
fi
echo "##### DONE"
