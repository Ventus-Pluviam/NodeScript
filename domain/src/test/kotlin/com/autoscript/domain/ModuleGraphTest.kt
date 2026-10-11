package com.autoscript.domain

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 全仓模块依赖图守护（docs §4.1 依赖方向铁律 + §6 模块表 + §6 末「依赖方向无环」）。
 *
 * 与各模块内 archUnit 测试的分工：
 * - **模块内 archUnit**：按字节码校验该模块的包没有 import 不该碰的包 —— 只对有 class 的模块有效；
 * - **本测试**：按构建脚本校验模块依赖边符合 §6「允许依赖」列且无环 —— 空模块（`:bridge:image`
 *   等无 Kotlin 源码）同样被覆盖，且能拦住 Gradle 层反向依赖。`settings` 里注释掉的 include
 *   不算声明（正则锚行首）——审查步骤 1 摘除 `:engine:sandbox` 空壳就靠这条。
 *
 * 两者缺一不可：字节码检查看不见 `implementation(project(...))` 这条边，
 * 构建脚本检查看不见「声明了 :domain 却 import 了 :bridge」这类越权。
 *
 * 放在 :domain 的原因：本模块是纯 JVM、无 Android SDK 依赖、CI 必跑，
 * 且它的另一条 archUnit 测试已在管「领域层零 Android」——依赖方向类约束就近集中。
 */
class ModuleGraphTest {

    private val root: File = findRepoRoot()

    private val declaredModules: Set<String> =
        // 锚行首：`// include(":x")` 注释行不计（审查步骤 1 摘除空壳的机器口径）。
        Regex("""(?m)^\s*include\(\s*"(:[^"]+)"\s*\)""")
            .findAll(File(root, "settings.gradle.kts").readText())
            .map { it.groupValues[1] }
            .toSet()

    /** CI 真跑的测试任务（`ci.yml` 的 ./gradlew 行）—— 「测试任务数」的唯一事实来源。 */
    private val ciTestTasks: Set<String> =
        File(root, ".github/workflows/ci.yml").readLines()
            .filter { it.contains("./gradlew") }
            .flatMap { line ->
                Regex("""(:[A-Za-z0-9_:\-]+):test(?:DebugUnitTest)?""")
                    .findAll(line).map { it.groupValues[1] }.asIterable()
            }
            .toSet()

    /** 计数扫描面。settings 两处是**冻结文件**，只读扫描：数字不对时由协调者改。 */
    private val scannedFiles = listOf(
        "CLAUDE.md",
        "docs/design/06-modules.md",
        "settings.gradle.kts",
        "build-logic/settings.gradle.kts",
    )

    /** 引文/历史片段：扫描前抠掉，免得把「别人的建议」当成本仓现状。 */
    private val quotedCounts = mapOf(
        "约 12 个模块" to "§6 开头引的批判建议原文（「约 12 个模块、不要过度拆分」）",
    )

    /** 模块 → 它在 build.gradle.kts 里声明的其他模块依赖（Gradle 依赖边的唯一事实来源）。 */
    private val edges: Map<String, Set<String>> = declaredModules.associateWith { module ->
        val buildFile = File(root, moduleDirOf(module) + "/build.gradle.kts")
        if (!buildFile.isFile) {
            emptySet()
        } else {
            Regex("""project\(\s*"(:[^"]+)"\s*\)""")
                .findAll(buildFile.readText())
                .map { it.groupValues[1] }
                .toSet()
        }
    }

    /**
     * §6 模块表「允许依赖」列的机器可读副本。
     * 依赖边必须是本表的**子集**：少声明不报错（尚未用到），多声明即违反铁律。
     *
     * - `:app` 含 `:bridge:java` 与 `:platform:capabilities`/`:platform:system`：§6 的
     *   **两则包级例外**——都仅 `com.autoscript.shell` 装配包可用（前者挂 handler 上
     *   `BridgeRouter` 字段级转接；后者 `SystemSpis` + `CapabilityNamespaces` 生产装配，
     *   落点 `PlatformWiring`；见 §6「例外不是开后门」+ :app ArchitectureTest）；
     * - `:bridge:native` / `:bridge:image` 的「被引擎宿主 / :main 引用」是**运行期 .so 装载**
     *   （`System.loadLibrary` / JNI），不是 Gradle 依赖边，故此处允许集为空 ——
     *   §6 禁 `:app` 非装配包直连 `:platform`，:main 侧的分析器经 `:platform:capabilities`
     *   实现 `ImageAnalyzer` SPI 间接使用。
     */
    private val allowed: Map<String, Set<String>> = mapOf(
        ":app" to setOf(
            ":app-service:runtime",
            ":app-service:scheduler",
            ":app-service:script-repo",
            ":app-service:permission-center",
            ":app-service:packager",
            ":app-service:npm",     // §6 审查步骤 5：npm 面自 packager 拆出
            ":domain",
            ":bridge:java",          // §6 包级例外一（shell 装配包挂 handler）
            ":platform:capabilities",// §6 包级例外二（shell 装配包生产装配，PlatformWiring）
            ":platform:editor",      // §6 包级例外二（同上，EditorHighlighters 转接）
            ":platform:system",      // §6 包级例外二（同上，SystemSpis 入口）
            ":engine:node-process",  // §8.1 注入点：根包 AppShellApplication 构造 engineFactory 传入
                                     //（shell 装配包仍禁碰 engine —— :app ArchitectureTest 量化）
            ":ui",                   // APK 组装 + launcher manifest 合并；:app 源码零 import ui
                                     //（接线走 :domain 的 HostSummary —— 反向 import 即成环）
        ),
        ":app-service:runtime" to setOf(":domain"),
        ":app-service:scheduler" to setOf(":domain"),
        ":app-service:script-repo" to setOf(":domain"),
        ":app-service:permission-center" to setOf(":domain"),
        ":app-service:packager" to setOf(":domain"),
        // `:platform:process` = 进程/`/proc` 收尸面（批 105）：叶子模块，零模块依赖 ——
        // 它连 :domain 都不需要，用 std 的 java.nio + Process 桩面。放进 :domain 等于让
        // 领域层知道 /proc，那正是批 95/99 留两份副本的原因。
        ":app-service:npm" to setOf(":domain", ":platform:process"),
        ":domain" to emptySet(),
        ":bridge:java" to setOf(":domain"),
        ":bridge:native" to emptySet(),
        ":bridge:image" to emptySet(),
        // :domain = ScriptEngine SPI 实现方向（domain KDoc「实现位于 :engine:node-process」的机器可读化）；
        // :bridge:native 是运行期 .so 装载（main.cpp dlopen），不是 Kotlin 源码边。
        ":engine:node-process" to setOf(":bridge:native", ":domain"),
        ":platform:capabilities" to setOf(":domain"),
        ":platform:editor" to setOf(":domain"),
        ":platform:process" to emptySet(),
        ":platform:system" to setOf(":domain", ":platform:process"),
        // 呈现层只认 :domain（HostSummary 读口 + DTO）；反向依赖 :app 会成环。
        ":ui" to setOf(":domain"),
        ":bridge:treesitter" to emptySet(),
    )

    @Test
    fun `模块表与 settings_gradle 一致（新增模块必须同步登记依赖规则）`() {
        assertEquals(
            allowed.keys, declaredModules,
            "settings.gradle.kts 与 §6 允许依赖表不一致：新增/删除模块时必须同步本表"
                + "（模块数以 settings.gradle.kts 的 include 条数为准，计数由下面的派生门看着）",
        )
    }

    @Test
    fun `每条依赖边都在 §6 允许集内`() {
        val violations = edges.flatMap { (module, deps) ->
            val permit = allowed.getValue(module)
            deps.filterNot { it in permit }.map { "$module → $it（允许：${permit.ifEmpty { "无" }}）" }
        }
        assertTrue(
            violations.isEmpty(),
            "违反 §4.1 依赖方向铁律的模块依赖边：\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `依赖目标都是已声明模块（防 project 引用拼写错误）`() {
        val dangling = edges.flatMap { (module, deps) ->
            deps.filterNot { it in declaredModules }.map { "$module → $it（未在 settings.gradle.kts 声明）" }
        }
        assertTrue(dangling.isEmpty(), "悬空的模块引用：\n" + dangling.joinToString("\n"))
    }

    @Test
    fun `依赖图无环`() {
        val cycle = findCycle()
        assertTrue(cycle == null, "模块依赖成环（§6 要求无环）：${cycle?.joinToString(" → ")}")
    }

    @Test
    fun `禁止自依赖`() {
        val selfLoops = edges.filterValues { deps -> deps.any { it in edges.keys } }
            .filter { (m, _) -> m in edges.getValue(m) }
            .keys
        assertTrue(selfLoops.isEmpty(), "模块依赖自身：$selfLoops")
    }

    /**
     * 文档与构建脚本里的「N 个模块」「N 个测试任务」计数守护 —— 计数不再手抄。
     *
     * 由来：这两个数曾在 CLAUDE.md、§6、settings 注释、build-logic 注释、本文件里各写一遍，
     * 改一次模块要改五处；已漂过一次（`:engine:sandbox` 摘除后 design-status 里留下「不计 14 模块」）。
     * 口径改成两条：
     * - **测试任务数从 `ci.yml` 自己数**，并与「有 `src/test` 的模块集合」**双向相等** ——
     *   新模块加了测试却漏进 CI、或 CI 里留了已删模块的任务，都在这里当场红；
     * - **文档里的数字只做校验**：可以整句不写数字（口径写在别处即可），写了就必须等于派生值。
     *
     * 扫描面**不含 ledger**（`design-status.md` / `design-decisions.md` / `docs/archive/`）：
     * 那三处是沿革，「14 → 15」这类历史数字是记录不是错误，改它才是篡改。
     */
    @Test
    fun `模块数与测试任务数都从 settings 与 ci_yml 派生（文档里的数字只做校验）`() {
        val withTests = declaredModules
            .filter { File(root, "${moduleDirOf(it)}/src/test").isDirectory }
            .toSet()
        assertEquals(
            withTests, ciTestTasks,
            "CI 测试任务与「有 src/test 的模块」不一致 —— 新模块要么补测试要么补 CI 任务行",
        )

        val expected = mapOf("模块" to declaredModules.size, "测试任务" to ciTestTasks.size)
        val problems = scannedFiles.flatMap { rel ->
            var text = File(root, rel).readText()
            quotedCounts.keys.forEach { text = text.replace(it, "") }   // 引文先抠掉
            Regex("""(\d+)\s*个?\s*(模块|测试任务)""").findAll(text).mapNotNull { m ->
                val (n, what) = m.destructured
                val want = expected.getValue(what)
                if (n.toInt() == want) null else "$rel：「$what」写成 $n，派生值是 $want"
            }
        }
        assertTrue(problems.isEmpty(), "计数漂了（改模块时这几处要一起改）：\n" + problems.joinToString("\n"))
    }

    // —— 工具 ——


    private fun moduleDirOf(module: String): String = module.removePrefix(":").replace(':', '/')

    /** DFS 三色标记找环；返回环路径（首尾同节点）或 null。 */
    private fun findCycle(): List<String>? {
        val state = mutableMapOf<String, Int>()   // 0=未访问 1=在栈 2=完成
        val stack = ArrayDeque<String>()

        fun dfs(node: String): List<String>? {
            if (state[node] == 1) {
                val from = stack.indexOf(node)
                return stack.drop(from) + node
            }
            if (state[node] == 2) return null
            state[node] = 1
            stack.addLast(node)
            for (next in edges[node].orEmpty()) {
                dfs(next)?.let { return it }
            }
            stack.removeLast()
            state[node] = 2
            return null
        }

        for (m in declaredModules) dfs(m)?.let { return it }
        return null
    }

    /** 从测试工作目录上溯到含 settings.gradle.kts 的目录。 */
    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("未找到仓库根（上溯 ${System.getProperty("user.dir")} 未见 settings.gradle.kts）")
    }
}
// 测试完整性自检已在上面的 ModuleGraphTest 里
