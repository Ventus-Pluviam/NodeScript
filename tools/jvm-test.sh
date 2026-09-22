#!/usr/bin/env bash
# 本机纯 JVM 测试脚手架（无 Android SDK 环境用；CI 仍以 ./gradlew 为准）。
#
# 用法: tools/jvm-test.sh [--android-jar] <main-src-root> <test-src-root> [extra-main-src-root...]
# 例: tools/jvm-test.sh "app-service/permission-center/src/main/kotlin domain/src/main/kotlin" app-service/permission-center/src/test/kotlin
#     tools/jvm-test.sh --android-jar "platform/system/src/main/kotlin domain/src/main/kotlin" platform/system/src/test/kotlin
#
# --android-jar：classpath 补一份 android.jar（取自 Gradle transforms 缓存的 android-library
#   编译期 android.jar），供 :app、:platform:system 这类含 android.* import 的模块本机编译+跑测。
#   它只是**编译期桩**：android.* 方法体在运行时全是 stub（抛 RuntimeException），
#   所以这些模块的单测必须把 Android 接触面挡在可注入的 ops 缝后面（见 platform/system/README.md）。
#   缺该 jar 时脚本直接报错退出，不静默降级成「编译一半」。
set -euo pipefail
cd "$(dirname "$0")/.."

JDK=/root/develop/claude/tools/jdk-17.0.17+10
G=/root/.gradle/caches/modules-2/files-2.1

WITH_ANDROID_JAR=0
[ "${1:-}" = "--android-jar" ] && { WITH_ANDROID_JAR=1; shift; }

jar() { find "$G/$1" -name "$2" 2>/dev/null | head -1; }

# android.jar 只从 AGP 的 transforms 缓存里找（= 与 Gradle 编译 android-library 用的同一份），
# 不去找 $ANDROID_HOME/platforms（本机无 SDK；且那份是完整 framework，语义不同）。
ANDROID_JAR=""
if [ "$WITH_ANDROID_JAR" = 1 ]; then
  ANDROID_JAR=$(find /root/.gradle/caches/8.9/transforms -name 'android.jar' -path '*/transformed/*' 2>/dev/null | head -1)
  if [ -z "$ANDROID_JAR" ]; then
    echo "tools/jvm-test.sh: --android-jar 需要 android.jar，但 Gradle transforms 缓存里没有。" >&2
    echo "  先跑一次任意 android-library 模块的 Gradle 编译把缓存养出来（CI 上由 setup-android 提供），" >&2
    echo "  或去掉 --android-jar 只编译纯 JVM 模块。" >&2
    exit 2
  fi
fi

KOTLIN_STDLIB=$(jar org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-2.0.21.jar')
COROUTINES=$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-1.8.1.jar')
JUNIT_API=$(jar org.junit.jupiter/junit-jupiter-api 'junit-jupiter-api-5.10.2.jar')
JUNIT_ENGINE=$(jar org.junit.jupiter/junit-jupiter-engine 'junit-jupiter-engine-5.10.2.jar')
JUNIT_PARAMS=$(jar org.junit.jupiter/junit-jupiter-params 'junit-jupiter-params-5.10.2.jar')
PLATFORM_LAUNCHER=$(jar org.junit.platform/junit-platform-launcher 'junit-platform-launcher-1.10.2.jar')
PLATFORM_ENGINE=$(jar org.junit.platform/junit-platform-engine 'junit-platform-engine-1.10.2.jar')
PLATFORM_COMMONS=$(jar org.junit.platform/junit-platform-commons 'junit-platform-commons-1.10.2.jar')
OPENTEST4J=$(jar org.opentest4j/opentest4j 'opentest4j-1.3.0.jar')
APIGUARDIAN=$(jar org.apiguardian/apiguardian-api 'apiguardian-api-1.1.2.jar')
ARCHUNIT=$(jar com.tngtech.archunit/archunit 'archunit-1.3.0.jar')
ARCHUNIT_JUNIT5=$(jar com.tngtech.archunit/archunit-junit5 'archunit-junit5-1.3.0.jar')
ARCHUNIT_JUNIT5_API=$(jar com.tngtech.archunit/archunit-junit5-api 'archunit-junit5-api-1.3.0.jar')
ARCHUNIT_JUNIT5_ENGINE=$(jar com.tngtech.archunit/archunit-junit5-engine 'archunit-junit5-engine-1.3.0.jar')
ARCHUNIT_JUNIT5_ENGINE_API=$(jar com.tngtech.archunit/archunit-junit5-engine-api 'archunit-junit5-engine-api-1.3.0.jar')
SLF4J=$(jar org.slf4j/slf4j-api 'slf4j-api-1.7.30.jar')

MAIN_CP="$KOTLIN_STDLIB:$COROUTINES"
TEST_CP="$KOTLIN_STDLIB:$COROUTINES:$JUNIT_API:$JUNIT_ENGINE:$JUNIT_PARAMS:$PLATFORM_LAUNCHER:$PLATFORM_ENGINE:$PLATFORM_COMMONS:$OPENTEST4J:$APIGUARDIAN:$ARCHUNIT:$ARCHUNIT_JUNIT5:$ARCHUNIT_JUNIT5_API:$ARCHUNIT_JUNIT5_ENGINE:$ARCHUNIT_JUNIT5_ENGINE_API:$SLF4J"

KOTLINC_CP=$(find /root/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable -name 'kotlin-compiler-embeddable-2.0.21.jar' | head -1)
KOTLINC_STDLIB_FOR_COMPILER=$(jar org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-2.0.21.jar')
KOTLINC_REFLECT=$(jar org.jetbrains.kotlin/kotlin-reflect 'kotlin-reflect-*.jar')
KOTLINC_SCRIPT_RT=$(jar org.jetbrains.kotlin/kotlin-script-runtime 'kotlin-script-runtime-2.0.21.jar')
KOTLINC_DAEMON=$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable 'kotlin-daemon-embeddable-2.0.21.jar')
TROVE=$(jar org.jetbrains.intellij.deps/trove4j 'trove4j-*.jar')
ANNOTATIONS=$(jar org.jetbrains/annotations 'annotations-*.jar')
KOTLINC_FULL="$KOTLINC_CP:$KOTLINC_STDLIB_FOR_COMPILER:$KOTLINC_SCRIPT_RT:$KOTLINC_DAEMON:$TROVE:$COROUTINES:$ANNOTATIONS"
[ -n "$KOTLINC_REFLECT" ] && KOTLINC_FULL="$KOTLINC_FULL:$KOTLINC_REFLECT"
kotlinc() { "$JDK/bin/java" -cp "$KOTLINC_FULL" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "$@"; }

MAIN_ROOTS="$1"; TEST_ROOT="$2"
# 输出目录带上工作树名：多个 worktree 并行跑时不会互相 rm -rf。
OUT=/tmp/jvm-test-out/$(basename "$PWD")-$(echo "$MAIN_ROOTS" | md5sum | cut -c1-12)
rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"

MAIN_SOURCES=$(find $MAIN_ROOTS -name '*.kt')

# ANDROID_JAR 只加在编译期 classpath 末尾：只补 android.* 符号，不覆盖任何真依赖；
# 也不进运行期 classpath（android.jar 里还带着 org.json / junit.framework 等影子类）。
CP_MAIN="$MAIN_CP${ANDROID_JAR:+:$ANDROID_JAR}"
CP_TEST="$MAIN_CP:$TEST_CP${ANDROID_JAR:+:$ANDROID_JAR}"

# 一次编译调用 = 同一 Kotlin module（internal 可见性与 Gradle main+test 同模块惯例对齐）。
# main 编译两遍的代价值得：换来与 CI/Gradle 完全一致的可见性语义。
TEST_SOURCES=$(find "$TEST_ROOT" -name '*.kt')
kotlinc -no-stdlib -module-name main-under-test -cp "$CP_MAIN" -d "$OUT/main" $MAIN_SOURCES
kotlinc -no-stdlib -module-name main-under-test -cp "$CP_TEST:$OUT/main" -d "$OUT/test" $MAIN_SOURCES $TEST_SOURCES

mkdir -p "$OUT/runner"
cat > "$OUT/runner/RunTests.java" <<'EOF'
import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import java.nio.file.*;

public class RunTests {
    public static void main(String[] args) throws Exception {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClasspathRoots(
                java.util.Set.of(Paths.get(args[0]))))
            .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(request, listener);
        var summary = listener.getSummary();
        summary.printTo(new java.io.PrintWriter(System.out));
        summary.printFailuresTo(new java.io.PrintWriter(System.out));
        System.exit(summary.getTotalFailureCount() == 0 ? 0 : 1);
    }
}
EOF
"$JDK/bin/javac" -cp "$TEST_CP" -d "$OUT/runner" "$OUT/runner/RunTests.java"
"$JDK/bin/java" -cp "$OUT/runner:$OUT/main:$OUT/test:$TEST_CP" RunTests "$OUT/test"
