package com.autoscript.shell

import com.autoscript.e2e.repoRoot
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 引擎四件 `.so` 的**落位门**（2026-10-14 真机 bug 的回归门）。
 *
 * **它挡的是什么**：`AppShellApplication` 装配引擎时把四处路径喂给
 * `NodeEngineConfig.hostBinary` / `.libnodePath`、`npmNodeBin` 与 `npmLibnode`（后者
 * 2026-10-17 补：npm 那条路一直没把 `AUTOSCRIPT_LIBNODE` 注下去，设备上点安装 = exit 2），
 * 四处**都**必须指向 `nativeLibraryDir`（jniLibs 交付位，§19；`useLegacyPackaging = true` 安装期解压）。
 * 批 109（`e338000`，`typed ids`）批量改名时把这三处的接收者从 `nativeDir` 串成了
 * `nsDir`（`filesDir/.ns`）—— **Kotlin 不报错**，因为两个都是 `Path`、`resolve` 都合法；
 * 设备上表现为点执行零输出：`NodeProcessEngine.execute` 的必填件预检如实点名
 * `ERR_FILE_NOT_FOUND … /data/user/0/com.autoscript/files/.ns/libnoden.so`，池判 `Failed`、
 * 意图日志 `CRASHED`，悬浮窗绑不到在途 run → 「本次执行未开始」。CI 全绿（JVM 侧走假引擎
 * 或 PATH 上的系统 `node`，两条都不碰这三个路径）。这正是 `design-decisions.md` §77.3
 * 记的「改名的碰撞」：逐字符替换查不出来，只有读代码或一道门能抓。
 *
 * **为什么是读源码而不是编译期类型**：三个接收者都是 `Path`，类型系统这里**没有**可判的
 * 区别（`nsDir` 与 `nativeLibDir` 同型）—— 要拦它只能拦「哪一行读的是哪个目录」，那是
 * 事实而非类型。与同模块 `ShizukuKeepRuleTest` 同一条纪律（读源码钉住一处跨文件的字符串
 * 事实），也同一个理由：这类缝改一边不改另一边时，**运行期才红**，而运行期在设备上。
 *
 * **为什么期望值是从绑定行现推的**：写死字面量 `nativeLibDir` 的话，谁把那个 `val` 改名，
 * 门就跟着红在无关的地方；从 `val X = Paths.get(applicationInfo.nativeLibraryDir)` 现取 X
 * 才是门真正要钉的事实 ——「这三处读的是 nativeLibraryDir 派生出来的那个路径」。
 */
class AppShellEnginePathsTest {

    /** 四处喂给引擎/npm 的 `.so` 路径（属性名 → 交付位文件）。 */
    private val expectedSites = mapOf(
        "hostBinary" to "libnoden.so",
        "libnodePath" to "libnode.so",
        "npmNodeBin" to "libnoden.so",
        "npmLibnode" to "libnode.so",
    )

    @Test
    fun `引擎四件 so 的路径都读 nativeLibraryDir 而不是 files 目录下的 ns`() {
        val source = String(
            Files.readAllBytes(repoRoot().resolve(SOURCE)),
            Charsets.UTF_8,
        )

        // 期望接收者 = `Paths.get(applicationInfo.nativeLibraryDir)` 绑到的那个 val
        // （见类 KDoc 末段：现推而不是写死，改名不该让门红在无关处）。
        val nativeBinding = NATIVE_BINDING.find(source)
        assertTrue(
            nativeBinding != null,
            "源里找不到 `val <X> = Paths.get(applicationInfo.nativeLibraryDir)` —— " +
                "nativeLibraryDir 的取法改了？（改了就同步改本门，别让门退化成恒绿）",
        )
        val nativeVar = nativeBinding!!.groupValues[1]

        val sites = SITE.findAll(source)
            .map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            .toList()
        assertEquals(
            expectedSites.keys.sorted(),
            sites.map { it.first }.sorted(),
            "喂给引擎/npm 的 .so 路径点变了（多一处少一处都要连着改本门）：$sites",
        )
        for ((prop, _, file) in sites) {
            assertEquals(
                file, expectedSites[prop],
                "`$prop` 指的交付位文件变了（jniLibs 三件套的命名是打包管线定的）",
            )
        }

        // 判据本体：每一处的**接收者**都得是 nativeLibraryDir 派生的那个 val。
        val wrong = sites.filter { (_, receiver, _) -> receiver != nativeVar }
        assertTrue(
            wrong.isEmpty(),
            "这些 .so 路径指向了非 nativeLibraryDir 的目录（左 = 属性，右 = 实际接收者）：$wrong —— " +
                "真机形态 = 点执行零输出 + 悬浮窗「本次执行未开始」，意图日志里是 " +
                "ERR_FILE_NOT_FOUND 点名 files/.ns/libnoden.so（2026-10-14 bug，design-decisions §77.3）。",
        )

        // 反向：`files/.ns` 派生的那个 val 一个都不许出现在这三处（点名它，好让报错直接指到病根）。
        val nsBinding = NS_BINDING.find(source)?.groupValues?.get(1)
        assertTrue(
            nsBinding != null,
            "源里找不到 `val <X> = filesDir.resolve(\".ns\")` —— 管理目录的取法改了？",
        )
        assertTrue(
            sites.none { it.second == nsBinding },
            "有 .so 路径读到了 files/.ns（= 2026-10-14 那个 bug 的原样复发）：$sites",
        )
    }

    private companion object {
        const val SOURCE = "app/src/main/kotlin/com/autoscript/AppShellApplication.kt"

        /** `val X = Paths.get(applicationInfo.nativeLibraryDir)`。 */
        val NATIVE_BINDING = Regex("""val\s+(\w+)\s*=\s*Paths\.get\(applicationInfo\.nativeLibraryDir\)""")

        /** `val X = filesDir.resolve(".ns")`（管理目录；引擎 .so 绝不该读它）。 */
        val NS_BINDING = Regex("""val\s+(\w+)\s*=\s*filesDir\.resolve\("\.ns"\)""")

        /** 一处喂给引擎/npm 的 .so 路径：`<属性> = <接收者>.resolve("<交付位文件>")`。 */
        val SITE = Regex("""(\w+)\s*=\s*(\w+)\.resolve\("(libnode[n]?\.so)"\)""")
    }
}
