package com.autoscript.platform.capabilities

import com.autoscript.build.ArchGate
import org.junit.jupiter.api.Assertions.assertTrue
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.io.File
import java.util.jar.JarFile
import java.util.regex.Pattern
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition
import org.junit.jupiter.api.Test
import com.autoscript.platform.capabilities.a11y.A11yBridge
import com.autoscript.platform.capabilities.a11y.AndroidUiTree
import com.autoscript.platform.capabilities.dialogs.AndroidDialogHost
import com.autoscript.platform.capabilities.device.ShizukuInput

/**
 * 依赖方向守护（docs §6 模块表）：capabilities 实现 :domain SPI，不反向；
 * 禁服务逻辑（app-service）、禁桥/引擎直连、禁 UI。
 *
 * `a11y`/`screen`/`dialogs` 命名空间挂 Router 所需的接缝类型（`com.autoscript.domain.
 * bridge.NamespaceHandler`）住 `:domain`，本模块只见 `:domain`（见 [CapabilityNamespaces]）；
 * `com.autoscript.bridge..` 仍整体在黑名单里 —— 依赖 `:domain` 的挂载缝不等于依赖桥实现层。
 *
 * `android..` 按**包**豁免（第二条规则）：设备面全部住 `com.autoscript.platform.capabilities.device`
 * 子包（无障碍服务 + 截图回调 + 对话框设备面），其余（语义层 [AndroidUiTree]/
 * [AndroidDialogHost]/[A11yBridge] 接缝/handler）保持纯 JVM —— 它们的可测性
 * （假桥/假 ops 注入、本机无 SDK 跑测）全押在这条线上。按包不按名：SAM/匿名合成类
 * （`...$dialog$1`）跟着源文件走，名单不用人肉续（按名豁免已经错过两轮）。
 *
 * 第一条走共享 [ArchGate]；第二、三条是复合规则（`and().resideOutsideOfPackage`），形状不通用，
 * 留在本文件自写 —— 共享门只收「最通用的一条」，不硬塞变体。
 *
 * **第三条（2026-10-09）**：设备面里**只许 [ShizukuInput] 反射 Shizuku**。守的是一个
 * 真机上才暴露的坑：`newProcess` 的返回值是**包内可见**的 AIDL proxy，从它上面
 * `getMethod` 要么找不到签名、要么 `invoke` 抛 `IllegalAccessException` —— 而正确的写法
 * （从公开接口取）与错误的写法在源码上长得几乎一样。这条规则把"谁能碰 Shizuku"收成一处，
 * 新写一处反射时会被当场拦下（去复用 `ShizukuInput` 的接缝，而不是再抄一遍）。
 */
class ArchitectureTest {

    @Test
    fun `capabilities 包零跨层泄漏`() = ArchGate(
        "com.autoscript.platform.capabilities",
        rulePackage = "..capabilities..",
    ).noLeakTo(
        "androidx..",
        "com.autoscript.bridge..",
        "com.autoscript.engine..",
        "com.autoscript.appservice..",
        "androidx.compose..",
        "java.awt..",
        "javax.swing..",
    )

    @Test
    fun `android 只许设备包子包碰`() {
        val classes = ClassFileImporter().importPackages("com.autoscript.platform.capabilities")

        ArchRuleDefinition.noClasses()
            .that().resideInAPackage("..capabilities..")
            .and().resideOutsideOfPackage("..capabilities.device")
            .should().dependOnClassesThat().resideInAnyPackage("android..")
            .check(classes)
    }

    /**
     * **这一条守的是"名字被改掉"**，不是"引用被看见"。
     *
     * 本模块的生产代码**引用不到** `rikka.shizuku..`/`moe.shizuku..`：那两条依赖是
     * `implementation`，反射面全走字符串（`Class.forName("rikka.shizuku.Shizuku")` 等四处，
     * 全在 [ShizukuInput] 里）—— 所以一条"禁依赖"规则在这里永远绿，是空转的门。
     * 会漂的是**字符串**：谁再抄一份反射（或把类名常量搬去别处），字符串跟着多一份。
     * 于是规则落在字符串上：全模块的常量池里出现这两个类名前缀的，只许 [ShizukuInput] 一个类。
     *
     * 为什么非要有它：真机上 adb 档 100% 不可用的那个 bug，修法与错法在源码上只差一个
     * 表达式（从公开接口取 vs 从 `process.javaClass` 取），任何编译期检查都看不见它；
     * 唯一机械可行的守卫是"别让第二份反射长出来"，逼后来者复用 [ShizukuInput] 的接缝。
     */
    @Test
    fun `Shizuku 反射只许 ShizukuInput 一处`() {
        // **只扫生产类**：单测侧的替身（`src/test/java` 的 FakeRemoteProcess / ShizukuProbe）
        // 要实现的正是那个 AIDL 接口，必然引用它 —— 它们只活在单测类路径上，不构成
        // 「反射面又抄了一份」的风险。按**位置**排除（不是按类名白名单）：将来再添替身
        // 不必回来续名单（按名豁免在本仓已经错过两轮，见本文件类 KDoc）。
        val production = ClassFileImporter()
            .withImportOption(ImportOption { location -> !location.matches(TEST_CLASSES) })
            .importPackages("com.autoscript.platform.capabilities")

        // 常量池按**字节**读（ArchUnit 的 JavaClass 不暴露它）：把本模块生产类的
        // 编译产物逐个读进来，找那两个包名前缀的 UTF-8 常量。
        //
        // 豁免按**源文件**而不是类名：`ShizukuInput.kt` 里还住着 `RemoteProcessApi`
        // 与 `StreamTail` 两个同文件类，它们当然也带着那些字符串 —— 按类名豁免会逼着
        // 每加一个同文件私有类就回来续一次名单（按名豁免在本仓已经错过两轮）。
        val sourceFiles = production.associate { it.name to it.source.orElse(null)?.fileName?.orElse(null) }
        val offenders = productionClassBytes()
            .filter { (name, bytes) -> sourceFiles[name] != SHIZUKU_FILE && bytes.mentionsShizuku() }
            .map { it.first }

        assertTrue(
            offenders.isEmpty(),
            "Shizuku 的反射面只许 ShizukuInput 一处（复用它的接缝，别抄第二份）：$offenders",
        )
    }

    /**
     * 本模块**生产类**的字节（类名 → 常量池字节）。
     *
     * 从 [ShizukuInput] 自己的 code source 出发：单测里它指向本模块生产类的编译产物
     * （AGP 给的是 jar 或 class 目录，两种都接）。比"按相对路径去读 build/ 目录"稳 ——
     * 不依赖测试的工作目录，也不写死构建布局。
     */
    private fun productionClassBytes(): List<Pair<String, ByteArray>> {
        val codeSource = requireNotNull(ShizukuInput::class.java.protectionDomain?.codeSource) {
            "拿不到 ShizukuInput 的 code source（测试的类加载方式变了？）"
        }
        val location = File(codeSource.location.toURI())
        return if (location.isFile) {
            JarFile(location).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .map { it.name.removeSuffix(".class").replace('/', '.') to jar.getInputStream(it).readBytes() }
                    .toList()
            }
        } else {
            location.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(location).path.removeSuffix(".class").replace('/', '.') to it.readBytes() }
                .toList()
        }
    }

    private companion object {
        /** Shizuku 反射面唯一允许的源文件（`ShizukuInput.kt` 及其同文件类）。 */
        const val SHIZUKU_FILE = "ShizukuInput.kt"

        /** 单测类的编译产物路径（Kotlin 与 Java 两处都含 `debugUnitTest`）。 */
        val TEST_CLASSES: Pattern = Pattern.compile(".*/debugUnitTest/.*")

        /**
         * 两个包名的**两种形态**都要找，缺一不可：
         * - 点分（`rikka.shizuku`）—— `Class.forName("…")` 的字符串常量；
         * - 斜杠（`rikka/shizuku`）—— 类型描述符（`Lrikka/shizuku/Shizuku;`）与 import 出来的引用。
         *
         * 2026-10-09 实测教训：只找斜杠形态时，一个只写 `Class.forName("rikka.shizuku.Shizuku")`
         * 的新类**照样绿**（那正是这条门要拦的东西）—— 门必须先自己红一次才算立住。
         */
        val NEEDLES: List<ByteArray> = listOf("rikka.shizuku", "rikka/shizuku", "moe.shizuku", "moe/shizuku")
            .map { it.toByteArray() }

        /** 常量池里有没有 Shizuku 的包名前缀（手写子串查找：`ByteArray` 没有现成的 `contains(ByteArray)`）。 */
        fun ByteArray.mentionsShizuku(): Boolean = NEEDLES.any { indexOfSlice(it) >= 0 }

        /** 朴素子串查找（常量池只有几百 KB，不值得上 KMP）。 */
        fun ByteArray.indexOfSlice(needle: ByteArray): Int {
            if (needle.isEmpty() || needle.size > size) return -1
            outer@ for (i in 0..size - needle.size) {
                for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
