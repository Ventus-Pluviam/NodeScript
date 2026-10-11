plugins {
    id("autoscript.jvm")
}

// 进程/文件系统面：杀树的 `/proc` ppid 图 + `kill` 二进制 + pid 两级反射阶梯
// （2026-10-12 批 105：由 `app-service:npm` 与 `platform:system` 两份**逐字同款**的
// `ProcOps` 合并而成 —— 手法同款但代码两份是 2026-10-11 批 95/99 留下的重复，
// 当时按「等维护者拍板抽模块」挂着，现批落地）。
//
// **纯 JVM、零模块依赖**：这一面只用 `java.nio` 与 `Process` 桩面，连 `:domain` 都不需要。
// 它刻意**不**住 `:domain` —— `/proc` 与 `ProcessBuilder` 是进程/文件系统面，
// 不是领域概念；放进领域层会让「领域层零 Android/零平台面」这条边界失守。
dependencies {
    // 无：std only（见上）
}
