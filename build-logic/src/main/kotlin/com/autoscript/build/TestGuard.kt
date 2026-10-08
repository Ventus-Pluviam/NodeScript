package com.autoscript.build

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

/**
 * 「aborted ≠ 绿」守卫 —— 承已删除的 tools/jvm-test-all.sh 的纪律（2026-09-30 起由约定插件承接）：
 * 测试里出现 skipped（JUnit5 Assumptions 中止 / @Disabled）即红。当年的事故是
 * SocketE2EHostTest 因 dist 落空 abort 成"静默跳过"、全仓大面积 abort 而门一路放行。
 *
 * 放行只有两条路：
 *  · [ENV_GATED] —— 设计上"环境不齐就诚实跳过"的 E2E（各类 KDoc 自述该契约）；
 *    **登记 ≠ 可以不跑**：nightly 的 e2e-nightly.yml 另有 check-e2e-ran.sh 证明这些类
 *    在那边真的执行过（登记只免掉"本机没装 node/npm 时"的红）；
 *  · `-PallowSkipped=<类名,…>`（简单名或全名，逗号分隔）—— 显式、临时、留痕。
 */
object TestGuard {

    /** 环境门禁类：跳过是其契约（"本地没有 npm/宿主 node 不假扮通过"），不计违规。 */
    private val ENV_GATED: Set<String> = setOf(
        "NpmCliDeployerTest",        // app-service/npm：素材源取本机 npm 安装
        "HostNodeNpmE2ETest",        // app-service/npm：拉真宿主 node+npm 进程
        "NpmCacheSeedDeployerTest",  // app-service/npm：离线首装金标准要宿主 npm + 真 tarball
        // app-service/npm：零 spawn 金标准（child_process 门禁下跑 P0 命令矩阵）——
        // 要宿主 node+npm 真起进程；**本机没装 npm 时跳过是对的**，而 CI 上两者恒在，
        // 所以这道门在那边一定真跑（§10.12 末行「vendored npm 升级只准通过此闸」）。
        "NpmSpawnGateMatrixTest",
        "P0LoopbackTest",            // app：P0 回环要宿主 node+npm（-PskipNpmE2E 下整类不跑）
    )

    fun apply(project: Project) {
        project.tasks.withType(Test::class.java).configureEach {
            val test = this
            val extra = (project.findProperty("allowSkipped") as String?)
                ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
                .orEmpty()
            val allow = ENV_GATED + extra
            val skipped = LinkedHashSet<String>()

            test.addTestListener(object : TestListener {
                override fun beforeSuite(suite: TestDescriptor) = Unit
                override fun beforeTest(testDescriptor: TestDescriptor) = Unit

                override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
                    if (result.resultType == TestResult.ResultType.SKIPPED) {
                        testDescriptor.className?.let { skipped += it }
                    }
                }

                // 整类被禁/整容器跳过只在这里现形（方法级 afterTest 收不到）。
                override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                    if (result.resultType == TestResult.ResultType.SKIPPED) {
                        suite.className?.let { skipped += it }
                    }
                }
            })

            test.doLast {
                fun allowed(cn: String) = allow.any {
                    it == cn || it.substringAfterLast('.') == cn.substringAfterLast('.')
                }
                val bad = skipped.filterNot(::allowed)
                if (bad.isNotEmpty()) {
                    throw GradleException(
                        "skipped/aborted ≠ 绿（守卫承 jvm-test-all.sh 纪律）：$bad。" +
                            "环境门禁类在 TestGuard.ENV_GATED；其余要么修掉 assumption，" +
                            "要么 -PallowSkipped=<简单名,…> 显式放行。",
                    )
                }
            }
        }
    }
}
