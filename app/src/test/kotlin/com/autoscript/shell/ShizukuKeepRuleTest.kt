package com.autoscript.shell

import com.autoscript.e2e.repoRoot
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Shizuku 反射面 ↔ R8 keep 规则的对齐门（backlog 外审第 6 条，2026-10-10）。
 *
 * **它挡的是什么**：`ShizukuInput` 全程按**字符串**找类、找方法（`Class.forName` /
 * `getMethod`），R8 看不见这些引用 —— release 包上名字一改，表现是「能力静默失效」
 * （不崩、不报错，只是永远走不通那条分支），而 debug 包不过 R8，**本机全绿**。
 * 两条规则分居两个模块（反射面在 `:platform:capabilities`，keep 规则在 `:app`），
 * 谁都不知道对方改了没有 —— 2026-10-09 那次真机 bug 正是这个形态的变体：
 * keep 里保的是 `rikka.shizuku.ShizukuRemoteProcess.waitForTimeout(long, TimeUnit)`，
 * 而真跑的那条路径反射的是 `moe.shizuku.server.IRemoteProcess.waitForTimeout(long, String)`
 * —— 保错的那条**正好掩盖了**跑错的那条。
 *
 * **门的三条判据**（双向，两边都红）：
 * 1. **类名集合相等**：源集里按字符串引用的 Shizuku 类 == keep 规则里保的 Shizuku 类。
 *    多一个（源里反射了没保）与少一个（保了源里不再反射的 = 死规则）都红。
 * 2. **方法集合相等**：源里 `getMethod("x", …)` 的 `(名字, 参数表)` 集合 == keep 块里
 *    列的 `(名字, 参数表)` 集合。**参数表要一起比** —— 上面那个真机 bug 的名字是对的、
 *    签名是错的，只比名字照样绿。
 * 3. **签名在真类上存在**：每条 keep 规则拿 `Class.forName` + `getMethod(名字, 参数类)`
 *    真解析一遍（AIDL 接口在单测类路径上**真能加载**，见 `:platform:capabilities` 的
 *    依赖声明）。这条挡的是「两边一起错」——判据 1/2 只证明它们**彼此一致**。
 *
 * **为什么不把 keep 改成 `{ *; }` 一了百了**：那样判据 2 就退化成恒真（keep 永远覆盖源），
 * 而那张显式方法表本身就是「本应用反射了哪几个方法」的唯一文档。保住表、用门让它自维护，
 * 比删掉表换一个恒真的门强。
 *
 * **为什么读源码而不是读常量池**（同模块那条 Shizuku 门读的是字节）：这里要的是
 * `(名字, 参数表)` 而**不是**「提到过 Shizuku」—— 参数表在字节里只剩描述符，
 * 与 keep 规则的源码语法对不上，得先反解一遍，比直接读源码脆。
 */
class ShizukuKeepRuleTest {

    @Test
    fun `Shizuku 反射面与 R8 keep 规则逐条对齐`() {
        val source = readRepoFile(SHIZUKU_SOURCE)
        val rules = parseKeepRules(readRepoFile(PROGUARD_RULES))

        val sourceClasses = classNamesInSource(source)
        val sourceMethods = methodSitesInSource(source)
        val keptClasses = rules.keys.filter { it.startsWith(SHIZUKU_PACKAGE_PREFIX) || it.startsWith(MOE_PACKAGE_PREFIX) }.toSet()
        val keptMethods = rules.filterKeys { it in keptClasses }.values.flatten().toSet()

        assertEquals(
            keptClasses,
            sourceClasses,
            "keep 规则与源集引用的 Shizuku 类名不一致（左 = proguard，右 = 源集）：" +
                "只在左 = 保了不再反射的类（死规则，2026-10-09 那个 bug 就是这个形态）；" +
                "只在右 = 反射了没保（release 包上静默失效）。",
        )
        assertEquals(
            keptMethods,
            sourceMethods,
            "keep 规则与源集的反射方法不一致（左 = proguard，右 = 源集），" +
                "**参数表也是判据的一部分**（名字对、签名错正是 2026-10-09 那个 bug 的形态）。",
        )

        // 判据 3：两边一致还不够，签名得在真类上解析得出来。
        val unresolved = mutableListOf<String>()
        for ((className, members) in rules.filterKeys { it in keptClasses }) {
            val clazz = try {
                Class.forName(className, false, javaClass.classLoader)
            } catch (t: Throwable) {
                unresolved += "$className（类加载失败：${t::class.java.name}）"
                continue
            }
            for (m in members) {
                val params = m.params.map { paramClass(it) }
                if (params.any { it == null }) {
                    unresolved += "$className.${m.name}(${m.params.joinToString()})（参数类型解析不了）"
                    continue
                }
                try {
                    clazz.getMethod(m.name, *params.filterNotNull().toTypedArray())
                } catch (_: NoSuchMethodException) {
                    unresolved += "$className.${m.name}(${m.params.joinToString()})"
                }
            }
        }
        assertTrue(
            unresolved.isEmpty(),
            "keep 规则里的方法在真类上不存在（Shizuku 升级改了 AIDL 签名？）：$unresolved",
        )
    }

    // ── 源集侧 ────────────────────────────────────────────────────────────────

    /**
     * 源集里按字符串引用的 Shizuku 类名。
     *
     * 只认**字符串字面量**（`"rikka.shizuku.X"`），不认注释里反引号包着的那些 ——
     * 注释不是反射面。Kotlin 源码里内层类写成 `"…IShizukuService\$Stub"`（`$` 要转义），
     * 故取值时把反斜杠去掉。
     */
    private fun classNamesInSource(source: String): Set<String> =
        CLASS_LITERAL.findAll(source)
            .map { it.groupValues[1].replace("\\", "") }
            .toSet()

    /** 源集里 `getMethod("名字", 参数…)` 的 `(名字, 参数表)` 集合。 */
    private fun methodSitesInSource(source: String): Set<Member> =
        GET_METHOD.findAll(source)
            .map { m ->
                val params = m.groupValues[2]
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { sourceParamType(it) }
                Member(m.groupValues[1], params)
            }
            .toSet()

    /**
     * 源码里的参数表达式 → keep 规则那套 JVM 名字。
     *
     * 只认本文件真出现过的三种形态（`X::class.java` / `X::class.javaPrimitiveType` /
     * `Array<X>::class.java`）；**认不出来就原样回**，让比对当场红 —— 那时补这条规则，
     * 别放它过去。
     */
    private fun sourceParamType(expr: String): String = when {
        expr.endsWith("::class.javaPrimitiveType") -> primitiveName(expr.removeSuffix("::class.javaPrimitiveType"))
        expr.endsWith("::class.java") -> referenceName(expr.removeSuffix("::class.java"))
        else -> expr
    }

    private fun primitiveName(name: String): String = when (name) {
        "Long" -> "long"
        "Int" -> "int"
        "Boolean" -> "boolean"
        "Unit" -> "void"
        else -> name
    }

    private fun referenceName(name: String): String = when {
        name.startsWith("Array<") && name.endsWith(">") -> referenceName(name.substring(6, name.length - 1)) + "[]"
        name.contains('.') -> name
        name in JAVA_LANG -> "java.lang.$name"
        else -> name
    }

    // ── proguard 侧 ───────────────────────────────────────────────────────────

    /**
     * 解析 `-keep class/interface <fqcn> { … }` 块（`{ … }` 单行写法与多行块都接）。
     *
     * 只认带成员表的块：`-keepclasseswithmembernames` 与 `-keep class X { <init>(); }`
     * 这类不含「名字 + 参数表」的行，解析出来是空集，不影响判据。
     */
    private fun parseKeepRules(text: String): Map<String, List<Member>> {
        val rules = linkedMapOf<String, MutableList<Member>>()
        // **先剥注释行**：本文件里有一段注释逐字引用了 2026-10-09 删掉的那条规则
        // （`# **2026-10-09 删掉的一条**：-keep class rikka.shizuku.ShizukuRemoteProcess { … }`），
        // 不剥就会被当成一条活规则 —— 这正是本门第一次跑出来的那条假红。
        val effective = text.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        for (block in KEEP_BLOCK.findAll(effective)) {
            val members = rules.getOrPut(block.groupValues[1]) { mutableListOf() }
            for (line in block.groupValues[2].lines()) {
                val m = MEMBER_LINE.find(line.trim()) ?: continue
                val params = m.groupValues[3].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                members += Member(m.groupValues[2], params)
            }
        }
        return rules
    }

    /** keep 规则里一条成员：名字 + 参数表（返回类型不参与 —— `getMethod` 不看它）。 */
    private data class Member(val name: String, val params: List<String>)

    /** 参数类型名 → `Class`（keep 规则里写的是源码式名字，不是描述符）。 */
    private fun paramClass(name: String): Class<*>? {
        if (name.endsWith("[]")) {
            val component = paramClass(name.dropLast(2)) ?: return null
            return java.lang.reflect.Array.newInstance(component, 0).javaClass
        }
        PRIMITIVES[name]?.let { return it }
        return try {
            Class.forName(name, false, javaClass.classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readRepoFile(relative: String): String {
        val path: Path = repoRoot().resolve(relative)
        assertTrue(Files.isRegularFile(path), "读不到 $relative（仓库根=${repoRoot()}）—— 路径漂了？")
        return String(Files.readAllBytes(path), Charsets.UTF_8)
    }

    private companion object {
        const val SHIZUKU_SOURCE = "platform/capabilities/src/main/kotlin/com/autoscript/platform/capabilities/device/ShizukuInput.kt"
        const val PROGUARD_RULES = "app/proguard-rules.pro"

        const val SHIZUKU_PACKAGE_PREFIX = "rikka.shizuku."
        const val MOE_PACKAGE_PREFIX = "moe.shizuku."

        val JAVA_LANG = setOf("String", "Integer", "Long", "Boolean", "Object")

        val PRIMITIVES: Map<String, Class<*>> = mapOf(
            "long" to Long::class.javaPrimitiveType!!,
            "int" to Int::class.javaPrimitiveType!!,
            "boolean" to Boolean::class.javaPrimitiveType!!,
            "void" to Void.TYPE,
        )

        /** `"rikka.shizuku.X"` / `"moe.shizuku.X"` 这类字符串字面量。 */
        val CLASS_LITERAL = Regex("\"((?:rikka|moe)\\.shizuku\\.[A-Za-z0-9_$.\\\\]+)\"")

        /** `getMethod("名字")` / `getMethod("名字", 参数…)`。 */
        val GET_METHOD = Regex("getMethod\\(\\s*\"([A-Za-z0-9_]+)\"\\s*(,[^)]*)?")

        /** `-keep class|interface <fqcn> { … }`（成员表可能跨行）。 */
        val KEEP_BLOCK = Regex("-keep\\s+(?:class|interface)\\s+([\\w.$]+)\\s*\\{([^}]*)}")

        /** keep 块里的一行成员：`public static boolean pingBinder();`。 */
        val MEMBER_LINE = Regex("^[\\w\\s]*?([\\w.$\\[\\]]+)\\s+([A-Za-z0-9_]+)\\(([^)]*)\\);$")
    }
}
