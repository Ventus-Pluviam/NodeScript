package com.autoscript.appservice.packager.axml

import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * 测试夹具定位：**锚仓库根（首个含 settings.gradle.kts 的目录），不锚目录名**。
 *
 * 本仓多 worktree 并行，目录名即分支名；锚死任一名字都会让其余 worktree 与 CI
 * 解析到 `/` 而拿不到夹具（`SocketE2EHostTest` 曾栽在这里，见 `tools/jvm-test-all.sh`
 * 对 aborted 的处理）。
 */
internal object FixtureAxml {

    private val repoRoot: String = run {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").isFile) d = d.parentFile
        requireNotNull(d) { "未找到仓库根（上溯 ${System.getProperty("user.dir")} 未见 settings.gradle.kts）" }
        d.absolutePath
    }

    /**
     * aapt2 link 产出的最小模板 APK（`com.autoscript.template` / versionName=1.0.0 /
     * versionCode=1，label = `@string/app_name` → 0x7f010000）。
     * **label 走资源引用**，正是要靠 ARSC 改写的那条路径。
     */
    fun templateApk(): Path =
        File(repoRoot, "app-service/packager/src/test/resources/packager/fixture-template.apk").toPath()

    /**
     * aapt2 link 产出的**完整**模板 APK：组件与图标资源俱全（换图标/组件名改写的夹具）。
     *
     * - 身份同最小夹具（`com.autoscript.template` / versionName=1.0.0 / versionCode=1，
     *   label = `@string/app_name`）；
     * - 组件覆盖四种写法 + alias：相对 `.MainActivity`、绝对 `com.autoscript.template.FqActivity`、
     *   裸名 `BareService`、外部 `com.other.KeepReceiver`，另有 `.AppTemplate`（application）、
     *   `.LauncherAlias` → `targetActivity=.MainActivity`、`.TplProvider`（provider）；
     * - 图标两侧俱全：5 密度 `ic_launcher.png`（旧框架链接的产物条目名带 `-v4` 后缀，如
     *   `res/mipmap-xhdpi-v4/ic_launcher.png`）+ xxxhdpi `ic_launcher_round.png` +
     *   `mipmap-anydpi-v26` 自适应 XML ×2（要换的与要删的都在）；前景
     *   `drawable-xxhdpi-v4/ic_launcher_foreground.png` 不该被换。
     *
     * **生成口径**（本机无 SDK：aapt2 2.19 + 含 resources.arsc 的 API16 android.jar 作 `-I`）：
     * `aapt2 compile --dir res -o res.zip` → `aapt2 link -I <framework.jar>
     * --manifest AndroidManifest.xml res.zip --min-sdk-version 26 --target-sdk-version 30`。
     * `android:roundIcon` 属 API26 属性、旧框架表里没有，故 manifest 未写它 —— round
     * **条目**仍在包内，图标改写按条目名走，不依赖该属性。
     */
    fun templateFullApk(): Path =
        File(repoRoot, "app-service/packager/src/test/resources/packager/fixture-template-full.apk").toPath()

    fun entry(apk: Path, name: String): ByteArray =
        ZipFile(apk.toFile()).use { zf ->
            val entry = zf.getEntry(name) ?: error("夹具缺条目 $name")
            zf.getInputStream(entry).readBytes()
        }
}
