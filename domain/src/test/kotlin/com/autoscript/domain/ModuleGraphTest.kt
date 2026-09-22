package com.autoscript.domain

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 全仓模块依赖图守护（docs/framework-design.md §4.1 依赖方向铁律 + §6 模块表 + §6 末「依赖方向无环」）。
 *
 * 与各模块内 archUnit 测试的分工：
 * - **模块内 archUnit**：按字节码校验该模块的包没有 import 不该碰的包 —— 只对有 class 的模块有效；
 * - **本测试**：按构建脚本校验模块依赖边符合 §6「允许依赖」列且无环 —— 空模块（`:engine:sandbox`、
 *   `:bridge:image` 等尚无源码）同样被覆盖，且能拦住 Gradle 层反向依赖。
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
        Regex("""include\(\s*"(:[^"]+)"\s*\)""")
            .findAll(File(root, "settings.gradle.kts").readText())
            .map { it.groupValues[1] }
            .toSet()

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
     * - `:app` 含 `:bridge:java`：§6 唯一例外——仅 `com.autoscript.shell` 装配包把 handler
     *   挂上 `BridgeRouter` 时可用（字段级转接，见 §6「例外不是开后门」+ :app ArchitectureTest）；
     * - `:bridge:native` / `:bridge:image` 的「被引擎宿主 / :main 引用」是**运行期 .so 装载**
     *   （`System.loadLibrary` / JNI），不是 Gradle 依赖边，故此处允许集为空 ——
     *   §6 明令 `:app` 禁直连 `:platform`，:main 侧的分析器经 `:platform:capabilities`
     *   实现 `ImageAnalyzer` SPI 间接使用。
     */
    private val allowed: Map<String, Set<String>> = mapOf(
        ":app" to setOf(
            ":app-service:runtime",
            ":app-service:scheduler",
            ":app-service:script-repo",
            ":app-service:permission-center",
            ":app-service:packager",
            ":domain",
            ":bridge:java",          // §6 唯一例外（shell 装配包）
        ),
        ":app-service:runtime" to setOf(":domain"),
        ":app-service:scheduler" to setOf(":domain"),
        ":app-service:script-repo" to setOf(":domain"),
        ":app-service:permission-center" to setOf(":domain"),
        ":app-service:packager" to setOf(":domain"),
        ":domain" to emptySet(),
        ":bridge:java" to setOf(":domain"),
        ":bridge:native" to emptySet(),
        ":bridge:image" to emptySet(),
        ":engine:node-process" to setOf(":bridge:native"),
        ":engine:sandbox" to emptySet(),
        ":platform:capabilities" to setOf(":domain"),
        ":platform:system" to setOf(":domain"),
    )

    @Test
    fun `模块表与 settings_gradle 一致（新增模块必须同步登记依赖规则）`() {
        assertEquals(
            allowed.keys, declaredModules,
            "settings.gradle.kts 与 §6 允许依赖表不一致：新增/删除模块时必须同步本表（§6 冻结 14 个模块）",
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
