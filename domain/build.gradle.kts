plugins {
    id("autoscript.jvm")
    // 契约测试夹具（§8.5）：`IntentStoreContract` 是**同一组用例打在多个实现上**的那一份
    // —— 存储引擎从 jsonl 换成 SQLite 时，两个实现必须逐条等价，所以规格只能有一份。
    // 消费方：`:domain` 自己的 test（InMemoryIntentStore，无门禁）、`:platform:system`（SqliteIntentStore）。
    id("java-test-fixtures")
}

// 领域层：纯 Kotlin，零 Android / 零桥依赖。全部 SPI 契约见 docs §4.1/§7/§9.5。
java {
    withSourcesJar()
}

dependencies {
    // kotlinx.coroutines（Flow/suspend）是契约层签名的一部分；core 仅含 Flow/协程原语，零 Android。
    api(libs.kotlinx.coroutines.core)
    // 夹具里写的是 JUnit 断言（抽象测试类），消费方拿到的也是 JUnit 面 —— api 而非 implementation。
    "testFixturesApi"(libs.junit.jupiter)
}

// ModuleGraphTest 读仓库根的文件（settings/CI/文档计数），这些不是本模块的源码 ——
// 不声明成输入的话，改了文档 Gradle 仍判 :domain:test UP-TO-DATE，门在本机就哑了。
tasks.named<Test>("test") {
    inputs.files(
        rootProject.file("settings.gradle.kts"),
        rootProject.file("build-logic/settings.gradle.kts"),
        rootProject.file(".github/workflows/ci.yml"),
        rootProject.file("CLAUDE.md"),
        rootProject.file("docs/design/06-modules.md"),
    ).withPropertyName("moduleGraphDocs").withPathSensitivity(PathSensitivity.RELATIVE)
}
