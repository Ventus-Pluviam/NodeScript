plugins {
    id("autoscript.jvm")
    // 测试夹具（backlog D9）：本模块的 testFixtures 源集里放**全仓唯一一份** HostNpm
    // 宿主 npm 发现器（源码住 build-logic/testkit-shared/，同 arch-shared 的注源手法）。
    // `:app` 的 P0LoopbackTest 与 `:app-service:npm` 自己的三个 E2E 都取这一份。
    id("java-test-fixtures")
}

// npm 安装/审批/镜像验证 + `npm` 命名空间桥处理器（docs §10/§14）。
// 2026-09-30 审查步骤 5 自 :app-service:packager 拆出（npm/ 对父包零 import，切分即净）。
// 纯 JVM（零 `import android.`）。
kotlin {
    // 共享测试夹具源（backlog D9）：`build-logic/testkit-shared` 与 arch-shared 同形 ——
    // 不进模块图、由约定/模块 build 脚本注进源集。住这里而不是各模块各抄一份，是因为
    // 两份已经分叉过（见该文件 KDoc）。
    sourceSets.named("testFixtures") {
        kotlin.srcDir(rootProject.file("build-logic/testkit-shared/src/main/kotlin"))
    }
}

dependencies {
    implementation(project(":domain"))
    "testFixturesImplementation"(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
}

tasks.test {
    // 真实 npm e2e 默认跳过：CI 走 -PskipNpmE2E（本机不带 flag 即跑，宿主机 node+npm 存在才启用）。
    // 清单：HostNodeNpmE2ETest（install/ci 真跑）+ NpmCacheSeedDeployerTest 的金标准
    // （仅凭种子 npm ci --offline）+ NpmSpawnGateMatrixTest 的零 spawn 金标准
    // （child_process 门禁下跑 P0 命令矩阵，§10.12 末行）——三者都要拉真 npm 进程。
    //
    // 后两条**不碰网络**（矩阵只用本地 `file:` 依赖），但仍随本 flag 一起排除：它们的
    // 环境前置与那两条相同（宿主 node+npm），而 CI 的 jvm-tests 恒带本 flag —— 留在这里
    // 是为了让"要不要跑真 npm"仍然只有一个开关（nightly 去掉 flag 即全跑，
    // check-e2e-ran.sh 逐类验尸）。
    if (project.hasProperty("skipNpmE2E")) {
        exclude("**/HostNodeNpmE2ETest*")
        exclude("**/NpmCacheSeedDeployerTest*")
        exclude("**/NpmSpawnGateMatrixTest*")
    }
}
