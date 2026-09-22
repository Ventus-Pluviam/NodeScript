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

    fun entry(apk: Path, name: String): ByteArray =
        ZipFile(apk.toFile()).use { zf ->
            val entry = zf.getEntry(name) ?: error("夹具缺条目 $name")
            zf.getInputStream(entry).readBytes()
        }
}
